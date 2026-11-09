package io.nekohasekai.sagernet.widget

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Finds the country of the public exit IP by asking a geolocation API THROUGH the running
 * core's local mixed inbound, so the request always leaves via the VPN tunnel.
 * The proxy address is unresolved, so the hostname is resolved by the tunnel (no local DNS leak).
 * The IP itself is never returned, stored or logged; only the 2-letter country code is kept.
 */
object ExitCountry {
    private const val ENDPOINT = "https://ipwho.is/?fields=success,country_code"
    private val codeRegex = Regex("^[A-Z]{2}$")

    /** Returns an ISO 3166-1 alpha-2 code such as "TR", or null when unavailable. Blocking: call off the main thread. */
    fun fetch(): String? {
        return try {
            val proxy = Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved("127.0.0.1", DataStore.mixedPort)
            )
            val conn = URL(ENDPOINT).openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            try {
                if (conn.responseCode != 200) return null
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                if (!json.optBoolean("success", false)) return null
                json.optString("country_code", "").takeIf { codeRegex.matches(it) }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Logs.w("Exit country lookup unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Converts "TR" to the regional-indicator flag emoji 🇹🇷. */
    fun flagOf(code: String): String {
        val base = 0x1F1E6
        return String(Character.toChars(base + (code[0] - 'A'))) +
                String(Character.toChars(base + (code[1] - 'A')))
    }
}
