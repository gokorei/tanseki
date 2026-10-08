package gokorei.tanseki.service

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * The release identity has exactly one editable source (`gradle.properties`) and
 * two ways of reaching the runtime. Both are asserted here, because a seam that
 * silently falls back to [TansekiVersion.UNKNOWN] would publish `0.0.0` to every
 * dependent while every other gate stayed green.
 */
class TansekiVersionTest {
    @AfterEach
    fun clearProperty() {
        System.clearProperty(TansekiVersion.PROPERTY)
    }

    @Test
    fun `the packaged resource carries a real version`() {
        val resource =
            TansekiVersion::class.java.getResourceAsStream("/tanseki-version.properties")
                ?: error("tanseki-version.properties is missing from the runtime classpath")
        val packaged =
            resource.use { stream ->
                Properties().apply { load(stream) }.getProperty("version")
            }
        assertTrue(!packaged.isNullOrBlank(), "tanseki-version.properties has no version")
        assertNotEquals(TansekiVersion.UNKNOWN, packaged, "the packaged version is still the fallback")
        assertEquals(packaged, TansekiVersion.value, "the system property must not shadow the packaged version here")
    }

    @Test
    fun `the system property wins over the packaged resource`() {
        System.setProperty(TansekiVersion.PROPERTY, "9.9.9-probe")
        assertEquals("9.9.9-probe", TansekiVersion.value)
    }

    @Test
    fun `a blank system property is ignored rather than published`() {
        System.setProperty(TansekiVersion.PROPERTY, "   ")
        assertNotEquals("   ", TansekiVersion.value)
        assertTrue(TansekiVersion.value.isNotBlank())
    }
}
