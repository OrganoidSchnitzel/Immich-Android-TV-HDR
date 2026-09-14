package nl.giejay.mediaslider.util

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import timber.log.Timber

/**
 * Process-wide record of what the slider last observed about HDR, so the user can read it back
 * from Settings -> Debug without a logcat connection.
 *
 * HDR on a TV box fails silently in a lot of places (the server hands out a tone mapped transcode,
 * the video ends up on a TextureView which cannot carry HDR, the display refuses HDR for app
 * content, the JPEG never had a gain map). Every one of those looks identical on screen: a normal
 * looking SDR picture. Recording the facts at the point where they are known is the only way to
 * tell them apart afterwards.
 *
 * Writes come from the main thread, reads from the settings dialog; fields are volatile so a read
 * never sees a stale value.
 */
object HdrDiagnostics {

    @Volatile
    var lastImageUrl: String? = null
        private set

    @Volatile
    var lastImageBitmap: String? = null
        private set

    @Volatile
    var lastImageGainmap: String? = null
        private set

    /** What the host decided to do with the window color mode for the last HDR-capable image. */
    @Volatile
    var lastColorModeDecision: String? = null

    @Volatile
    var lastVideoUrl: String? = null
        private set

    @Volatile
    var lastVideoSurface: String? = null

    @Volatile
    var lastVideoFormat: String? = null

    @Volatile
    var lastVideoDecoder: String? = null

    fun recordImage(url: String?, resource: Drawable) {
        lastImageUrl = url
        val bitmap = (resource as? BitmapDrawable)?.bitmap
        if (bitmap == null) {
            lastImageBitmap = "${resource.javaClass.simpleName} (not a bitmap)"
            lastImageGainmap = "n/a"
            return
        }
        lastImageBitmap = "${bitmap.width}x${bitmap.height} ${bitmap.config?.name ?: "unknown config"}"
        lastImageGainmap = describeGainmap(bitmap)
    }

    fun recordVideo(url: String?) {
        lastVideoUrl = url
        // The format/decoder of the previous video must not be read as belonging to this one.
        lastVideoFormat = null
        lastVideoDecoder = null
    }

    private fun describeGainmap(bitmap: Bitmap): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return "not supported below Android 14"
        }
        if (!bitmap.hasGainmap()) {
            return "no (plain SDR image)"
        }
        return try {
            val gainmap = bitmap.gainmap!!
            val contents = gainmap.gainmapContents
            val ratioMax = gainmap.ratioMax
            "yes, ${contents.width}x${contents.height}, " +
                "ratioMax ${"%.2f".format(ratioMax[0])}, " +
                "full HDR at ${"%.2f".format(gainmap.displayRatioForFullHdr)}x SDR"
        } catch (e: Exception) {
            Timber.w(e, "Could not describe gain map")
            "yes (details unavailable)"
        }
    }

    /** Label/value pairs for the debug screen, in display order. */
    fun lines(): List<Pair<String, String>> = listOf(
        "Last image" to (lastImageUrl ?: "none yet"),
        "Decoded as" to (lastImageBitmap ?: "-"),
        "Ultra HDR gain map" to (lastImageGainmap ?: "-"),
        "Window color mode" to (lastColorModeDecision ?: "-"),
        "Last video" to (lastVideoUrl ?: "none yet"),
        "Video surface" to (lastVideoSurface ?: "-"),
        "Video format" to (lastVideoFormat ?: "-"),
        "Video decoder" to (lastVideoDecoder ?: "-")
    )
}
