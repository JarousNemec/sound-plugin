package cz.jarda.soundplugin.player

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.jarda.soundplugin.events.SoundRequest
import cz.jarda.soundplugin.soundpack.ToneGenerator
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Robustness tests (PRD §8, acceptance: triggering an error must not crash). We construct a private
 * [SoundPlayerService] instance (the application is available under the platform fixture) so as not
 * to disturb the shared app service.
 */
class SoundPlayerServiceTest : BasePlatformTestCase() {

    fun testMissingFileReportsErrorAndDoesNotThrow() {
        val player = SoundPlayerService()
        try {
            val failed = AtomicReference<SoundRequest?>()
            val latch = CountDownLatch(1)
            player.onFileError = { failed.set(it); latch.countDown() }
            player.play(SoundRequest("e.missing", Path.of(System.getProperty("java.io.tmpdir"), "does-not-exist.wav"), 0.5f))
            assertTrue("file error callback should fire", latch.await(5, TimeUnit.SECONDS))
            assertNotNull(failed.get())
        } finally {
            player.dispose()
        }
    }

    fun testCorruptFileReportsErrorAndDoesNotThrow() {
        val player = SoundPlayerService()
        val corrupt = Files.createTempFile("corrupt", ".wav")
        Files.write(corrupt, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        try {
            val latch = CountDownLatch(1)
            player.onFileError = { latch.countDown() }
            player.play(SoundRequest("e.corrupt", corrupt, 0.5f))
            assertTrue("corrupt file should report an error", latch.await(5, TimeUnit.SECONDS))
        } finally {
            player.dispose()
            Files.deleteIfExists(corrupt)
        }
    }

    fun testValidWavDoesNotThrowEvenWithoutAudioDevice() {
        val player = SoundPlayerService()
        val dir = Files.createTempDirectory("valid-wav")
        val file = dir.resolve("ok.wav")
        ToneGenerator.write(ToneGenerator.BUNDLED_TONES.first(), file)
        try {
            // No assertion on audibility (CI is usually headless); we assert nothing is thrown and
            // the service survives. Give the playback thread a moment to run.
            player.play(SoundRequest("e.ok", file, 0.5f))
            Thread.sleep(300)
            // Service still usable; a second request also must not throw.
            player.play(SoundRequest("e.ok2", file, 0.2f))
            Thread.sleep(100)
        } finally {
            player.dispose()
            dir.toFile().deleteRecursively()
        }
    }

    fun testDisposeShutsDownExecutor() {
        val player = SoundPlayerService()
        player.dispose()
        assertTrue("executor should be shut down after dispose", player.isShutdown())
    }
}
