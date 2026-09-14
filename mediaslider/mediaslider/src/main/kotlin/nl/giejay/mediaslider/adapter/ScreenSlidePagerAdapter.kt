package nl.giejay.mediaslider.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import androidx.viewpager.widget.PagerAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.zeuskartik.mediaslider.R
import nl.giejay.mediaslider.player.AmlogicSafeRenderersFactory
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.transformations.HdrGainmapTransformation
import nl.giejay.mediaslider.util.HdrDiagnostics
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.view.ExoPlayerListener
import nl.giejay.mediaslider.view.ExoPlayerView
import nl.giejay.mediaslider.view.TouchImageView
import timber.log.Timber


class ScreenSlidePagerAdapter(private val context: Context,
                              private var items: List<SliderItemViewHolder>,
                              private val config: MediaSliderConfiguration,
                              private val currentIndex: () -> Int,
                              private val exoPlayerListener: ExoPlayerListener) : PagerAdapter() {
    private var imageView: TouchImageView? = null
    private val progressBars: MutableMap<Int, ProgressBar> = HashMap()
    // position -> whether the decoded primary image carries an Ultra HDR gain map
    private val hdrByPosition: MutableMap<Int, Boolean> = HashMap()
    private val failedPositions = mutableSetOf<String>()

    fun setItems(items: List<SliderItemViewHolder>) {
        this.items = items
        notifyDataSetChanged()
    }

    fun hideProgressBar(position: Int) {
        val progressBar = progressBars[position]
        if (progressBar != null) {
            progressBar.visibility = GONE
            progressBars.remove(position)
        }
    }

    override fun getItemPosition(`object`: Any): Int {
        return POSITION_NONE
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        var view: View? = null
        val model = items[position]
        if (model.type == SliderItemType.IMAGE) {
            if (model.hasSecondaryItem()) {
                view = inflater.inflate(R.layout.image_double_item, container, false)
                loadImageIntoView(view, R.id.left_image, position, model.mainItem, isPrimary = true)
                loadImageIntoView(view, R.id.right_image, position, model.secondaryItem!!, isPrimary = false)
            } else {
                view = inflater.inflate(R.layout.image_item, container, false)
                loadImageIntoView(view, R.id.mBigImage, position, model.mainItem, isPrimary = true)
            }
        } else if (model.type == SliderItemType.VIDEO) {
            // A TextureView is composited by the GPU and is therefore always SDR: a video on one
            // can never drive the display into HDR or Dolby Vision. Keep it for the cases that
            // need it - a video stored rotated, and a url that already failed on a SurfaceView -
            // and put everything else on the SurfaceView. An orientation the server did not report
            // now counts as "not rotated": treating it as portrait sent every video without
            // rotation metadata down the SDR path.
            val useTextureView = isRotated(model.mainItem.orientation) || failedPositions.contains(model.url)
            view = ExoPlayerView(context, if (useTextureView) R.layout.video_item_texture_view else R.layout.video_item)
            view.setupPlayer(config, AmlogicSafeRenderersFactory(context), exoPlayerListener) { player, error ->
                val shouldRetry = !useTextureView && !failedPositions.contains(model.url)
                Timber.e(error,
                    "Player error at position $position for url ${model.url}. Already failed: ${failedPositions.contains(model.url)}." +
                            "Should retry: $shouldRetry. " +
                            "Current index: ${currentIndex()}. " +
                            "Did use texture view: ${useTextureView}. Error: ${error.message}")

                if (shouldRetry) {
                    model.url?.let { failedPositions.add(it) }
                    Timber.w("MediaCodec error at position $position, retrying with TextureView")
                    // Release the current player to avoid memory leaks
                    player.release()
                    // Trigger recreation of this item
                    notifyDataSetChanged()
                    true
                } else {
                    Toast.makeText(context, "Cannot play video: ${error.message}", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }
        view?.tag = "view$position"
        container.addView(view)
        return view!!
    }

    private fun loadImageIntoView(imageRootLayout: View,
                                  imageViewResource: Int,
                                  position: Int,
                                  model: SliderItem,
                                  isPrimary: Boolean) {
        imageView = imageRootLayout.findViewById(imageViewResource)
        val progressBar = imageRootLayout.findViewById<ProgressBar>(R.id.mProgressBar)
        if (progressBar != null) {
            progressBars[position] = progressBar
        }
        // Captured here: inside the Glide listener below, `model` is the listener's own parameter.
        val imageUrl = if (config.isOnlyUseThumbnails) model.thumbnailUrl else model.url
        var glideLoader = Glide.with(context)
            .load(imageUrl)
            .transform(HdrGainmapTransformation(context, config))
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?,
                                          model: Any?,
                                          target: com.bumptech.glide.request.target.Target<Drawable>,
                                          isFirstResource: Boolean): Boolean {
                    Timber.e(e, "Could not fetch image: %s", model)
                    hideProgressBar(position)
                    return false
                }

                override fun onResourceReady(resource: Drawable,
                                             model: Any,
                                             target: com.bumptech.glide.request.target.Target<Drawable>,
                                             dataSource: com.bumptech.glide.load.DataSource,
                                             isFirstResource: Boolean): Boolean {
                    hideProgressBar(position)
                    if (isPrimary) {
                        val hasGainmap = hasGainmap(resource)
                        if (position == currentIndex()) {
                            HdrDiagnostics.recordImage(imageUrl, resource)
                        }
                        hdrByPosition[position] = hasGainmap
                        // Only the on-screen image may drive the window color mode. Off-screen
                        // pages are preloaded by the pager (e.g. the image next to a playing
                        // video), and must NOT flip the window into HDR while a video is showing.
                        if (position == currentIndex()) {
                            config.onImageHdrDetected(hasGainmap)
                        }
                    }
                    return false
                }

            })
        if (!config.isOnlyUseThumbnails) {
            glideLoader = glideLoader.thumbnail(Glide.with(context)
                .load(model.thumbnailUrl))
        }
        glideLoader.into(imageView!!)
    }

    /**
     * Reports the on-screen image's HDR (gain map) status to the host so the window color mode
     * follows the currently displayed image. Called when a page settles. Safe before the image has
     * loaded: reports false until the decode finishes, at which point [instantiateItem]'s listener
     * reports the real value if the image is still current.
     */
    fun reportHdrForImagePosition(position: Int) {
        if (position !in items.indices) return
        if (items[position].type != SliderItemType.IMAGE) return
        config.onImageHdrDetected(hdrByPosition[position] == true)
    }

    /** EXIF orientations that mean the video is stored rotated: 180, 90 CW and 90 CCW. */
    private fun isRotated(orientation: Int): Boolean = orientation == 3 || orientation == 6 || orientation == 8

    private fun hasGainmap(resource: Drawable): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }
        return (resource as? BitmapDrawable)?.bitmap?.hasGainmap() == true
    }

    override fun getCount(): Int {
        return items.size
    }

    override fun isViewFromObject(view: View, o: Any): Boolean {
        return (view === o)
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        val view = `object` as View
        if (view is ExoPlayerView) {
            view.releasePlayer()
        } else {
            val imageView = view.findViewById<View>(R.id.mBigImage)
            if (imageView != null) {
                Glide.with(context).clear(imageView)
            }
        }
        container.removeView(view)
    }
}