package nl.giejay.mediaslider.hdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class P010ConverterTest {

    private val width = 4
    private val height = 4

    /** Cr samples sit two bytes after the Cb samples they interleave with. */
    private val CR_OFFSET = 2

    private fun source(vararg rowsTopDown: IntArray): ByteBuffer {
        // The converter expects GL order, so the rows are handed over bottom-up.
        val buffer = ByteBuffer.allocate(width * height * 4).order(ByteOrder.nativeOrder())
        val ints = buffer.asIntBuffer()
        rowsTopDown.reversed().forEachIndexed { row, values ->
            values.forEachIndexed { column, packed -> ints.put(row * width + column, packed) }
        }
        return buffer
    }

    private fun pack(red: Int, green: Int, blue: Int): Int =
        (red and 0x3FF) or ((green and 0x3FF) shl 10) or ((blue and 0x3FF) shl 20) or (0x3 shl 30)

    private fun filled(packed: Int) = Array(height) { IntArray(width) { packed } }

    private fun planes(): Triple<P010Converter.Plane, P010Converter.Plane, P010Converter.Plane> {
        val luma = P010Converter.Plane(ByteBuffer.allocate(width * height * 2), width * 2, 2)
        val chromaBytes = ByteBuffer.allocate(width * height * 2)
        // Semi-planar, the way an Image hands these over: both planes point into the same memory,
        // and the Cr plane's own index 0 is already the first Cr sample, two bytes along.
        val chromaBlue = P010Converter.Plane(chromaBytes, width * 2, 4)
        val redBytes = chromaBytes.duplicate().apply { position(CR_OFFSET) }.slice()
        val chromaRed = P010Converter.Plane(redBytes, width * 2, 4)
        return Triple(luma, chromaBlue, chromaRed)
    }

    /** P010 keeps its 10 bits in the top of each 16-bit sample. */
    private fun code(plane: P010Converter.Plane, offset: Int): Int =
        (plane.buffer.order(ByteOrder.LITTLE_ENDIAN).getShort(offset).toInt() and 0xFFFF) ushr 6

    @Test
    fun `white maps to the top of the limited luma range with neutral chroma`() {
        val (luma, cb, cr) = planes()
        P010Converter.convert(source(*filled(pack(1023, 1023, 1023))), width, height, luma, cb, cr)

        assertEquals(940, code(luma, 0))
        assertEquals(512, code(cb, 0))
        assertEquals(512, code(cr, 0))
    }

    @Test
    fun `black maps to the bottom of the limited luma range with neutral chroma`() {
        val (luma, cb, cr) = planes()
        P010Converter.convert(source(*filled(pack(0, 0, 0))), width, height, luma, cb, cr)

        assertEquals(64, code(luma, 0))
        assertEquals(512, code(cb, 0))
        assertEquals(512, code(cr, 0))
    }

    @Test
    fun `red lifts Cr and drops Cb, blue does the opposite`() {
        val (redLuma, redCb, redCr) = planes()
        P010Converter.convert(source(*filled(pack(1023, 0, 0))), width, height, redLuma, redCb, redCr)
        assertTrue("Cr should rise for red", code(redCr, 0) > 512)
        assertTrue("Cb should fall for red", code(redCb, 0) < 512)

        val (blueLuma, blueCb, blueCr) = planes()
        P010Converter.convert(source(*filled(pack(0, 0, 1023))), width, height, blueLuma, blueCb, blueCr)
        assertTrue("Cb should rise for blue", code(blueCb, 0) > 512)
        assertTrue("Cr should fall for blue", code(blueCr, 0) < 512)
    }

    @Test
    fun `luma follows the BT 2020 coefficients, so green weighs most`() {
        fun lumaOfPrimary(packed: Int): Int {
            val (luma, cb, cr) = planes()
            P010Converter.convert(source(*filled(packed)), width, height, luma, cb, cr)
            return code(luma, 0)
        }

        val green = lumaOfPrimary(pack(0, 1023, 0))
        val red = lumaOfPrimary(pack(1023, 0, 0))
        val blue = lumaOfPrimary(pack(0, 0, 1023))

        assertTrue("green $green should outweigh red $red", green > red)
        assertTrue("red $red should outweigh blue $blue", red > blue)
        // 0.6780 of the range above the 64 offset.
        val expectedGreen = 64 + (0.6780f * 876f).toInt()
        assertTrue("green $green should be about $expectedGreen", green in expectedGreen - 1..expectedGreen + 1)
    }

    @Test
    fun `rows are flipped, because GL counts them from the bottom`() {
        val top = IntArray(width) { pack(1023, 1023, 1023) }
        val rest = IntArray(width) { pack(0, 0, 0) }
        val (luma, cb, cr) = planes()

        P010Converter.convert(source(top, rest, rest, rest), width, height, luma, cb, cr)

        assertEquals("first output row is the top of the picture", 940, code(luma, 0))
        assertEquals("last output row is the bottom", 64, code(luma, (height - 1) * width * 2))
    }

    @Test
    fun `chroma averages over each two by two block`() {
        val white = IntArray(width) { pack(1023, 1023, 1023) }
        val black = IntArray(width) { pack(0, 0, 0) }
        val (luma, cb, cr) = planes()

        // A block holding two white and two black pixels averages to mid grey: still neutral.
        P010Converter.convert(source(white, black, white, black), width, height, luma, cb, cr)

        assertEquals(512, code(cb, 0))
        assertEquals(512, code(cr, 0))
        assertEquals("luma keeps full resolution", 940, code(luma, 0))
        assertEquals("second row is still black", 64, code(luma, width * 2))
    }
}
