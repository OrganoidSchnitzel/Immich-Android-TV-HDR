package nl.giejay.mediaslider.hdr

import nl.giejay.mediaslider.hdr.EglHdrCapabilities.HdrTagging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EglHdrCapabilitiesTest {

    private fun capabilities(
        tenBitConfig: Boolean = true,
        pq: Boolean = false,
        hlg: Boolean = false,
        dataSpace: Boolean = false
    ) = EglHdrCapabilities(tenBitConfig, pq, hlg, dataSpace, extensions = "")

    @Test
    fun `the colour space extension is preferred when the driver has it`() {
        assertEquals(HdrTagging.EGL_PQ, capabilities(pq = true, dataSpace = true).preferredTagging())
    }

    @Test
    fun `without the extension the layer is tagged instead`() {
        // The Homatics/SEI box: no EGL colour space extensions at all, but Android 13+ layer
        // tagging is still available.
        val driver = capabilities(dataSpace = true)

        assertTrue(driver.usable)
        assertEquals(HdrTagging.SURFACE_CONTROL_PQ, driver.preferredTagging())
    }

    @Test
    fun `layer tagging beats the hlg colour space because hlg clips highlights`() {
        assertEquals(
            HdrTagging.SURFACE_CONTROL_PQ,
            capabilities(hlg = true, dataSpace = true).preferredTagging()
        )
    }

    @Test
    fun `hlg is still used when it is the only thing on offer`() {
        assertEquals(HdrTagging.EGL_HLG, capabilities(hlg = true).preferredTagging())
    }

    @Test
    fun `no ten bit config means no hdr layer at all`() {
        val driver = capabilities(tenBitConfig = false, pq = true, dataSpace = true)

        assertFalse(driver.usable)
        assertEquals(HdrTagging.NONE, driver.preferredTagging())
        assertTrue(driver.describe().contains("10-bit"))
    }

    @Test
    fun `colour space extensions are picked out of the driver's extension string`() {
        val driver = EglHdrCapabilities(
            tenBitConfig = true, pqColorSpace = false, hlgColorSpace = false, dataSpaceTagging = true,
            extensions = "EGL_KHR_image_base EGL_EXT_gl_colorspace_display_p3 EGL_ANDROID_recordable"
        )

        assertEquals("EGL_EXT_gl_colorspace_display_p3", driver.colorSpaceExtensions())
        assertEquals("none", capabilities(dataSpace = true).colorSpaceExtensions())
    }
}
