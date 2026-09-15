package nl.giejay.mediaslider.hdr

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HdrSurfaceStatusTest {

    @Before
    fun reset() {
        HdrSurfaceStatus.active = false
        HdrSurfaceStatus.lastFailure = null
        repeat(HdrSurfaceStatus.photosDrawn) { /* counter is sticky by design */ }
    }

    @Test
    fun `a released layer does not read the same as one that never worked`() {
        val before = HdrSurfaceStatus.describe()
        assertTrue(before, before.startsWith("never used") || before.startsWith("yes"))

        HdrSurfaceStatus.recordRender("PRODUCER_PQ")
        assertTrue(HdrSurfaceStatus.describe().startsWith("yes - a PRODUCER_PQ layer is on screen now"))

        // The layer is torn down when the photo leaves the screen; that must still report success.
        HdrSurfaceStatus.active = false
        val after = HdrSurfaceStatus.describe()
        assertTrue(after, after.startsWith("yes -"))
        assertTrue(after, after.contains("PRODUCER_PQ"))
    }

    @Test
    fun `a failure outranks an earlier success`() {
        HdrSurfaceStatus.recordRender("EGL_PQ")
        HdrSurfaceStatus.active = false
        HdrSurfaceStatus.lastFailure = "no 10-bit config"

        assertTrue(HdrSurfaceStatus.describe().startsWith("no - no 10-bit config"))
    }
}
