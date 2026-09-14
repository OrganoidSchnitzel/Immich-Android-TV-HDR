package nl.giejay.android.tv.immich.shared.util

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Window
import nl.giejay.android.tv.immich.shared.prefs.HdrImageMode
import nl.giejay.mediaslider.util.HdrDiagnostics
import timber.log.Timber

/**
 * Switches a host [Window] into HDR color mode so Ultra HDR (gain map) images are rendered with
 * real highlight headroom instead of being tone mapped to SDR.
 *
 * Deliberately conservative, because the cost of getting this wrong lands on video: a window color
 * mode change makes the display renegotiate its output, and a renegotiation around the start of a
 * video is what drops a TV out of Dolby Vision. So the window is only ever touched when
 *
 *  - the device runs Android 14+ (gain maps and window HDR color mode do not exist before that),
 *  - [HdrCapabilities.ultraHdrImagesSupported] says the display really does HDR for app content
 *    (unless the user picked [HdrImageMode.ALWAYS]), and
 *  - an Ultra HDR image is the item actually on screen.
 *
 * Once in HDR mode the window stays there between images - plain SDR images render correctly in an
 * HDR window, and toggling per image would make many TVs blank while they resync. [reset] drops it
 * again when a video takes over or the viewer/screensaver is left.
 */
class HdrColorModeController(
    private val mode: HdrImageMode,
    private val capabilities: HdrCapabilities,
    private val windowProvider: () -> Window?
) {
    constructor(context: Context, mode: HdrImageMode, windowProvider: () -> Window?) :
        this(mode, HdrCapabilities.of(context), windowProvider)

    private var hdrActive = false

    private val allowed: Boolean = when (mode) {
        HdrImageMode.OFF -> false
        HdrImageMode.AUTO -> capabilities.ultraHdrImagesSupported
        HdrImageMode.ALWAYS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    init {
        Timber.i(
            "HDR images: mode=%s, allowed=%s, device verdict='%s', display HDR types=%s",
            mode, allowed, capabilities.ultraHdrVerdict(), capabilities.supportedHdrTypes
        )
        if (!allowed) {
            HdrDiagnostics.lastColorModeDecision = whyNotAllowed()
        }
    }

    /** Report whether the image currently on screen is an Ultra HDR image. */
    fun onHdrDetected(isHdr: Boolean) {
        if (!allowed) {
            return
        }
        if (!isHdr) {
            HdrDiagnostics.lastColorModeDecision =
                if (hdrActive) "HDR (kept from an earlier image; this one is SDR)" else "default (SDR image)"
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
        HdrDiagnostics.lastColorModeDecision = "HDR requested for this image"
        Timber.i("Switched window to HDR color mode for Ultra HDR image")
    }

    /**
     * Restore the default color mode. Call when a video becomes the current item, so the decoder
     * can drive the display's native HDR/Dolby Vision output, and when leaving the slider.
     */
    fun reset() {
        if (!hdrActive) {
            return
        }
        windowProvider()?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
        hdrActive = false
        HdrDiagnostics.lastColorModeDecision = "default (released for video / on exit)"
        Timber.i("Restored default window color mode")
    }

    private fun whyNotAllowed(): String = when (mode) {
        HdrImageMode.OFF -> "never touched - HDR photos are set to Off"
        else -> "never touched - ${capabilities.ultraHdrVerdict()}"
    }
}
