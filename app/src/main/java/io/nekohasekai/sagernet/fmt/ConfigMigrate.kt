package io.nekohasekai.sagernet.fmt

/**
 * Rewrites a sing-box config produced by the (legacy-format) ConfigBuilder into the
 * format accepted by sing-box 1.13/1.14:
 *  - inbound sniff / domain_strategy  -> route rule actions (sniff / resolve)
 *  - tun inet4_address/inet6_address  -> address
 *  - legacy DNS servers (address: ...) -> typed DNS servers, fakeip, rcode block
 *  - outbound domain_strategy         -> domain_resolver
 *  - wireguard outbound               -> wireguard endpoint
 * Only legacy keys are touched, so already-migrated (e.g. user custom) config is left as is.
 */
@Suppress("UNCHECKED_CAST")
object ConfigMigrate {

    private fun map(v: Any?): MutableMap<String, Any?>? = v as? MutableMap<String, Any?>
    private fun list(v: Any?): MutableList<Any?>? = v as? MutableList<Any?>

    fun migrate(root: MutableMap<String, Any?>) {
        val route = map(root["route"]) ?: mutableMapOf<String, Any?>().also { root["route"] = it }
        val routeRules = list(route["rules"]) ?: mutableListOf<Any?>().also { route["rules"] = it }

        migrateInbounds(root, routeRules)
        val dnsResolverTag = migrateDns(root, route)
        migrateOutbounds(root, dnsResolverTag)
    }

    private fun migrateInbounds(root: MutableMap<String, Any?>, routeRules: MutableList<Any?>) {
        val sniffTags = mutableListOf<Any?>()
        val resolve = LinkedHashMap<String, MutableList<Any?>>()
        list(root["inbounds"])?.forEach { raw ->
            val ib = map(raw) ?: return@forEach
            val tag = ib["tag"]
            if (ib.remove("sniff") == true) sniffTags.add(tag)
            ib.remove("sniff_override_destination")
            ib.remove("sniff_timeout")
            val ds = ib.remove("domain_strategy") as? String
            if (!ds.isNullOrEmpty()) resolve.getOrPut(ds) { mutableListOf() }.add(tag)
            if (ib["type"] == "tun") {
                val addr = mutableListOf<Any?>()
                (ib.remove("inet4_address") as? List<Any?>)?.let { addr.addAll(it) }
                (ib.remove("inet6_address") as? List<Any?>)?.let { addr.addAll(it) }
                if (addr.isNotEmpty() && ib["address"] == null) ib["address"] = addr
            }
        }
        // order: sniff first, then resolve, then everything else
        var idx = 0
        resolve.entries.forEach { (strategy, tags) ->
            routeRules.add(idx, mutableMapOf<String, Any?>(
                "inbound" to tags, "action" to "resolve", "strategy" to strategy
            ))
        }
        if (sniffTags.isNotEmpty()) {
            routeRules.add(0, mutableMapOf<String, Any?>("inbound" to sniffTags, "action" to "sniff"))
        }
    }

    private fun parseLegacyAddress(address: String): MutableMap<String, Any?> {
        val m = mutableMapOf<String, Any?>()
        if (address == "local") { m["type"] = "local"; return m }
        if (address == "fakeip") { m["type"] = "fakeip"; return m }
        val scheme = if (address.contains("://")) address.substringBefore("://") else "udp"
        val rest = if (address.contains("://")) address.substringAfter("://") else address
        val type = when (scheme) {
            "https" -> "https"; "h3" -> "h3"; "tls" -> "tls"; "quic" -> "quic"
            "tcp" -> "tcp"; "dhcp" -> "dhcp"; else -> "udp"
        }
        m["type"] = type
        if (type == "dhcp") {
            if (rest.isNotEmpty() && rest != "auto") m["interface"] = rest
            return m
        }
        var hostPort = rest.substringBefore("/")
        val path = rest.substringAfter("/", "")
        if (path.isNotEmpty() && (type == "https" || type == "h3")) m["path"] = "/$path"
        var port: Int? = null
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            val after = hostPort.substring(end + 1)
            hostPort = hostPort.substring(1, end)
            if (after.startsWith(":")) port = after.substring(1).toIntOrNull()
        } else if (hostPort.count { it == ':' } == 1) {
            port = hostPort.substringAfter(":").toIntOrNull()
            hostPort = hostPort.substringBefore(":")
        }
        m["server"] = hostPort
        if (port != null) m["server_port"] = port
        return m
    }

    /** returns tag of the server used as default domain resolver (or null) */
    private fun migrateDns(root: MutableMap<String, Any?>, route: MutableMap<String, Any?>): String? {
        val dns = map(root["dns"]) ?: return null
        dns.remove("independent_cache")
        val fakeip = map(dns.remove("fakeip"))
        val servers = list(dns["servers"]) ?: return null
        val blockTags = mutableSetOf<String>()
        var globalStrategy: String? = null
        val newServers = mutableListOf<Any?>()

        servers.forEach { raw ->
            val s = map(raw) ?: return@forEach
            val address = s["address"] as? String
            if (address == null) { newServers.add(s); return@forEach } // already new format
            val tag = s["tag"] as? String ?: ""
            if (address.startsWith("rcode://")) { blockTags.add(tag); return@forEach }
            val n = parseLegacyAddress(address)
            n["tag"] = tag
            s["detour"]?.let { if (it != "") n["detour"] = it }
            val resolver = s["address_resolver"] as? String
            val strategy = s["strategy"] as? String
            if (!resolver.isNullOrEmpty()) {
                val dr = mutableMapOf<String, Any?>("server" to resolver)
                if (!strategy.isNullOrEmpty()) dr["strategy"] = strategy
                n["domain_resolver"] = dr
            }
            if (n["type"] == "fakeip" && fakeip != null) {
                fakeip["inet4_range"]?.let { n["inet4_range"] = it }
                fakeip["inet6_range"]?.let { n["inet6_range"] = it }
            }
            if (tag == "dns-remote" && !strategy.isNullOrEmpty()) globalStrategy = strategy
            newServers.add(n)
        }
        dns["servers"] = newServers
        if (globalStrategy != null && dns["strategy"] == null) dns["strategy"] = globalStrategy

        // rules: block server -> predefined rcode; outbound any -> default domain resolver
        var needDefaultResolver = false
        val rules = list(dns["rules"])
        if (rules != null) {
            val it = rules.iterator()
            while (it.hasNext()) {
                val r = map(it.next()) ?: continue
                val ob = r["outbound"]
                if (ob is List<*> && ob.contains("any") && r["domain"] == null && r["domain_suffix"] == null
                    && r["rule_set"] == null && r["domain_regex"] == null && r["domain_keyword"] == null
                ) {
                    needDefaultResolver = true
                    it.remove()
                    continue
                }
                val server = r["server"] as? String
                if (server != null && server in blockTags) {
                    r.remove("server")
                    r.remove("disable_cache")
                    r["action"] = "predefined"
                    r["rcode"] = "NOERROR"
                }
            }
        }
        val directTag = newServers.firstNotNullOfOrNull { map(it)?.takeIf { m -> m["tag"] == "dns-direct" }?.get("tag") } as? String
        if (needDefaultResolver && directTag != null && route["default_domain_resolver"] == null) {
            route["default_domain_resolver"] = directTag
        }
        return directTag
    }

    private fun migrateOutbounds(root: MutableMap<String, Any?>, resolverTag: String?) {
        val outbounds = list(root["outbounds"]) ?: return
        val endpoints = list(root["endpoints"]) ?: mutableListOf<Any?>()
        val it = outbounds.iterator()
        val moved = mutableListOf<Any?>()
        while (it.hasNext()) {
            val ob = map(it.next()) ?: continue
            val ds = ob.remove("domain_strategy") as? String
            if (!ds.isNullOrEmpty() && ob["domain_resolver"] == null && resolverTag != null) {
                ob["domain_resolver"] = mutableMapOf<String, Any?>("server" to resolverTag, "strategy" to ds)
            }
            if (ob["type"] == "wireguard" && ob["peers"] == null) {
                val ep = LinkedHashMap<String, Any?>()
                ep["type"] = "wireguard"
                ep["tag"] = ob["tag"]
                ep["address"] = ob["local_address"] ?: ob["address"] ?: listOf<Any?>()
                ep["private_key"] = ob["private_key"]
                ob["mtu"]?.let { ep["mtu"] = it }
                val peer = LinkedHashMap<String, Any?>()
                peer["address"] = ob["server"]
                peer["port"] = ob["server_port"]
                peer["public_key"] = ob["peer_public_key"]
                ob["pre_shared_key"]?.let { if (it != "") peer["pre_shared_key"] = it }
                peer["allowed_ips"] = listOf("0.0.0.0/0", "::/0")
                ob["reserved"]?.let { peer["reserved"] = it }
                ep["peers"] = mutableListOf<Any?>(peer)
                for (k in listOf("detour", "bind_interface", "routing_mark", "connect_timeout", "domain_resolver", "tcp_fast_open", "udp_fragment")) {
                    ob[k]?.let { v -> ep[k] = v }
                }
                moved.add(ep)
                it.remove()
            }
        }
        if (moved.isNotEmpty()) {
            endpoints.addAll(moved)
            root["endpoints"] = endpoints
        }
    }
}
