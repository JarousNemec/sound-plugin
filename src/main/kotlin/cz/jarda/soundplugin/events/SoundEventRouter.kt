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
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps an [EventId] to a concrete [SoundRequest] using the active soundpack's defaults plus the
 * user's local overrides, then hands it to the player (PRD §4.2).
 *
 * Resolution order (PRD §6.2): global gate (master switch + global volume) → user override →
 * soundpack default. Overrides win over defaults. The router also owns the per-event debounce
 * ([RateLimiter]) and the set of mappings temporarily deactivated after an I/O failure.
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

    /** Listener entry point: debounce, resolve and play. Cheap and EDT-safe. */
    fun handle(eventId: String) {
        SafeRunner.run("routing event $eventId") {
            if (!rateLimiter.tryAcquire(eventId)) return@run
            val request = route(eventId) ?: return@run
            SoundPlayerService.getInstance().play(request)
        }
    }

    /**
     * Pure resolution (no rate limit, no playback). Returns the [SoundRequest] that this event
     * would play right now, or null if it is muted/disabled/unmapped. Visible for the settings
     * UI's preview needs and unit tests.
     */
    fun route(eventId: String): SoundRequest? {
        return SafeRunner.compute("resolving event $eventId", null) {
            val settings = SoundSettingsState.getInstance()
            val pack = SoundpackManager.getInstance().getActive(settings.activeSoundpackId)
            resolveRequest(
                enabled = settings.enabled,
                globalVolume = settings.globalVolume,
                eventId = eventId,
                deactivated = eventId in deactivated,
                override = settings.overrideFor(eventId),
                packDefault = pack?.defaultMappingFor(eventId),
            ) { rel -> pack?.resolveSound(rel) }
        }
    }

    fun deactivate(eventId: String) {
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

    companion object {
        /** Sentinel used by the UI's "— none —" choice. */
        const val NONE = "—none—"

        @JvmStatic
        fun getInstance(): SoundEventRouter =
            ApplicationManager.getApplication().getService(SoundEventRouter::class.java)

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
        ): SoundRequest? {
            if (!enabled) return null
            if (globalVolume <= 0) return null
            if (deactivated) return null
            if (override != null && !override.enabled) return null

            // An override's soundFile: null/blank means "inherit the pack default", the NONE
            // sentinel means "explicitly muted" (overrides the default), anything else is the file.
            val overrideSound = override?.soundFile?.takeIf { it.isNotBlank() }
            val soundFile = if (overrideSound != null) {
                if (overrideSound == NONE) return null
                overrideSound
            } else {
                packDefault?.soundFile?.takeIf { it.isNotBlank() } ?: return null
            }

            val mappingVolume = (override?.volume ?: packDefault?.volume ?: 100).coerceIn(0, 100)
            if (mappingVolume <= 0) return null

            val effective = (globalVolume.coerceIn(0, 100) / 100f) * (mappingVolume / 100f)
            if (effective <= 0f) return null

            val path = resolvePath(soundFile) ?: return null
            return SoundRequest(eventId, path, effective)
        }
    }
}
