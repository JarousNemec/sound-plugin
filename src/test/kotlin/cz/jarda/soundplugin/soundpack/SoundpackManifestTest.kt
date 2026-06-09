package cz.jarda.soundplugin.soundpack

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SoundpackManifestTest {

    private val gson = Gson()

    @Test
    fun `parses a manifest and tolerates unknown fields`() {
        val json = """
            {
              "schemaVersion": 1,
              "id": "demo",
              "name": "Demo Pack",
              "author": "tester",
              "futureField": "ignored",
              "defaultMappings": [
                { "eventId": "build.finished.success", "soundFile": "sounds/ok.wav", "volume": 80, "extra": true }
              ]
            }
        """.trimIndent()

        val manifest = gson.fromJson(json, SoundpackManifest::class.java)
        assertEquals("demo", manifest.id)
        assertEquals("Demo Pack", manifest.name)
        assertEquals(1, manifest.defaultMappings.size)
        val mapping = manifest.defaultMappingFor("build.finished.success")
        assertNotNull(mapping)
        assertEquals("sounds/ok.wav", mapping!!.soundFile)
        assertEquals(80, mapping.volume)
        assertNull(manifest.defaultMappingFor("nope"))
    }

    @Test
    fun `round-trips through gson`() {
        val original = SoundpackManifest().apply {
            id = "rt"
            name = "Round Trip"
            defaultMappings.add(DefaultMapping("test.finished.passed", "sounds/pass.wav", 75))
        }
        val restored = gson.fromJson(gson.toJson(original), SoundpackManifest::class.java)
        assertEquals(original.id, restored.id)
        assertEquals(1, restored.defaultMappings.size)
        assertEquals("sounds/pass.wav", restored.defaultMappings[0].soundFile)
        assertEquals(75, restored.defaultMappings[0].volume)
    }

    @Test
    fun `missing volume defaults to 100`() {
        val json = """{ "id": "v", "defaultMappings": [ { "eventId": "x", "soundFile": "sounds/x.wav" } ] }"""
        val manifest = gson.fromJson(json, SoundpackManifest::class.java)
        assertEquals(100, manifest.defaultMappings[0].volume)
    }
}
