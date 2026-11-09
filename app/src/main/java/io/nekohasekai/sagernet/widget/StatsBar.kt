package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
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
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var statusDot: View
    private lateinit var pingBadge: TextView
    private lateinit var exitIpText: TextView
    private var exitInfo: ExitIpInfo? = null
    private var exitJob: kotlinx.coroutines.Job? = null
    private lateinit var behavior: YourBehavior

    // Display-only: panel tint follows the real service state, animated once per change (220 ms).
    private var panelColor = 0
    private var panelAnimator: ValueAnimator? = null

    var allowShow = true

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
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        statusDot = findViewById(R.id.statusDot)
        pingBadge = findViewById(R.id.pingBadge)
        exitIpText = findViewById(R.id.exitIp)
        setPing(null)
        super.setOnClickListener(l)
    }

    override fun onDetachedFromWindow() {
        panelAnimator?.cancel()
        panelAnimator = null
        super.onDetachedFromWindow()
    }

    /** Ping badge in the panel. [ms] comes from the real connection test; null shows an em dash. */
    private fun setPing(ms: Int?) {
        PingStyle.apply(pingBadge, ms, if (ms == null) "PING —" else "PING $ms ms")
    }

    private fun applyStateStyle(state: BaseService.State) {
        val dot: Int
        val text: Int
        val panel: Int
        when (state) {
            BaseService.State.Connected -> {
                dot = R.color.delta_state_on
                text = R.color.delta_state_on_text
                panel = R.color.delta_panel_on
            }
            BaseService.State.Connecting, BaseService.State.Stopping -> {
                dot = R.color.delta_state_connecting
                text = R.color.delta_state_connecting_text
                panel = R.color.delta_panel_connecting
            }
            else -> {
                dot = R.color.delta_state_off
                text = R.color.delta_state_off_text
                panel = R.color.delta_panel_off
            }
        }
        ViewCompat.setBackgroundTintList(
            statusDot, ColorStateList.valueOf(ContextCompat.getColor(context, dot))
        )
        statusText.setTextColor(ContextCompat.getColor(context, text))

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

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    fun changeState(state: BaseService.State) {
        val activity = context as MainActivity
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { what() }
        }
        applyStateStyle(state)
        if (state != BaseService.State.Connected) {
            setPing(null)
            exitJob?.cancel()
            exitInfo = null
            exitIpText.visibility = View.GONE
        }
        if ((state == BaseService.State.Connected).also { hideOnScroll = it }) {
            postWhenStarted {
                if (allowShow) performShow()
                setStatus(app.getText(R.string.vpn_connected))
                loadExitInfo()
            }
        } else {
            postWhenStarted {
                performHide()
            }
            updateSpeed(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    /** Shows "🇫🇮 Finland" + the real exit IP instead of the server's profile name. */
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
                setStatus(result.label)
                exitIpText.text = result.ip
                exitIpText.visibility = View.VISIBLE
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    fun testConnection() {
        val activity = context as MainActivity
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    setPing(elapsed)
                    if (exitInfo != null) setStatus(exitInfo!!.label) else setStatus(
                        app.getString(
                            if (DataStore.connectionTestURL.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        )
                    )
                }

            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    setStatus(app.getText(R.string.connection_test_testing))

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
