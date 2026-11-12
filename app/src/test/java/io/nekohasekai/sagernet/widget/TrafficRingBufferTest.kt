package io.nekohasekai.sagernet.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficRingBufferTest {

    @Test fun keepsOnlyTheNewestSamples() {
        val b = TrafficRingBuffer(4)
        for (i in 1..6) b.add(i * 1000L, i.toLong(), (i * 10).toLong())
        assertEquals(4, b.size)
        assertEquals(3000L, b.timeAt(0))
        assertEquals(6000L, b.timeAt(3))
        assertEquals(60L, b.rxAt(3))
    }

    @Test fun clearStartsANewSession() {
        val b = TrafficRingBuffer(4)
        b.add(1000, 5, 5)
        b.clear()
        assertEquals(0, b.size)
        b.add(2000, 7, 9)
        assertEquals(1, b.size)
        assertEquals(7L, b.txAt(0))
    }

    @Test fun zeroTrafficIsStoredAsRealZero() {
        val b = TrafficRingBuffer(4)
        b.add(1000, 0, 0)
        assertEquals(0L, b.txAt(0))
        assertEquals(0L, b.maxRateSince(0))
    }

    @Test fun negativeRatesAreClampedAndMaxRespectsWindow() {
        val b = TrafficRingBuffer(4)
        b.add(1000, -5, 100)
        b.add(5000, 300, 50)
        assertEquals(0L, b.txAt(0))
        assertEquals(300L, b.maxRateSince(2000))
        assertEquals(300L, b.maxRateSince(0))
    }
}
