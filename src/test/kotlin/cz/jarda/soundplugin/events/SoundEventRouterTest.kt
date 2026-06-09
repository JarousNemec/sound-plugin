package cz.jarda.soundplugin.events

import cz.jarda.soundplugin.settings.MappingOverride
import cz.jarda.soundplugin.soundpack.DefaultMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

/** Unit tests for the pure routing decision [SoundEventRouter.resolveRequest]. */
class SoundEventRouterTest {

    private val resolve: (String) -> Path? = { rel -> Path.of("/packs/active", rel) }

    private fun route(
        enabled: Boolean = true,
        globalVolume: Int = 100,
        eventId: String = "build.finished.success",
        deactivated: Boolean = false,
        override: MappingOverride? = null,
        packDefault: DefaultMapping? = DefaultMapping(eventId, "sounds/ok.wav", 100),
    ) = SoundEventRouter.resolveRequest(enabled, globalVolume, eventId, deactivated, override, packDefault, resolve)

    @Test
    fun `pack default resolves when no override`() {
        val r = route()!!
        assertEquals("ok.wav", r.absolutePath.fileName.toString())
        assertEquals(1.0f, r.effectiveVolume, 0.0001f)
    }

    @Test
    fun `override soundFile and volume win over pack default`() {
        val r = route(override = MappingOverride("sounds/custom.wav", 50, true))!!
        assertEquals("custom.wav", r.absolutePath.fileName.toString())
        assertEquals(0.5f, r.effectiveVolume, 0.0001f)
    }

    @Test
    fun `disabled override mutes the event`() {
        assertNull(route(override = MappingOverride(null, null, false)))
    }

    @Test
    fun `master switch off mutes everything`() {
        assertNull(route(enabled = false))
    }

    @Test
    fun `global volume zero mutes everything`() {
        assertNull(route(globalVolume = 0))
    }

    @Test
    fun `per-mapping volume zero mutes the event`() {
        assertNull(route(override = MappingOverride(null, 0, true)))
    }

    @Test
    fun `effective volume multiplies global and mapping volume`() {
        val r = route(globalVolume = 50, packDefault = DefaultMapping("e", "sounds/ok.wav", 80))!!
        assertEquals(0.4f, r.effectiveVolume, 0.0001f) // 0.5 * 0.8
    }

    @Test
    fun `unmapped event returns null`() {
        assertNull(route(packDefault = null, override = null))
    }

    @Test
    fun `none sentinel in override returns null`() {
        assertNull(route(override = MappingOverride(SoundEventRouter.NONE, null, true)))
    }

    @Test
    fun `deactivated event returns null`() {
        assertNull(route(deactivated = true))
    }
}
