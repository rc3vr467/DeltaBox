package io.nekohasekai.sagernet.ui.web

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ui.AppManagerActivity
import io.nekohasekai.sagernet.ui.AssetsActivity
import io.nekohasekai.sagernet.ui.BlackWallActivity
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.RouteActivity
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Hosts the HTML UI (assets/ui/index.html) in a locked-down WebView.
 * All real work stays in Kotlin; the page talks to us only through [UiBridge] (`NativeBridge`)
 * and receives events via `window.DeltaNative.onEvent(name, payload)`.
 */
@Suppress("DEPRECATION")
class WebMainActivity : ThemedActivity(),
    SagerConnection.Callback,
    ProfileManager.Listener,
    GroupManager.Listener {

    private lateinit var webView: WebView
    private var pageReady = false

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) { denied ->
        if (denied) emit("toast", JSONObject().put("code", "vpn_permission_denied"))
    }

    private val bridge by lazy { UiBridge(this) }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        runOnDefaultDispatcher {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("cannot open file")
                if (bytes.size > MAX_IMPORT_BYTES) throw IllegalStateException("file too large")
                bridge.importText(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                emit("toast", JSONObject().put("code", "import_failed").put("msg", e.readableMessage))
            }
        }
    }

    fun pickImportFile() {
        try {
            pickFile.launch("*/*")
        } catch (_: ActivityNotFoundException) {
            emit("toast", JSONObject().put("code", "import_failed").put("msg", "No file manager found"))
        }
    }

    fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            emit("toast", JSONObject().put("code", "import_failed").put("msg", "No app can open this link"))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (io.nekohasekai.sagernet.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        webView = WebView(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.delta_bg))
            overScrollMode = WebView.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false          // no localStorage as a data source
                allowFileAccess = false            // does not affect file:///android_asset
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                blockNetworkLoads = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(false)
                builtInZoomControls = false
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
            }
            webViewClient = LockedClient()
            addJavascriptInterface(bridge, "NativeBridge")
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.delta_bg))
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this) {
            // The page decides (close sheet/drawer/page); if it does not handle it, leave the app like MainActivity.
            webView.evaluateJavascript(
                "(window.DeltaNative&&window.DeltaNative.onBack&&window.DeltaNative.onBack())===true"
            ) { handled -> if (handled != "true") moveTaskToBack(true) }
        }

        if (savedInstanceState == null) {
            webView.loadUrl(ENTRY_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }

        connection.connect(this, this)
        ProfileManager.addListener(this)
        GroupManager.addListener(this)
    }

    // ------------------------------------------------------------------ WebView lockdown

    private inner class LockedClient : WebViewClient() {
        private fun allowed(url: String) = url.startsWith(ASSET_PREFIX)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
            !allowed(request.url.toString()) // block every navigation away from the bundled UI

        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest,
        ): WebResourceResponse? =
            if (allowed(request.url.toString())) null
            else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageReady = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            pageReady = true
            emit("ready", JSONObject().put("night", isNightMode()))
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onReceivedError(
            view: WebView, errorCode: Int, description: String?, failingUrl: String?,
        ) {
            // Bundled UI failed to load: fall back to the classic native UI so the app stays usable.
            startActivity(Intent(this@WebMainActivity, MainActivity::class.java))
            finish()
        }
    }

    // ------------------------------------------------------------------ Kotlin -> JS events

    fun emit(name: String, payload: JSONObject) {
        runOnUiThread {
            if (!pageReady || isDestroyed) return@runOnUiThread
            webView.evaluateJavascript(
                "window.DeltaNative&&window.DeltaNative.onEvent(${JSONObject.quote(name)},$payload)", null
            )
        }
    }

    fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun serviceStateName(): String = DataStore.serviceState.name.lowercase()

    private fun pushState(msg: String? = null) = emit(
        "state", JSONObject()
            .put("service", serviceStateName())
            .put("canStop", DataStore.serviceState.canStop)
            .put("connected", DataStore.serviceState.connected)
            .put("msg", msg ?: "")
    )

    // ------------------------------------------------------------------ actions

    fun toggleConnection() {
        if (DataStore.serviceState.canStop) SagerNet.stopService() else connect.launch(null)
    }

    fun openNative(target: String) {
        when (target) {
            "assets" -> startActivity(Intent(this, AssetsActivity::class.java))
            "split" -> startActivity(Intent(this, AppManagerActivity::class.java))
            "route" -> startActivity(Intent(this, RouteActivity::class.java))
            "blackwall" -> startActivity(Intent(this, BlackWallActivity::class.java))
            else -> startActivity(
                Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_NAV, target)
            )
        }
    }

    // ------------------------------------------------------------------ SagerConnection.Callback

    private fun changeState(state: BaseService.State, msg: String? = null) {
        DataStore.serviceState = state
        pushState(msg)
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) =
        changeState(state, msg)

    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)

    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) = emit(
        "speed", JSONObject()
            .put("txProxy", stats.txRateProxy).put("rxProxy", stats.rxRateProxy)
            .put("txDirect", stats.txRateDirect).put("rxDirect", stats.rxRateDirect)
            .put("txTotal", stats.txTotal).put("rxTotal", stats.rxTotal)
    )

    override fun cbTrafficUpdate(data: TrafficData) {
        // same as MainActivity: persist in the default dispatcher; listeners below notify the page
        io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher { ProfileManager.postUpdate(data) }
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    // ------------------------------------------------------------------ ProfileManager / GroupManager listeners
    // The page re-pulls data through the bridge; we only tell it that something changed.

    private fun changed(what: String) = emit("changed", JSONObject().put("what", what))

    override suspend fun onAdd(profile: ProxyEntity) = changed("profiles")
    override suspend fun onUpdated(data: TrafficData) = changed("traffic")
    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = changed("profiles")
    override suspend fun onRemoved(groupId: Long, profileId: Long) = changed("profiles")

    override suspend fun groupAdd(group: ProxyGroup) = changed("groups")
    override suspend fun groupUpdated(group: ProxyGroup) = changed("groups")
    override suspend fun groupRemoved(groupId: Long) = changed("groups")
    override suspend fun groupUpdated(groupId: Long) = changed("groups")

    // ------------------------------------------------------------------ lifecycle

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (pageReady) emit("changed", JSONObject().put("what", "all"))
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        emit("theme", JSONObject().put("night", isNightMode()))
    }

    override fun onDestroy() {
        ProfileManager.removeListener(this)
        GroupManager.removeListener(this)
        connection.disconnect(this)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeJavascriptInterface("NativeBridge")
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val MAX_IMPORT_BYTES = 1_000_000
        const val ENTRY_URL = "file:///android_asset/ui/index.html"
        const val ASSET_PREFIX = "file:///android_asset/ui/"
        val NATIVE_TARGETS = setOf(
            "assets", "split", "route", "blackwall", "group", "tools", "logs", "about", "settings", "classic"
        )
    }
}
