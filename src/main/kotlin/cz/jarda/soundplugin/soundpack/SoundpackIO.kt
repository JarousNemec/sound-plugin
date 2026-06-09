package cz.jarda.soundplugin.soundpack

import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Low-level zip read/write helpers for soundpack import/export (PRD §7.3). */
object SoundpackIO {

    /**
     * Writes a `<name>.soundpack.zip` containing [manifestJson] as `soundpack.json` plus every file
     * under [packRoot]'s `sounds/` directory. Any existing `soundpack.json` under [packRoot] is
     * replaced by [manifestJson] so exported mappings reflect the user's current state.
     */
    fun zipPack(packRoot: Path, manifestJson: String, targetZip: Path) {
        Files.createDirectories(targetZip.parent)
        ZipOutputStream(BufferedOutputStream(Files.newOutputStream(targetZip))).use { zip ->
            // manifest first
            zip.putNextEntry(ZipEntry(Soundpack.MANIFEST_FILE))
            zip.write(manifestJson.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            val soundsDir = packRoot.resolve(Soundpack.SOUNDS_DIR)
            if (soundsDir.isDirectory()) {
                Files.walk(soundsDir).use { stream ->
                    stream.filter { it.isRegularFile() }.forEach { file ->
                        val rel = packRoot.relativize(file).joinToString("/")
                        zip.putNextEntry(ZipEntry(rel))
                        Files.copy(file, zip)
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    /**
     * Extracts [zip] into a fresh temporary directory and returns the directory that contains the
     * `soundpack.json` (the zip may wrap the pack in a top-level folder). Guards against zip-slip.
     * Returns null if no manifest is found.
     */
    fun unzipAndLocateManifest(zip: Path): Path? {
        val temp = Files.createTempDirectory("soundplugin-import")
        ZipInputStream(Files.newInputStream(zip)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val resolved = temp.resolve(entry.name).normalize()
                if (!resolved.startsWith(temp)) {
                    // zip-slip attempt — skip this entry
                    entry = zis.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    Files.createDirectories(resolved)
                } else {
                    Files.createDirectories(resolved.parent)
                    Files.newOutputStream(resolved).use { out -> zis.copyTo(out) }
                }
                entry = zis.nextEntry
            }
        }
        return findManifestDir(temp)
    }

    /** Finds the directory containing a `soundpack.json` within [root] (depth-limited search). */
    fun findManifestDir(root: Path): Path? {
        if (root.resolve(Soundpack.MANIFEST_FILE).isRegularFile()) return root
        if (!root.isDirectory()) return null
        Files.walk(root, 3).use { stream ->
            return stream.filter { it.isRegularFile() && it.name == Soundpack.MANIFEST_FILE }
                .map { it.parent }
                .findFirst()
                .orElse(null)
        }
    }

    /** Recursively copies [from] into [to] (must be a directory), creating [to] if absent. */
    fun copyDirectory(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.forEach { src ->
                val dest = to.resolve(from.relativize(src).toString())
                if (src.isDirectory()) {
                    Files.createDirectories(dest)
                } else {
                    Files.createDirectories(dest.parent)
                    Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
