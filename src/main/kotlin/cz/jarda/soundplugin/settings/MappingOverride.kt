package cz.jarda.soundplugin.settings

/**
 * A user override of a soundpack's default mapping for one event (PRD §6.2). Stored locally and
 * roamed via Settings Sync. Overrides take precedence over the active soundpack's defaults.
 *
 * Mutable with a no-arg constructor so the IntelliJ XML serializer can (de)serialize it.
 *
 * @property soundFile relative sound file (within the pack) to use, or null to keep the pack default
 * @property volume per-mapping volume 0..100, or null to keep the pack default
 * @property enabled whether this event plays at all
 */
class MappingOverride() {
    var soundFile: String? = null
    var volume: Int? = null
    var enabled: Boolean = true

    constructor(soundFile: String?, volume: Int?, enabled: Boolean) : this() {
        this.soundFile = soundFile
        this.volume = volume
        this.enabled = enabled
    }

    fun copy(): MappingOverride = MappingOverride(soundFile, volume, enabled)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MappingOverride) return false
        return soundFile == other.soundFile && volume == other.volume && enabled == other.enabled
    }

    override fun hashCode(): Int {
        var result = soundFile?.hashCode() ?: 0
        result = 31 * result + (volume ?: 0)
        result = 31 * result + enabled.hashCode()
        return result
    }
}
