package io.nekohasekai.sagernet.fmt.openvpn

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts a standard OpenVPN (.ovpn) profile into a sing-box "openvpn-client"
 * endpoint JSON fragment, so it can run under the sing-box core already bundled
 * in DeltaBox (requires sing-box built with the `with_openvpn` Go build tag).
 *
 * Ported from ZedSecure's OvpnConfig.kt (dev.cluvex.zedsecure.domain.config),
 * rewritten against org.json instead of kotlinx.serialization.json to match
 * the JSON stack already used throughout DeltaBox's fmt/ package.
 *
 * This file only produces JSON. It does not read/write files, start the VPN,
 * or touch the UI; see OpenVpnBean.java for the stored profile model and
 * BoxInstance.kt / ProxyEntity.kt for how the produced endpoint is merged
 * into the sing-box config DeltaBox actually launches.
 */
object OpenVpnFmt {

    const val SINGBOX_ENDPOINT_TYPE = "openvpn-client"

    enum class Reason {
        NoRemote,
        Tap,
        ExternalFiles,
        UnsupportedCredentials,
    }

    class UnsupportedException(val reason: Reason, val detail: String = "") :
        Exception("OpenVPN profile unsupported ($reason)${if (detail.isBlank()) "" else ": $detail"}")

    data class Info(
        val host: String,
        val port: Int,
        val network: String,
        val needsCredentials: Boolean,
        val hasInlineCredentials: Boolean,
        val name: String,
    )

    private data class Directive(val name: String, val args: List<String>)
    private data class Remote(val host: String, val port: Int, val network: String)

    @JvmStatic
    fun looksLikeOvpn(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("{") || trimmed.startsWith("[")) return false
        if (trimmed.startsWith("[Interface]", ignoreCase = true)) return false // WireGuard, not OpenVPN
        val directives = directives(trimmed)
        if (directives.none { it.name == "remote" }) return false
        val marks = directives.count { it.name in OVPN_MARKERS } + blocks(trimmed).keys.count { it in INLINE_BLOCKS }
        return marks > 0
    }

    @JvmStatic
    fun inspect(text: String): Info {
        val directives = directives(text)
        val blocks = blocks(text)
        refuseWhatCannotRun(directives, blocks)
        val remote = remotes(directives).firstOrNull() ?: throw UnsupportedException(Reason.NoRemote)
        val credentials = inlineCredentials(blocks)
        return Info(
            host = remote.host,
            port = remote.port,
            network = remote.network,
            needsCredentials = directives.any { it.name == "auth-user-pass" } || credentials != null,
            hasInlineCredentials = credentials != null,
            name = comment(text) ?: remote.host,
        )
    }

    /**
     * Returns a single sing-box "openvpn-client" endpoint object (NOT wrapped in
     * {"endpoints":[...]}), so callers can insert it into whatever top-level
     * config they are building (see OpenVpnBean.buildOpenVpnEndpoint).
     */
    @JvmStatic
    fun toSingBoxEndpoint(
        text: String,
        tag: String,
        username: String? = null,
        password: String? = null,
    ): JSONObject {
        val directives = directives(text)
        val blocks = blocks(text)
        refuseWhatCannotRun(directives, blocks)
        val remotes = remotes(directives)
        val first = remotes.firstOrNull() ?: throw UnsupportedException(Reason.NoRemote)
        val inline = inlineCredentials(blocks)

        val staticKey = blocks["secret"] ?: blocks["static-key"]

        val endpoint = JSONObject()
        endpoint.put("type", SINGBOX_ENDPOINT_TYPE)
        endpoint.put("tag", tag.ifBlank { comment(text) ?: first.host })

        if (remotes.size > 1) {
            val servers = JSONArray()
            remotes.forEach { r ->
                servers.put(
                    JSONObject().apply {
                        put("server", r.host)
                        put("server_port", r.port)
                        put("network", r.network)
                    },
                )
            }
            endpoint.put("servers", servers)
        } else {
            endpoint.put("server", first.host)
            endpoint.put("server_port", first.port)
        }
        endpoint.put("network", first.network)
        if (directives.flag("remote-random")) endpoint.put("remote_random", true)

        if (staticKey != null) {
            endpoint.put("mode", "static_key")
            endpoint.put("static_key", JSONArray(staticKey))
            keyDirection(directives, "secret")?.let { endpoint.put("key_direction", it) }

            ifconfig(directives)?.let { (local, peer) ->
                endpoint.put("address", JSONArray(listOf(local)))
                endpoint.put("peer_address", peer)
            }
            directives.first("cipher")?.firstOrNull()?.let { endpoint.put("cipher", it) }
        } else {
            endpoint.put("tls", tls(directives, blocks) ?: JSONObject())
            (username ?: inline?.first)?.takeIf { it.isNotBlank() }?.let { endpoint.put("username", it) }
            (password ?: inline?.second)?.takeIf { it.isNotBlank() }?.let { endpoint.put("password", it) }
            directives.first("static-challenge")?.let { args ->
                args.getOrNull(0)?.let { endpoint.put("static_challenge", it) }
                if (args.getOrNull(1) == "1") endpoint.put("static_challenge_echo", true)
            }

            val legacyCipher = directives.first("cipher")?.firstOrNull()
            val ciphers = directives.first("data-ciphers")?.firstOrNull()
                ?.split(':')?.filter { it.isNotBlank() }
                .orEmpty()
            // A bare "cipher X" with no "data-ciphers" is NOT the same as
            // "only negotiate X". Per OpenVPN's own NCP rules, a legacy
            // "cipher" directive (with no data-ciphers) is a *fallback* for
            // servers that don't support NCP at all - real OpenVPN 2.5+
            // clients still propose their normal modern default list
            // (AES-256-GCM:AES-128-GCM[:CHACHA20-POLY1305]) during NCP, and
            // only use the configured cipher if the server never negotiates
            // one. Forcing data_ciphers down to just the legacy cipher (as
            // this used to do) breaks any modern server that merely lists
            // "cipher AES-256-CBC" for backward compatibility but actually
            // negotiates an AEAD cipher via NCP - the server has no shared
            // cipher with that single-entry proposal and kills the data
            // channel with "Data channel cipher negotiation failed (no
            // shared cipher)". So: leave data_ciphers alone (empty = sing-box
            // proposes its own sane modern defaults for NCP) and only ever
            // use the legacy cipher as data_ciphers_fallback, exactly like a
            // real client does.
            if (ciphers.isNotEmpty()) endpoint.put("data_ciphers", JSONArray(ciphers))

            val fallback = directives.first("data-ciphers-fallback")?.firstOrNull()
                ?: legacyCipher
            fallback?.let { endpoint.put("data_ciphers_fallback", it) }

            directives.number("reneg-sec")?.let {
                if (it == 0) endpoint.put("renegotiate_disabled", true)
                else endpoint.put("renegotiate_interval", "${it}s")
            }

            if (directives.flag("route-nopull")) endpoint.put("route_no_pull", true)
            val pullFilters = directives.filter { it.name == "pull-filter" }.mapNotNull { d ->
                val action = d.args.getOrNull(0)?.lowercase()?.takeIf { it in PULL_FILTER_ACTIONS }
                val filterText = d.args.getOrNull(1)
                if (action == null || filterText == null) null else action to filterText
            }
            if (pullFilters.isNotEmpty()) {
                val arr = JSONArray()
                pullFilters.forEach { (action, filterText) ->
                    arr.put(JSONObject().apply {
                        put("action", action)
                        put("text", filterText)
                    })
                }
                endpoint.put("pull_filters", arr)
            }
        }

        val routes = localRoutes(directives)
        if (routes.isNotEmpty()) endpoint.put("routes", JSONArray(routes))
        directives.first("redirect-gateway")?.let { flags ->
            endpoint.put("redirect_gateway", true)
            if (flags.isNotEmpty()) endpoint.put("redirect_gateway_flags", JSONArray(flags))
        }
        if (directives.flag("redirect-private")) endpoint.put("redirect_private", true)
        if (directives.flag("block-ipv6")) endpoint.put("block_ipv6", true)

        directives.first("auth")?.firstOrNull()?.let { endpoint.put("auth", it) }

        directives.first("compress")?.let { endpoint.put("compression", it.firstOrNull() ?: "stub") }
        directives.first("comp-lzo")?.let { endpoint.put("compression_lzo", it.firstOrNull() ?: "yes") }
        directives.first("allow-compression")?.firstOrNull()?.let { endpoint.put("allow_compression", it) }

        directives.number("mssfix")?.let {
            if (it == 0) endpoint.put("mss_fix_disabled", true) else endpoint.put("mss_fix", it)
        }
        directives.first("mssfix")?.getOrNull(1)?.let { endpoint.put("mss_fix_mode", it) }
        directives.number("fragment")?.let { endpoint.put("fragment", it) }
        directives.number("tun-mtu")?.let { endpoint.put("mtu", it) }
        directives.number("replay-window")?.let { endpoint.put("replay_window", it) }
        directives.first("replay-window")?.getOrNull(1)?.toIntOrNull()
            ?.let { endpoint.put("replay_window_time", "${it}s") }

        directives.first("auth-retry")?.firstOrNull()
            ?.takeIf { it == "none" || it == "nointeract" || it == "interact" }
            ?.let { endpoint.put("auth_retry", it) }

        val keepalive = directives.first("keepalive")
        val pingInterval = keepalive?.getOrNull(0)?.toIntOrNull() ?: directives.number("ping")
        val pingRestart = keepalive?.getOrNull(1)?.toIntOrNull() ?: directives.number("ping-restart")
        pingInterval?.let { endpoint.put("ping_interval", "${it}s") }
        pingRestart?.let { endpoint.put("ping_restart", "${it}s") }
        directives.first("explicit-exit-notify")?.let {
            endpoint.put("explicit_exit_notify", it.firstOrNull()?.toIntOrNull() ?: 1)
        }

        return endpoint
    }

    private fun tls(directives: List<Directive>, blocks: Map<String, List<String>>): JSONObject? {
        val ca = blocks["ca"]
        val cert = blocks["cert"]
        val key = blocks["key"]
        val wrapType = when {
            blocks.containsKey("tls-crypt-v2") -> "tls_crypt_v2"
            blocks.containsKey("tls-crypt") -> "tls_crypt"
            blocks.containsKey("tls-auth") -> "tls_auth"
            else -> null
        }
        val verify = directives.first("verify-x509-name")
        val remoteCert = directives.first("remote-cert-tls")?.firstOrNull()
        val versionMin = directives.first("tls-version-min")?.firstOrNull()
        val versionMax = directives.first("tls-version-max")?.firstOrNull()
        val tlsCipher = tlsCipherList(directives.first("tls-cipher")?.firstOrNull())
        val groups = tlsGroupList(directives.first("tls-groups")?.firstOrNull())

        val fingerprints = (blocks["peer-fingerprint"] ?: directives.first("peer-fingerprint"))
            ?.mapNotNull(::certFingerprint)?.takeIf { it.isNotEmpty() }
        val remoteKu = directives.first("remote-cert-ku")?.takeIf { it.isNotEmpty() }
        val remoteEku = directives.first("remote-cert-eku")?.joinToString(" ")?.takeIf { it.isNotBlank() }
        val nsCertType = directives.first("ns-cert-type")?.firstOrNull()
        if (ca == null && cert == null && key == null && wrapType == null && verify == null &&
            remoteCert == null && versionMin == null && versionMax == null && tlsCipher == null &&
            groups == null && fingerprints == null && remoteKu == null && remoteEku == null &&
            nsCertType == null
        ) {
            return null
        }
        val out = JSONObject()
        ca?.let { out.put("certificate", JSONArray(it)) }
        cert?.let { out.put("client_certificate", JSONArray(it)) }
        key?.let { out.put("client_key", JSONArray(it)) }
        verify?.let { args ->
            args.getOrNull(0)?.let { out.put("server_name", it) }
            out.put("server_name_type", args.getOrNull(1) ?: "subject")
        }
        remoteCert?.let { out.put("remote_certificate_tls", it) }
        versionMin?.let { out.put("version_min", it) }
        versionMax?.let { out.put("version_max", it) }
        tlsCipher?.let { out.put("cipher", it) }
        groups?.let { out.put("groups", it) }
        fingerprints?.let { out.put("peer_fingerprint", JSONArray(it)) }
        remoteKu?.let { out.put("remote_certificate_ku", JSONArray(it)) }
        remoteEku?.let { out.put("remote_certificate_eku", it) }
        nsCertType?.let { out.put("ns_certificate_type", it) }
        if (wrapType != null) {
            val material = blocks["tls-crypt-v2"] ?: blocks["tls-crypt"] ?: blocks.getValue("tls-auth")
            val controlWrap = JSONObject()
            controlWrap.put("type", wrapType)
            controlWrap.put("key", JSONArray(material))
            if (wrapType == "tls_auth") {
                keyDirection(directives, "tls-auth")?.let { controlWrap.put("direction", it) }
            }
            out.put("control_wrap", controlWrap)
        }
        return out
    }

    private fun tlsGroupList(raw: String?): String? {
        val tokens = raw?.split(':')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() } ?: return null
        return tokens.filter { it in TLS_GROUP_NAMES }.distinct().joinToString(":").takeIf { it.isNotEmpty() }
    }

    private fun certFingerprint(raw: String): String? = raw
        .filterNot { it == ':' || it == ' ' || it == '-' }
        .lowercase()
        .takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    private fun tlsCipherList(raw: String?): String? {
        val tokens = raw?.split(':')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
        val usable = tokens
            .map { if (it.startsWith("TLS-", ignoreCase = true)) it.replace('-', '_') else it }
            .filter { it in TLS_CIPHER_NAMES }
            .distinct()
        return usable.joinToString(":").takeIf { it.isNotEmpty() }
    }

    private fun keyDirection(directives: List<Directive>, directive: String): String? {
        val value = directives.first("key-direction")?.firstOrNull()
            ?: directives.first(directive)?.lastOrNull()?.takeIf { it == "0" || it == "1" }
            ?: return null
        return when (value) {
            "1" -> "client"
            "0" -> "server"
            else -> null
        }
    }

    private fun refuseWhatCannotRun(directives: List<Directive>, blocks: Map<String, List<String>>) {
        directives.first("dev")?.firstOrNull()?.let {
            if (it.startsWith("tap")) throw UnsupportedException(Reason.Tap, it)
        }
        directives.first("dev-type")?.firstOrNull()?.let {
            if (it.startsWith("tap")) throw UnsupportedException(Reason.Tap, it)
        }
        listOf("pkcs12", "cryptoapicert", "pkcs11-id").forEach { name ->
            if (directives.any { it.name == name }) throw UnsupportedException(Reason.UnsupportedCredentials, name)
        }
        listOf("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "secret").forEach { name ->
            val named = directives.first(name)?.firstOrNull()
            if (named != null && !blocks.containsKey(name)) {
                throw UnsupportedException(Reason.ExternalFiles, "$name $named")
            }
        }
    }

    private val PULL_FILTER_ACTIONS = setOf("accept", "ignore", "reject")
    private val OUTSIDE_GATEWAYS = setOf("net_gateway", "remote_host")

    private fun localRoutes(directives: List<Directive>): List<String> {
        val out = mutableListOf<String>()
        directives.forEach { d ->
            when (d.name) {
                "route" -> {
                    if (d.args.getOrNull(2)?.lowercase() in OUTSIDE_GATEWAYS) return@forEach
                    val network = ipv4(d.args.getOrNull(0)) ?: return@forEach
                    val maskText = d.args.getOrNull(1)?.takeUnless { it.equals("default", ignoreCase = true) }
                    val mask = if (maskText == null) -1L else ipv4(maskText) ?: return@forEach
                    val bits = (mask and 0xffffffffL).countOneBits()
                    if ((mask and 0xffffffffL) != ((0xffffffffL shl (32 - bits)) and 0xffffffffL)) return@forEach
                    out += "${dotted(network and mask)}/$bits"
                }
                "route-ipv6" -> {
                    if (d.args.getOrNull(1)?.lowercase() in OUTSIDE_GATEWAYS) return@forEach
                    val value = d.args.getOrNull(0) ?: return@forEach
                    val address = value.substringBefore('/')
                    val bits = if ('/' in value) value.substringAfter('/').toIntOrNull() ?: return@forEach else 128
                    val plausible = ':' in address && bits in 0..128 &&
                        address.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
                    if (plausible) out += "$address/$bits"
                }
            }
        }
        return out.distinct()
    }

    private fun ipv4(value: String?): Long? {
        val parts = value?.split('.') ?: return null
        if (parts.size != 4) return null
        var out = 0L
        parts.forEach { part ->
            val octet = part.toIntOrNull()?.takeIf { part.isNotEmpty() && part.length <= 3 && it in 0..255 } ?: return null
            out = (out shl 8) or octet.toLong()
        }
        return out
    }

    private fun dotted(value: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { ((value shr it) and 0xff).toString() }

    private fun ifconfig(directives: List<Directive>): Pair<String, String>? {
        val args = directives.first("ifconfig") ?: return null
        val local = args.getOrNull(0)?.takeIf { it.count { c -> c == '.' } == 3 } ?: return null
        val peer = args.getOrNull(1)?.takeIf { it.count { c -> c == '.' } == 3 } ?: return null
        return "$local/32" to peer
    }

    private fun remotes(directives: List<Directive>): List<Remote> {
        val defaultPort = directives.number("port") ?: 1194
        val defaultNetwork = network(directives.first("proto")?.firstOrNull()) ?: "udp"
        return directives.filter { it.name == "remote" }.mapNotNull { d ->
            val host = d.args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Remote(
                host = host,
                port = d.args.getOrNull(1)?.toIntOrNull() ?: defaultPort,
                network = network(d.args.getOrNull(2)) ?: defaultNetwork,
            )
        }
    }

    private fun network(value: String?): String? = when {
        value == null -> null
        value.startsWith("tcp") -> "tcp"
        value.startsWith("udp") -> "udp"
        else -> null
    }

    private fun inlineCredentials(blocks: Map<String, List<String>>): Pair<String, String>? {
        val lines = blocks["auth-user-pass"]?.filter { it.isNotBlank() } ?: return null
        if (lines.size < 2) return null
        return lines[0] to lines[1]
    }

    private fun comment(text: String): String? = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { (it.startsWith("#") || it.startsWith(";")) && it.length > 2 }
        ?.drop(1)?.trim()?.takeIf { it.isNotBlank() && it.length <= 60 }

    private fun List<Directive>.first(name: String): List<String>? = firstOrNull { it.name == name }?.args
    private fun List<Directive>.flag(name: String): Boolean = any { it.name == name }
    private fun List<Directive>.number(name: String): Int? = first(name)?.firstOrNull()?.toIntOrNull()

    private fun directives(text: String): List<Directive> {
        val out = mutableListOf<Directive>()
        var skipUntil: String? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.equals("<connection>", ignoreCase = true) || line.equals("</connection>", ignoreCase = true)) {
                return@forEach
            }
            if (skipUntil != null) {
                if (line.equals(skipUntil, ignoreCase = true)) skipUntil = null
                return@forEach
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEach
            if (line.startsWith("<") && line.endsWith(">") && !line.startsWith("</")) {
                skipUntil = "</" + line.trim('<', '>') + ">"
                return@forEach
            }
            val tokens = tokenize(line)
            if (tokens.isNotEmpty()) out += Directive(tokens.first().lowercase(), tokens.drop(1))
        }
        return out
    }

    private fun blocks(text: String): Map<String, List<String>> {
        val out = mutableMapOf<String, List<String>>()
        var name: String? = null
        val lines = mutableListOf<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                name == null && line.startsWith("<") && line.endsWith(">") && !line.startsWith("</") -> {
                    name = line.trim('<', '>').lowercase()
                    lines.clear()
                }
                name != null && line.equals("</$name>", ignoreCase = true) -> {
                    out[name!!] = lines.toList()
                    name = null
                }
                name != null -> lines += line
            }
        }
        return out
    }

    private fun tokenize(line: String): List<String> {
        val out = mutableListOf<String>()
        val token = StringBuilder()
        var quoted = false
        line.forEach { c ->
            when {
                c == '"' -> quoted = !quoted
                c.isWhitespace() && !quoted -> {
                    if (token.isNotEmpty()) {
                        out += token.toString()
                        token.clear()
                    }
                }
                else -> token.append(c)
            }
        }
        if (token.isNotEmpty()) out += token.toString()
        return out
    }

    private val OVPN_MARKERS = setOf(
        "client", "dev", "proto", "ca", "cert", "key", "auth-user-pass", "tls-client", "remote-cert-tls",
        "cipher", "comp-lzo", "compress", "tls-auth", "tls-crypt", "resolv-retry", "persist-tun", "nobind",
    )

    private val INLINE_BLOCKS = setOf("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "secret")

    private val TLS_GROUP_NAMES: Set<String> = setOf(
        "X25519", "CURVE25519",
        "SECP256R1", "PRIME256V1", "P-256", "NISTP256",
        "SECP384R1", "P-384", "NISTP384",
        "SECP521R1", "P-521", "NISTP521",
    )

    private val TLS_CIPHER_NAMES: Set<String> = setOf(
        "RC4-SHA", "TLS_RSA_WITH_RC4_128_SHA",
        "DES-CBC3-SHA", "TLS_RSA_WITH_3DES_EDE_CBC_SHA",
        "AES128-SHA", "TLS_RSA_WITH_AES_128_CBC_SHA",
        "AES256-SHA", "TLS_RSA_WITH_AES_256_CBC_SHA",
        "AES128-SHA256", "TLS_RSA_WITH_AES_128_CBC_SHA256",
        "AES128-GCM-SHA256", "TLS_RSA_WITH_AES_128_GCM_SHA256",
        "AES256-GCM-SHA384", "TLS_RSA_WITH_AES_256_GCM_SHA384",
        "ECDHE-ECDSA-AES128-SHA", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
        "ECDHE-ECDSA-AES256-SHA", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
        "ECDHE-ECDSA-RC4-SHA", "TLS_ECDHE_ECDSA_WITH_RC4_128_SHA",
        "ECDHE-RSA-RC4-SHA", "TLS_ECDHE_RSA_WITH_RC4_128_SHA",
        "ECDHE-RSA-DES-CBC3-SHA", "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA",
        "ECDHE-RSA-AES128-SHA", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
        "ECDHE-RSA-AES256-SHA", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
        "ECDHE-ECDSA-AES128-SHA256", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
        "ECDHE-RSA-AES128-SHA256", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
        "ECDHE-ECDSA-AES128-GCM-SHA256", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "ECDHE-RSA-AES128-GCM-SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "ECDHE-ECDSA-AES256-GCM-SHA384", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "ECDHE-RSA-AES256-GCM-SHA384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "ECDHE-ECDSA-CHACHA20-POLY1305", "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "ECDHE-RSA-CHACHA20-POLY1305", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
    )
}
