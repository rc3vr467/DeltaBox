package io.nekohasekai.sagernet.diagnostics

import android.content.Context
import android.text.format.DateUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R

/** Read-only dialog showing the real, measured quality data kept by [ServerDiagnostics]. */
object ServerQualityDialog {

    fun show(context: Context, profileId: Long, title: CharSequence) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(buildMessage(context, profileId))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun buildMessage(context: Context, profileId: Long): String {
        val na = context.getString(R.string.delta_quality_not_available)
        val qualities = ServerDiagnostics.available(profileId)
        if (qualities.isEmpty()) return context.getString(R.string.delta_quality_no_data)

        val sb = StringBuilder()
        for (q in qualities) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(
                context.getString(
                    if (q.kind == TestKind.TCP_CONNECT) R.string.delta_quality_kind_tcp
                    else R.string.delta_quality_kind_url
                )
            ).append('\n')
            sb.append(context.getString(R.string.delta_quality_status, healthLabel(context, q.health)))
            sb.append('\n')
            sb.append(context.getString(R.string.delta_quality_samples, q.samples, q.successes))
            sb.append('\n')
            sb.append(
                context.getString(
                    R.string.delta_quality_latency,
                    ms(q.medianMs, na), ms(q.averageMs, na), ms(q.jitterMs, na)
                )
            ).append('\n')
            sb.append(
                context.getString(R.string.delta_quality_rates, pct(q.successRate, na), pct(q.timeoutRate, na))
            ).append('\n')
            q.lastSuccessAtMs?.let {
                sb.append(
                    context.getString(
                        R.string.delta_quality_last_success,
                        DateUtils.getRelativeTimeSpanString(
                            it, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS
                        )
                    )
                ).append('\n')
            }
            sb.append(
                if (q.score != null) context.getString(R.string.delta_quality_score, q.score)
                else context.getString(R.string.delta_quality_score_unknown, ServerQualityCalculator.MIN_SAMPLES)
            )
        }
        sb.append("\n\n").append(context.getString(R.string.delta_quality_note))
        return sb.toString()
    }

    private fun ms(v: Int?, na: String) = if (v == null) na else "$v ms"
    private fun pct(v: Double?, na: String) = if (v == null) na else "${Math.round(v * 100)}%"

    private fun healthLabel(context: Context, h: ServerHealth): String = context.getString(
        when (h) {
            ServerHealth.UNKNOWN -> R.string.delta_health_unknown
            ServerHealth.TESTING -> R.string.delta_health_testing
            ServerHealth.HEALTHY -> R.string.delta_health_healthy
            ServerHealth.SLOW -> R.string.delta_health_slow
            ServerHealth.UNREACHABLE -> R.string.delta_health_unreachable
            ServerHealth.TIMEOUT -> R.string.delta_health_timeout
            ServerHealth.ERROR -> R.string.delta_health_error
        }
    )
}
