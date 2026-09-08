package nl.giejay.mediaslider.transformations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import android.util.DisplayMetrics
import androidx.annotation.RequiresApi
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import timber.log.Timber
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Runs the slideshow's configured Glide bitmap transform, but keeps the Ultra HDR gain map
 * attached to the result on Android 14+ (API 34) so the image can still be rendered in HDR.
 *
 * Glide decodes the gain map (see [Bitmap.hasGainmap]), but every transform that redraws the
 * bitmap through a [android.graphics.Canvas] (center-crop / center-inside scaling) produces a
 * fresh bitmap WITHOUT the gain map. Here the source gain map is captured before the transform
 * and re-attached to the result:
 *  - uniform scaling (center-inside, or a safe-crop that is left untouched): the gain map maps
 *    across the whole image regardless of resolution, so it is re-attached unchanged.
 *  - center-crop: the gain map is cropped to the same centered region the base image was cropped
 *    to, so the HDR boost stays aligned with the visible pixels.
 *
 * For SDR images (no gain map) this behaves exactly like the wrapped transform.
 */
class HdrGainmapTransformation(
    context: Context,
    private val config: MediaSliderConfiguration
) : BitmapTransformation() {
    private val transformation: GlideTransformations = config.glideTransformation

    // Screen size, only needed to mirror SafeCenterCrop's crop/no-crop decision.
    private val screenWidth: Int
    private val screenHeight: Int

    private val id = "$ID_PREFIX$transformation"

    init {
        val metrics: DisplayMetrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val gainmap: Gainmap? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && toTransform.hasGainmap()) {
                toTransform.gainmap
            } else {
                null
            }
        return when (transformation) {
            GlideTransformations.CENTER_CROP ->
                reattach(TransformationUtils.centerCrop(pool, toTransform, outWidth, outHeight), toTransform, gainmap, cropped = true)

            GlideTransformations.CENTER_INSIDE ->
                reattach(TransformationUtils.centerInside(pool, toTransform, outWidth, outHeight), toTransform, gainmap, cropped = false)

            GlideTransformations.SAFE_CENTER_CROP ->
                if (SafeCenterCrop.isWithinCutoff(toTransform, screenWidth, screenHeight, config.maxCutOffWidth, config.maxCutOffHeight)) {
                    reattach(TransformationUtils.centerCrop(pool, toTransform, outWidth, outHeight), toTransform, gainmap, cropped = true)
                } else {
                    // returned unchanged → the source (and its gain map) are kept as-is
                    toTransform
                }
        }
    }

    private fun reattach(result: Bitmap, source: Bitmap, gainmap: Gainmap?, cropped: Boolean): Bitmap {
        // SDK guard is repeated (not just implied by gainmap != null) so lint can see the
        // API 34 gain map calls below are safe.
        if (gainmap == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return result
        }
        // Nothing to do when the transform was a no-op (result === source keeps its gain map) or
        // when the platform already copied the gain map across.
        if (result === source || result.hasGainmap()) {
            return result
        }
        try {
            result.gainmap = if (cropped) {
                cropGainmapLike(gainmap, source.width, source.height, result.width, result.height)
            } else {
                gainmap
            }
        } catch (e: Exception) {
            // Never let HDR handling break plain rendering; fall back to the SDR result.
            Timber.w(e, "Could not re-attach gain map after transform, showing image without HDR")
        }
        return result
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(id.toByteArray(Charsets.UTF_8))
    }

    override fun equals(other: Any?): Boolean {
        return other is HdrGainmapTransformation && other.transformation == transformation
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    companion object {
        private const val ID_PREFIX = "nl.giejay.mediaslider.transformations.HdrGainmapTransformation."

        /**
         * The centered region of the source that survives a center-crop into [dstW] x [dstH],
         * expressed as fractions (0..1) of the source. Pure and side-effect free so it can be
         * unit tested without a real [Bitmap] or [Gainmap].
         */
        fun centerCropVisibleFraction(srcW: Int, srcH: Int, dstW: Int, dstH: Int): VisibleFraction {
            if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
                return VisibleFraction(0f, 0f, 1f, 1f)
            }
            val scale = max(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
            val fracW = (dstW / (scale * srcW)).coerceIn(0f, 1f)
            val fracH = (dstH / (scale * srcH)).coerceIn(0f, 1f)
            val left = ((1f - fracW) / 2f).coerceIn(0f, 1f)
            val top = ((1f - fracH) / 2f).coerceIn(0f, 1f)
            return VisibleFraction(left, top, fracW, fracH)
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun cropGainmapLike(gainmap: Gainmap, srcW: Int, srcH: Int, dstW: Int, dstH: Int): Gainmap {
            val frac = centerCropVisibleFraction(srcW, srcH, dstW, dstH)
            val contents = gainmap.gainmapContents
            val gw = contents.width
            val gh = contents.height
            val cx = (frac.left * gw).roundToInt().coerceIn(0, (gw - 1).coerceAtLeast(0))
            val cy = (frac.top * gh).roundToInt().coerceIn(0, (gh - 1).coerceAtLeast(0))
            val cw = (frac.width * gw).roundToInt().coerceAtLeast(1).coerceAtMost(gw - cx)
            val ch = (frac.height * gh).roundToInt().coerceAtLeast(1).coerceAtMost(gh - cy)
            if (cx == 0 && cy == 0 && cw == gw && ch == gh) {
                // No actual crop (e.g. aspect ratios already match): keep the original gain map.
                return gainmap
            }
            val croppedContents = Bitmap.createBitmap(contents, cx, cy, cw, ch)
            return copyGainmapMetadata(gainmap, croppedContents)
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun copyGainmapMetadata(src: Gainmap, newContents: Bitmap): Gainmap {
            val gm = Gainmap(newContents)
            val gamma = src.gamma
            gm.setGamma(gamma[0], gamma[1], gamma[2])
            val ratioMin = src.ratioMin
            gm.setRatioMin(ratioMin[0], ratioMin[1], ratioMin[2])
            val ratioMax = src.ratioMax
            gm.setRatioMax(ratioMax[0], ratioMax[1], ratioMax[2])
            val epsilonSdr = src.epsilonSdr
            gm.setEpsilonSdr(epsilonSdr[0], epsilonSdr[1], epsilonSdr[2])
            val epsilonHdr = src.epsilonHdr
            gm.setEpsilonHdr(epsilonHdr[0], epsilonHdr[1], epsilonHdr[2])
            gm.displayRatioForFullHdr = src.displayRatioForFullHdr
            gm.minDisplayRatioForHdrTransition = src.minDisplayRatioForHdrTransition
            return gm
        }
    }

    /** Centered visible region of a center-crop, as fractions (0..1) of the source. */
    data class VisibleFraction(val left: Float, val top: Float, val width: Float, val height: Float)
}
