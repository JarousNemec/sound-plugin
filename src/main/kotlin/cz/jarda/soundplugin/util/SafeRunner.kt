package cz.jarda.soundplugin.util

import com.intellij.openapi.diagnostic.Logger

/**
 * Central error-isolation helper. The plugin must never crash the IDE or any of its processes
 * (PRD §8.1): every listener callback and every `javax.sound` operation is funnelled through
 * here so that any [Throwable] is caught, logged once and never propagated.
 */
object SafeRunner {
    private val LOG = Logger.getInstance(SafeRunner::class.java)

    /** Runs [block], swallowing and logging any [Throwable]. Never rethrows. */
    inline fun run(context: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // Inline functions cannot touch the private LOG directly, so we delegate.
            report(context, t)
        }
    }

    /**
     * Runs [block] and returns its result, or [fallback] if it threw. Never rethrows.
     * Useful where a value is needed (e.g. building an EventId) but failure must stay silent.
     */
    inline fun <T> compute(context: String, fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (t: Throwable) {
            report(context, t)
            fallback
        }
    }

    /** Public so the inline functions above can report from the call site. */
    fun report(context: String, t: Throwable) {
        // Never let logging itself bring anything down.
        try {
            LOG.warn("SoundPlugin: suppressed error during '$context'", t)
        } catch (ignored: Throwable) {
            // nothing we can safely do here
        }
    }
}
