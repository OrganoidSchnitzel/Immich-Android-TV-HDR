package nl.giejay.android.tv.immich.shared.prefs

import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R

/** How aggressively the viewer/screensaver may put its window into HDR color mode for photos. */
enum class HdrImageMode : EnumWithTitle {
    /** Never touch the window color mode. Identical to the behaviour before HDR photos existed. */
    OFF {
        override fun getTitle(): String =
            ImmichApplication.appContext!!.getString(R.string.hdr_images_off)
    },

    /** Only when the display says it can render app content with HDR headroom. The safe default. */
    AUTO {
        override fun getTitle(): String =
            ImmichApplication.appContext!!.getString(R.string.hdr_images_auto)
    },

    /**
     * Ask for HDR color mode on any Android 14+ device, even when the display does not advertise
     * headroom for app content. For devices whose reporting is wrong; may cost Dolby Vision on the
     * next video if the display has to renegotiate.
     */
    ALWAYS {
        override fun getTitle(): String =
            ImmichApplication.appContext!!.getString(R.string.hdr_images_always)
    }
}
