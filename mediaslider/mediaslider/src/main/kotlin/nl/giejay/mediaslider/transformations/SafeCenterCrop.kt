package nl.giejay.mediaslider.transformations

import android.content.Context
import android.graphics.Bitmap
import android.util.DisplayMetrics
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import timber.log.Timber
import java.security.MessageDigest


class SafeCenterCrop(context: Context,
                     private val maxCutOffHeight: Int,
                     private val maxCutOffWidth: Int) : BitmapTransformation() {
    val width: Int
    val height: Int

    init {
        val metrics: DisplayMetrics = context.resources.displayMetrics
        width = metrics.widthPixels
        height = metrics.heightPixels
    }

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        return if (isWithinCutoff(toTransform, width, height, maxCutOffWidth, maxCutOffHeight)) {
            TransformationUtils.centerCrop(pool, toTransform, outWidth, outHeight)
        } else {
            toTransform
        }
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {}

    companion object {
        /**
         * Decides whether [bitmap] is close enough to the screen aspect to be safely center
         * cropped. Extracted so the HDR-aware transform can mirror the exact same decision.
         */
        fun isWithinCutoff(bitmap: Bitmap, screenWidth: Int, screenHeight: Int, maxCutOffWidth: Int, maxCutOffHeight: Int): Boolean {
            val percentageDiffWidth: Int = Math.round(((bitmap.width % screenWidth).toFloat() / screenWidth) * 100)
            val percentageDiffHeight: Int = Math.round(((bitmap.height % screenHeight).toFloat() / screenHeight) * 100)
            val within = percentageDiffWidth <= maxCutOffWidth && percentageDiffHeight <= maxCutOffHeight
            if (within) {
                Timber.i("Safe cropping because width differs with ${percentageDiffWidth}% vs screen size. Max cut off: $maxCutOffWidth and height differs with ${percentageDiffHeight}% vs screen size. Max cut off: $maxCutOffHeight")
            } else if (percentageDiffWidth > maxCutOffWidth) {
                Timber.i("Not safe cropping because width differs with ${percentageDiffWidth}% vs screen size. Max cut off: $maxCutOffWidth")
            } else {
                Timber.i("Not safe cropping because height differs with ${percentageDiffHeight}% vs screen size. Max cut off: $maxCutOffHeight")
            }
            return within
        }
    }
}