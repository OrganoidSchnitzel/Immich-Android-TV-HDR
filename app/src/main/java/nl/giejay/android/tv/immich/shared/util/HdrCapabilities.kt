package nl.giejay.android.tv.immich.shared.util

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager
import timber.log.Timber

/**
 * What the display attached to this device can actually do with HDR.
 *
 * Two very different things are called "HDR support" on a TV box and they are independent:
 *
 *  - HDR *video*: the video decoder hands an HDR frame to the display pipeline, which switches the
 *    HDMI output into HDR10 / HLG / Dolby Vision. Every HDR capable TV box does this, and it needs
 *    no cooperation from the app beyond keeping the video on a [android.view.SurfaceView].
 *  - HDR for *app content* (Ultra HDR photos): the compositor renders the app's window with
 *    headroom above SDR white, so a bitmap's gain map has somewhere to go. This is what
 *    `Window.setColorMode(COLOR_MODE_HDR)` asks for, it exists only on Android 14+, and a lot of TV
 *    devices do not implement it — their HDMI output is either fully SDR or fully HDR, never mixed.
 *
 * [ultraHdrImagesSupported] reports the second one. Asking for HDR color mode on a device that does
 * not support it is not free: the window change can knock the display out of the HDR/Dolby Vision
 * mode a video just negotiated, so the app only asks when the platform says it will be honoured.
 */
data class HdrCapabilities(
    val sdkInt: Int,
    val device: String,
    val displayIsHdr: Boolean,
    val supportedHdrTypes: List<String>,
    val hdrSdrRatioAvailable: Boolean,
    val hdrSdrRatio: Float?
) {
    /** True when this device can render Ultra HDR (gain map) images with real HDR headroom. */
    val ultraHdrImagesSupported: Boolean
        get() = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hdrSdrRatioAvailable

    val supportsDolbyVisionVideo: Boolean
        get() = supportedHdrTypes.contains(DOLBY_VISION)

    /** Why [ultraHdrImagesSupported] is false, for the debug screen. */
    fun ultraHdrVerdict(): String = when {
        sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            "no - needs Android 14, this device runs Android API $sdkInt"
        !displayIsHdr ->
            "no - the display does not report any HDR support"
        !hdrSdrRatioAvailable ->
            "no - the display does HDR for video only, not for app content"
        else -> "yes"
    }

    fun lines(): List<Pair<String, String>> = listOf(
        "Device" to device,
        "Android API" to sdkInt.toString(),
        "Display reports HDR" to if (displayIsHdr) "yes" else "no",
        "HDR types" to supportedHdrTypes.joinToString(", ").ifBlank { "none" },
        "HDR headroom for app content" to
            if (hdrSdrRatioAvailable) "yes (current ratio ${hdrSdrRatio ?: 1f})" else "no",
        "Ultra HDR photos possible" to ultraHdrVerdict()
    )

    companion object {
        const val DOLBY_VISION = "Dolby Vision"

        fun of(context: Context): HdrCapabilities {
            val display = displayOf(context)
            var ratioAvailable = false
            var ratio: Float? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && display != null &&
                display.isHdrSdrRatioAvailable
            ) {
                ratioAvailable = true
                ratio = display.hdrSdrRatio
            }
            return HdrCapabilities(
                sdkInt = Build.VERSION.SDK_INT,
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                displayIsHdr = display?.isHdr == true,
                supportedHdrTypes = display?.let { supportedHdrTypeNames(it) } ?: emptyList(),
                hdrSdrRatioAvailable = ratioAvailable,
                hdrSdrRatio = ratio
            )
        }

        private fun displayOf(context: Context): Display? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
            }
        } catch (e: Exception) {
            // Contexts without a display (e.g. the application context on some devices) throw.
            Timber.w(e, "Could not resolve the display for HDR capability detection")
            null
        }

        @Suppress("DEPRECATION")
        private fun supportedHdrTypeNames(display: Display): List<String> =
            (display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)).map { type ->
                when (type) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> DOLBY_VISION
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                    else -> "type $type"
                }
            }
    }
}
