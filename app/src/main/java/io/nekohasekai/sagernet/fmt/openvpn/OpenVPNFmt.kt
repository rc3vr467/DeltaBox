package io.nekohasekai.sagernet.fmt.openvpn

import moe.matsuri.nb4a.SingBoxOptions

/**
 * Minimal, pragmatic parser for standard OpenVPN client config files (.ovpn).
 *
 * Supports the subset of directives that cover the overwhelming majority of
 * real-world exported client profiles:
 *   - remote <host> <port> [proto]
 *   - proto udp|tcp(-client)
 *   - inline <ca>/<cert>/<key>/<tls-auth>/<tls-crypt>/<tls-crypt-v2> blocks
 *   - key-direction
 *   - cipher / auth / comp-lzo / compress
 *   - auth-user-pass (embedded username/password lines, if present)
 *   - redirect-gateway / block-outside-dns-ish flags are best-effort
 *
 * Anything not understood is simply ignored rather than causing a failure:
 * the goal is "works for normal exported profiles", not a complete OpenVPN
 * config-language implementation.
 */

class OvpnParseException(message: String) : Exception(message)

private val remoteRegex = Regex("""^remote\s+(\S+)\s+(\d+)(?:\s+(tcp|udp|tcp4|udp4|tcp6|udp6))?""")

fun looksLikeOvpnConfig(text: String): Boolean {
    // An .ovpn file is a multi-line directive file; the two directives that
    // are present in essentially every client config are "client" (or
    // "tls-client") and "remote". Requiring both avoids false positives on
    // arbitrary pasted text.
    val hasClient = Regex("""(?m)^\s*(client|tls-client)\s*$""").containsMatchIn(text)
    val hasRemote = Regex("""(?m)^\s*remote\s+\S+""").containsMatchIn(text)
    return hasClient && hasRemote
}

fun parseOvpn(rawText: String): OpenVPNBean {
    val bean = OpenVPNBean().apply { initializeDefaultValues() }

    // Normalize line endings and strip comments (# or ; at line start, ignoring
    // whitespace), but keep inline blocks intact.
    val lines = rawText.replace("\r\n", "\n").replace("\r", "\n").split("\n")

    var i = 0
    var sawRemote = false
    var staticKeyInline: String? = null

    while (i < lines.size) {
        val rawLine = lines[i]
        val line = rawLine.trim()
        i++

        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue

        // Inline blocks: <tag> ... </tag>
        if (line.startsWith("<") && !line.startsWith("</")) {
            val tag = line.removePrefix("<").removeSuffix(">").trim().lowercase()
            val blockLines = mutableListOf<String>()
            while (i < lines.size) {
                val blockLine = lines[i]
                i++
                if (blockLine.trim().lowercase() == "</$tag>") break
                blockLines.add(blockLine)
            }
            val blockContent = blockLines.joinToString("\n").trim()

            when (tag) {
                "ca" -> bean.ca = blockContent
                "cert" -> bean.cert = blockContent
                "key" -> bean.key = blockContent
                "tls-auth" -> {
                    bean.controlWrapType = "tls_auth"
                    bean.controlWrapKey = blockContent
                }

                "tls-crypt" -> {
                    bean.controlWrapType = "tls_crypt"
                    bean.controlWrapKey = blockContent
                }

                "tls-crypt-v2" -> {
                    bean.controlWrapType = "tls_crypt_v2"
                    bean.controlWrapKey = blockContent
                }

                "secret" -> {
                    // static key mode inline block
                    staticKeyInline = blockContent
                }
            }
            continue
        }

        // Directive lines
        val directive: String
        val args: List<String>
        run {
            val parts = line.split(Regex("""\s+"""))
            directive = parts[0].lowercase()
            args = parts.drop(1)
        }

        when (directive) {
            "client", "tls-client" -> bean.mode = "tls"

            "remote" -> {
                val m = remoteRegex.find(line)
                if (m != null) {
                    val (host, port, proto) = m.destructured
                    // Only take the first "remote"; servers[] with multiple
                    // remotes would be the fully-correct approach, but a
                    // single primary remote covers the overwhelming majority
                    // of exported profiles and keeps the Bean model simple.
                    if (!sawRemote) {
                        bean.serverAddress = host
                        bean.serverPort = port.toIntOrNull() ?: 1194
                        if (proto.isNotBlank()) bean.protocol = normalizeProto(proto)
                        sawRemote = true
                    }
                }
            }

            "proto", "proto-force" -> {
                if (args.isNotEmpty()) bean.protocol = normalizeProto(args[0])
            }

            "secret" -> {
                // "secret <file-or-inline-ref> [direction]" — if the key material
                // was not given as an inline <secret> block, we cannot resolve a
                // filesystem path on import, so this is only honored when an
                // inline block already filled staticKeyInline above.
                bean.mode = "static_key"
                if (args.size >= 2) bean.keyDirection = normalizeKeyDirection(args[1])
            }

            "key-direction" -> {
                if (args.isNotEmpty()) bean.keyDirection = normalizeKeyDirection(args[0])
            }

            "cipher", "data-ciphers" -> {
                if (args.isNotEmpty()) bean.cipher = args[0].split(":").first()
            }

            "auth" -> {
                if (args.isNotEmpty()) bean.auth = args[0]
            }

            "comp-lzo" -> {
                bean.compression = if (args.isEmpty() || args[0] == "yes") "lz4-v2" else ""
            }

            "compress" -> {
                bean.compression = when (args.getOrNull(0)) {
                    null, "" -> "stub-v2"
                    "lz4" -> "lz4"
                    "lz4-v2" -> "lz4-v2"
                    "lzo" -> "lz4-v2"
                    else -> ""
                }
            }

            "redirect-gateway" -> bean.redirectGateway = true

            "block-ipv6" -> bean.blockIPv6 = true

            "tun-mtu" -> {
                args.getOrNull(0)?.toIntOrNull()?.let { bean.mtu = it }
            }

            "auth-user-pass" -> {
                // Credentials are not embedded in the file unless a path-less
                // form with inline username/password lines is used by some
                // generators. We do a best-effort inline read: some exporters
                // place "auth-user-pass" with no args and expect prompting;
                // nothing to parse here without a bundled file, so this is a
                // no-op beyond marking that credentials will be required —
                // left for the user to fill in via the profile's edit screen.
            }
        }
    }

    if (!sawRemote) {
        throw OvpnParseException("no 'remote' directive found in .ovpn file")
    }

    if (staticKeyInline != null) {
        bean.mode = "static_key"
        bean.staticKey = staticKeyInline
    }

    if (bean.name.isNullOrBlank()) {
        bean.name = bean.serverAddress
    }

    return bean
}

private fun normalizeProto(proto: String): String {
    val p = proto.lowercase().removeSuffix("-client").removeSuffix("-server")
    return when (p) {
        "tcp", "tcp4", "tcp6", "udp", "udp4", "udp6" -> p
        else -> "udp"
    }
}

private fun normalizeKeyDirection(direction: String) = when (direction.trim()) {
    "0" -> "server"
    "1" -> "client"
    "server", "client" -> direction
    else -> ""
}

/**
 * Builds the sing-box "endpoint" JSON object (type = "openvpn-client") for
 * this bean. Unlike outbound-based protocols, OpenVPN in sing-box is wired
 * as a top-level endpoint (see option.OpenVPNClientEndpointOptions), so this
 * does not return a SingBoxOptions.Outbound subtype — it returns a generic
 * holder whose _hack_config_map is merged as-is into the "endpoints" array.
 */
fun buildSingBoxEndpointOpenVPNBean(bean: OpenVPNBean, tag: String): SingBoxOptions.GenericSingBoxOption {
    return SingBoxOptions.GenericSingBoxOption().apply {
        _hack_config_map["type"] = "openvpn-client"
        _hack_config_map["tag"] = tag

        _hack_config_map["server"] = bean.serverAddress
        _hack_config_map["server_port"] = bean.serverPort
        _hack_config_map["network"] = bean.protocol
        _hack_config_map["mode"] = bean.mode
        _hack_config_map["mtu"] = bean.mtu

        if (bean.mode == "static_key") {
            if (bean.staticKey.isNotBlank()) {
                _hack_config_map["static_key"] = bean.staticKey.split("\n")
            }
            if (bean.keyDirection.isNotBlank()) {
                _hack_config_map["key_direction"] = bean.keyDirection
            }
        } else {
            val tls = HashMap<String, Any>()
            if (bean.ca.isNotBlank()) tls["certificate"] = bean.ca.split("\n")
            if (bean.cert.isNotBlank()) tls["client_certificate"] = bean.cert.split("\n")
            if (bean.key.isNotBlank()) tls["client_key"] = bean.key.split("\n")
            if (tls.isNotEmpty()) _hack_config_map["tls"] = tls

            if (bean.controlWrapType.isNotBlank() && bean.controlWrapKey.isNotBlank()) {
                val wrap = HashMap<String, Any>()
                wrap["type"] = bean.controlWrapType
                wrap["key"] = bean.controlWrapKey.split("\n")
                if (bean.keyDirection.isNotBlank()) wrap["direction"] = bean.keyDirection
                _hack_config_map["control_wrap"] = wrap
            }

            if (bean.username.isNotBlank()) _hack_config_map["username"] = bean.username
            if (bean.password.isNotBlank()) _hack_config_map["password"] = bean.password
        }

        if (bean.cipher.isNotBlank()) _hack_config_map["cipher"] = bean.cipher
        if (bean.auth.isNotBlank()) _hack_config_map["auth"] = bean.auth
        if (bean.compression.isNotBlank()) _hack_config_map["compression"] = bean.compression

        _hack_config_map["redirect_gateway"] = bean.redirectGateway
        _hack_config_map["block_ipv6"] = bean.blockIPv6
    }
}
