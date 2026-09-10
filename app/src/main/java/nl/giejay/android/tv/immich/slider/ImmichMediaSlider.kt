package nl.giejay.android.tv.immich.slider

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.navigation.fragment.findNavController
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_DISPLAY_HDR_IMAGES
import nl.giejay.android.tv.immich.shared.util.HdrColorModeController
import nl.giejay.mediaslider.plugin.TimelineStoryProgressPlugin
import nl.giejay.mediaslider.view.MediaSliderFragment
import nl.giejay.mediaslider.view.MediaSliderView
import timber.log.Timber

class ImmichMediaSlider : MediaSliderFragment() {
    private val favoriteService = FavoriteService()
    private val hdrColorMode = HdrColorModeController { activity?.window }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return MediaSliderView(requireContext())
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Timber.i("Loading ${this.javaClass.simpleName}")

        val bundle = ImmichMediaSliderArgs.fromBundle(requireArguments())
        val config = sliderViewModel.configuration

        if (config == null || config.items.isEmpty()) {
            Timber.i("No items to play for photoslider")
            Toast.makeText(requireContext(), getString(R.string.no_items_to_play), Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        setDefaultExoFactory(
            DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(mapOf("x-api-key" to PreferenceManager.get(API_KEY)))
        )

        if (bundle.timelineView) {
            val timelinePlugin = TimelineStoryProgressPlugin()
            config.viewPlugins += timelinePlugin
            config.controllerPlugins += timelinePlugin
            config.keyEventPlugins += timelinePlugin
        }

        val enabledPlugins = PreferenceManager.createEnabledSliderPlugins(lifecycleScope, favoriteService)
        config.controllerPlugins += enabledPlugins.controllerPlugins
        config.viewPlugins += enabledPlugins.viewPlugins
        config.keyEventPlugins += enabledPlugins.keyEventPlugins

        val hdrImagesEnabled = PreferenceManager.get(SLIDER_DISPLAY_HDR_IMAGES)
        config.onImageHdrDetected = { hasGainmap -> hdrColorMode.onHdrDetected(hasGainmap && hdrImagesEnabled) }
        // A video must reclaim the display's native HDR/Dolby Vision path from any image HDR mode.
        config.onVideoShown = { hdrColorMode.reset() }

        loadMediaSliderView(config)

        if (bundle.timelineView) {
            // Memories: timeline plugin mounts story progress; start autoplay.
            (view as MediaSliderView).toggleSlideshow(false)
        }
    }

    override fun onDestroyView() {
        // Leaving the viewer: drop HDR color mode so the rest of the UI renders in SDR again.
        hdrColorMode.reset()
        super.onDestroyView()
    }
}
