package nl.giejay.mediaslider.transformations

import org.junit.Assert.assertEquals
import org.junit.Test

class HdrGainmapTransformationTest {

    private val tolerance = 0.0001f

    @Test
    fun `matching aspect ratio keeps the whole image`() {
        val frac = HdrGainmapTransformation.centerCropVisibleFraction(4000, 2000, 2000, 1000)
        assertEquals(0f, frac.left, tolerance)
        assertEquals(0f, frac.top, tolerance)
        assertEquals(1f, frac.width, tolerance)
        assertEquals(1f, frac.height, tolerance)
    }

    @Test
    fun `wider source than target crops the sides, keeps full height`() {
        // Source 4000x2000 (2.0) cropped into a 1000x1000 (1.0) target.
        // scale = max(1000/4000, 1000/2000) = 0.5, visible width = 1000/0.5 = 2000 of 4000 => 0.5
        val frac = HdrGainmapTransformation.centerCropVisibleFraction(4000, 2000, 1000, 1000)
        assertEquals(0.25f, frac.left, tolerance)
        assertEquals(0.5f, frac.width, tolerance)
        assertEquals(0f, frac.top, tolerance)
        assertEquals(1f, frac.height, tolerance)
    }

    @Test
    fun `taller source than target crops top and bottom, keeps full width`() {
        // Source 1000x2000 (0.5) cropped into a 1000x1000 (1.0) target.
        // scale = max(1000/1000, 1000/2000) = 1.0, visible height = 1000/1.0 = 1000 of 2000 => 0.5
        val frac = HdrGainmapTransformation.centerCropVisibleFraction(1000, 2000, 1000, 1000)
        assertEquals(0f, frac.left, tolerance)
        assertEquals(1f, frac.width, tolerance)
        assertEquals(0.25f, frac.top, tolerance)
        assertEquals(0.5f, frac.height, tolerance)
    }

    @Test
    fun `invalid dimensions fall back to the whole image`() {
        val frac = HdrGainmapTransformation.centerCropVisibleFraction(0, 0, 100, 100)
        assertEquals(0f, frac.left, tolerance)
        assertEquals(0f, frac.top, tolerance)
        assertEquals(1f, frac.width, tolerance)
        assertEquals(1f, frac.height, tolerance)
    }
}
