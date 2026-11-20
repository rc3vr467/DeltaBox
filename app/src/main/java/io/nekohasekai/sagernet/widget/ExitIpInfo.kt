package io.nekohasekai.sagernet.widget

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** Real exit IP + country, looked up THROUGH the tunnel (local mixed inbound). */
data class ExitIpInfo(val ip: String, val countryCode: String, val country: String) {

    /** "🇫🇮 Finland" */
    val label: String get() = "${flag(countryCode)} $country".trim()

    companion object {
        fun flag(code: String): String {
            val c = code.uppercase()
            if (c.length != 2 || c.any { it !in 'A'..'Z' }) return "🌐"
            return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) +
                    String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
        }

        // browserleaks.com/ip has no public JSON API, so free JSON geo-IP services are used
        // (same data source family: MaxMind/IP2Location). First one that answers wins.
        private val ENDPOINTS = listOf(
            "https://ipwho.is/" to { j: JSONObject ->
                if (!j.optBoolean("success", true)) null
                else ExitIpInfo(j.getString("ip"), j.optString("country_code"), j.optString("country"))
            },
            "https://ipapi.co/json/" to { j: JSONObject ->
                ExitIpInfo(j.getString("ip"), j.optString("country_code"), j.optString("country_name"))
            },
            "https://api.myip.com/" to { j: JSONObject ->
                ExitIpInfo(j.getString("ip"), j.optString("cc"), j.optString("country"))
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
