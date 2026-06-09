package cz.jarda.soundplugin.player

import java.nio.file.Files
import java.nio.file.Path

/**
 * In-memory LRU cache of raw audio file bytes keyed by absolute path (PRD §4.3, §8.4).
 *
 * We cache the original file bytes (not a decoded PCM buffer or a [javax.sound.sampled.Clip]):
 * a `Clip` is single-use and cannot be replayed concurrently, so the player builds a fresh
 * `Clip` per playback from these bytes. Caching the bytes still removes the disk read from the
 * hot path. Eviction is by accumulated byte size with a hard cap.
 *
 * Not thread-safe by itself; the player confines all access to its single playback thread.
 */
class SoundCache(private val maxBytes: Long = DEFAULT_MAX_BYTES) {

    private var currentBytes: Long = 0

    // accessOrder=true makes this a true LRU: get() moves the entry to the tail.
    private val map = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean {
            if (currentBytes <= maxBytes) return false
            // Manual eviction below keeps currentBytes accurate; signal not to auto-remove here.
            return false
        }
    }

    /**
     * Returns the file bytes for [path], loading and caching them on first access.
     * Throws if the file cannot be read; the caller (player) is responsible for error isolation.
     */
    fun get(path: Path): ByteArray {
        val key = path.toAbsolutePath().toString()
        map[key]?.let { return it }

        val bytes = Files.readAllBytes(path)
        // A single file larger than the whole cap is used once but never retained.
        if (bytes.size.toLong() > maxBytes) {
            return bytes
        }
        map[key] = bytes
        currentBytes += bytes.size
        evictIfNeeded()
        return bytes
    }

    private fun evictIfNeeded() {
        val iterator = map.entries.iterator()
        while (currentBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            currentBytes -= eldest.value.size
            iterator.remove()
        }
    }

    fun clear() {
        map.clear()
        currentBytes = 0
    }

    /** Visible for testing. */
    fun sizeBytes(): Long = currentBytes

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 32L * 1024 * 1024
    }
}
