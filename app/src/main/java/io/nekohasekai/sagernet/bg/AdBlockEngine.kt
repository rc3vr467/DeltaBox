package io.nekohasekai.sagernet.bg

import android.content.Context
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.SingBoxOptions.RouteOptions
import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * AdBlock — ad / tracker domain blocking.
 *
 * Works exactly like Black Wall: a switch stored in DataStore.configurationStore,
 * applied by ConfigBuilder when the config for the *next* connection is generated.
 * It only appends ONE standard sing-box route rule ("reject" action, already used
 * by this app for user "block" rules) so it cannot break the core: no new rule-set
 * files, no new outbound, no core-version-specific field.
 *
 * Block list = built-in defaults (assets/adblock_default.txt)
 *            + hosts-format lists downloaded in the app (AdAway's default sources).
 */
object AdBlockEngine {

    private const val KEY_ENABLED = "ad_block_enabled"
    private const val MAX_DOMAINS = 60_000

    val SOURCES = listOf(
        "https://adaway.org/hosts.txt",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
    )

    private val store get() = DataStore.configurationStore

    var enabled: Boolean
        get() = store.getBoolean(KEY_ENABLED, false)
        set(value) {
            store.putBoolean(KEY_ENABLED, value)
            Logs.d("AdBlock: ${if (value) "ENABLED" else "DISABLED"}")
        }

    private fun listFile(context: Context) = File(context.filesDir, "adblock_domains.txt")

    // ---- parsing (same rules as AdAway's SourceLoader) --------------------------------
    private val HOSTS_LINE = Regex("^\\s*([^#\\s]+)\\s+([^#\\s]+).*$")
    private val SINK = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0")
    private val LOCAL = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0")
    private val DOMAIN =
        Regex("^(?=.{1,253}$)([a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?\\.)+[a-z0-9-]{2,63}$")

    internal fun parseLine(raw: String): String? {
        val line = raw.trim()
        if (line.isEmpty() || line[0] == '#' || line[0] == '!') return null
        val m = HOSTS_LINE.matchEntire(line)
        val host = when {
            m != null -> if (m.groupValues[1] in SINK) m.groupValues[2] else return null
            line.none { it.isWhitespace() } -> line
            else -> return null
        }.lowercase().trimEnd('.')
        return if (host !in LOCAL && DOMAIN.matches(host)) host else null
    }

    private fun readInto(reader: BufferedReader, out: MutableSet<String>) {
        reader.useLines { lines ->
            for (l in lines) {
                if (out.size >= MAX_DOMAINS) break
                parseLine(l)?.let { out.add(it) }
            }
        }
    }

    // ---- list management ---------------------------------------------------------------

    /** Downloads and merges the hosts sources. Blocking; call from a background thread. */
    fun updateLists(context: Context): Int {
        val domains = LinkedHashSet<String>()
        for (url in SOURCES) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                if (c.responseCode == 200) {
                    readInto(BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)), domains)
                }
                c.disconnect()
            } catch (e: Exception) {
                Logs.w("AdBlock: $url: ${e.message}")
            }
        }
        if (domains.isEmpty()) return 0 // keep the previous list when everything failed
        val tmp = File(context.filesDir, "adblock_domains.tmp")
        tmp.bufferedWriter().use { w -> domains.forEach { w.write(it); w.newLine() } }
        tmp.renameTo(listFile(context))
        return domains.size
    }

    fun loadDomains(context: Context): List<String> {
        val out = LinkedHashSet<String>()
        try {
            context.assets.open("adblock_default.txt").bufferedReader().let { readInto(it, out) }
        } catch (e: Exception) {
            Logs.w("AdBlock: default list: ${e.message}")
        }
        val f = listFile(context)
        if (f.exists()) try {
            f.bufferedReader().let { readInto(it, out) }
        } catch (e: Exception) {
            Logs.w("AdBlock: list: ${e.message}")
        }
        return out.toList()
    }

    fun domainCount(context: Context): Int = loadDomains(context).size

    // ---- config hook -------------------------------------------------------------------

    /**
     * Called by ConfigBuilder. [protectedHosts] (the proxy servers themselves) are never
     * blocked, so a server whose domain appears on a list can't cut its own tunnel.
     */
    fun applyToRoute(context: Context, route: RouteOptions, protectedHosts: Collection<String>) {
        if (!enabled) return
        try {
            val skip = protectedHosts.map { it.lowercase() }.toSet()
            val list = loadDomains(context).filter { d -> skip.none { it == d || it.endsWith(".$d") } }
            if (list.isEmpty()) return
            route.rules.add(0, Rule_DefaultOptions().apply {
                domain_suffix = list
                action = "reject"
            })
            Logs.d("AdBlock: ${list.size} domains")
        } catch (e: Exception) {
            // Never let ad blocking stop the core from starting.
            Logs.w("AdBlock: skipped: ${e.message ?: e}")
        }
    }
}
