package cz.jarda.soundplugin.events

import java.nio.file.Path

/**
 * A fully-resolved request to play one sound. Produced by [SoundEventRouter] and consumed by the
 * player. Carries an absolute file path (resolution already done) and the effective volume.
 *
 * @property eventId the originating event id (used for rate-limit keying and failure deactivation)
 * @property absolutePath absolute path to the decoded-on-demand audio file
 * @property effectiveVolume final gain in the range 0f..1f (global * per-mapping), already applied
 */
data class SoundRequest(
    val eventId: String,
    val absolutePath: Path,
    val effectiveVolume: Float,
)
