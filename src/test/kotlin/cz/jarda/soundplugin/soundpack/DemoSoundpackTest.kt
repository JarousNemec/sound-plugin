package cz.jarda.soundplugin.soundpack

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.jarda.soundplugin.events.LifecycleEvent
import cz.jarda.soundplugin.settings.SoundSettingsState
import java.nio.file.Files
import javax.sound.sampled.AudioSystem

/**
 * The bundled demo-midi pack is the default: it must unpack completely, cover every curated
 * lifecycle event, and every mapped file must be a readable audio file.
 */
class DemoSoundpackTest : BasePlatformTestCase() {

    private fun unpacked(): SoundpackManager =
        SoundpackManager().apply {
            soundpacksRoot = Files.createTempDirectory("packs-demo")
            initialize()
        }

    fun testDemoPackIsDefault() {
        assertEquals(SoundpackManager.DEFAULT_PACK_ID, SoundSettingsState.DEFAULT_SOUNDPACK_ID)
        val pack = unpacked().getActive(SoundSettingsState.DEFAULT_SOUNDPACK_ID)
        assertNotNull(pack)
        assertEquals("demo-midi", pack!!.id)
    }

    fun testDemoPackMapsEveryLifecycleEvent() {
        val pack = unpacked().getPack(SoundpackManager.DEFAULT_PACK_ID)!!
        for (event in LifecycleEvent.ALL) {
            assertNotNull("missing demo mapping for ${event.id}", pack.defaultMappingFor(event.id))
        }
        assertTrue("demo pack should also map IDE actions", pack.defaultMappingFor("\$Copy") != null)
    }

    fun testEveryDemoMappingResolvesToReadableAudio() {
        val pack = unpacked().getPack(SoundpackManager.DEFAULT_PACK_ID)!!
        // Validation drops mappings with broken files, so compare with the raw bundled manifest.
        val bundledCount = javaClass.getResourceAsStream("/soundpacks/demo-midi/soundpack.json")!!
            .bufferedReader().use { r -> Regex("\"eventId\"").findAll(r.readText()).count() }
        assertEquals("no mapping may be dropped by validation", bundledCount, pack.manifest.defaultMappings.size)
        for (m in pack.manifest.defaultMappings) {
            val file = pack.resolveSound(m.soundFile)
            assertTrue("${m.soundFile} should exist", Files.exists(file))
            assertNotNull(AudioSystem.getAudioFileFormat(file.toFile()))
        }
    }

    fun testMissingFileIsRestoredOnNextInit() {
        val mgr = unpacked()
        val pack = mgr.getPack(SoundpackManager.DEFAULT_PACK_ID)!!
        val file = pack.resolveSound(pack.manifest.defaultMappings.first().soundFile)
        Files.delete(file)
        mgr.initialize()
        assertTrue("deleted bundled sound should be restored", Files.exists(file))
    }
}
