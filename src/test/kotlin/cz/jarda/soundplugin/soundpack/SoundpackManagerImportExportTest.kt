package cz.jarda.soundplugin.soundpack

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.jarda.soundplugin.settings.MappingOverride
import java.nio.file.Files

/**
 * Acceptance: a soundpack exported from one IDE can be imported into another, sounds and mappings
 * intact (PRD §13). Also covers first-run unpack generating valid placeholder tones.
 */
class SoundpackManagerImportExportTest : BasePlatformTestCase() {

    fun testUnpackGeneratesTonesAndLoadsBundledPack() {
        val mgr = SoundpackManager()
        mgr.soundpacksRoot = Files.createTempDirectory("packs-unpack")
        mgr.initialize()

        val pack = mgr.getActive(SoundpackManager.BUNDLED_ID)
        assertNotNull("bundled pack should load", pack)
        assertEquals(SoundpackManager.BUNDLED_ID, pack!!.id)
        assertEquals("all 8 placeholder tones should be generated", 8, pack.availableSoundFiles.size)
        assertFalse("bundled default mappings should resolve to real files", pack.manifest.defaultMappings.isEmpty())
    }

    fun testExportThenImportPreservesMappingsAndFiles() {
        val sourceRoot = Files.createTempDirectory("packs-src")
        val source = SoundpackManager().apply { soundpacksRoot = sourceRoot }
        source.initialize()
        val pack = source.getActive(SoundpackManager.BUNDLED_ID)!!

        // A user override that changes one mapping's sound + volume.
        val overrides = mapOf(
            "build.finished.success" to MappingOverride("sounds/test_pass.wav", 33, true),
        )
        val zip = Files.createTempFile("export", ".soundpack.zip")
        assertTrue("export should succeed", source.exportPack(pack, overrides, zip))

        // Import into a completely separate config root (simulating a second IDE).
        val destRoot = Files.createTempDirectory("packs-dest")
        val dest = SoundpackManager().apply { soundpacksRoot = destRoot }
        val staged = dest.stageImport(zip)
        assertNotNull("staged import should parse", staged)

        val installed = dest.installStaged(staged!!, staged.manifest.id)
        assertNotNull("install should succeed", installed)

        val mapping = installed!!.defaultMappingFor("build.finished.success")
        assertNotNull("overridden mapping should be exported", mapping)
        assertEquals("sounds/test_pass.wav", mapping!!.soundFile)
        assertEquals(33, mapping.volume)
        assertTrue("sound files should be copied across", installed.availableSoundFiles.isNotEmpty())

        Files.deleteIfExists(zip)
    }

    fun testImportCollisionSuggestsFreeId() {
        val root = Files.createTempDirectory("packs-collide")
        val mgr = SoundpackManager().apply { soundpacksRoot = root }
        mgr.initialize()
        assertTrue(mgr.packExists(SoundpackManager.BUNDLED_ID))
        val free = mgr.suggestFreeId(SoundpackManager.BUNDLED_ID)
        assertEquals("${SoundpackManager.BUNDLED_ID}-2", free)
    }
}
