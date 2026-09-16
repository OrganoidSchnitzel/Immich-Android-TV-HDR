package nl.giejay.mediaslider.hdr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Converts a 10-bit RGB frame into YCbCr 4:2:0 P010, the pixel format an HDR video decoder
 * produces.
 *
 * The detour through YUV is not for its own sake. An [android.media.ImageWriter] is the only way to
 * give a surface's buffers a data space of our choosing, and it cannot hand out 10-bit RGB images
 * at all: the platform's plane-count table has no entry for RGBA_1010102, so asking for one throws
 * before a frame exists. P010 is the one 10-bit format it does know, and it is also what a video
 * decoder emits - which is the whole point, since a video layer is the one thing this display
 * pipeline reliably switches its output into HDR for.
 *
 * Input is the GL read-back: packed RGBA_1010102 (`GL_UNSIGNED_INT_2_10_10_10_REV`, so red sits in
 * the low bits), already PQ-encoded and in BT.2020 primaries, with rows running bottom-up the way
 * GL numbers them. Output is limited-range BT.2020 non-constant luminance, 10 bits held in the
 * most significant bits of each 16-bit sample, rows top-down.
 */
object P010Converter {

    /** One destination plane of an [android.media.Image]. */
    data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

    fun convert(source: ByteBuffer, width: Int, height: Int, luma: Plane, chromaBlue: Plane, chromaRed: Plane) {
        require(width > 0 && height > 0) { "Frame must not be empty" }
        require(source.capacity() >= width * height * BYTES_PER_PIXEL) {
            "Read-back holds ${source.capacity()} bytes, need ${width * height * BYTES_PER_PIXEL}"
        }
        val pixels = source.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer()
        luma.buffer.order(ByteOrder.LITTLE_ENDIAN)
        chromaBlue.buffer.order(ByteOrder.LITTLE_ENDIAN)
        chromaRed.buffer.order(ByteOrder.LITTLE_ENDIAN)

        writeLuma(pixels, width, height, luma)
        writeChroma(pixels, width, height, chromaBlue, chromaRed)
    }

    private fun writeLuma(pixels: java.nio.IntBuffer, width: Int, height: Int, luma: Plane) {
        for (row in 0 until height) {
            val sourceRow = (height - 1 - row) * width
            val destinationRow = row * luma.rowStride
            for (column in 0 until width) {
                val packed = pixels.get(sourceRow + column)
                val y = lumaOf(red(packed), green(packed), blue(packed))
                luma.buffer.putShort(destinationRow + column * luma.pixelStride, toSample(y))
            }
        }
    }

    /**
     * Chroma is written at half resolution, each sample averaged over the 2x2 block of source
     * pixels it covers. Averaging the RGB of the block and converting once is equivalent to
     * converting each pixel and averaging, and costs a quarter as much.
     */
    private fun writeChroma(
        pixels: java.nio.IntBuffer,
        width: Int,
        height: Int,
        chromaBlue: Plane,
        chromaRed: Plane
    ) {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        for (row in 0 until chromaHeight) {
            for (column in 0 until chromaWidth) {
                var red = 0
                var green = 0
                var blue = 0
                var counted = 0
                for (dy in 0..1) {
                    val y = row * 2 + dy
                    if (y >= height) continue
                    val sourceRow = (height - 1 - y) * width
                    for (dx in 0..1) {
                        val x = column * 2 + dx
                        if (x >= width) continue
                        val packed = pixels.get(sourceRow + x)
                        red += red(packed)
                        green += green(packed)
                        blue += blue(packed)
                        counted++
                    }
                }
                val averageRed = red.toFloat() / counted
                val averageGreen = green.toFloat() / counted
                val averageBlue = blue.toFloat() / counted
                val y = lumaOf(averageRed, averageGreen, averageBlue)
                chromaBlue.buffer.putShort(
                    row * chromaBlue.rowStride + column * chromaBlue.pixelStride,
                    toChromaSample((averageBlue / MAX_CODE - y) / CB_SCALE)
                )
                chromaRed.buffer.putShort(
                    row * chromaRed.rowStride + column * chromaRed.pixelStride,
                    toChromaSample((averageRed / MAX_CODE - y) / CR_SCALE)
                )
            }
        }
    }

    /** BT.2020 non-constant luminance, on the PQ-encoded values, normalised to 0..1. */
    private fun lumaOf(red: Float, green: Float, blue: Float): Float =
        (KR * red + KG * green + KB * blue) / MAX_CODE

    private fun lumaOf(red: Int, green: Int, blue: Int): Float =
        lumaOf(red.toFloat(), green.toFloat(), blue.toFloat())

    /** Limited-range luma: 64..940 in 10 bits, held in the top 10 bits of a 16-bit sample. */
    private fun toSample(luma: Float): Short {
        val code = (luma * LUMA_RANGE).roundToInt() + LUMA_OFFSET
        return (code.coerceIn(LUMA_MIN, LUMA_MAX) shl SAMPLE_SHIFT).toShort()
    }

    /** Limited-range chroma: 64..960, zero at 512. */
    private fun toChromaSample(chroma: Float): Short {
        val code = (chroma * CHROMA_RANGE).roundToInt() + CHROMA_OFFSET
        return (code.coerceIn(CHROMA_MIN, CHROMA_MAX) shl SAMPLE_SHIFT).toShort()
    }

    private fun red(packed: Int) = packed and 0x3FF
    private fun green(packed: Int) = (packed ushr 10) and 0x3FF
    private fun blue(packed: Int) = (packed ushr 20) and 0x3FF

    private const val BYTES_PER_PIXEL = 4
    private const val MAX_CODE = 1023f

    // BT.2020 luma coefficients.
    private const val KR = 0.2627f
    private const val KG = 0.6780f
    private const val KB = 0.0593f
    private const val CB_SCALE = 2f * (1f - KB)
    private const val CR_SCALE = 2f * (1f - KR)

    private const val LUMA_RANGE = 876f
    private const val LUMA_OFFSET = 64
    private const val LUMA_MIN = 64
    private const val LUMA_MAX = 940
    private const val CHROMA_RANGE = 896f
    private const val CHROMA_OFFSET = 512
    private const val CHROMA_MIN = 64
    private const val CHROMA_MAX = 960

    /** 10 significant bits sit in the most significant bits of each 16-bit P010 sample. */
    private const val SAMPLE_SHIFT = 6
}
