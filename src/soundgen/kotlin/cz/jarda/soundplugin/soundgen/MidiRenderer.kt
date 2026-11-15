package cz.jarda.soundplugin.soundgen

import cz.jarda.soundplugin.soundgen.DemoSoundCatalog.SoundDef
import java.io.File
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import javax.sound.midi.Soundbank
import javax.sound.midi.Synthesizer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a [SoundDef] into a MIDI [Sequence] and renders it offline to mono 16-bit PCM using the
 * JDK's built-in Gervill synthesizer (`com.sun.media.sound.SoftSynthesizer`). Offline rendering
 * uses `AudioSynthesizer.openStream`, which is not exported API, so it is reached via reflection
 * (the Gradle task passes `--add-opens java.desktop/com.sun.media.sound=ALL-UNNAMED`).
 * No audio device is needed.
 */
class MidiRenderer(private val soundbank: Soundbank?) {

    private val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 2, true, false)

    /** Channel per distinct program, in order of first use (channel 9 is drums, skip it). */
    private fun channelsFor(def: SoundDef): Map<Int, Int> {
        val programs = def.notes.map { it.program }.distinct()
        require(programs.size <= 8) { "${def.name}: too many instruments" }
        return programs.mapIndexed { i, p -> p to i }.toMap()
    }

    /** Timed MIDI messages (microseconds) for the sound: setup at t=0, then notes. */
    private fun messages(def: SoundDef): List<Pair<Long, MidiMessage>> {
        val out = mutableListOf<Pair<Long, MidiMessage>>()
        for ((program, ch) in channelsFor(def)) {
            out += 0L to ShortMessage(ShortMessage.PROGRAM_CHANGE, ch, program, 0)
            out += 0L to ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 7, 110)   // channel volume
            out += 0L to ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 91, REVERB) // reverb send
            out += 0L to ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 93, 0)      // chorus off
        }
        val channels = channelsFor(def)
        for (n in def.notes) {
            val ch = channels.getValue(n.program)
            // Small offset so program changes are applied before the first note.
            val on = (n.startMs + LEAD_IN_MS) * 1000L
            val off = on + n.durationMs * 1000L
            out += on to ShortMessage(ShortMessage.NOTE_ON, ch, n.pitch, n.velocity)
            out += off to ShortMessage(ShortMessage.NOTE_OFF, ch, n.pitch, 0)
        }
        return out.sortedBy { it.first }
    }

    /** Writes the sound as a standard MIDI file (type 1), for editing in a DAW. */
    fun writeMidi(def: SoundDef, target: File) {
        val sequence = Sequence(Sequence.PPQ, PPQ)
        val track = sequence.createTrack()
        // 120 BPM: one quarter = 500 ms, so ticks = ms * PPQ / 500.
        val tempo = MetaMessage(0x51, byteArrayOf(0x07, 0xA1.toByte(), 0x20), 3)
        track.add(MidiEvent(tempo, 0))
        for ((micros, msg) in messages(def)) {
            track.add(MidiEvent(msg, micros / 1000L * PPQ / 500L))
        }
        target.parentFile.mkdirs()
        MidiSystem.write(sequence, 1, target)
    }

    /** Renders the sound to normalised, trimmed mono 16-bit PCM samples. */
    fun render(def: SoundDef): ShortArray {
        val synth = MidiSystem.getSynthesizer()
        val stream = openStream(synth)
        try {
            if (soundbank != null) {
                synth.unloadAllInstruments(synth.defaultSoundbank)
                check(synth.loadAllInstruments(soundbank)) { "could not load soundfont" }
            }
            val receiver = synth.receiver
            val msgs = messages(def)
            for ((micros, msg) in msgs) receiver.send(msg, micros)

            val endMs = def.notes.maxOf { it.startMs + it.durationMs } + LEAD_IN_MS + TAIL_MS
            val frames = SAMPLE_RATE * endMs / 1000
            val bytes = ByteArray(frames * format.frameSize)
            var read = 0
            while (read < bytes.size) {
                val n = stream.read(bytes, read, bytes.size - read)
                if (n < 0) break
                read += n
            }
            return postProcess(toMono(bytes))
        } finally {
            stream.close()
            synth.close()
        }
    }

    private fun openStream(synth: Synthesizer): AudioInputStream {
        val method = synth.javaClass.getMethod("openStream", AudioFormat::class.java, Map::class.java)
        method.isAccessible = true
        return method.invoke(synth, format, null) as AudioInputStream
    }

    /** Instrument name the active bank uses for a program (for the generator's report). */
    fun instrumentName(program: Int): String {
        val synth = MidiSystem.getSynthesizer()
        val stream = openStream(synth)
        try {
            val bank = soundbank ?: synth.defaultSoundbank
            return bank?.instruments?.firstOrNull { it.patch.bank == 0 && it.patch.program == program }?.name ?: "?"
        } finally {
            stream.close()
            synth.close()
        }
    }

    private fun toMono(bytes: ByteArray): DoubleArray {
        val frames = bytes.size / 4
        return DoubleArray(frames) { i ->
            val l = (bytes[i * 4].toInt() and 0xFF) or (bytes[i * 4 + 1].toInt() shl 8)
            val r = (bytes[i * 4 + 2].toInt() and 0xFF) or (bytes[i * 4 + 3].toInt() shl 8)
            (l.toShort() + r.toShort()) / 2.0 / Short.MAX_VALUE
        }
    }

    /** Trims silence, fades the tail out and normalises the peak to [TARGET_PEAK]. */
    private fun postProcess(samples: DoubleArray): ShortArray {
        val peak = samples.maxOfOrNull { abs(it) } ?: 0.0
        check(peak > 1e-4) { "rendered silence - soundbank missing the instrument?" }
        val threshold = peak * SILENCE_RATIO
        val first = max(0, samples.indexOfFirst { abs(it) > threshold } - SAMPLE_RATE / 500) // keep 2 ms
        val last = samples.indexOfLast { abs(it) > threshold }
        val trimmed = samples.copyOfRange(first, last + 1)

        val gain = TARGET_PEAK / peak
        val fade = min(SAMPLE_RATE * FADE_OUT_MS / 1000, trimmed.size / 2)
        return ShortArray(trimmed.size) { i ->
            val fromEnd = trimmed.size - 1 - i
            val env = if (fromEnd < fade) fromEnd.toDouble() / fade else 1.0
            (trimmed[i] * gain * env * Short.MAX_VALUE).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    companion object {
        const val SAMPLE_RATE = 44100
        private const val PPQ = 480
        private const val REVERB = 30
        private const val LEAD_IN_MS = 20
        private const val TAIL_MS = 600
        private const val FADE_OUT_MS = 60
        /** −3 dBFS. */
        private const val TARGET_PEAK = 0.708

        /**
         * Anything quieter than −40 dB relative to the peak counts as silence. Cuts the inaudible
         * end of the reverb/decay tail (at default volumes that is below −55 dBFS).
         */
        private const val SILENCE_RATIO = 0.01
    }
}
