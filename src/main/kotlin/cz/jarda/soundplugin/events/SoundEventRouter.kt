package cz.jarda.soundplugin.events

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import cz.jarda.soundplugin.player.SoundPlayerService
import cz.jarda.soundplugin.settings.MappingOverride
import cz.jarda.soundplugin.settings.SoundSettingsState
import cz.jarda.soundplugin.soundpack.DefaultMapping
import cz.jarda.soundplugin.soundpack.SoundpackManager
import cz.jarda.soundplugin.util.RateLimiter
import cz.jarda.soundplugin.util.SafeRunner
import cz.jarda.soundplugin.util.SoundLog
import cz.jarda.soundplugin.util.SoundPluginAppInit
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10

/**
 * Maps an [EventId] to a concrete [SoundRequest] using the active soundpack's defaults plus the
 * user's local overrides, then hands it to the player (PRD §4.2).
 *
 * Resolution order (PRD §6.2): global gate (master switch + global volume) → user override →
 * soundpack default. Overrides win over defaults. The router also owns the per-event debounce
 * ([RateLimiter]) and the set of mappings temporarily deactivated after an I/O failure.
 *
 * Every decision is traced with its reason (verbose logging), and handling that takes longer than
 * [SLOW_HANDLE_MS] on the calling thread (usually the EDT) is always reported.
 */
@Service
class SoundEventRouter {

    private val rateLimiter = RateLimiter()
    private val deactivated: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        // Let the player tell us when a sound file is broken so we stop trying (PRD §8.1).
        SafeRunner.run("wiring player failure callback") {
            SoundPlayerService.getInstance().onFileError = { request -> deactivate(request.eventId) }
        }
    }

    /** Listener entry point: resolve, debounce and play. Cheap and EDT-safe. */
    fun handle(eventId: String) {
        val start = System.nanoTime()
        SafeRunner.run("routing event $eventId") {
            // A plugin loaded without restart may never get its startup activity; bootstrap lazily.
            SoundPluginAppInit.getInstance().ensureStartedAsync("first event '$eventId'")

            val resolution = routeExplained(eventId)
            val request = resolution.request
            if (request == null) {
                SoundLog.trace { "${kind(eventId)} '$eventId' silent: ${resolution.reason}" }
                return@run
            }
            if (!rateLimiter.tryAcquire(eventId)) {
                SoundLog.trace { "${kind(eventId)} '$eventId' silent: debounced (repeated within ${RateLimiter.DEFAULT_WINDOW_MS} ms)" }
                return@run
            }
            SoundLog.trace {
                "${kind(eventId)} '$eventId' -> ${request.absolutePath.fileName} (${resolution.reason}, gain ${gainDb(request.effectiveVolume)})"
            }
            SoundPlayerService.getInstance().play(request)
        }
        val elapsedNanos = System.nanoTime() - start
        if (elapsedNanos > SLOW_HANDLE_MS * 1_000_000) {
            SoundLog.warnThrottled("slow-handle") {
                "handling '$eventId' took ${SoundLog.msSince(start)} ms on thread '${Thread.currentThread().name}' (limit $SLOW_HANDLE_MS ms)"
            }
        }
    }

    /**
     * Pure resolution (no rate limit, no playback). Returns the [SoundRequest] that this event
     * would play right now, or null if it is muted/disabled/unmapped.
     */
    fun route(eventId: String): SoundRequest? = routeExplained(eventId).request

    /** Like [route], but also says why (for logging). */
    fun routeExplained(eventId: String): Resolution {
        return SafeRunner.compute("resolving event $eventId", Resolution(null, "error while resolving (see warning above)")) {
            val settings = SoundSettingsState.getInstance()
            val manager = SoundpackManager.getInstance()
            val pack = manager.getActive(settings.activeSoundpackId)
            val resolution = resolve(
                enabled = settings.enabled,
                globalVolume = settings.globalVolume,
                eventId = eventId,
                deactivated = eventId in deactivated,
                override = settings.overrideFor(eventId),
                packDefault = pack?.defaultMappingFor(eventId),
            ) { rel -> pack?.resolveSound(rel) }
            if (resolution.request == null && pack == null) {
                Resolution(null, "${resolution.reason}; no soundpack loaded (startup not finished, or no valid pack in ${manager.soundpacksRoot})")
            } else if (resolution.request != null && pack != null) {
                Resolution(resolution.request, "${resolution.reason}, pack '${pack.id}'")
            } else {
                resolution
            }
        }
    }

    fun deactivate(eventId: String) {
        SoundLog.warn("mapping for '$eventId' deactivated after a file error; it stays silent until settings are applied again")
        deactivated.add(eventId)
    }

    fun reactivate(eventId: String) {
        deactivated.remove(eventId)
    }

    /** Called when settings or the active pack change so previously broken mappings get retried. */
    fun clearDeactivations() {
        deactivated.clear()
        rateLimiter.reset()
    }

    /** Result of a routing decision: the request to play (or null) and a human-readable reason. */
    class Resolution(val request: SoundRequest?, val reason: String)

    companion object {
        /** Sentinel used by the UI's "— none —" choice. */
        const val NONE = "—none—"

        /** Handling one event on the caller's thread should take well under a millisecond. */
        const val SLOW_HANDLE_MS = 15L

        @JvmStatic
        fun getInstance(): SoundEventRouter =
            ApplicationManager.getApplication().getService(SoundEventRouter::class.java)

        private val LIFECYCLE_IDS: Set<String> by lazy { LifecycleEvent.ALL.map { it.id }.toSet() }

        private fun kind(eventId: String): String = if (eventId in LIFECYCLE_IDS) "event" else "action"

        fun gainDb(linear: Float): String =
            if (linear <= 0f) "-inf dB" else "%.1f dB".format(20 * log10(linear.toDouble()))

        /**
         * The pure routing decision. Extracted as a free function so it can be unit-tested with
         * plain data and no IDE services.
         */
        fun resolveRequest(
            enabled: Boolean,
            globalVolume: Int,
            eventId: String,
            deactivated: Boolean,
            override: MappingOverride?,
            packDefault: DefaultMapping?,
            resolvePath: (String) -> Path?,
        ): SoundRequest? = resolve(enabled, globalVolume, eventId, deactivated, override, packDefault, resolvePath).request

        /** [resolveRequest] plus the reason for the decision. */
        fun resolve(
            enabled: Boolean,
            globalVolume: Int,
            eventId: String,
            deactivated: Boolean,
            override: MappingOverride?,
            packDefault: DefaultMapping?,
            resolvePath: (String) -> Path?,
        ): Resolution {
            if (!enabled) return Resolution(null, "plugin disabled in settings")
            if (globalVolume <= 0) return Resolution(null, "global volume is 0 %")
            if (deactivated) return Resolution(null, "mapping deactivated after an earlier file error")
            if (override != null && !override.enabled) return Resolution(null, "disabled by user override")

            // An override's soundFile: null/blank means "inherit the pack default", the NONE
            // sentinel means "explicitly muted" (overrides the default), anything else is the file.
            val overrideSound = override?.soundFile?.takeIf { it.isNotBlank() }
            val soundFile = if (overrideSound != null) {
                if (overrideSound == NONE) return Resolution(null, "muted by user override (— none —)")
                overrideSound
            } else {
                packDefault?.soundFile?.takeIf { it.isNotBlank() }
                    ?: return Resolution(null, "no sound mapped")
            }

            val mappingVolume = (override?.volume ?: packDefault?.volume ?: 100).coerceIn(0, 100)
            if (mappingVolume <= 0) return Resolution(null, "mapping volume is 0 %")

            val effective = (globalVolume.coerceIn(0, 100) / 100f) * (mappingVolume / 100f)
            if (effective <= 0f) return Resolution(null, "effective volume is 0")

            val path = resolvePath(soundFile) ?: return Resolution(null, "sound file '$soundFile' could not be resolved")
            val source = if (overrideSound != null || override?.volume != null) "user override" else "pack default"
            return Resolution(SoundRequest(eventId, path, effective), "$source, volume $globalVolume % x $mappingVolume %")
        }
    }
}
