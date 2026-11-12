package io.nekohasekai.sagernet.diagnostics

/**
 * Pure analysis of real samples. No Android dependencies (unit-tested on the JVM).
 *
 * Score (0..100) is an internal heuristic, NOT a ranking of "the best server":
 *   0.45 * latencyScore + 0.20 * jitterScore + 0.35 * successScore
 * where
 *   latencyScore = 100 at median <= kind.goodMs, 0 at median >= kind.badMs, linear between
 *   jitterScore  = 100 when jitter/median <= 0.10, 0 when >= 1.0, linear between
 *   successScore = successRate * 100
 * It needs at least [MIN_SAMPLES] fresh samples of the same kind; otherwise it is null (Unknown).
 * With zero successes among enough samples the score is 0. The weights are not validated against
 * real-world data yet. Throughput is not part of the score, so a low score or a high score says
 * nothing about download speed.
 */
object ServerQualityCalculator {

    const val MIN_SAMPLES = 3
    const val TTL_MS = 10 * 60 * 1000L

    fun compute(
        kind: TestKind,
        all: List<LatencySample>,
        generation: Int,
        nowMs: Long,
        testing: Boolean,
    ): ServerQuality {
        val fresh = all.filter {
            it.kind == kind && it.generation == generation && nowMs - it.atMs <= TTL_MS
        }.sortedBy { it.atMs }

        val successes = fresh.filter { it.outcome == Outcome.SUCCESS }
        val latencies = successes.map { it.latencyMs }
        val median = median(latencies)
        val average = if (latencies.isEmpty()) null else latencies.sum() / latencies.size
        val jitter = jitter(latencies)
        val successRate = if (fresh.isEmpty()) null else successes.size.toDouble() / fresh.size
        val timeoutRate = if (fresh.isEmpty()) null
        else fresh.count { it.outcome == Outcome.TIMEOUT }.toDouble() / fresh.size
        val lastSuccess = successes.lastOrNull()?.atMs

        val health = when {
            testing -> ServerHealth.TESTING
            fresh.isEmpty() -> ServerHealth.UNKNOWN
            else -> {
                val last = fresh.last()
                when (last.outcome) {
                    Outcome.SUCCESS ->
                        if (last.latencyMs > kind.slowMs) ServerHealth.SLOW else ServerHealth.HEALTHY
                    Outcome.TIMEOUT -> ServerHealth.TIMEOUT
                    Outcome.UNREACHABLE -> ServerHealth.UNREACHABLE
                    Outcome.ERROR -> ServerHealth.ERROR
                }
            }
        }

        return ServerQuality(
            kind = kind,
            health = health,
            samples = fresh.size,
            successes = successes.size,
            medianMs = median,
            averageMs = average,
            jitterMs = jitter,
            successRate = successRate,
            timeoutRate = timeoutRate,
            lastSuccessAtMs = lastSuccess,
            score = score(kind, fresh.size, successes.size, median, jitter, successRate),
        )
    }

    internal fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Mean absolute difference of consecutive values; needs at least two successes. */
    internal fun jitter(values: List<Int>): Int? {
        if (values.size < 2) return null
        var sum = 0L
        for (i in 1 until values.size) sum += kotlin.math.abs(values[i] - values[i - 1])
        return (sum / (values.size - 1)).toInt()
    }

    internal fun score(
        kind: TestKind, samples: Int, successes: Int, median: Int?, jitter: Int?, successRate: Double?,
    ): Int? {
        if (samples < MIN_SAMPLES || successRate == null) return null
        if (successes == 0) return 0
        // one success cannot say anything about stability
        if (median == null || jitter == null) return null

        val latencyScore = linear(median.toDouble(), kind.goodMs.toDouble(), kind.badMs.toDouble())
        val relJitter = jitter.toDouble() / median.coerceAtLeast(1)
        val jitterScore = linear(relJitter, 0.10, 1.0)
        val successScore = successRate * 100.0
        val total = 0.45 * latencyScore + 0.20 * jitterScore + 0.35 * successScore
        return total.toInt().coerceIn(0, 100)
    }

    /** 100 when value <= good, 0 when value >= bad, linear in between. */
    private fun linear(value: Double, good: Double, bad: Double): Double = when {
        value <= good -> 100.0
        value >= bad -> 0.0
        else -> 100.0 * (bad - value) / (bad - good)
    }
}
