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
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Floating status panel at the bottom of the main screen.
 *
 * Left: exit country flag + name, connected server address, live ping with signal bars.
 * Right: live upload / download speed.
 * The connect button sits in the cradle at the centre; the panel passes behind it.
 *
 * Every value shown here is real: ping comes from a connection test through the running
 * core, speeds from the core's traffic statistics, and the server address from the active
 * profile. Unknown values are shown as "--" or hidden, never filled in with guesses.
 */
class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView   // country name when known, otherwise the status line
    private lateinit var exitFlagText: TextView
    private lateinit var serverText: TextView
    private lateinit var statusDot: View
    private lateinit var pingValue: TextView
    private lateinit var pingSignal: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var behavior: YourBehavior

    // Display-only: panel tint follows the real service state, animated once per change (220 ms).
    private var panelColor = 0
    private var panelAnimator: ValueAnimator? = null

    private var connectedNow = false
    private var currentStatus: CharSequence = ""
    private var countryName: CharSequence? = null
    private var flagGeneration = 0
    private var livePingJob: Job? = null

    var allowShow = true

    init {
        // Neon border follows the panel outline, including the notch around the connect button.
        (background as? MaterialShapeDrawable)?.apply {
            setStrokeWidth(context.resources.displayMetrics.density * 1.25f)
            setStrokeTint(
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.delta_bar_neon))
            )
        }
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
        statusText = findViewById(R.id.status)
        exitFlagText = findViewById(R.id.exitFlag)
        serverText = findViewById(R.id.serverAddr)
        statusDot = findViewById(R.id.statusDot)
        pingValue = findViewById(R.id.pingValue)
        pingSignal = findViewById(R.id.pingSignal)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        setPing(null)
        super.setOnClickListener(l)
    }

    override fun onDetachedFromWindow() {
        panelAnimator?.cancel()
        panelAnimator = null
        stopLivePing()
        super.onDetachedFromWindow()
    }

    /** Ping value and signal bars. [ms] is a real measurement; null shows "--" with no bars. */
    private fun setPing(ms: Int?) {
        val ctx = context
        if (ms == null || ms < 0) {
            pingValue.text = "--"
            pingValue.setTextColor(ContextCompat.getColor(ctx, R.color.delta_bar_muted))
            pingSignal.text = ""
            return
        }
        val (color, bars) = when {
            ms <= 80 -> R.color.delta_bar_ping_fast to 4
            ms <= 150 -> R.color.delta_bar_ping_ok to 3
            ms <= 250 -> R.color.delta_bar_ping_slow to 2
            else -> R.color.delta_bar_ping_bad to 1
        }
        val tint = ContextCompat.getColor(ctx, color)
        pingValue.text = "$ms ms"
        pingValue.setTextColor(tint)
        pingSignal.text = SIGNAL_GLYPHS.take(bars)
        pingSignal.setTextColor(tint)
    }

    private fun applyStateStyle(state: BaseService.State) {
        val accent: Int
        val panel: Int
        when (state) {
            BaseService.State.Connected -> {
                accent = R.color.delta_bar_neon
                panel = R.color.delta_bar_on
            }
            BaseService.State.Connecting, BaseService.State.Stopping -> {
                accent = R.color.delta_bar_blue
                panel = R.color.delta_bar_connecting
            }
            else -> {
                accent = R.color.delta_state_off
                panel = R.color.delta_bar_off
            }
        }
        val accentColor = ContextCompat.getColor(context, accent)
        ViewCompat.setBackgroundTintList(statusDot, ColorStateList.valueOf(accentColor))
        (background as? MaterialShapeDrawable)?.setStrokeTint(ColorStateList.valueOf(accentColor))
        statusText.setTextColor(
            ContextCompat.getColor(
                context,
                if (state == BaseService.State.Connected) R.color.delta_bar_text else R.color.delta_bar_muted
            )
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

    /** Row one: the country name when known, otherwise the current status line. */
    private fun renderStatusRow() {
        val name = if (connectedNow) countryName else null
        statusText.text = name ?: currentStatus
        TooltipCompat.setTooltipText(this, statusText.text)
    }

    /** Shows the flag and name of the exit country, or hides them when unknown (never a guessed flag). */
    private fun setCountry(code: String?) {
        if (code == null) {
            exitFlagText.text = ""
            exitFlagText.visibility = View.GONE
            countryName = null
        } else {
            exitFlagText.text = ExitCountry.flagOf(code)
            exitFlagText.visibility = View.VISIBLE
            countryName = Locale("", code).getDisplayCountry(Locale.getDefault()).ifBlank { code }
        }
        renderStatusRow()
    }

    private fun setServer(address: String?) {
        serverText.text = address ?: ""
        serverText.visibility = if (address.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /**
     * Refreshes the country (exit lookup through the tunnel) and the server address (active
     * profile) a little after connecting, off the main thread, without delaying the tunnel.
     * A newer connection invalidates older results through [flagGeneration].
     */
    private fun refreshExitInfo() {
        val generation = ++flagGeneration
        setCountry(null)
        setServer(null)
        val profileId = DataStore.selectedProxy
        runOnDefaultDispatcher {
            val address = withContext(Dispatchers.IO) {
                runCatching { SagerDatabase.proxyDao.getById(profileId)?.displayAddress() }.getOrNull()
            }
            onMainDispatcher {
                if (generation == flagGeneration) setServer(address)
            }
            delay(2000L)
            val code = withContext(Dispatchers.IO) { ExitCountry.fetch() }
            onMainDispatcher {
                if (generation == flagGeneration) setCountry(code)
            }
        }
    }

    /**
     * One coroutine, tied to the activity lifecycle, that measures ping every
     * [LIVE_PING_INTERVAL_MS] while connected. It is cancelled on disconnect and on detach.
     */
    private fun startLivePing() {
        val activity = context as MainActivity
        livePingJob?.cancel()
        livePingJob = activity.lifecycleScope.launch {
            delay(3000L)
            while (isActive) {
                val ms = runCatching { withContext(Dispatchers.IO) { activity.urlTest() } }.getOrNull()
                setPing(ms)
                delay(LIVE_PING_INTERVAL_MS)
            }
        }
    }

    private fun stopLivePing() {
        livePingJob?.cancel()
        livePingJob = null
    }

    fun changeState(state: BaseService.State) {
        val activity = context as MainActivity
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { what() }
        }
        connectedNow = state == BaseService.State.Connected
        applyStateStyle(state)

        currentStatus = activity.getText(
            when (state) {
                BaseService.State.Connected -> R.string.vpn_connected
                BaseService.State.Connecting -> R.string.connecting
                BaseService.State.Stopping -> R.string.stopping
                else -> R.string.not_connected
            }
        )

        if (connectedNow) {
            refreshExitInfo()
            startLivePing()
        } else {
            stopLivePing()
            flagGeneration++
            setCountry(null)
            setServer(null)
            setPing(null)
            updateSpeed(0, 0)
        }
        renderStatusRow()

        if ((connectedNow).also { hideOnScroll = it }) {
            postWhenStarted {
                if (allowShow) performShow()
            }
        } else {
            postWhenStarted {
                performHide()
            }
        }
    }

    /** Live traffic values; the unit (B/s, kB/s, MB/s) is chosen by the system formatter. */
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = context.getString(R.string.speed, Formatter.formatFileSize(context, txRate))
        rxText.text = context.getString(R.string.speed, Formatter.formatFileSize(context, rxRate))
    }

    /** Manual connection test on tap. Shows the real measured ping or the error. */
    fun testConnection() {
        val activity = context as MainActivity
        isEnabled = false
        statusText.text = app.getText(R.string.connection_test_testing)
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    setPing(elapsed)
                    renderStatusRow()
                }

            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    renderStatusRow()

                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }

    companion object {
        private const val LIVE_PING_INTERVAL_MS = 30_000L
        private const val SIGNAL_GLYPHS = "▁▃▅▇"
    }

}
