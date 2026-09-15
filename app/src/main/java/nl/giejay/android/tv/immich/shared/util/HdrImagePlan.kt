package nl.giejay.android.tv.immich.shared.util

import android.content.Context
import nl.giejay.android.tv.immich.shared.prefs.HdrImageMode
import nl.giejay.mediaslider.hdr.EglHdrCapabilities

/**
 * Which of the two HDR photo paths this device should use, if either.
 *
 * They are mutually exclusive and suit opposite hardware:
 *
 *  - **Window HDR colour mode** asks the compositor to render the whole app window with headroom
 *    above SDR white. Phones and tablets do this; it is what Android's Ultra HDR documentation
 *    describes. A TV box normally does not - its HDMI output is either SDR or HDR, never mixed.
 *  - **The BT.2020 PQ surface** hands the photo to the compositor as its own HDR layer, the same
 *    shape of layer a video decoder produces. That is the path that works on a TV box, because
 *    switching the output into HDR for an HDR layer is exactly what it already does for video.
 */
data class HdrImagePlan(
    val mode: HdrImageMode,
    val capabilities: HdrCapabilities,
    val useWindowColorMode: Boolean,
    val useHdrSurface: Boolean,
    val hdrLayerSupported: Boolean
) {
    /** One line for the debug screen explaining what will be attempted and why. */
    fun describe(): String = when {
        useWindowColorMode -> "window HDR colour mode (the display reports headroom for app content)"
        useHdrSurface -> "BT.2020 HDR layer (the display does HDR for video layers only)"
        mode == HdrImageMode.OFF -> "nothing - HDR photos are set to Off"
        capabilities.canUseHdrVideoLayer && !hdrLayerSupported ->
            "nothing - the display would take an HDR layer, but this GPU cannot produce one"
        else -> "nothing - ${capabilities.ultraHdrVerdict()}"
    }

    companion object {
        fun of(context: Context, mode: HdrImageMode): HdrImagePlan =
            of(mode, HdrCapabilities.of(context), EglHdrCapabilities.probe().usable)

        /**
         * [hdrLayerSupported] is whether the GPU can actually produce an HDR layer. It gates the
         * surface path on purpose: making a SurfaceView visible only to find out it cannot work
         * still makes the display renegotiate, which costs the Dolby Vision handshake of whatever
         * plays next.
         */
        fun of(
            mode: HdrImageMode,
            capabilities: HdrCapabilities,
            hdrLayerSupported: Boolean = true
        ): HdrImagePlan {
            val window: Boolean
            val surface: Boolean
            when (mode) {
                HdrImageMode.OFF -> {
                    window = false
                    surface = false
                }
                HdrImageMode.WINDOW_HDR -> {
                    window = capabilities.supportsUltraHdrDecoding
                    surface = false
                }
                HdrImageMode.HDR_SURFACE -> {
                    window = false
                    surface = capabilities.canUseHdrVideoLayer && hdrLayerSupported
                }
                HdrImageMode.AUTO -> {
                    window = capabilities.ultraHdrImagesSupported
                    surface = !window && capabilities.canUseHdrVideoLayer && hdrLayerSupported
                }
            }
            return HdrImagePlan(mode, capabilities, window, surface, hdrLayerSupported)
        }
    }
}
