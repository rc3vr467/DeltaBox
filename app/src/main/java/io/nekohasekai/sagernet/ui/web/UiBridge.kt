package io.nekohasekai.sagernet.ui.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BlackWallEngine
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The ONLY object exposed to JavaScript (as `NativeBridge`).
 *
 * Rules:
 *  - every method takes/returns primitives or JSON strings;
 *  - data is read from Room / DataStore on each call (HTML never persists it);
 *  - sensitive work (VPN, DB writes, clipboard, Intents) is done here in Kotlin;
 *  - inputs are validated (key whitelist, id checks, length limits).
 *
 * @JavascriptInterface methods are invoked on a WebView background thread, not the main thread.
 */
class UiBridge(private val host: WebMainActivity) {

    private val store get() = DataStore.configurationStore

    // ---------------------------------------------------------------- state

    @JavascriptInterface
    fun getState(): String = JSONObject().apply {
        put("service", host.serviceStateName())
        put("canStop", DataStore.serviceState.canStop)
        put("connected", DataStore.serviceState.connected)
        put("selectedProxy", DataStore.selectedProxy)
        put("selectedGroup", DataStore.selectedGroup)
        put("night", host.isNightMode())
        put("language", DataStore.appLanguage)
    }.toString()

    @JavascriptInterface
    fun toggleConnection() {
        host.runOnUiThread { host.toggleConnection() }
    }

    // ---------------------------------------------------------------- groups & profiles

    @JavascriptInterface
    fun getGroups(): String {
        val arr = JSONArray()
        for (g in SagerDatabase.groupDao.allGroups()) {
            arr.put(JSONObject().apply {
                put("id", g.id)
                put("name", g.displayName())
                put("ungrouped", g.ungrouped)
                put("type", g.type)
                put("count", SagerDatabase.proxyDao.countByGroup(g.id))
            })
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun selectGroup(groupId: Long): Boolean {
        if (SagerDatabase.groupDao.getById(groupId) == null) return false
        DataStore.selectedGroup = groupId
        return true
    }

    @JavascriptInterface
    fun getProfiles(groupId: Long): String {
        val selected = DataStore.selectedProxy
        val arr = JSONArray()
        var list = SagerDatabase.proxyDao.getByGroup(groupId)
        // same ordering rules as ConfigurationFragment.reloadProfiles()
        when (SagerDatabase.groupDao.getById(groupId)?.order) {
            GroupOrder.BY_NAME -> list = list.sortedBy { safe { it.displayName() } }
            GroupOrder.BY_DELAY -> list = list.sortedBy { if (it.status == 1) it.ping else 114514 }
        }
        for (p in list) arr.put(profileJson(p, selected))
        return arr.toString()
    }

    private fun profileJson(p: ProxyEntity, selected: Long) = JSONObject().apply {
        put("id", p.id)
        put("groupId", p.groupId)
        put("name", safe { p.displayName() })
        put("type", safe { p.displayType() })
        put("addr", safe { p.displayAddress() })
        put("status", p.status) // 0 untested, 1 ok (ping = ms), 2 plugin missing, 3 error
        put("ping", p.ping)
        put("error", p.error ?: "")
        put("tx", p.tx)
        put("rx", p.rx)
        put("selected", p.id == selected)
        put("reality", safe2 { (p.requireBean() as? StandardV2RayBean)?.realityPubKey.orEmpty().isNotBlank() })
    }

    private inline fun safe2(block: () -> Boolean): Boolean = try {
        block()
    } catch (_: Exception) {
        false
    }

    private inline fun safe(block: () -> String): String = try {
        block()
    } catch (_: Exception) {
        ""
    }

    /** Mirrors ConfigurationFragment: select, notify listeners, reload the service if running. */
    @JavascriptInterface
    fun selectProfile(profileId: Long): Boolean {
        if (SagerDatabase.proxyDao.getById(profileId) == null) return false
        runOnDefaultDispatcher {
            val last = DataStore.selectedProxy
            val update = last != profileId
            DataStore.selectedProxy = profileId
            if (update) {
                ProfileManager.postUpdate(last)
                ProfileManager.postUpdate(profileId)
                if (DataStore.serviceState.canStop) SagerNet.reloadService()
            }
        }
        return true
    }

    @JavascriptInterface
    fun deleteProfile(profileId: Long): Boolean {
        val p = SagerDatabase.proxyDao.getById(profileId) ?: return false
        runOnDefaultDispatcher { ProfileManager.deleteProfile(p.groupId, p.id) }
        return true
    }

    /** Imports share links / configs from text. Result is delivered as the `toast` event. */
    @JavascriptInterface
    fun importText(text: String?) {
        if (text.isNullOrBlank() || text.length > MAX_IMPORT_CHARS) {
            host.emit("toast", JSONObject().put("code", "import_invalid"))
            return
        }
        runOnDefaultDispatcher {
            try {
                val beans = parseProxies(text)
                if (beans.isEmpty()) {
                    host.emit("toast", JSONObject().put("code", "no_proxies"))
                } else {
                    val target = DataStore.selectedGroupForImport()
                    for (b in beans) ProfileManager.createProfile(target, b)
                    host.emit("toast", JSONObject().put("code", "imported").put("count", beans.size))
                }
            } catch (e: Exception) {
                host.emit(
                    "toast", JSONObject().put("code", "import_failed").put("msg", e.readableMessage)
                )
            }
        }
    }

    @JavascriptInterface
    fun importFromClipboard() {
        host.runOnUiThread {
            val cm = host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(host)?.toString()
            if (text.isNullOrBlank()) {
                host.emit("toast", JSONObject().put("code", "clipboard_empty"))
            } else {
                importText(text)
            }
        }
    }

    @JavascriptInterface
    fun copyText(text: String?) {
        if (text == null || text.length > MAX_IMPORT_CHARS) return
        host.runOnUiThread {
            val cm = host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DeltaBox", text))
        }
    }

    @JavascriptInterface
    fun shareText(text: String?) {
        if (text == null || text.length > MAX_IMPORT_CHARS) return
        host.runOnUiThread {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            host.startActivity(Intent.createChooser(send, null))
        }
    }

    // ---------------------------------------------------------------- profile maintenance (mirrors ConfigurationFragment)

    @JavascriptInterface
    fun renameProfile(profileId: Long, name: String?): Boolean {
        val n = name?.trim()
        if (n.isNullOrEmpty() || n.length > 200) return false
        val p = SagerDatabase.proxyDao.getById(profileId) ?: return false
        runOnDefaultDispatcher {
            p.requireBean().name = n
            ProfileManager.updateProfile(p)
        }
        return true
    }

    @JavascriptInterface
    fun clearTestResults(groupId: Long) {
        runOnDefaultDispatcher {
            val toClear = SagerDatabase.proxyDao.getByGroup(groupId).filter { it.status != 0 }
            for (p in toClear) {
                p.status = 0
                p.ping = 0
                p.error = null
            }
            if (toClear.isNotEmpty()) ProfileManager.updateProfile(toClear)
        }
    }

    @JavascriptInterface
    fun clearTraffic(groupId: Long) {
        runOnDefaultDispatcher {
            val toClear = SagerDatabase.proxyDao.getByGroup(groupId).filter { it.tx != 0L || it.rx != 0L }
            for (p in toClear) {
                p.tx = 0
                p.rx = 0
            }
            if (toClear.isNotEmpty()) ProfileManager.updateProfile(toClear)
        }
    }

    /** Ids of profiles that were tested and failed (status 2/3), so the page can ask before deleting. */
    @JavascriptInterface
    fun getInvalidIds(groupId: Long): String {
        val arr = JSONArray()
        for (p in SagerDatabase.proxyDao.getByGroup(groupId)) {
            if (p.status != 0 && p.status != 1) arr.put(p.id)
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun deleteProfiles(idsJson: String?): Boolean {
        val ids = try {
            val a = JSONArray(idsJson ?: return false)
            (0 until a.length()).map { a.getLong(it) }
        } catch (_: Exception) {
            return false
        }
        if (ids.size > 5000) return false
        runOnDefaultDispatcher {
            for (id in ids) {
                val p = SagerDatabase.proxyDao.getById(id) ?: continue
                ProfileManager.deleteProfile(p.groupId, p.id)
            }
        }
        return true
    }

    /** Persistent "sort by delay", same as the group order option the native list uses. */
    @JavascriptInterface
    fun sortGroupByDelay(groupId: Long): Boolean {
        val g = SagerDatabase.groupDao.getById(groupId) ?: return false
        runOnDefaultDispatcher {
            g.order = GroupOrder.BY_DELAY
            SagerDatabase.groupDao.updateGroup(g)
            ProfileManager.postUpdate(DataStore.selectedProxy, true)
        }
        return true
    }

    // ---------------------------------------------------------------- tests (mirror ConfigurationFragment.runConnectionTest)

    /** kind = "url" (real delay through the core) or "tcp". Progress arrives as `changed` events, end as `testDone`. */
    @JavascriptInterface
    fun testGroup(groupId: Long, kind: String?): Boolean {
        if (kind != "url" && kind != "tcp") return false
        if (DataStore.runningTest) return false
        DataStore.runningTest = true
        runOnDefaultDispatcher {
            try {
                val queue = ConcurrentLinkedQueue(SagerDatabase.proxyDao.getByGroup(groupId))
                val jobs = (1..DataStore.connectionTestConcurrent.coerceAtLeast(1)).map {
                    launch(Dispatchers.IO) {
                        while (isActive) {
                            val p = queue.poll() ?: break
                            p.status = 0
                            try {
                                p.ping = if (kind == "url") UrlTest().doTest(p) else tcpPing(p)
                                p.status = 1
                            } catch (e: PluginManager.PluginNotFoundException) {
                                p.status = 2
                                p.error = e.readableMessage
                            } catch (e: Exception) {
                                p.status = 3
                                p.error = e.readableMessage
                            }
                            ProfileManager.updateProfile(p)
                        }
                    }
                }
                jobs.joinAll()
            } finally {
                DataStore.runningTest = false
                host.emit("testDone", JSONObject().put("groupId", groupId))
            }
        }
        return true
    }

    private fun tcpPing(p: ProxyEntity): Int {
        val bean = p.requireBean()
        val host = bean.serverAddress
        val port = bean.serverPort ?: 0
        if (host.isNullOrBlank() || port <= 0) throw IllegalStateException("No server address")
        val start = android.os.SystemClock.elapsedRealtime()
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 5000) }
        return (android.os.SystemClock.elapsedRealtime() - start).toInt().coerceAtLeast(1)
    }

    /** Status-bar connection test (tap while connected): real urlTest through the running service. */
    @JavascriptInterface
    fun testConnection(): Boolean {
        if (!DataStore.serviceState.connected) return false
        runOnDefaultDispatcher {
            try {
                val ms = host.connection.service?.urlTest() ?: throw IllegalStateException("not started")
                host.emit("connectionTest", JSONObject().put("ok", true).put("ms", ms))
            } catch (e: Exception) {
                host.emit("connectionTest", JSONObject().put("ok", false).put("msg", e.readableMessage))
            }
        }
        return true
    }

    // ---------------------------------------------------------------- settings (whitelisted)

    @JavascriptInterface
    fun getSettings(): String {
        val o = JSONObject()
        for (k in UiSettingsSchema.booleans) o.put(k, readBool(k))
        for (k in UiSettingsSchema.strings) o.put(k, readString(k))
        return o.toString()
    }

    private fun readBool(key: String): Boolean = store.getBoolean(key, false)

    private fun readString(key: String): String =
        when (key) {
            "mixedPort" -> DataStore.mixedPort.toString()
            "connectionTestConcurrent" -> DataStore.connectionTestConcurrent.toString()
            else -> store.getString(key, "") ?: ""
        }

    /** @return true when the value was accepted and stored. */
    @JavascriptInterface
    fun setSetting(key: String?, value: String?): Boolean {
        if (key == null || value == null || value.length > MAX_SETTING_CHARS) return false
        if (!UiSettingsSchema.isAllowed(key)) return false
        if (key in UiSettingsSchema.booleans) {
            if (value != "true" && value != "false") return false
            store.putBoolean(key, value == "true")
        } else if (key == "mixedPort") {
            val port = value.toIntOrNull()?.takeIf { it in 1..65535 } ?: return false
            DataStore.mixedPort = port
        } else if (key == "connectionTestConcurrent") {
            // stored as Int (same as UrlTestPreference)
            DataStore.connectionTestConcurrent = value.toIntOrNull()?.takeIf { it in 1..50 } ?: return false
        } else if (key == "nightTheme") {
            // native values: 1 = Dark, 2 = Light (Theme.getNightMode)
            val mode = value.toIntOrNull()?.takeIf { it == 1 || it == 2 } ?: return false
            store.putString(key, value)
            Theme.currentNightMode = mode
            host.runOnUiThread { Theme.applyNightTheme() }
        } else if (key == "appLanguage") {
            if (value !in LANGUAGE_TAGS) return false
            store.putString(key, value)
            host.runOnUiThread {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(value))
            }
        } else {
            store.putString(key, value)
        }
        return true
    }

    /** Same as the native "update subscription" menu action. */
    @JavascriptInterface
    fun updateSubscription(groupId: Long): Boolean {
        val g = SagerDatabase.groupDao.getById(groupId) ?: return false
        if (g.type != GroupType.SUBSCRIPTION) return false
        GroupUpdater.startUpdate(g, true)
        return true
    }

    // ---------------------------------------------------------------- Black Wall

    @JavascriptInterface
    fun getBlackWall(): String = JSONObject().apply {
        put("enabled", BlackWallEngine.enabled)
        put("level", BlackWallEngine.level.name)
    }.toString()

    @JavascriptInterface
    fun setBlackWall(enabled: Boolean, level: String?): Boolean {
        val lv = BlackWallEngine.StealthLevel.values().firstOrNull { it.name == level } ?: return false
        BlackWallEngine.level = lv
        BlackWallEngine.enabled = enabled
        return true
    }

    // ---------------------------------------------------------------- files & links

    /** Opens the system file picker; the chosen file is imported like pasted text. */
    @JavascriptInterface
    fun importFile() {
        host.runOnUiThread { host.pickImportFile() }
    }

    /** Opens an allow-listed https link in the browser / Telegram. */
    @JavascriptInterface
    fun openUrl(url: String?): Boolean {
        if (url == null || url !in ALLOWED_URLS) return false
        host.runOnUiThread { host.openUrl(url) }
        return true
    }

    // ---------------------------------------------------------------- native screens

    /** Opens an existing native screen. Only whitelisted targets are accepted. */
    @JavascriptInterface
    fun openNative(target: String?): Boolean {
        if (target == null || target !in WebMainActivity.NATIVE_TARGETS) return false
        host.runOnUiThread { host.openNative(target) }
        return true
    }

    companion object {
        private const val MAX_IMPORT_CHARS = 1_000_000
        private val ALLOWED_URLS = setOf(
            "https://t.me/v2ray_DA",
            "https://github.com/rc3vr467/DeltaBox",
        )
        private const val MAX_SETTING_CHARS = 20_000
        private val LANGUAGE_TAGS = setOf("", "en", "fa", "ru", "tr", "es", "fr", "it", "nl", "pt-BR", "uk", "in", "ko")
    }
}
