package nl.giejay.mediaslider.hdr

import android.graphics.ColorSpace
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Colour conversions the HDR shader needs as uniforms.
 *
 * [ColorSpace.Rgb.getTransform] maps RGB to XYZ under the colour space's *own* white point, so two
 * of them only compose into a single matrix once they share one. sRGB and Display P3 already share
 * BT.2020's D65, but anything else (a D50 space, say) has to be chromatically adapted first.
 */
@RequiresApi(Build.VERSION_CODES.O)
object ColorMath {

    /** Parametric transfer parameters in shader order: g, a, b, c, d, e, f. */
    fun transferParameters(colorSpace: ColorSpace.Rgb?): FloatArray {
        val parameters = colorSpace?.transferParameters ?: return SRGB_TRANSFER.copyOf()
        return floatArrayOf(
            parameters.g.toFloat(),
            parameters.a.toFloat(),
            parameters.b.toFloat(),
            parameters.c.toFloat(),
            parameters.d.toFloat(),
            parameters.e.toFloat(),
            parameters.f.toFloat()
        )
    }

    /** Column-major 3x3 converting linear [source] RGB into linear BT.2020 RGB. */
    fun toBt2020Matrix(source: ColorSpace.Rgb): FloatArray {
        val bt2020 = ColorSpace.get(ColorSpace.Named.BT2020) as ColorSpace.Rgb
        // Bradford-adapt the source onto BT.2020's white point; a no-op for D65 spaces.
        val adapted = ColorSpace.adapt(source, bt2020.whitePoint) as ColorSpace.Rgb
        return multiplyColumnMajor(bt2020.inverseTransform, adapted.transform)
    }

    /**
     * Product of two column-major 3x3 matrices, i.e. the matrix that applies [right] first and
     * then [left]. Column-major means element (row, col) lives at index `col * 3 + row`, which is
     * also what `glUniformMatrix3fv` expects.
     */
    fun multiplyColumnMajor(left: FloatArray, right: FloatArray): FloatArray {
        require(left.size == 9 && right.size == 9) { "Expected two 3x3 matrices" }
        val result = FloatArray(9)
        for (col in 0..2) {
            for (row in 0..2) {
                var sum = 0f
                for (k in 0..2) {
                    sum += left[k * 3 + row] * right[col * 3 + k]
                }
                result[col * 3 + row] = sum
            }
        }
        return result
    }

    /** g, a, b, c, d, e, f of the sRGB curve, used when a colour space has no parametric form. */
    private val SRGB_TRANSFER = floatArrayOf(2.4f, 1f / 1.055f, 0.055f / 1.055f, 1f / 12.92f, 0.04045f, 0f, 0f)
}
