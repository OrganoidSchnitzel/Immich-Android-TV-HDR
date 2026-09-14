package nl.giejay.mediaslider.hdr

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ColorMathTest {

    @Test
    fun `multiplying by the identity returns the original matrix`() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val matrix = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)

        assertArrayEquals(matrix, ColorMath.multiplyColumnMajor(identity, matrix), 1e-6f)
        assertArrayEquals(matrix, ColorMath.multiplyColumnMajor(matrix, identity), 1e-6f)
    }

    @Test
    fun `a matrix times its inverse is the identity`() {
        // Column-major: columns are (2,0,0), (0,4,0), (0,0,5).
        val scale = floatArrayOf(2f, 0f, 0f, 0f, 4f, 0f, 0f, 0f, 5f)
        val inverse = floatArrayOf(0.5f, 0f, 0f, 0f, 0.25f, 0f, 0f, 0f, 0.2f)

        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            ColorMath.multiplyColumnMajor(scale, inverse),
            1e-6f
        )
    }

    @Test
    fun `applies the right hand matrix first`() {
        // left scales x by 2 (column-major), right swaps x and y.
        val left = floatArrayOf(2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val swapXy = floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        // Swap first, then scale x: input (0,1,0) -> (1,0,0) -> (2,0,0), so column 1 is (2,0,0).
        assertArrayEquals(
            floatArrayOf(0f, 1f, 0f, 2f, 0f, 0f, 0f, 0f, 1f),
            ColorMath.multiplyColumnMajor(left, swapXy),
            1e-6f
        )
    }
}
