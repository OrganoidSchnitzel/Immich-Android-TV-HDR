package nl.giejay.android.tv.immich.shared.util

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.view.Window
import nl.giejay.mediaslider.util.HdrDiagnostics
import timber.log.Timber

/**
 * Switches a host [Window] into HDR colour mode so Ultra HDR photos are rendered with real
 * highlight headroom instead of being tone mapped to SDR.
 *
 * Only used when [HdrImagePlan.useWindowColorMode] says the display gives app windows that
 * headroom. On a TV box it does not, and asking anyway is not free: the window colour mode change
 * makes the display renegotiate its output, which is what drops a TV out of Dolby Vision when a
 * video follows. Those devices get the PQ surface path instead.
 *
 * Once in HDR mode the window stays there between photos - plain SDR photos render correctly in an
 * HDR window, and toggling per photo would make many TVs blank while they resync. [reset] drops it
 * again when a video takes over or the viewer/screensaver is left.
 */
class HdrColorModeController(
    private val plan: HdrImagePlan,
    private val windowProvider: () -> Window?
) {
    private var hdrActive = false

    init {
        Timber.i("HDR photos: mode=%s, plan=%s", plan.mode, plan.describe())
        HdrDiagnostics.lastColorModeDecision = "not used - ${plan.describe()}"
    }

    /** Report whether the photo currently on screen is an Ultra HDR photo. */
    // setColorMode needs API 26. useWindowColorMode is only ever true when the capabilities report
    // Android 14 or newer (gain maps do not exist before that), which HdrColorModeControllerTest pins down,
    // so every path reaching the call below is already well past 26. Lint cannot see that far.
    @SuppressLint("NewApi")
    fun onHdrDetected(isHdr: Boolean) {
        if (!plan.useWindowColorMode) {
            return
        }
        if (!isHdr) {
            HdrDiagnostics.lastColorModeDecision =
                if (hdrActive) "HDR (kept from an earlier photo; this one is SDR)" else "default (SDR photo)"
            return
        }
        if (hdrActive) {
            HdrDiagnostics.lastColorModeDecision = "HDR (already active)"
            return
        }
        val window = windowProvider()
        if (window == null) {
            HdrDiagnostics.lastColorModeDecision = "not applied - no window"
            return
        }
        window.colorMode = ActivityInfo.COLOR_MODE_HDR
        hdrActive = true
        HdrDiagnostics.lastColorModeDecision = "HDR requested for this photo"
        Timber.i("Switched window to HDR colour mode for an Ultra HDR photo")
    }

    /**
     * Restore the default colour mode. Call when a video becomes the current item, so the decoder
     * can drive the display's native HDR/Dolby Vision output, and when leaving the slider.
     */
    @SuppressLint("NewApi") // Only reachable once onHdrDetected has run on Android 14+; see above.
    fun reset() {
        if (!hdrActive) {
            return
        }
        windowProvider()?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
        hdrActive = false
        HdrDiagnostics.lastColorModeDecision = "default (released for video / on exit)"
        Timber.i("Restored default window colour mode")
    }
}
