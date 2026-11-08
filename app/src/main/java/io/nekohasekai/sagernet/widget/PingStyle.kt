package io.nekohasekai.sagernet.widget

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import io.nekohasekai.sagernet.R

/**
 * Display-only mapping from a ping value (ms) to badge colours.
 * It never measures, stores or changes a ping: the caller passes in the value the app already has,
 * or null when there is none, in which case the badge shows an em dash instead of a made-up number.
 *
 * Thresholds (visual rules only): 0-500 green, 501-1600 orange, >1600 red, unknown grey.
 */
object PingStyle {

    private enum class Level(val text: Int, val bg: Int) {
        GOOD(R.color.delta_ping_good, R.color.delta_ping_good_bg),
        WARN(R.color.delta_ping_warn, R.color.delta_ping_warn_bg),
        BAD(R.color.delta_ping_bad, R.color.delta_ping_bad_bg),
        NONE(R.color.delta_ping_none, R.color.delta_ping_none_bg)
    }

    private fun levelOf(ms: Int?): Level = when {
        ms == null || ms < 0 -> Level.NONE
        ms <= 500 -> Level.GOOD
        ms <= 1600 -> Level.WARN
        else -> Level.BAD
    }

    /** Shows [label] as a rounded badge coloured for [ms]. */
    fun apply(view: TextView, ms: Int?, label: CharSequence) {
        val level = levelOf(ms)
        val ctx = view.context
        view.text = label
        view.setTextColor(ContextCompat.getColor(ctx, level.text))
        view.setBackgroundResource(R.drawable.bg_ping_badge)
        ViewCompat.setBackgroundTintList(
            view, ColorStateList.valueOf(ContextCompat.getColor(ctx, level.bg))
        )
        view.setPadding(pad(view, 8), pad(view, 2), pad(view, 8), pad(view, 2))
    }

    /** Plain text without badge background (errors, traffic summary, empty). */
    fun clear(view: TextView) {
        ViewCompat.setBackgroundTintList(view, null)
        view.background = null
        view.setPadding(0, 0, 0, 0)
    }

    private fun pad(view: TextView, dp: Int) = (dp * view.resources.displayMetrics.density + 0.5f).toInt()
}
