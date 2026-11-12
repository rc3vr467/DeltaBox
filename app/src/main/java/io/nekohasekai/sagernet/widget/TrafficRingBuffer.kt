package io.nekohasekai.sagernet.widget

/**
 * Fixed-capacity ring buffer of real traffic samples (bytes per second) for the live chart.
 *
 * - No Android dependencies, so it can be unit-tested on the JVM.
 * - No allocation after construction: three primitive arrays are reused.
 * - Not thread-safe. It is only touched from the main thread (StatsBar.updateSpeed).
 * - Samples are exactly the rates the service already computed (txRateProxy / rxRateProxy);
 *   nothing is recomputed, smoothed or invented here.
 */
class TrafficRingBuffer(val capacity: Int = DEFAULT_CAPACITY) {

    private val times = LongArray(capacity)
    private val tx = LongArray(capacity)
    private val rx = LongArray(capacity)
    private var head = 0 // index of the oldest sample
    var size = 0
        private set

    init {
        require(capacity > 1) { "capacity must be > 1" }
    }

    /** Appends a sample; the oldest one is dropped when the buffer is full. */
    fun add(timeMs: Long, txRate: Long, rxRate: Long) {
        val index = (head + size) % capacity
        times[index] = timeMs
        tx[index] = txRate.coerceAtLeast(0L)
        rx[index] = rxRate.coerceAtLeast(0L)
        if (size < capacity) {
            size++
        } else {
            head = (head + 1) % capacity
        }
    }

    /** Starts a new session (new connection): all previous samples are discarded. */
    fun clear() {
        head = 0
        size = 0
    }

    /** i = 0 is the oldest sample, i = size - 1 the newest. */
    fun timeAt(i: Int): Long = times[(head + i) % capacity]
    fun txAt(i: Int): Long = tx[(head + i) % capacity]
    fun rxAt(i: Int): Long = rx[(head + i) % capacity]

    /** Highest single rate (upload or download) among samples with time >= [sinceMs]. */
    fun maxRateSince(sinceMs: Long): Long {
        var max = 0L
        for (i in 0 until size) {
            if (timeAt(i) < sinceMs) continue
            val a = txAt(i)
            val b = rxAt(i)
            if (a > max) max = a
            if (b > max) max = b
        }
        return max
    }

    companion object {
        /** 90 samples = 90 s at the default 1000 ms interval, 45 s at 500 ms. */
        const val DEFAULT_CAPACITY = 90
    }
}
