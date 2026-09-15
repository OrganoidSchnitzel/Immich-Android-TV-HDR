package nl.giejay.mediaslider.hdr

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.os.Build
import timber.log.Timber

/**
 * What this GPU driver offers for putting an HDR layer on screen, probed once without touching any
 * window.
 *
 * Worth knowing before a [HdrImageSurfaceView] is ever made visible: creating and destroying a
 * SurfaceView makes the display pipeline renegotiate, and on a TV box that costs the Dolby Vision
 * handshake of whatever plays next. A device that cannot do the HDR layer should never get as far
 * as creating the surface.
 *
 * There are two independent ways to tell the compositor that a layer holds HDR:
 *
 *  - Create the EGL surface in a BT.2020 colour space, which tags every buffer the driver produces.
 *    Needs a driver extension that plenty of TV boxes do not ship.
 *  - Render into an ordinary 10-bit surface and tag the layer itself with
 *    `SurfaceControl.Transaction.setDataSpace` (Android 13+), which does not involve the driver.
 */
data class EglHdrCapabilities(
    val tenBitConfig: Boolean,
    val pqColorSpace: Boolean,
    val hlgColorSpace: Boolean,
    val producerDataSpace: Boolean,
    val extensions: String
) {
    /** True when at least one way of producing an HDR layer is available. */
    val usable: Boolean
        get() = tenBitConfig && (pqColorSpace || hlgColorSpace || producerDataSpace)

    /** How the surface should announce itself as HDR, best first. */
    fun preferredTagging(): HdrTagging = when {
        !tenBitConfig -> HdrTagging.NONE
        pqColorSpace -> HdrTagging.EGL_PQ
        // Producing the buffers ourselves beats the HLG colour space: HLG clips the gain map's
        // brightest highlights, PQ carries all of them.
        producerDataSpace -> HdrTagging.PRODUCER_PQ
        hlgColorSpace -> HdrTagging.EGL_HLG
        else -> HdrTagging.NONE
    }

    fun describe(): String = when {
        !tenBitConfig -> "no - this GPU has no 10-bit (RGBA_1010102) EGL config"
        else -> "yes, via ${preferredTagging()}"
    }

    /**
     * The driver extensions that have any bearing on HDR. Deliberately wider than colour spaces:
     * HDR static metadata extensions decide whether some TV pipelines switch their output at all,
     * and a filter narrow enough to hide them hides the answer.
     */
    fun hdrExtensions(): String {
        val relevant = extensions.split(' ').filter { extension ->
            HDR_EXTENSION_HINTS.any { extension.contains(it, ignoreCase = true) }
        }
        return if (relevant.isEmpty()) "none" else relevant.joinToString(", ")
    }

    enum class HdrTagging {
        NONE,

        /** EGL tags every buffer, because the driver has the BT.2020 PQ colour space extension. */
        EGL_PQ,

        /** As above for BT.2020 HLG. */
        EGL_HLG,

        /**
         * No driver extension: render offscreen and hand the frames to the surface through an
         * [android.media.ImageWriter] whose data space says BT.2020 PQ. The buffer's own data
         * space is what the compositor reads, the same way a video decoder's output is read, so
         * unlike a layer-level tag nothing overwrites it when the frame is queued.
         */
        PRODUCER_PQ
    }

    companion object {
        private const val EXT_PQ = "EGL_EXT_gl_colorspace_bt2020_pq"
        private const val EXT_HLG = "EGL_EXT_gl_colorspace_bt2020_hlg"
        private val HDR_EXTENSION_HINTS =
            listOf("colorspace", "hdr", "smpte", "cta861", "2086", "pq", "hlg", "bt2020")

        @Volatile
        private var cached: EglHdrCapabilities? = null

        fun probe(): EglHdrCapabilities = cached ?: synchronized(this) {
            cached ?: run(::doProbe).also { cached = it }
        }

        private fun run(probe: () -> EglHdrCapabilities): EglHdrCapabilities = try {
            probe()
        } catch (e: Exception) {
            Timber.w(e, "Could not probe the GPU for HDR layer support")
            EglHdrCapabilities(false, false, false, false, "")
        }

        private fun doProbe(): EglHdrCapabilities {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                return EglHdrCapabilities(false, false, false, false, "")
            }
            val version = IntArray(2)
            // Reference counted and already initialised by the view system; deliberately never
            // terminated here, since that would pull the display out from under everything else.
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return EglHdrCapabilities(false, false, false, false, "")
            }
            val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
            val capabilities = EglHdrCapabilities(
                tenBitConfig = hasTenBitConfig(display),
                pqColorSpace = extensions.contains(EXT_PQ),
                hlgColorSpace = extensions.contains(EXT_HLG),
                // ImageWriter.Builder gained setDataSpace in Android 14.
                producerDataSpace = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                extensions = extensions
            )
            Timber.i("HDR layer support: %s (HDR extensions: %s)",
                capabilities.describe(), capabilities.hdrExtensions())
            Timber.i("Full EGL extension list: %s", extensions)
            return capabilities
        }

        private fun hasTenBitConfig(display: EGLDisplay): Boolean {
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 10,
                EGL14.EGL_GREEN_SIZE, 10,
                EGL14.EGL_BLUE_SIZE, 10,
                EGL14.EGL_ALPHA_SIZE, 2,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            return EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0
        }
    }
}
