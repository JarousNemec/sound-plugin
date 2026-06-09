package cz.jarda.soundplugin.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeRunnerTest {

    @Test
    fun `run swallows throwable and does not propagate`() {
        var ran = false
        SafeRunner.run("boom") {
            ran = true
            throw RuntimeException("explode")
        }
        assertTrue("the block should have started", ran)
        // Reaching here means nothing was rethrown.
    }

    @Test
    fun `run swallows even Errors`() {
        SafeRunner.run("oom-ish") {
            throw StackOverflowError("deep")
        }
    }

    @Test
    fun `compute returns the block result on success`() {
        val result = SafeRunner.compute("ok", -1) { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `compute returns the fallback on failure`() {
        val result = SafeRunner.compute("fail", -1) { throw IllegalStateException("x") }
        assertEquals(-1, result)
    }
}
