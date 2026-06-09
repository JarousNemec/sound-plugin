package cz.jarda.soundplugin.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RateLimiterTest {

    @Test
    fun `first acquire passes, second within window is blocked, after window passes again`() {
        var now = 1_000L
        val limiter = RateLimiter(defaultWindowMs = 120) { now }

        assertTrue("first should pass", limiter.tryAcquire("e"))
        now += 50
        assertFalse("within window should be blocked", limiter.tryAcquire("e"))
        now += 80 // total 130ms since first
        assertTrue("after the window should pass", limiter.tryAcquire("e"))
    }

    @Test
    fun `different keys are independent`() {
        val now = 1_000L
        val limiter = RateLimiter(defaultWindowMs = 120) { now }
        assertTrue(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"))
        assertFalse(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("b"))
    }

    @Test
    fun `reset clears state`() {
        var now = 1_000L
        val limiter = RateLimiter(defaultWindowMs = 120) { now }
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        limiter.reset()
        now += 1
        assertTrue(limiter.tryAcquire("a"))
    }

    @Test
    fun `concurrent access does not throw and lets exactly one pass per window`() {
        val now = 5_000L
        val limiter = RateLimiter(defaultWindowMs = 1_000) { now }
        val passes = AtomicInteger(0)
        val threads = (1..16).map {
            Thread { if (limiter.tryAcquire("shared")) passes.incrementAndGet() }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // With a frozen clock and a single window, exactly one thread may pass.
        assertTrue("exactly one pass expected, got ${passes.get()}", passes.get() == 1)
    }
}
