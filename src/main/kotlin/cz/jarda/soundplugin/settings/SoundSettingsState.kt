package cz.jarda.soundplugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.MapAnnotation

/**
 * Local IDE settings (PRD §6.2). Persisted to `soundPlugin.xml` and roamed via Settings Sync.
 * Holds the master switch, global volume, the active soundpack id and per-event user overrides.
 * Overrides take precedence over soundpack defaults.
 */
@Service
@State(name = "SoundPluginSettings", storages = [Storage("soundPlugin.xml")])
class SoundSettingsState : PersistentStateComponent<SoundSettingsState.State> {

    class State {
        var enabled: Boolean = true

        /** 0..100, where 0 means fully muted. */
        var globalVolume: Int = 70

        var activeSoundpackId: String = DEFAULT_SOUNDPACK_ID

        @get:MapAnnotation(surroundWithTag = false, entryTagName = "override", keyAttributeName = "eventId")
        var overrides: MutableMap<String, MappingOverride> = mutableMapOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    // ---- Convenience accessors ----

    var enabled: Boolean
        get() = state.enabled
        set(value) {
            state.enabled = value
        }

    var globalVolume: Int
        get() = state.globalVolume.coerceIn(0, 100)
        set(value) {
            state.globalVolume = value.coerceIn(0, 100)
        }

    var activeSoundpackId: String
        get() = state.activeSoundpackId
        set(value) {
            state.activeSoundpackId = value
        }

    val overrides: MutableMap<String, MappingOverride>
        get() = state.overrides

    fun overrideFor(eventId: String): MappingOverride? = state.overrides[eventId]

    fun setOverride(eventId: String, override: MappingOverride) {
        state.overrides[eventId] = override
    }

    fun clearOverrides() = state.overrides.clear()

    /** Replaces the entire override map (used when applying settings UI changes). */
    fun replaceOverrides(newOverrides: Map<String, MappingOverride>) {
        state.overrides = newOverrides.mapValues { it.value.copy() }.toMutableMap()
    }

    companion object {
        const val DEFAULT_SOUNDPACK_ID = "stronghold-classic"

        @JvmStatic
        fun getInstance(): SoundSettingsState =
            ApplicationManager.getApplication().getService(SoundSettingsState::class.java)
    }
}
