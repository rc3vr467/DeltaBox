package io.nekohasekai.sagernet.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class ServerQualityCalculatorTest {

    private val now = 1_000_000L

    private fun ok(ms: Int, at: Long = now, gen: Int = 0, kind: TestKind = TestKind.TCP_CONNECT) =
        LatencySample(at, kind, Outcome.SUCCESS, ms, gen)

    private fun fail(o: Outcome, at: Long = now, gen: Int = 0, kind: TestKind = TestKind.TCP_CONNECT) =
        LatencySample(at, kind, o, 0, gen)

    private fun q(list: List<LatencySample>, gen: Int = 0, testing: Boolean = false) =
        ServerQualityCalculator.compute(TestKind.TCP_CONNECT, list, gen, now + 1000, testing)

    @Test fun noSamplesIsUnknownWithoutScore() {
        val r = q(emptyList())
        assertEquals(ServerHealth.UNKNOWN, r.health)
        assertNull(r.score)
        assertNull(r.medianMs)
    }

    @Test fun twoSamplesAreNotEnoughForAScore() {
        val r = q(listOf(ok(50, now - 2000), ok(60, now - 1000)))
        assertNull(r.score)
        assertEquals(ServerHealth.HEALTHY, r.health)
    }

    @Test fun medianAverageAndJitter() {
        val r = q(listOf(ok(100, now - 3000), ok(120, now - 2000), ok(80, now - 1000)))
        assertEquals(100, r.medianMs)
        assertEquals(100, r.averageMs)
        // |120-100| = 20, |80-120| = 40 -> mean 30
        assertEquals(30, r.jitterMs)
        assertEquals(1.0, r.successRate!!, 0.0001)
        assertNotNull(r.score)
    }

    @Test fun stableFastServerScoresHigherThanUnstableOne() {
        val stable = q(listOf(ok(60, now - 3000), ok(62, now - 2000), ok(61, now - 1000)))
        val unstable = q(
            listOf(ok(60, now - 4000), fail(Outcome.TIMEOUT, now - 3000), ok(900, now - 2000), ok(70, now - 1000))
        )
        assertTrue(stable.score!! > unstable.score!!)
    }

    @Test fun allFailuresGiveZeroScoreAfterEnoughSamples() {
        val r = q(listOf(fail(Outcome.TIMEOUT, now - 3000), fail(Outcome.TIMEOUT, now - 2000), fail(Outcome.TIMEOUT, now - 1000)))
        assertEquals(0, r.score)
        assertEquals(ServerHealth.TIMEOUT, r.health)
        assertEquals(1.0, r.timeoutRate!!, 0.0001)
    }

    @Test fun singleSuccessAmongManyHasNoScore() {
        val r = q(listOf(fail(Outcome.ERROR, now - 3000), fail(Outcome.ERROR, now - 2000), ok(80, now - 1000)))
        assertNull(r.score)
    }

    @Test fun slowThresholdUsesLastResult() {
        val r = q(listOf(ok(900, now - 1000)))
        assertEquals(ServerHealth.SLOW, r.health)
    }

    @Test fun staleSamplesAreIgnored() {
        val old = now - ServerQualityCalculator.TTL_MS - 5000
        val r = q(listOf(ok(50, old), ok(50, old + 1), ok(50, old + 2)))
        assertEquals(0, r.samples)
        assertEquals(ServerHealth.UNKNOWN, r.health)
    }

    @Test fun samplesFromAnotherNetworkGenerationAreIgnored() {
        val r = q(listOf(ok(50, now - 3000, gen = 0), ok(50, now - 2000, gen = 0), ok(50, now - 1000, gen = 0)), gen = 1)
        assertEquals(0, r.samples)
        assertNull(r.score)
    }

    @Test fun otherKindIsNeverMixedIn() {
        val r = q(listOf(ok(50, now - 1000, kind = TestKind.URL_LATENCY)))
        assertEquals(0, r.samples)
    }

    @Test fun testingMarkWinsOverOldResult() {
        val r = q(listOf(ok(50, now - 1000)), testing = true)
        assertEquals(ServerHealth.TESTING, r.health)
    }

    @Test fun storeKeepsServersSeparateAndClassifiesErrors() {
        ServerDiagnostics.forget(1L); ServerDiagnostics.forget(2L)
        ServerDiagnostics.record(1L, TestKind.TCP_CONNECT, Outcome.SUCCESS, 40, now)
        assertEquals(1, ServerDiagnostics.quality(1L, TestKind.TCP_CONNECT, now).samples)
        assertEquals(0, ServerDiagnostics.quality(2L, TestKind.TCP_CONNECT, now).samples)
        assertEquals(Outcome.TIMEOUT, ServerDiagnostics.classify(RuntimeException(SocketTimeoutException("x"))))
        assertEquals(Outcome.UNREACHABLE, ServerDiagnostics.classify(java.net.ConnectException("refused")))
        assertEquals(Outcome.ERROR, ServerDiagnostics.classify(IllegalStateException("boom")))
        ServerDiagnostics.onNetworkChanged()
        assertEquals(0, ServerDiagnostics.quality(1L, TestKind.TCP_CONNECT, now).samples)
        ServerDiagnostics.forget(1L)
    }
}
