package nl.giejay.android.tv.immich.shared.util

import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Window
import timber.log.Timber

/**
 * Switches a host [Window] into HDR color mode so Ultra HDR (gain map) images are rendered in HDR
 * instead of tone-mapped to SDR.
 *
 * The window enters HDR mode the first time an Ultra HDR image is shown and stays there until
 * [reset] is called when leaving the viewer/screensaver. Plain SDR images render correctly in an
 * HDR window, so we deliberately do NOT toggle back to SDR between images: switching the display
 * color mode mid-slideshow makes many TVs re-negotiate the output and briefly blank the screen.
 *
 * All calls are no-ops below Android 14 (API 34), where window HDR color mode does not exist.
 */
class HdrColorModeController(private val windowProvider: () -> Window?) {
    private var hdrActive = false

    /** Report whether the image currently being shown is an Ultra HDR image. */
    fun onHdrDetected(isHdr: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        if (isHdr && !hdrActive) {
            windowProvider()?.let { window ->
                window.colorMode = ActivityInfo.COLOR_MODE_HDR
                hdrActive = true
                Timber.i("Switched window to HDR color mode for Ultra HDR image")
            }
        }
    }

    /** Restore the default color mode. Call when leaving the viewer/screensaver. */
    fun reset() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        if (hdrActive) {
            windowProvider()?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            hdrActive = false
            Timber.i("Restored default window color mode")
        }
    }
}
