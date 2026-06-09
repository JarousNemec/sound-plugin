package cz.jarda.soundplugin.soundpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import javax.sound.sampled.AudioSystem

class ToneGeneratorTest {

    @Test
    fun `every bundled tone produces a valid loadable WAV`() {
        val dir = Files.createTempDirectory("tones")
        try {
            for (tone in ToneGenerator.BUNDLED_TONES) {
                val file = dir.resolve("sounds").resolve(tone.fileName)
                ToneGenerator.write(tone, file)
                assertTrue("file should exist: ${tone.fileName}", Files.exists(file))
                assertTrue("file should be non-empty: ${tone.fileName}", Files.size(file) > 44)

                // Must be parseable by Java Sound (valid WAV header + PCM data).
                val format = AudioSystem.getAudioFileFormat(file.toFile())
                assertEquals(44100f, format.format.sampleRate, 0.1f)
                assertEquals(16, format.format.sampleSizeInBits)
                assertEquals(1, format.format.channels)

                // And decodable as an audio stream.
                AudioSystem.getAudioInputStream(file.toFile()).use { ais ->
                    assertTrue(ais.frameLength > 0)
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
