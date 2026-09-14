package nl.giejay.android.tv.immich.shared.prefs

import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R

/** How the viewer/screensaver should try to get Ultra HDR photos onto the screen. */
enum class HdrImageMode : EnumWithTitle {
    /** Never. Photos render exactly as they did before HDR support existed. */
    OFF {
        override fun getTitle(): String =
            ImmichApplication.appContext!!.getString(R.string.hdr_images_off)
    },

    /**
     * Pick whatever the display actually supports: the window HDR colour mode on a display that
     * gives app content headroom, otherwise the BT.2020 PQ surface on a TV that only does HDR for
     * video layers. The sensible default.
     */
    AUTO {
        override fun getTitle(): String =
            ImmichApplication.appContext!!.getString(R.string.hdr_images_auto)
    },

    /** Always use the PQ surface, even if the display claims it can do HDR for app content. */
    HDR_SURFACE {
        override fun getTitle(): String =
            ImmichApplication.appContext!!.getString(R.string.hdr_images_surface)
    },

    /**
     * Always ask the window for HDR colour mode. For a device whose headroom reporting is wrong;
     * it can cost Dolby Vision on the next video if the display has to renegotiate.
     */
    WINDOW_HDR {
        override fun getTitle(): String =
            ImmichApplication.appContext!!.getString(R.string.hdr_images_window)
    }
}
