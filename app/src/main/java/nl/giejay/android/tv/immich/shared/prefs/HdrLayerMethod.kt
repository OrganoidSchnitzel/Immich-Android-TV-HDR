package nl.giejay.android.tv.immich.shared.prefs

import nl.giejay.mediaslider.hdr.EglHdrCapabilities.HdrTagging

/**
 * Which mechanism the HDR photo layer should use to tell the compositor it holds HDR.
 *
 * A diagnostic knob rather than a preference: which of these a TV pipeline actually acts on is not
 * something the device reports, so it has to be tried. Anything the driver does not support falls
 * back to the best mechanism it does. Labels stay as the technical names on purpose - they are the
 * names used in the diagnostics and in bug reports.
 */
enum class HdrLayerMethod(private val label: String, val tagging: HdrTagging?) : EnumWithTitle {
    AUTO("Auto (best the driver offers)", null),

    /** Frames handed over with a BT.2020 PQ data space on each buffer. */
    PRODUCER_PQ("PRODUCER_PQ (data space on each frame)", HdrTagging.PRODUCER_PQ),

    /** SMPTE 2086 / CTA-861.3 mastering metadata on the EGL surface. */
    EGL_METADATA_PQ("EGL_METADATA_PQ (HDR static metadata)", HdrTagging.EGL_METADATA_PQ),

    /** The driver's own BT.2020 PQ colour space, where it has one. */
    EGL_PQ("EGL_PQ (driver colour space)", HdrTagging.EGL_PQ),

    /** The driver's own BT.2020 HLG colour space; clips the brightest highlights. */
    EGL_HLG("EGL_HLG (driver colour space, clips highlights)", HdrTagging.EGL_HLG);

    override fun getTitle(): String = label
}
