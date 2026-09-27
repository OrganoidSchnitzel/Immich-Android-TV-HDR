package nl.giejay.mediaslider.hdr

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HdrEncoderProbeTest {

    @Test
    fun `ten bit profiles are recognised`() {
        assertEquals("HEVC Main10 HDR10",
            HdrEncoderProbe.tenBitProfileName("video/hevc", CodecProfileLevel.HEVCProfileMain10HDR10))
        assertEquals("AV1 Main10",
            HdrEncoderProbe.tenBitProfileName("video/av01", CodecProfileLevel.AV1ProfileMain10))
        assertEquals("VP9 Profile 2",
            HdrEncoderProbe.tenBitProfileName("video/x-vnd.on2.vp9", CodecProfileLevel.VP9Profile2))
    }

    @Test
    fun `eight bit profiles and other codecs are not`() {
        // An 8-bit encoder cannot carry PQ without banding, so it does not count.
        assertNull(HdrEncoderProbe.tenBitProfileName("video/hevc", CodecProfileLevel.HEVCProfileMain))
        assertNull(HdrEncoderProbe.tenBitProfileName("video/avc", CodecProfileLevel.AVCProfileHigh10))
    }
}
