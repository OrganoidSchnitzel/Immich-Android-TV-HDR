package nl.giejay.android.tv.immich.shared.util

import android.content.Context
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_FORCE_ORIGINAL_VIDEO
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_HDR_IMAGE_MODE
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_GLIDE_TRANSFORMATION
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_HEIGHT
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MAX_CUT_OFF_WIDTH
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_ONLY_USE_THUMBNAILS
import nl.giejay.mediaslider.hdr.EglHdrCapabilities
import nl.giejay.mediaslider.transformations.GlideTransformations
import nl.giejay.mediaslider.hdr.HdrSurfaceStatus
import nl.giejay.mediaslider.util.HdrDiagnostics

/**
 * Human readable dump of everything that decides whether HDR can work on this device, shown from
 * Settings -> Debug. Intentionally plain text: it is meant to be read off a TV screen (or
 * photographed) and pasted into a bug report.
 */
object HdrReport {

    fun build(context: Context): String {
        val capabilities = HdrCapabilities.of(context)
        val forceOriginal = PreferenceManager.get(SLIDER_FORCE_ORIGINAL_VIDEO)
        val thumbnailsOnly = PreferenceManager.get(SLIDER_ONLY_USE_THUMBNAILS)
        val plan = HdrImagePlan.of(PreferenceManager.get(SLIDER_HDR_IMAGE_MODE), capabilities)
        val transformation = PreferenceManager.get(SLIDER_GLIDE_TRANSFORMATION)

        val sections = listOf(
            "DISPLAY" to capabilities.lines(),
            "GPU" to EglHdrCapabilities.probe().let { egl ->
                listOf(
                    "Can produce an HDR layer" to egl.describe(),
                    "HDR-related extensions" to egl.hdrExtensions()
                )
            },
            "SETTINGS" to listOf(
                "HDR photos" to "${plan.mode.name} -> ${plan.describe()}",
                "Only use thumbnails" to if (thumbnailsOnly)
                    "on - photos come from the server's preview JPEG, which has no gain map"
                else "off",
                "Original video quality" to if (forceOriginal) "on" else
                    "off - videos play the server's transcode, which is SDR",
                // Not HDR, but it decides how much of a photo is cut away, which is the other
                // thing that makes a photo look wrong on screen.
                "Photo transformation" to if (transformation == GlideTransformations.CENTER_CROP)
                    "CENTER_CROP - crops every photo to the screen's shape"
                else transformation.name,
                "Max cut-off" to "${PreferenceManager.get(SLIDER_MAX_CUT_OFF_WIDTH)}% wide, " +
                    "${PreferenceManager.get(SLIDER_MAX_CUT_OFF_HEIGHT)}% high"
            ),
            "LAST SEEN IN THE SLIDESHOW" to
                HdrDiagnostics.lines() + ("HDR photo layer" to HdrSurfaceStatus.describe())
        )

        return buildString {
            sections.forEach { (header, lines) ->
                append(header).append('\n')
                lines.forEach { (label, value) -> append("  ").append(label).append(": ").append(value).append('\n') }
                append('\n')
            }
            append(hint(capabilities, plan, forceOriginal, thumbnailsOnly))
        }
    }

    private fun hint(
        capabilities: HdrCapabilities,
        plan: HdrImagePlan,
        forceOriginal: Boolean,
        thumbnailsOnly: Boolean
    ): String = when {
        !forceOriginal && capabilities.supportsDolbyVisionVideo ->
            "This TV supports Dolby Vision, but 'Original video quality' is off, so Immich serves " +
                "its own SDR transcode. Turn it on under View settings > Slideshow > Display."
        thumbnailsOnly ->
            "'Only use thumbnails' is on, so the gain map never reaches the app - a photo can " +
                "never be HDR this way. Turn it off under View settings > Slideshow > Display."
        !plan.useWindowColorMode && !plan.useHdrSurface ->
            "HDR photos cannot be rendered on this device: ${capabilities.ultraHdrVerdict()}. " +
                "HDR videos are unaffected."
        plan.useHdrSurface && HdrSurfaceStatus.lastFailure != null ->
            "The HDR photo layer could not be used: ${HdrSurfaceStatus.lastFailure}. " +
                "Photos fall back to SDR; HDR videos are unaffected."
        else ->
            "HDR photos should work here. A photo that looks normal but flat means the HDR layer " +
                "was not used; one that looks very dark or washed out means the layer was drawn " +
                "but the TV did not switch its output into HDR."
    }
}
