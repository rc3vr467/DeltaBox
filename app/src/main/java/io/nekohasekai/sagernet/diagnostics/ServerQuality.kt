package io.nekohasekai.sagernet.diagnostics

/**
 * What a server test actually measured. These are different things and must never be presented as
 * one another (for example, a TCP connect time is NOT a download speed).
 *
 *  - TCP_CONNECT: time to complete a plain TCP connect to the server's host:port (no proxy handshake).
 *  - URL_LATENCY: time for an HTTP request to the test URL THROUGH the proxy (includes the protocol
 *    handshake and the proxy path; the handshake share cannot be separated with the current core API).
 *
 * Not measured by DeltaBox (and therefore never shown): ICMP ping (Android apps cannot send raw
 * ICMP), packet loss, handshake time on its own, and real throughput.
 *
 * [slowMs]: a successful result above this is labelled "Slow".
 * [goodMs] / [badMs]: latency score is 100 at or below goodMs and 0 at or above badMs.
 */
enum class TestKind(val slowMs: Int, val goodMs: Int, val badMs: Int) {
    TCP_CONNECT(slowMs = 500, goodMs = 100, badMs = 1000),
    URL_LATENCY(slowMs = 1500, goodMs = 400, badMs = 3000),
}

enum class Outcome { SUCCESS, TIMEOUT, UNREACHABLE, ERROR }

enum class ServerHealth { UNKNOWN, TESTING, HEALTHY, SLOW, UNREACHABLE, TIMEOUT, ERROR }

/** One real test result. [latencyMs] is only meaningful when [outcome] is SUCCESS. */
data class LatencySample(
    val atMs: Long,
    val kind: TestKind,
    val outcome: Outcome,
    val latencyMs: Int,
    val generation: Int,
)

data class ServerQuality(
    val kind: TestKind,
    val health: ServerHealth,
    /** Fresh samples used for this analysis (stale or pre-network-change samples are excluded). */
    val samples: Int,
    val successes: Int,
    val medianMs: Int?,
    val averageMs: Int?,
    /** Mean absolute difference between consecutive successful latencies. */
    val jitterMs: Int?,
    val successRate: Double?,
    val timeoutRate: Double?,
    val lastSuccessAtMs: Long?,
    /** 0..100, or null when there is not enough real data (never invented). */
    val score: Int?,
)
