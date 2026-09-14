package nl.giejay.android.tv.immich.shared.util

import android.content.Context
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_FORCE_ORIGINAL_VIDEO
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_HDR_IMAGE_MODE
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

        val sections = listOf(
            "DISPLAY" to capabilities.lines(),
            "SETTINGS" to listOf(
                "HDR photos" to PreferenceManager.get(SLIDER_HDR_IMAGE_MODE).name,
                "Original video quality" to if (forceOriginal) "on" else
                    "off - videos play the server's transcode, which is SDR"
            ),
            "LAST SEEN IN THE SLIDESHOW" to HdrDiagnostics.lines()
        )

        return buildString {
            sections.forEach { (header, lines) ->
                append(header).append('\n')
                lines.forEach { (label, value) -> append("  ").append(label).append(": ").append(value).append('\n') }
                append('\n')
            }
            append(hint(capabilities, forceOriginal))
        }
    }

    private fun hint(capabilities: HdrCapabilities, forceOriginal: Boolean): String = when {
        !forceOriginal && capabilities.supportsDolbyVisionVideo ->
            "This TV supports Dolby Vision, but 'Original video quality' is off, so Immich serves " +
                "its own SDR transcode. Turn it on under View settings > Slideshow > Display."
        !capabilities.ultraHdrImagesSupported ->
            "HDR photos cannot be rendered on this device: ${capabilities.ultraHdrVerdict()}. " +
                "HDR videos are unaffected."
        else -> "HDR photos are supported here. If a photo still looks flat, check the gain map line above."
    }
}
