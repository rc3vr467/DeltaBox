package io.nekohasekai.sagernet.diagnostics

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory store of real test results per server (profile id) and test kind.
 *
 * - Nothing is persisted: no Room schema change. Results vanish with the process, which is the
 *   safe choice for latency data that goes stale anyway.
 * - Results never cross servers: the key is (profileId, kind).
 * - Every sample carries a timestamp and the network "generation" it was taken in. When the default
 *   network changes, the generation is bumped and older samples stop counting (they must be
 *   re-tested), see [ServerQualityCalculator.compute].
 * - Duplicate-test prevention and the concurrency limit stay where they already live:
 *   DataStore.runningTest (one group test at a time) and DataStore.connectionTestConcurrent.
 */
object ServerDiagnostics {

    const val MAX_SAMPLES = 20

    private data class Key(val profileId: Long, val kind: TestKind)

    private val samples = ConcurrentHashMap<Key, java.util.ArrayDeque<LatencySample>>()
    private val testing = ConcurrentHashMap.newKeySet<Key>()
    private val generation = AtomicInteger(0)

    fun markTesting(profileId: Long, kind: TestKind) {
        testing.add(Key(profileId, kind))
    }

    /** Clears the "testing" mark without recording anything (cancelled test). */
    fun finish(profileId: Long, kind: TestKind) {
        testing.remove(Key(profileId, kind))
    }

    fun record(
        profileId: Long,
        kind: TestKind,
        outcome: Outcome,
        latencyMs: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val key = Key(profileId, kind)
        val deque = samples.getOrPut(key) { java.util.ArrayDeque() }
        synchronized(deque) {
            deque.addLast(LatencySample(nowMs, kind, outcome, latencyMs, generation.get()))
            while (deque.size > MAX_SAMPLES) deque.removeFirst()
        }
        testing.remove(key)
    }

    /** Call when the default network changes: older results must be validated again. */
    fun onNetworkChanged() {
        generation.incrementAndGet()
    }

    fun forget(profileId: Long) {
        TestKind.values().forEach {
            samples.remove(Key(profileId, it))
            testing.remove(Key(profileId, it))
        }
    }

    fun quality(
        profileId: Long, kind: TestKind, nowMs: Long = System.currentTimeMillis(),
    ): ServerQuality {
        val key = Key(profileId, kind)
        val snapshot = samples[key]?.let { synchronized(it) { it.toList() } } ?: emptyList()
        return ServerQualityCalculator.compute(
            kind, snapshot, generation.get(), nowMs, testing.contains(key)
        )
    }

    /** Qualities that have at least one fresh sample or a running test, most informative first. */
    fun available(profileId: Long, nowMs: Long = System.currentTimeMillis()): List<ServerQuality> =
        listOf(TestKind.URL_LATENCY, TestKind.TCP_CONNECT)
            .map { quality(profileId, it, nowMs) }
            .filter { it.samples > 0 || it.health == ServerHealth.TESTING }

    /** Maps a test failure to an outcome. Looks through the whole cause chain. */
    fun classify(error: Throwable): Outcome {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            if (current is SocketTimeoutException) return Outcome.TIMEOUT
            val msg = current.message?.lowercase().orEmpty()
            if ("timeout" in msg || "timed out" in msg || "deadline exceeded" in msg) {
                return Outcome.TIMEOUT
            }
            if (current is ConnectException || current is NoRouteToHostException ||
                current is UnknownHostException || current is PortUnreachableException ||
                "unreachable" in msg || "refused" in msg
            ) return Outcome.UNREACHABLE
            current = current.cause
            depth++
        }
        return Outcome.ERROR
    }
}
