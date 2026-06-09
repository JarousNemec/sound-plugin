package cz.jarda.soundplugin.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-key debounce / rate limiter (PRD §8.3). The same key (an EventId) cannot pass more often
 * than [defaultWindowMs] milliseconds. Protects against floods of identical events (held Ctrl+Z,
 * bursts of notifications, rapid action sequences).
 *
 * Thread-safe. The clock is injectable so the logic can be unit-tested without sleeping.
 */
class RateLimiter(
    private val defaultWindowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastPassed = ConcurrentHashMap<String, Long>()

    /**
     * Returns true if [key] may pass now (and records this instant), false if it is still within
     * the debounce window of its previous pass.
     */
    fun tryAcquire(key: String, windowMs: Long = defaultWindowMs): Boolean {
        val now = clock()
        // compute() gives us an atomic read-modify-write per key.
        var acquired = false
        lastPassed.compute(key) { _, previous ->
            if (previous == null || now - previous >= windowMs) {
                acquired = true
                now
            } else {
                previous
            }
        }
        return acquired
    }

    /** Drops all remembered timestamps. */
    fun reset() = lastPassed.clear()

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 120
    }
}
