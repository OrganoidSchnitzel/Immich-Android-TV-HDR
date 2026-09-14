package nl.giejay.android.tv.immich.shared.util

import android.content.pm.ActivityInfo
import android.view.Window
import nl.giejay.android.tv.immich.shared.prefs.HdrImageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * The window color mode is the one thing in the HDR photo path that can damage video playback, so
 * the cases that must never touch it are pinned down here.
 */
class HdrColorModeControllerTest {

    private fun capabilities(
        sdkInt: Int = 34,
        displayIsHdr: Boolean = true,
        hdrSdrRatioAvailable: Boolean = true,
        types: List<String> = listOf(HdrCapabilities.DOLBY_VISION, "HDR10")
    ) = HdrCapabilities(
        sdkInt = sdkInt,
        device = "Test Device",
        displayIsHdr = displayIsHdr,
        supportedHdrTypes = types,
        hdrSdrRatioAvailable = hdrSdrRatioAvailable,
        hdrSdrRatio = if (hdrSdrRatioAvailable) 1f else null
    )

    @Test
    fun `a tv that only does hdr for video never gets its window colour mode changed`() {
        val window: Window = mock()
        val capabilities = capabilities(hdrSdrRatioAvailable = false)

        assertFalse(capabilities.ultraHdrImagesSupported)
        assertTrue(capabilities.supportsDolbyVisionVideo)

        HdrColorModeController(HdrImageMode.AUTO, capabilities) { window }.onHdrDetected(true)

        verify(window, never()).colorMode = ActivityInfo.COLOR_MODE_HDR
    }

    @Test
    fun `off never changes the window colour mode even on a capable display`() {
        val window: Window = mock()

        HdrColorModeController(HdrImageMode.OFF, capabilities()) { window }.onHdrDetected(true)

        verify(window, never()).colorMode = ActivityInfo.COLOR_MODE_HDR
    }

    @Test
    fun `an ultra hdr image on a capable display switches the window into hdr once`() {
        val window: Window = mock()
        val controller = HdrColorModeController(HdrImageMode.AUTO, capabilities()) { window }

        controller.onHdrDetected(true)
        controller.onHdrDetected(true)

        verify(window).colorMode = ActivityInfo.COLOR_MODE_HDR
    }

    @Test
    fun `an sdr image does not switch the window into hdr`() {
        val window: Window = mock()

        HdrColorModeController(HdrImageMode.AUTO, capabilities()) { window }.onHdrDetected(false)

        verify(window, never()).colorMode = ActivityInfo.COLOR_MODE_HDR
    }

    @Test
    fun `reset hands the display back so a video can negotiate its own hdr output`() {
        val window: Window = mock()
        val controller = HdrColorModeController(HdrImageMode.AUTO, capabilities()) { window }

        controller.onHdrDetected(true)
        controller.reset()

        verify(window).colorMode = ActivityInfo.COLOR_MODE_DEFAULT
    }

    @Test
    fun `reset does nothing when hdr was never switched on`() {
        val window: Window = mock()

        HdrColorModeController(HdrImageMode.AUTO, capabilities(hdrSdrRatioAvailable = false)) { window }.reset()

        verify(window, never()).colorMode = ActivityInfo.COLOR_MODE_DEFAULT
    }

    @Test
    fun `verdict explains why ultra hdr is unavailable`() {
        assertEquals(
            "no - needs Android 14, this device runs Android API 30",
            capabilities(sdkInt = 30).ultraHdrVerdict()
        )
        assertEquals(
            "no - the display does not report any HDR support",
            capabilities(displayIsHdr = false, hdrSdrRatioAvailable = false, types = emptyList()).ultraHdrVerdict()
        )
        assertEquals(
            "no - the display does HDR for video only, not for app content",
            capabilities(hdrSdrRatioAvailable = false).ultraHdrVerdict()
        )
        assertEquals("yes", capabilities().ultraHdrVerdict())
    }
}
