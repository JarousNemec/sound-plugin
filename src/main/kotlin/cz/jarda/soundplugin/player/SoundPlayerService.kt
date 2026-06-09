package cz.jarda.soundplugin.player

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import cz.jarda.soundplugin.events.SoundRequest
import cz.jarda.soundplugin.util.SafeRunner
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.math.ln

/**
 * Plays sounds off the EDT on a dedicated single playback thread (PRD §4.2, §8.2, §8.4).
 *
 * Design guarantees:
 *  - **Never on the EDT.** All `javax.sound` work runs on a bounded single-thread executor.
 *  - **Last wins / single active voice.** A new request stops & closes the currently playing
 *    [Clip] before starting the new one. Only one request can be pending; a newer one replaces it.
 *  - **Error isolation.** Every audio operation is wrapped; missing device / busy line / corrupt
 *    file degrade to silence + one log warning. File/decoder errors are reported via
 *    [onFileError] so the router can deactivate that mapping and stop spamming.
 *  - **Lifecycle.** Each clip is closed on [LineEvent.Type.STOP]; [dispose] stops playback,
 *    closes the line and shuts the executor down.
 */
@Service
class SoundPlayerService : Disposable {

    private val log = Logger.getInstance(SoundPlayerService::class.java)

    private val executor: ExecutorService =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("SoundPlugin-Player", 1)

    /** The single pending request slot. A newer request overwrites an older un-started one. */
    private val pending = AtomicReference<SoundRequest?>(null)

    /** The currently open/playing clip, accessed only from the playback thread. */
    @Volatile
    private var currentClip: Clip? = null

    @Volatile
    private var disposed = false

    /** Set once we have warned about the audio device being unavailable (avoids log spam). */
    @Volatile
    private var deviceWarningLogged = false

    /**
     * Called when a request fails because of a file/decoder problem (missing or corrupt sound).
     * The router uses this to temporarily deactivate the mapping (PRD §8.1). Device problems do
     * NOT trigger this — they are transient and may recover on the next event.
     */
    @Volatile
    var onFileError: ((SoundRequest) -> Unit)? = null

    /**
     * Submits [request] for playback. Returns immediately; all real work happens off-thread.
     * Honours "last wins": the newest request becomes the one that plays.
     */
    fun play(request: SoundRequest) {
        if (disposed) return
        pending.set(request)
        SafeRunner.run("SoundPlayerService.play submit") {
            executor.execute { drain() }
        }
    }

    private fun drain() {
        // Take the most recent pending request; older queued drain() calls find nothing to do.
        val request = pending.getAndSet(null) ?: return
        SafeRunner.run("SoundPlayerService.drain") {
            stopCurrent()
            if (!disposed) startPlaying(request)
        }
    }

    private fun startPlaying(request: SoundRequest) {
        val bytes: ByteArray
        try {
            bytes = cache.get(request.absolutePath)
        } catch (e: IOException) {
            reportFileError(request, "reading sound file", e)
            return
        }

        var clip: Clip? = null
        try {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
            val newClip = AudioSystem.getClip()
            clip = newClip
            newClip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    SafeRunner.run("closing clip on STOP") { newClip.close() }
                }
            }
            newClip.open(stream)
            applyVolume(newClip, request.effectiveVolume)
            currentClip = newClip
            newClip.start()
        } catch (e: UnsupportedAudioFileException) {
            safeClose(clip)
            reportFileError(request, "decoding sound file", e)
        } catch (e: IOException) {
            safeClose(clip)
            reportFileError(request, "decoding sound file", e)
        } catch (e: LineUnavailableException) {
            // No / busy audio device — transient, do not deactivate the mapping.
            safeClose(clip)
            logDeviceWarningOnce(e)
        } catch (e: IllegalArgumentException) {
            // Thrown by AudioSystem.getClip() when there is no audio device (e.g. headless).
            safeClose(clip)
            logDeviceWarningOnce(e)
        } catch (t: Throwable) {
            safeClose(clip)
            SafeRunner.report("starting playback", t)
        }
    }

    /** Converts a linear 0f..1f gain to dB and applies it via the best available control. */
    private fun applyVolume(clip: Clip, linear: Float) {
        SafeRunner.run("applying volume") {
            val control: FloatControl = when {
                clip.isControlSupported(FloatControl.Type.MASTER_GAIN) ->
                    clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                clip.isControlSupported(FloatControl.Type.VOLUME) ->
                    clip.getControl(FloatControl.Type.VOLUME) as FloatControl
                else -> null
            } ?: return@run

            val clamped = linear.coerceIn(0f, 1f)
            if (control.type == FloatControl.Type.MASTER_GAIN) {
                // dB. Guard against log10(0).
                val db = if (clamped <= 0.0001f) control.minimum
                else (20.0 * ln(clamped.toDouble()) / LN_10).toFloat()
                control.value = db.coerceIn(control.minimum, control.maximum)
            } else {
                // VOLUME control is already linear 0..1 within its own range.
                val v = control.minimum + (control.maximum - control.minimum) * clamped
                control.value = v.coerceIn(control.minimum, control.maximum)
            }
        }
    }

    private fun stopCurrent() {
        val clip = currentClip ?: return
        currentClip = null
        SafeRunner.run("stopping current clip") {
            if (clip.isRunning) clip.stop()
            clip.close()
        }
    }

    private fun safeClose(clip: Clip?) {
        if (clip == null) return
        SafeRunner.run("closing failed clip") { clip.close() }
    }

    private fun reportFileError(request: SoundRequest, what: String, e: Throwable) {
        log.warn("SoundPlugin: $what failed for '${request.absolutePath}' (event ${request.eventId}); deactivating mapping", e)
        SafeRunner.run("notifying file error") { onFileError?.invoke(request) }
    }

    private fun logDeviceWarningOnce(e: Throwable) {
        if (deviceWarningLogged) return
        deviceWarningLogged = true
        log.warn("SoundPlugin: audio device unavailable; sounds will stay silent until it recovers", e)
    }

    override fun dispose() {
        disposed = true
        pending.set(null)
        stopCurrent()
        SafeRunner.run("shutting down player executor") {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
        SafeRunner.run("clearing sound cache") { cache.clear() }
    }

    /** Visible for testing: whether the executor has been shut down. */
    fun isShutdown(): Boolean = executor.isShutdown

    private val cache = SoundCache()

    companion object {
        private val LN_10 = ln(10.0)

        @JvmStatic
        fun getInstance(): SoundPlayerService =
            ApplicationManager.getApplication().getService(SoundPlayerService::class.java)
    }
}
