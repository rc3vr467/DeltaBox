package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.shape.MaterialShapeDrawable
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bottom status panel (dark glass, neon edge).
 *
 * Left:   exit country (English name, no flag) or the profile name, then the selected profile's
 *         real server address; then connection dot, ping and quality.
 * Centre: room for the cat button, which is a child of the coordinator and sits on the top edge.
 * Right:  upload and download speed.
 *
 * Every value comes from real state: the service state (changeState), the speeds (updateSpeed),
 * the connection test (testConnection) and the selected profile (ProfileManager). Nothing is faked.
 */
class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {

    private lateinit var serverName: TextView
    private lateinit var serverAddress: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var statusDot: View
    private lateinit var pingText: TextView
    private lateinit var qualityText: TextView
    private lateinit var signalBars: List<View>

    private var exitInfo: ExitIpInfo? = null
    private var exitJob: Job? = null
    private var profileName: String? = null
    private var profileAddress: String? = null

    private var pingMs: Int? = null
    private var testing = false

    private lateinit var behavior: YourBehavior

    // Display-only: panel fill follows the real service state, animated once per change (220 ms).
    private var panelColor = 0
    private var panelAnimator: ValueAnimator? = null

    var allowShow = true

    init {
        // Thin edge drawn by the bar's own shape, so it follows the cradle around the cat button.
        (background as? MaterialShapeDrawable)?.setStrokeWidth(resources.displayMetrics.density * 1.25f)
    }

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideDown(child)
        }
    }


    override fun setOnClickListener(l: OnClickListener?) {
        serverName = findViewById(R.id.status)
        serverAddress = findViewById(R.id.serverAddress)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        statusDot = findViewById(R.id.statusDot)
        pingText = findViewById(R.id.pingBadge)
        qualityText = findViewById(R.id.qualityText)
        signalBars = listOf(
            findViewById(R.id.signalBar1),
            findViewById(R.id.signalBar2),
            findViewById(R.id.signalBar3),
        )
        setPing(null)
        renderServer()
        super.setOnClickListener(l)
    }

    override fun onDetachedFromWindow() {
        panelAnimator?.cancel()
        panelAnimator = null
        super.onDetachedFromWindow()
    }

    /** Ping from the real connection test; null shows "Ping —" (never a made-up value). */
    private fun setPing(ms: Int?) {
        pingMs = ms
        renderPing()
    }

    private fun renderPing() {
        val ms = pingMs
        val level = PingStyle.levelOf(ms)
        val color = ContextCompat.getColor(context, colorOf(level))
        pingText.text = when {
            testing -> context.getString(R.string.connection_test_testing)
            ms == null -> context.getString(R.string.stats_ping_none)
            else -> context.getString(R.string.stats_ping_value, ms)
        }
        pingText.setTextColor(color)

        // Quality uses the same thresholds as PingStyle: Good / Medium / Weak, or nothing when unknown.
        val (label, bars) = when (level) {
            PingStyle.Level.GOOD -> R.string.stats_quality_good to 3
            PingStyle.Level.WARN -> R.string.stats_quality_medium to 2
            PingStyle.Level.BAD -> R.string.stats_quality_weak to 1
            PingStyle.Level.NONE -> null to 0
        }
        qualityText.text = label?.let { context.getString(it) } ?: ""
        qualityText.setTextColor(color)
        val dim = ContextCompat.getColor(context, R.color.delta_stats_divider)
        signalBars.forEachIndexed { index, bar ->
            ViewCompat.setBackgroundTintList(
                bar, ColorStateList.valueOf(if (index < bars) color else dim)
            )
        }
    }

    private fun colorOf(level: PingStyle.Level): Int = when (level) {
        PingStyle.Level.GOOD -> R.color.delta_stats_good
        PingStyle.Level.WARN -> R.color.delta_stats_medium
        PingStyle.Level.BAD -> R.color.delta_stats_weak
        PingStyle.Level.NONE -> R.color.delta_stats_unknown
    }

    /** Title: exit country when the tunnel's lookup has answered, otherwise the profile name. */
    private fun renderServer() {
        serverName.text = exitInfo?.country?.takeIf { it.isNotBlank() }
            ?: profileName?.takeIf { it.isNotBlank() }
            ?: "—"
        val address = profileAddress
        serverAddress.text = address.orEmpty()
        serverAddress.visibility = if (address.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /** Reads the selected profile's own name and address (not the VPN exit IP). */
    private fun loadProfile() {
        (context as MainActivity).lifecycleScope.launch(Dispatchers.IO) {
            val entity = try {
                ProfileManager.getProfile(DataStore.currentProfile)
            } catch (_: Exception) {
                null
            }
            val name = try {
                entity?.displayName()
            } catch (_: Exception) {
                null
            }
            val address = try {
                entity?.displayAddress()
            } catch (_: Exception) {
                null
            }
            onMainDispatcher {
                profileName = name
                profileAddress = address
                renderServer()
            }
        }
    }

    private fun applyStateStyle(state: BaseService.State) {
        val dot: Int
        val stroke: Int
        val panel: Int
        when (state) {
            BaseService.State.Connected -> {
                dot = R.color.delta_stats_neon
                stroke = R.color.delta_stats_neon
                panel = R.color.delta_stats_glass_on
            }
            BaseService.State.Connecting, BaseService.State.Stopping -> {
                dot = R.color.delta_stats_connecting
                stroke = R.color.delta_stats_connecting
                panel = R.color.delta_stats_glass
            }
            else -> {
                dot = R.color.delta_stats_idle
                stroke = R.color.delta_stats_neon_2
                panel = R.color.delta_stats_glass
            }
        }
        ViewCompat.setBackgroundTintList(
            statusDot, ColorStateList.valueOf(ContextCompat.getColor(context, dot))
        )
        (background as? MaterialShapeDrawable)?.setStrokeColor(
            ColorStateList.valueOf(ContextCompat.getColor(context, stroke))
        )

        val target = ContextCompat.getColor(context, panel)
        val from = if (panelColor == 0) target else panelColor
        panelAnimator?.cancel()
        if (from == target) {
            panelColor = target
            setBackgroundTint(ColorStateList.valueOf(target))
        } else {
            panelAnimator = ValueAnimator.ofArgb(from, target).apply {
                duration = 220L
                addUpdateListener {
                    panelColor = it.animatedValue as Int
                    setBackgroundTint(ColorStateList.valueOf(panelColor))
                }
                start()
            }
        }
    }

    fun changeState(state: BaseService.State) {
        val activity = context as MainActivity
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { what() }
        }
        applyStateStyle(state)
        loadProfile()
        if (state != BaseService.State.Connected) {
            testing = false
            setPing(null)
            exitJob?.cancel()
            exitInfo = null
            renderServer()
        }
        val label = context.getText(
            when (state) {
                BaseService.State.Connected -> R.string.vpn_connected
                BaseService.State.Connecting -> R.string.connecting
                BaseService.State.Stopping -> R.string.stopping
                else -> R.string.not_connected
            }
        )
        contentDescription = label
        TooltipCompat.setTooltipText(this, label)

        if ((state == BaseService.State.Connected).also { hideOnScroll = it }) {
            postWhenStarted {
                if (allowShow) performShow()
                loadExitInfo()
            }
        } else {
            postWhenStarted {
                performHide()
            }
            updateSpeed(0, 0)
        }
    }

    /** Country of the tunnel's exit, looked up THROUGH the tunnel. Shown as text only, no flag. */
    private fun loadExitInfo() {
        exitJob?.cancel()
        exitJob = (context as MainActivity).lifecycleScope.launch(Dispatchers.IO) {
            // the tunnel needs a moment before the first request can pass
            var info: ExitIpInfo? = null
            for (attempt in 0 until 3) {
                info = ExitIpInfo.fetch(DataStore.mixedPort)
                if (info != null) break
                delay(1500L)
            }
            val result = info ?: return@launch

            onMainDispatcher {
                if (DataStore.serviceState != BaseService.State.Connected) return@onMainDispatcher
                exitInfo = result
                renderServer()
            }
        }
    }

    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = context.getString(R.string.speed, Formatter.formatFileSize(context, txRate))
        rxText.text = context.getString(R.string.speed, Formatter.formatFileSize(context, rxRate))
    }

    fun testConnection() {
        val activity = context as MainActivity
        isEnabled = false
        testing = true
        setPing(null)
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    testing = false
                    setPing(elapsed)
                }

            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    testing = false
                    renderPing()

                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }

}
