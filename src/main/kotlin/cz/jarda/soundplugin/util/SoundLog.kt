package cz.jarda.soundplugin.util

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Plugin logging. Every line goes to idea.log prefixed with [PREFIX], so the whole plugin can be
 * followed with a single grep / log filter.
 *
 * Three levels:
 *  - [info] / [warn]: always on, used for rare facts (startup summary, unpacking, audio device,
 *    problems). Must never fire per event.
 *  - [trace]: per-event decisions and timings. Off by default; enabled by the "Verbose logging"
 *    checkbox in Tools → SoundPlugin, or by adding `#cz.jarda.soundplugin` in
 *    Help → Diagnostic Tools → Debug Log Settings.
 *  - [warnThrottled]: always-on warnings for recurring problems (slow EDT handling, late
 *    playback), at most once per key per interval so they cannot flood the log.
 *
 * Logging itself never throws.
 */
object SoundLog {
    const val PREFIX = "[SoundPlugin]"

    @PublishedApi
    internal val LOG: Logger = Logger.getInstance("#cz.jarda.soundplugin")

    /** Mirrors the persisted "verbose logging" setting (set by SoundSettingsState). */
    @Volatile
    var verboseEnabled: Boolean = false

    val isVerbose: Boolean
        get() = verboseEnabled || runCatching { LOG.isDebugEnabled }.getOrDefault(false)

    private val lastThrottled = ConcurrentHashMap<String, Long>()

    /** Per-event detail; [message] is only built when verbose logging is on. */
    inline fun trace(message: () -> String) {
        if (!isVerbose) return
        val text = try {
            message()
        } catch (t: Throwable) {
            "<failed to build log message: $t>"
        }
        emit(text)
    }

    @PublishedApi
    internal fun emit(message: String) {
        try {
            LOG.info("$PREFIX $message")
        } catch (_: Throwable) {
            // never let logging break anything
        }
    }

    fun info(message: String) = emit(message)

    fun warn(message: String, t: Throwable? = null) {
        try {
            if (t == null) LOG.warn("$PREFIX $message") else LOG.warn("$PREFIX $message", t)
        } catch (_: Throwable) {
        }
    }

    /** Warns at most once per [intervalMs] for the same [key]. */
    fun warnThrottled(key: String, intervalMs: Long = 60_000, message: () -> String) {
        val now = System.currentTimeMillis()
        var pass = false
        lastThrottled.compute(key) { _, last ->
            if (last == null || now - last >= intervalMs) {
                pass = true
                now
            } else last
        }
        if (pass) warn(try { message() } catch (t: Throwable) { "<failed to build log message: $t>" })
    }

    /** Milliseconds elapsed since [startNanos] (from [System.nanoTime]), one decimal. */
    fun msSince(startNanos: Long): String = "%.1f".format((System.nanoTime() - startNanos) / 1_000_000.0)
}
