package io.nekohasekai.sagernet.widget

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** Real exit IP + country (+ ASN/org when the source provides it), looked up THROUGH the
 *  tunnel (local mixed inbound). */
data class ExitIpInfo(
    val ip: String,
    val countryCode: String,
    val country: String,
    val asn: String? = null,
    val org: String? = null,
) {

    /** "🇫🇮 Finland" */
    val label: String get() = "${flag(countryCode)} $country".trim()

    companion object {
        fun flag(code: String): String {
            val c = code.uppercase()
            if (c.length != 2 || c.any { it !in 'A'..'Z' }) return "🌐"
            return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) +
                    String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
        }

        private fun str(j: JSONObject, vararg keys: String): String? {
            for (k in keys) {
                val v = j.opt(k) ?: continue
                val s = v.toString().trim()
                if (s.isNotEmpty() && s != "null") return s
            }
            return null
        }

        /** Normalizes `12322`, `"12322"`, or `"AS12322"` -> `"AS12322"`. */
        private fun normalizeAsn(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val m = Regex("^(?:AS)?(\\d+)$", RegexOption.IGNORE_CASE).find(raw.trim())
            return if (m != null) "AS${m.groupValues[1]}" else raw
        }

        /** Accepts a 2-letter code directly, or falls back to a `country` field only when
         *  it also happens to be 2 letters (some APIs reuse that key for the code). */
        private fun countryCode(j: JSONObject): String? {
            val code = str(j, "country_code", "countryCode", "cc")
            if (code != null && code.length == 2) return code.uppercase()
            val country = str(j, "country")
            if (country != null && country.length == 2) return country.uppercase()
            return code?.uppercase()
        }

        // browserleaks.com/ip has no public JSON API, so free JSON geo-IP services are used
        // (same data source family: MaxMind/IP2Location/RIR data). First one that answers wins.
        // Each source may use different field names for the same data, so every parser pulls
        // from a small set of likely keys instead of assuming one exact schema.
        private val ENDPOINTS = listOf(
            "https://ipwho.is/" to fun(j: JSONObject): ExitIpInfo? {
                if (!j.optBoolean("success", true)) return null
                val ip = str(j, "ip") ?: return null
                val cc = countryCode(j) ?: return null
                // ipwho.is nests ASN/org under "connection": {"asn": 12322, "org": "..."}
                val conn = j.optJSONObject("connection")
                val asn = normalizeAsn(conn?.let { str(it, "asn") } ?: str(j, "asn"))
                val org = conn?.let { str(it, "org", "isp") } ?: str(j, "org", "isp")
                return ExitIpInfo(ip, cc, str(j, "country") ?: cc, asn, org)
            },
            "https://ipapi.co/json/" to fun(j: JSONObject): ExitIpInfo? {
                val ip = str(j, "ip") ?: return null
                val cc = countryCode(j) ?: return null
                return ExitIpInfo(
                    ip, cc, str(j, "country_name") ?: cc,
                    normalizeAsn(str(j, "asn")), str(j, "org"),
                )
            },
            "https://api.ip.sb/geoip/" to fun(j: JSONObject): ExitIpInfo? {
                val ip = str(j, "ip") ?: return null
                val cc = countryCode(j) ?: return null
                return ExitIpInfo(
                    ip, cc, str(j, "country") ?: cc,
                    normalizeAsn(str(j, "asn")), str(j, "asn_organization", "organization"),
                )
            },
            "https://ipinfo.io/json" to fun(j: JSONObject): ExitIpInfo? {
                val ip = str(j, "ip") ?: return null
                val cc = countryCode(j) ?: return null
                // ipinfo.io often returns org as "AS12322 Free SAS"; split the ASN out of it.
                val orgRaw = str(j, "org")
                var asn = normalizeAsn(str(j, "asn"))
                var org = orgRaw
                if (asn == null && orgRaw != null) {
                    val m = Regex("^(AS\\d+)\\s+(.*)$").find(orgRaw)
                    if (m != null) {
                        asn = m.groupValues[1]
                        org = m.groupValues[2]
                    }
                }
                return ExitIpInfo(ip, cc, str(j, "country") ?: cc, asn, org)
            },
            "https://api.myip.com/" to fun(j: JSONObject): ExitIpInfo? {
                val ip = str(j, "ip") ?: return null
                val cc = countryCode(j) ?: return null
                return ExitIpInfo(ip, cc, str(j, "country") ?: cc)
            },
            "https://ifconfig.co/json" to fun(j: JSONObject): ExitIpInfo? {
                val ip = str(j, "ip") ?: return null
                val cc = str(j, "country_iso", "country_code")?.uppercase() ?: return null
                return ExitIpInfo(
                    ip, cc, str(j, "country") ?: cc,
                    normalizeAsn(str(j, "asn")), str(j, "asn_org", "org"),
                )
            },
        )

        /** Blocking; call from a background thread. Returns null if every service fails. */
        fun fetch(mixedPort: Int): ExitIpInfo? {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", mixedPort))
            for ((url, parse) in ENDPOINTS) {
                try {
                    val c = URL(url).openConnection(proxy) as HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    c.setRequestProperty("User-Agent", "Mozilla/5.0")
                    try {
                        if (c.responseCode != 200) continue
                        val info = parse(JSONObject(c.inputStream.bufferedReader().readText()))
                        if (info != null && info.ip.isNotBlank()) return info
                    } finally {
                        c.disconnect()
                    }
                } catch (_: Exception) {
                }
            }
            return null
        }

        private val IP_REGEX = Regex("^[0-9a-fA-F:.]{3,45}$")

        /**
         * Plain exit IP as reported by https://2ip.io/, looked up THROUGH the tunnel
         * (local mixed inbound). 2ip.io answers non-browser requests with just the IP as
         * plain text (no JSON), so this is a lighter/quicker check than [fetch].
         * Blocking; call from a background thread. Returns null on any failure.
         */
        fun fetchPlainIp(mixedPort: Int): String? {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", mixedPort))
            try {
                val c = URL("https://2ip.io/").openConnection(proxy) as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                // a plain, non-browser User-Agent makes 2ip.io return bare text instead of HTML
                c.setRequestProperty("User-Agent", "curl/8.0")
                try {
                    if (c.responseCode != 200) return null
                    val text = c.inputStream.bufferedReader().readText().trim()
                    return if (IP_REGEX.matches(text)) text else null
                } finally {
                    c.disconnect()
                }
            } catch (_: Exception) {
                return null
            }
        }
    }
}
