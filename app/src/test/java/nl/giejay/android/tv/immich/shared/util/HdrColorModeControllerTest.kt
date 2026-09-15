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
 * The window colour mode is the one part of the HDR photo path that can damage video playback, so
 * the cases that must never touch it are pinned down here, together with the choice between the
 * window path and the PQ surface path.
 */
class HdrColorModeControllerTest {

    /** A phone-like display: HDR output and real headroom for app windows. */
    private fun phoneDisplay() = capabilities(hdrSdrRatioAvailable = true)

    /** A TV box: HDR output for video layers, no headroom for app windows. */
    private fun tvDisplay() = capabilities(hdrSdrRatioAvailable = false)

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

    private fun controller(mode: HdrImageMode, capabilities: HdrCapabilities, window: Window) =
        HdrColorModeController(HdrImagePlan.of(mode, capabilities)) { window }

    @Test
    fun `a tv that only does hdr for video never gets its window colour mode changed`() {
        val window: Window = mock()
        val capabilities = tvDisplay()

        assertFalse(capabilities.ultraHdrImagesSupported)
        assertTrue(capabilities.supportsDolbyVisionVideo)

        controller(HdrImageMode.AUTO, capabilities, window).onHdrDetected(true)

        verify(window, never()).colorMode = ActivityInfo.COLOR_MODE_HDR
    }

    @Test
    fun `auto sends a tv down the hdr layer path instead`() {
        val plan = HdrImagePlan.of(HdrImageMode.AUTO, tvDisplay())

        assertFalse(plan.useWindowColorMode)
        assertTrue(plan.useHdrSurface)
    }

    @Test
    fun `auto prefers the window colour mode when the display has app headroom`() {
        val plan = HdrImagePlan.of(HdrImageMode.AUTO, phoneDisplay())

        assertTrue(plan.useWindowColorMode)
        assertFalse(plan.useHdrSurface)
    }

    @Test
    fun `a display with no hdr at all gets neither path`() {
        val plan = HdrImagePlan.of(
            HdrImageMode.AUTO,
            capabilities(displayIsHdr = false, hdrSdrRatioAvailable = false, types = emptyList())
        )

        assertFalse(plan.useWindowColorMode)
        assertFalse(plan.useHdrSurface)
    }

    @Test
    fun `below android 14 neither path is offered`() {
        val plan = HdrImagePlan.of(HdrImageMode.AUTO, capabilities(sdkInt = 33, hdrSdrRatioAvailable = false))

        assertFalse(plan.useWindowColorMode)
        assertFalse(plan.useHdrSurface)
    }

    @Test
    fun `off never changes the window colour mode even on a capable display`() {
        val window: Window = mock()

        controller(HdrImageMode.OFF, phoneDisplay(), window).onHdrDetected(true)

        verify(window, never()).colorMode = ActivityInfo.COLOR_MODE_HDR
        assertFalse(HdrImagePlan.of(HdrImageMode.OFF, phoneDisplay()).useHdrSurface)
    }

    @Test
    fun `an ultra hdr photo on a capable display switches the window into hdr once`() {
        val window: Window = mock()
        val controller = controller(HdrImageMode.AUTO, phoneDisplay(), window)

        controller.onHdrDetected(true)
        controller.onHdrDetected(true)

        verify(window).colorMode = ActivityInfo.COLOR_MODE_HDR
    }

    @Test
    fun `an sdr photo does not switch the window into hdr`() {
        val window: Window = mock()

        controller(HdrImageMode.AUTO, phoneDisplay(), window).onHdrDetected(false)

        verify(window, never()).colorMode = ActivityInfo.COLOR_MODE_HDR
    }

    @Test
    fun `reset hands the display back so a video can negotiate its own hdr output`() {
        val window: Window = mock()
        val controller = controller(HdrImageMode.AUTO, phoneDisplay(), window)

        controller.onHdrDetected(true)
        controller.reset()

        verify(window).colorMode = ActivityInfo.COLOR_MODE_DEFAULT
    }

    @Test
    fun `reset does nothing when hdr was never switched on`() {
        val window: Window = mock()

        controller(HdrImageMode.AUTO, tvDisplay(), window).reset()

        verify(window, never()).colorMode = ActivityInfo.COLOR_MODE_DEFAULT
    }

    @Test
    fun `a gpu that cannot produce an hdr layer never gets a surface`() {
        // The display would take an HDR layer; the driver cannot make one. Creating the surface
        // anyway would cost the next video its Dolby Vision handshake for nothing.
        val plan = HdrImagePlan.of(HdrImageMode.AUTO, tvDisplay(), hdrLayerSupported = false)

        assertFalse(plan.useWindowColorMode)
        assertFalse(plan.useHdrSurface)
        assertTrue(plan.describe().contains("this GPU cannot produce one"))
    }

    @Test
    fun `forcing the layer path still respects what the gpu can do`() {
        assertFalse(
            HdrImagePlan.of(HdrImageMode.HDR_SURFACE, tvDisplay(), hdrLayerSupported = false).useHdrSurface
        )
        assertTrue(
            HdrImagePlan.of(HdrImageMode.HDR_SURFACE, tvDisplay(), hdrLayerSupported = true).useHdrSurface
        )
    }

    @Test
    fun `forcing the window path on a tv is possible but never also enables the layer path`() {
        val plan = HdrImagePlan.of(HdrImageMode.WINDOW_HDR, tvDisplay())

        assertTrue(plan.useWindowColorMode)
        assertFalse(plan.useHdrSurface)
    }

    @Test
    fun `verdict explains why app window headroom is unavailable`() {
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
            tvDisplay().ultraHdrVerdict()
        )
        assertEquals("yes", phoneDisplay().ultraHdrVerdict())
    }
}
