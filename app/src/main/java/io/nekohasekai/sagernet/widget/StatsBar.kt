package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Glass info panel shown to the right of the cat connect button.
 * UI only: state badge, ping, download / upload speed and the status / test message.
 * The public API (allowShow, changeState, updateSpeed, testConnection, performShow/Hide) is unchanged.
 */
class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : MaterialCardView(context, attrs, defStyleAttr) {
    private lateinit var stateText: TextView
    private lateinit var stateDot: View
    private lateinit var pingText: TextView
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView

    var allowShow = true

    override fun onFinishInflate() {
        super.onFinishInflate()
        stateText = findViewById(R.id.state)
        stateDot = findViewById(R.id.state_dot)
        pingText = findViewById(R.id.ping)
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        resetPing()
    }

    fun performShow() {
        visibility = View.VISIBLE
    }

    fun performHide() {
        visibility = View.GONE
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    private fun resetPing() {
        pingText.text = context.getString(
            R.string.delta_ping_label
        ) + " " + context.getString(R.string.delta_ping_unknown)
    }

    private fun setPing(elapsed: Int) {
        pingText.text = context.getString(R.string.delta_ping_label) + " " +
                context.getString(R.string.delta_ping_value, elapsed)
    }

    /** State badge (text + colored dot) follows the real service state. */
    private fun setBadge(state: BaseService.State) {
        val (textRes, colorRes) = when (state) {
            BaseService.State.Connected -> R.string.delta_state_connected to R.color.delta_success
            BaseService.State.Connecting -> R.string.connecting to R.color.delta_neon_soft
            BaseService.State.Stopping -> R.string.stopping to R.color.delta_neon_soft
            else -> R.string.delta_state_disconnected to R.color.delta_error
        }
        stateText.setText(textRes)
        stateDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, colorRes))
    }

    fun changeState(state: BaseService.State) {
        val activity = context as MainActivity
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { what() }
        }
        setBadge(state)
        if (state == BaseService.State.Connected) {
            postWhenStarted {
                if (allowShow) performShow()
                setStatus(app.getText(R.string.vpn_connected))
            }
        } else {
            postWhenStarted {
                if (allowShow) performShow()
            }
            updateSpeed(0, 0)
            resetPing()
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

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = context.getString(
            R.string.speed, Formatter.formatFileSize(context, txRate)
        )
        rxText.text = context.getString(
            R.string.speed, Formatter.formatFileSize(context, rxRate)
        )
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
                    setStatus(
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
