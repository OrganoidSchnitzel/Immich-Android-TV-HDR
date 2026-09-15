package nl.giejay.mediaslider.config

import nl.giejay.mediaslider.adapter.MetaDataItem
import nl.giejay.mediaslider.hdr.EglHdrCapabilities
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.plugin.SliderControllerPlugin
import nl.giejay.mediaslider.plugin.SliderKeyEventPlugin
import nl.giejay.mediaslider.plugin.SliderViewPlugin
import nl.giejay.mediaslider.transformations.GlideTransformations
import nl.giejay.mediaslider.util.LoadMore

class MediaSliderConfiguration(
    val startPosition: Int,
    val interval: Int,
    val isOnlyUseThumbnails: Boolean,
    var isVideoSoundEnable: Boolean,
    var items: List<SliderItemViewHolder>,
    var loadMore: LoadMore?,
    var onAssetSelected: (SliderItemViewHolder) -> Unit = {},
    /**
     * Called on the main thread after the primary image of a page finishes loading, with true when
     * the decoded bitmap carries an Ultra HDR gain map. Hosts use it to switch their window into
     * HDR color mode. Default no-op for hosts that do not care about HDR.
     */
    var onImageHdrDetected: (Boolean) -> Unit = {},
    /**
     * Called on the main thread when a video page becomes the current item. Hosts drop any HDR
     * window color mode set for a previous image so the video decoder can drive the display into
     * its native HDR/Dolby Vision output instead of being composited in an app-forced HDR window.
     */
    var onVideoShown: () -> Unit = {},
    /**
     * Render Ultra HDR photos on a dedicated BT.2020 PQ surface instead of the normal image view.
     * Set by hosts whose display only does HDR for video layers, never for app windows.
     */
    var hdrPhotoSurface: Boolean = false,
    /** How much of a photo's gain map to apply: 0 looks like the SDR photo, 1 is the full HDR one. */
    var hdrPhotoWeight: Float = 1f,
    /** Force one way of marking the HDR photo layer; null picks the best the driver offers. */
    var hdrLayerMethod: EglHdrCapabilities.HdrTagging? = null,
    val animationSpeedMillis: Int,
    val maxCutOffHeight: Int,
    val maxCutOffWidth: Int,
    val glideTransformation: GlideTransformations,
    val gradiantOverlay: Boolean,
    val enableSlideAnimation: Boolean,
    val metaDataConfig: List<MetaDataItem>,
    val zoomAndScrollPanorama: Boolean,
    val zoomEffectPercent: Int,
    val panEffectPercent: Int,
    val useLargeVideoBuffer: Boolean = false,
    /** When true, D-pad Left/Right seek in video; when false (default), they change assets. */
    val dpadSeeksInVideo: Boolean = false,
    /** When true, [nl.giejay.mediaslider.plugin.DateOverlayViewPlugin] shows DATE at the top. */
    val showDateTopLeft: Boolean = false,
    var controllerPlugins: List<SliderControllerPlugin> = emptyList(),
    var viewPlugins: List<SliderViewPlugin<*>> = emptyList(),
    var keyEventPlugins: List<SliderKeyEventPlugin> = emptyList()
) {
    val isGradiantOverlayVisible: Boolean
        get() = (metaDataConfig.isNotEmpty()) && this.gradiantOverlay
}
