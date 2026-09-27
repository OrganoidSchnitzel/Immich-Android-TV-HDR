package nl.giejay.mediaslider.hdr

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import timber.log.Timber

/**
 * Which 10-bit video encoders this device has.
 *
 * This decides whether an HDR photo can be shown the one way that is known to work on this
 * hardware: as video. On Amlogic TV boxes HDR output is driven by the video decoder feeding the
 * dedicated video plane, while everything the GPU draws - every app layer, however it is tagged -
 * lands on the graphics plane, which is SDR. That is why Dolby Vision video switches the TV and a
 * perfectly encoded PQ photo layer does not. Encoding the photo as a one-frame 10-bit HDR10 clip
 * and decoding it straight onto a SurfaceView puts it on the video plane, like any other video -
 * but only if there is an encoder to make the clip with.
 */
object HdrEncoderProbe {

    data class Encoder(val name: String, val hardware: Boolean, val profiles: List<String>) {
        override fun toString(): String =
            "$name (${if (hardware) "hardware" else "software"}: ${profiles.joinToString("/")})"
    }

    @Volatile
    private var cached: List<Encoder>? = null

    fun tenBitEncoders(): List<Encoder> = cached ?: synchronized(this) {
        cached ?: runCatching(::probe).getOrElse { error ->
            Timber.w(error, "Could not list video encoders")
            emptyList()
        }.also { cached = it }
    }

    fun describe(): String = tenBitEncoders().let { encoders ->
        if (encoders.isEmpty()) "none" else encoders.joinToString("; ")
    }

    private fun probe(): List<Encoder> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .mapNotNull { info ->
                val profiles = info.supportedTypes
                    .filter { it in CANDIDATE_TYPES }
                    .flatMap { type ->
                        info.getCapabilitiesForType(type).profileLevels.mapNotNull { level ->
                            tenBitProfileName(type, level.profile)
                        }
                    }
                    .distinct()
                if (profiles.isEmpty()) {
                    null
                } else {
                    val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated
                    Encoder(info.name, hardware, profiles)
                }
            }
            .sortedByDescending { it.hardware }

    /** A readable name for a 10-bit profile of [mimeType], or null when [profile] is 8-bit. */
    fun tenBitProfileName(mimeType: String, profile: Int): String? = when (mimeType) {
        MediaFormat.MIMETYPE_VIDEO_HEVC -> when (profile) {
            CodecProfileLevel.HEVCProfileMain10 -> "HEVC Main10"
            CodecProfileLevel.HEVCProfileMain10HDR10 -> "HEVC Main10 HDR10"
            CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "HEVC Main10 HDR10+"
            else -> null
        }
        MediaFormat.MIMETYPE_VIDEO_AV1 -> when (profile) {
            CodecProfileLevel.AV1ProfileMain10 -> "AV1 Main10"
            CodecProfileLevel.AV1ProfileMain10HDR10 -> "AV1 Main10 HDR10"
            CodecProfileLevel.AV1ProfileMain10HDR10Plus -> "AV1 Main10 HDR10+"
            else -> null
        }
        MediaFormat.MIMETYPE_VIDEO_VP9 -> when (profile) {
            CodecProfileLevel.VP9Profile2 -> "VP9 Profile 2"
            CodecProfileLevel.VP9Profile2HDR -> "VP9 Profile 2 HDR"
            CodecProfileLevel.VP9Profile2HDR10Plus -> "VP9 Profile 2 HDR10+"
            else -> null
        }
        else -> null
    }

    private val CANDIDATE_TYPES = setOf(
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaFormat.MIMETYPE_VIDEO_VP9
    )
}
