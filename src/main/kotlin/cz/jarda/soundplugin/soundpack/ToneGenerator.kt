package cz.jarda.soundplugin.soundpack

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Generates short, valid 16-bit PCM mono WAV files used as placeholder sounds for the bundled
 * pack (until real Stronghold samples are dropped in). Each tone is a windowed sine with a short
 * linear fade-in/out to avoid clicks. Pure `javax.sound.sampled` — no external dependencies.
 */
object ToneGenerator {

    private const val SAMPLE_RATE = 44100f
    private const val FADE_MS = 12

    /** A note/segment of a tone: a frequency held for a duration, at a relative amplitude. */
    data class Segment(val frequencyHz: Double, val durationMs: Int, val amplitude: Double = 0.5)

    /** Definition of one placeholder sound: a sequence of segments (played back-to-back). */
    data class ToneSpec(val fileName: String, val segments: List<Segment>)

    /**
     * The placeholder tones for the bundled pack's default-enabled events. Distinct pitches/shapes
     * so events are aurally distinguishable. File names match the bundled `soundpack.json`.
     */
    val BUNDLED_TONES: List<ToneSpec> = listOf(
        // build ok — rising C5 -> G5
        ToneSpec("build_ok.wav", listOf(Segment(523.25, 150), Segment(783.99, 170))),
        // build fail — falling G4 -> G3
        ToneSpec("build_fail.wav", listOf(Segment(392.00, 170), Segment(196.00, 200, 0.55))),
        // run terminated success — bright E5 blip
        ToneSpec("run_ok.wav", listOf(Segment(659.25, 220))),
        // run terminated failed — dull low A3
        ToneSpec("run_fail.wav", listOf(Segment(220.00, 300, 0.6))),
        // tests passed — A5 chime
        ToneSpec("test_pass.wav", listOf(Segment(880.00, 200, 0.45))),
        // tests failed — minor fall Eb4 -> Bb3
        ToneSpec("test_fail.wav", listOf(Segment(311.13, 150), Segment(233.08, 180, 0.55))),
        // vcs commit — soft F5 tick
        ToneSpec("commit.wav", listOf(Segment(698.46, 160, 0.4))),
        // notification error — A4 + C#5 alert dyad
        ToneSpec("notify_error.wav", listOf(Segment(440.00, 120, 0.5), Segment(554.37, 160, 0.5))),
    )

    /** Writes [spec] as a WAV file at [target], creating parent directories. */
    fun write(spec: ToneSpec, target: Path) {
        Files.createDirectories(target.parent)
        val pcm = render(spec.segments)
        val format = AudioFormat(SAMPLE_RATE, 16, 1, true, false)
        AudioInputStream(ByteArrayInputStream(pcm), format, (pcm.size / format.frameSize).toLong()).use { ais ->
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, target.toFile())
        }
    }

    private fun render(segments: List<Segment>): ByteArray {
        val totalSamples = segments.sumOf { samplesFor(it.durationMs) }
        val out = ByteArray(totalSamples * 2)
        var sampleIndex = 0
        var writePos = 0
        for (seg in segments) {
            val count = samplesFor(seg.durationMs)
            val fadeSamples = min(samplesFor(FADE_MS), count / 2)
            for (i in 0 until count) {
                val t = sampleIndex / SAMPLE_RATE
                var value = sin(2.0 * PI * seg.frequencyHz * t) * seg.amplitude
                // Linear fade in/out to avoid clicks at segment boundaries.
                val fade = when {
                    i < fadeSamples -> i.toDouble() / fadeSamples
                    i >= count - fadeSamples -> (count - i).toDouble() / fadeSamples
                    else -> 1.0
                }
                value *= fade
                val s = (value * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                // little-endian 16-bit
                out[writePos++] = (s and 0xFF).toByte()
                out[writePos++] = ((s shr 8) and 0xFF).toByte()
                sampleIndex++
            }
        }
        return out
    }

    private fun samplesFor(durationMs: Int): Int = (SAMPLE_RATE * durationMs / 1000f).toInt()
}
