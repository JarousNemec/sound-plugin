package cz.jarda.soundplugin.soundpack

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * A loaded soundpack: its manifest plus the resolved root directory on disk and an index of the
 * audio files it actually contains under `sounds/`.
 */
class Soundpack(
    val manifest: SoundpackManifest,
    val root: Path,
) {
    val id: String get() = manifest.id
    val name: String get() = manifest.name.ifBlank { manifest.id }

    /** Relative paths (POSIX-style, e.g. `sounds/build_ok.wav`) of available audio files. */
    val availableSoundFiles: List<String> by lazy { scanSoundFiles() }

    fun defaultMappingFor(eventId: String): DefaultMapping? = manifest.defaultMappingFor(eventId)

    /** Resolves a manifest-relative sound file reference to an absolute path under [root]. */
    fun resolveSound(relative: String): Path = root.resolve(relative).normalize()

    private fun scanSoundFiles(): List<String> {
        val soundsDir = root.resolve(SOUNDS_DIR)
        if (!Files.isDirectory(soundsDir)) return emptyList()
        return Files.walk(soundsDir).use { stream ->
            stream.filter { it.isRegularFile() && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
                .map { root.relativize(it).joinToString("/") }
                .sorted()
                .toList()
        }
    }

    companion object {
        const val MANIFEST_FILE = "soundpack.json"
        const val SOUNDS_DIR = "sounds"
        val SUPPORTED_EXTENSIONS = setOf("wav", "au", "aiff", "aif")

        fun isSupportedAudio(path: Path): Boolean =
            path.name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
    }
}
