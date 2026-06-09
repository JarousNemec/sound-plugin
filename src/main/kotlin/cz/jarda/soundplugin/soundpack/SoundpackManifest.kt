package cz.jarda.soundplugin.soundpack

/**
 * A single default mapping entry inside `soundpack.json` (PRD §7.2). Fields are mutable with a
 * no-arg constructor so Gson can populate them; unknown extra fields in the JSON are ignored.
 */
class DefaultMapping {
    var eventId: String = ""
    var soundFile: String = ""
    var volume: Int = 100

    constructor()

    constructor(eventId: String, soundFile: String, volume: Int) {
        this.eventId = eventId
        this.soundFile = soundFile
        this.volume = volume
    }
}

/**
 * Parsed `soundpack.json` manifest (PRD §7.2). The manifest is the shareable unit and never holds
 * personal IDE state. `schemaVersion` exists for future migrations; unknown `eventId`s are ignored
 * at load time with a warning rather than failing the whole pack.
 */
class SoundpackManifest {
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION
    var id: String = ""
    var name: String = ""
    var author: String = ""
    var description: String = ""
    var version: String = "1.0.0"
    var defaultMappings: MutableList<DefaultMapping> = mutableListOf()

    fun defaultMappingFor(eventId: String): DefaultMapping? =
        defaultMappings.firstOrNull { it.eventId == eventId }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
