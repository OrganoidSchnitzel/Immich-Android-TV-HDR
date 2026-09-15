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
        dataSpace: Boolean = false,
        metadata: Boolean = false
    ) = EglHdrCapabilities(tenBitConfig, pq, hlg, dataSpace, metadata, extensions = "")

    @Test
    fun `the colour space extension is preferred when the driver has it`() {
        assertEquals(HdrTagging.EGL_PQ, capabilities(pq = true, dataSpace = true).preferredTagging())
    }

    @Test
    fun `without the extension the frames are produced with a data space instead`() {
        // The Homatics/SEI box: no EGL colour space extensions at all, so the frames have to
        // carry the data space themselves.
        val driver = capabilities(dataSpace = true)

        assertTrue(driver.usable)
        assertEquals(HdrTagging.PRODUCER_PQ, driver.preferredTagging())
    }

    @Test
    fun `producing the frames beats the hlg colour space because hlg clips highlights`() {
        assertEquals(
            HdrTagging.PRODUCER_PQ,
            capabilities(hlg = true, dataSpace = true).preferredTagging()
        )
    }

    @Test
    fun `hlg is still used when it is the only thing on offer`() {
        assertEquals(HdrTagging.EGL_HLG, capabilities(hlg = true).preferredTagging())
    }

    @Test
    fun `a forced mechanism wins over the ranking`() {
        val driver = capabilities(pq = true, dataSpace = true, metadata = true)

        assertEquals(HdrTagging.EGL_PQ, driver.preferredTagging())
        assertEquals(HdrTagging.EGL_METADATA_PQ, driver.preferredTagging(HdrTagging.EGL_METADATA_PQ))
    }

    @Test
    fun `forcing a mechanism the driver lacks falls back to the best it has`() {
        val driver = capabilities(dataSpace = true)

        assertFalse(driver.supports(HdrTagging.EGL_PQ))
        assertEquals(HdrTagging.PRODUCER_PQ, driver.preferredTagging(HdrTagging.EGL_PQ))
    }

    @Test
    fun `hdr static metadata counts as a way to mark the layer`() {
        // The Homatics/SEI box advertises the SMPTE 2086 and CTA-861.3 surface extensions even
        // though it has no BT.2020 colour space.
        val driver = capabilities(metadata = true)

        assertTrue(driver.usable)
        assertTrue(driver.supports(HdrTagging.EGL_METADATA_PQ))
    }

    @Test
    fun `no ten bit config means no hdr layer at all`() {
        val driver = capabilities(tenBitConfig = false, pq = true, dataSpace = true, metadata = true)

        assertFalse(driver.usable)
        assertEquals(HdrTagging.NONE, driver.preferredTagging())
        assertTrue(driver.describe().contains("10-bit"))
    }

    @Test
    fun `hdr related extensions are picked out of the driver's extension string`() {
        val driver = EglHdrCapabilities(
            tenBitConfig = true, pqColorSpace = false, hlgColorSpace = false,
            producerDataSpace = true, hdrMetadata = true,
            extensions = "EGL_KHR_image_base EGL_EXT_gl_colorspace_display_p3 " +
                "EGL_EXT_surface_SMPTE2086_metadata EGL_ANDROID_recordable"
        )

        // Metadata extensions matter as much as colour spaces for whether a TV switches output,
        // so they must not be filtered out of the report.
        assertEquals(
            "EGL_EXT_gl_colorspace_display_p3, EGL_EXT_surface_SMPTE2086_metadata",
            driver.hdrExtensions()
        )
        assertEquals("none", capabilities(dataSpace = true).hdrExtensions())
    }
}
