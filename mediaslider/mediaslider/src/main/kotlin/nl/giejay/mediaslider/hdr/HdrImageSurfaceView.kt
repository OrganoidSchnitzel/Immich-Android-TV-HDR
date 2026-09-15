package nl.giejay.mediaslider.hdr

import android.content.Context
import android.graphics.Bitmap
import android.hardware.DataSpace
import android.hardware.HardwareBuffer
import android.media.ImageWriter
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import nl.giejay.mediaslider.hdr.EglHdrCapabilities.HdrTagging
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shows an Ultra HDR photo on a 10-bit BT.2020 surface.
 *
 * This is the way to get HDR stills onto a TV box. A TV's display pipeline does not give app
 * windows any headroom above SDR white - `Window.setColorMode(COLOR_MODE_HDR)` is a no-op there -
 * but it does switch the HDMI output into HDR whenever a layer arrives whose buffers say they hold
 * HDR, which is how video gets its Dolby Vision / HDR10 output. So the photo is rendered into its
 * own surface that announces itself the same way a video decoder's output does.
 *
 * Which mechanism does the announcing depends on the driver, see [EglHdrCapabilities]. With the
 * BT.2020 colour space extension EGL tags every buffer it produces. Without it the frames are
 * rendered offscreen and handed over through an [ImageWriter] carrying a BT.2020 PQ data space -
 * the data space then belongs to the buffer itself, which is what the compositor reads, rather
 * than to the layer, where the next queued frame would overwrite it.
 *
 * The view is inert until [setImage] is given a bitmap with a gain map. Any failure reports through
 * [onUnavailable] so the caller can fall back to the ordinary SDR image view.
 */
class HdrImageSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** Called on the main thread when this device cannot render the photo in HDR after all. */
    var onUnavailable: ((String) -> Unit)? = null

    @Volatile
    private var bitmap: Bitmap? = null

    @Volatile
    private var weight: Float = 1f

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var session: GlSession? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    init {
        holder.addCallback(this)
    }

    /**
     * Sets the photo to display. [hdrWeight] is how much of the gain map to apply, 0 (looks like
     * the SDR photo) to 1 (the full HDR rendition the photo was authored for).
     */
    fun setImage(bitmap: Bitmap?, hdrWeight: Float) {
        this.bitmap = bitmap
        this.weight = hdrWeight
        handler?.post { renderOnGlThread() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            reportUnavailable("Ultra HDR needs Android 14")
            return
        }
        val thread = HandlerThread("hdr-image-gl").apply { start() }
        this.thread = thread
        handler = Handler(thread.looper)
        val tagging = EglHdrCapabilities.probe().preferredTagging()
        // Labelling the layer as well costs one transaction and covers the case where the
        // compositor looks at the layer rather than the buffer.
        val control = surfaceControl?.takeIf { tagging == HdrTagging.PRODUCER_PQ }
        control?.let(::tagLayerAsPq)
        handler?.post { createSession(holder, tagging, control) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        handler?.post { renderOnGlThread() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Must not return until GL has let go of the surface, or the compositor tears down a
        // buffer the driver is still using.
        val handler = this.handler
        if (handler != null) {
            val released = CountDownLatch(1)
            handler.post {
                destroySession()
                released.countDown()
            }
            if (!released.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Timber.w("Timed out waiting for the HDR surface to be released")
            }
        }
        thread?.quitSafely()
        thread = null
        this.handler = null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun tagLayerAsPq(control: SurfaceControl) {
        if (!control.isValid) {
            Timber.w("No valid SurfaceControl to tag as HDR")
            return
        }
        SurfaceControl.Transaction().use { it.setDataSpace(control, BT2020_PQ).apply() }
    }

    // --- GL thread ---

    private fun createSession(holder: SurfaceHolder, tagging: HdrTagging, control: SurfaceControl?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            session = GlSession.create(holder, tagging, control)
            renderOnGlThread()
        } catch (e: Exception) {
            Timber.w(e, "Could not start the HDR surface")
            destroySession()
            reportUnavailable(e.message ?: "HDR surface could not be created")
        }
    }

    private fun renderOnGlThread() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val session = this.session ?: return
        val bitmap = this.bitmap ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        try {
            session.render(bitmap, surfaceWidth, surfaceHeight, weight)
        } catch (e: Exception) {
            Timber.w(e, "Could not render the HDR photo")
            destroySession()
            reportUnavailable(e.message ?: "HDR photo could not be rendered")
        }
    }

    private fun destroySession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        session?.release()
        session = null
    }

    private fun reportUnavailable(reason: String) {
        HdrSurfaceStatus.lastFailure = reason
        post { onUnavailable?.invoke(reason) }
    }

    /** EGL context producing BT.2020 HDR frames for the view's surface. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private class GlSession(
        private val display: EGLDisplay,
        private val context: EGLContext,
        private val config: EGLConfig,
        private val renderer: UltraHdrGlRenderer,
        private val tagging: HdrTagging,
        private val holder: SurfaceHolder,
        private val layerToTag: SurfaceControl?,
        /** Set for the EGL colour space modes; null when frames go through the [ImageWriter]. */
        private val windowSurface: EGLSurface?
    ) {
        private var uploadedBitmap: Bitmap? = null

        // Producer mode only: an offscreen target the frame is rendered into and read back from,
        // plus the writer that hands it to the surface with a BT.2020 PQ data space.
        private var offscreen: EGLSurface? = null
        private var imageWriter: ImageWriter? = null
        private var readback: ByteBuffer? = null
        private var targetWidth = 0
        private var targetHeight = 0

        fun render(bitmap: Bitmap, width: Int, height: Int, weight: Float) {
            if (windowSurface != null) {
                renderToWindow(bitmap, width, height, weight)
            } else {
                renderThroughWriter(bitmap, width, height, weight)
            }
            HdrSurfaceStatus.recordRender(tagging.name)
        }

        private fun renderToWindow(bitmap: Bitmap, width: Int, height: Int, weight: Float) {
            makeCurrent(windowSurface!!)
            if (!upload(bitmap)) return
            renderer.draw(bitmap, width, height, weight, usePq = tagging != HdrTagging.EGL_HLG)
            EGL14.eglSwapBuffers(display, windowSurface)
        }

        private fun renderThroughWriter(bitmap: Bitmap, width: Int, height: Int, weight: Float) {
            prepareTarget(width, height)
            makeCurrent(offscreen!!)
            if (!upload(bitmap)) return
            renderer.draw(bitmap, width, height, weight, usePq = true)

            val pixels = readback!!
            pixels.rewind()
            // GL_RGBA + UNSIGNED_INT_2_10_10_10_REV matches the RGBA_1010102 buffer the writer
            // hands out, so the frame can be copied across without touching the values.
            GLES30.glReadPixels(
                0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, pixels
            )

            val writer = imageWriter!!
            val image = writer.dequeueInputImage()
            try {
                image.dataSpace = BT2020_PQ
                copyFlipped(pixels, image.planes[0].buffer, image.planes[0].rowStride, width, height)
                writer.queueInputImage(image)
            } catch (e: Exception) {
                image.close()
                throw e
            }
            layerToTag?.takeIf { it.isValid }?.let { control ->
                SurfaceControl.Transaction().use { it.setDataSpace(control, BT2020_PQ).apply() }
            }
        }

        /**
         * Copies a GL read-back into an [android.media.Image] plane. GL numbers rows from the
         * bottom of the frame and the image plane from the top, so the rows are reversed on the
         * way across; [rowStride] can be wider than the frame, hence the row-at-a-time copy.
         */
        private fun copyFlipped(source: ByteBuffer, destination: ByteBuffer, rowStride: Int, width: Int, height: Int) {
            val rowBytes = width * BYTES_PER_PIXEL
            for (row in 0 until height) {
                source.limit((height - row) * rowBytes)
                source.position((height - row - 1) * rowBytes)
                destination.position(row * rowStride)
                destination.put(source)
            }
            source.clear()
        }

        private fun prepareTarget(width: Int, height: Int) {
            if (offscreen != null && width == targetWidth && height == targetHeight) return
            releaseTarget()
            targetWidth = width
            targetHeight = height
            offscreen = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0
            )
            check(offscreen != EGL14.EGL_NO_SURFACE) {
                "Could not create a 10-bit offscreen surface: ${EGL14.eglGetError()}"
            }
            readback = ByteBuffer.allocateDirect(width * height * BYTES_PER_PIXEL)
                .order(ByteOrder.nativeOrder())
            imageWriter = ImageWriter.Builder(holder.surface)
                .setHardwareBufferFormat(HardwareBuffer.RGBA_1010102)
                .setDataSpace(BT2020_PQ)
                .setWidthAndHeight(width, height)
                .setMaxImages(MAX_IMAGES)
                .build()
        }

        private fun upload(bitmap: Bitmap): Boolean {
            if (uploadedBitmap === bitmap) return true
            // A recycled bitmap or a gain map that went away is this photo's problem, not the
            // device's: skip the frame rather than tearing the HDR path down for good.
            if (!renderer.uploadImage(bitmap)) {
                Timber.w("Skipping HDR render: the bitmap no longer has a usable gain map")
                return false
            }
            uploadedBitmap = bitmap
            return true
        }

        fun release() {
            runCatching { makeCurrent(windowSurface ?: offscreen ?: EGL14.EGL_NO_SURFACE) }
            renderer.release()
            releaseTarget()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            windowSurface?.let { EGL14.eglDestroySurface(display, it) }
            EGL14.eglDestroyContext(display, context)
            // Deliberately no eglTerminate: this is the process-wide default display, shared
            // with the view system's own renderer and with ExoPlayer.
            uploadedBitmap = null
            HdrSurfaceStatus.active = false
        }

        private fun releaseTarget() {
            imageWriter?.close()
            imageWriter = null
            offscreen?.let { EGL14.eglDestroySurface(display, it) }
            offscreen = null
            readback = null
        }

        private fun makeCurrent(target: EGLSurface) {
            check(EGL14.eglMakeCurrent(display, target, target, context)) {
                "eglMakeCurrent failed: ${EGL14.eglGetError()}"
            }
        }

        companion object {
            @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            fun create(holder: SurfaceHolder, tagging: HdrTagging, layerToTag: SurfaceControl?): GlSession {
                check(tagging != HdrTagging.NONE) { "This device cannot present an HDR layer" }
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
                val version = IntArray(2)
                check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

                val config = chooseTenBitConfig(display, tagging)
                // ES 3 for GL_UNSIGNED_INT_2_10_10_10_REV read-back, which ES 2 does not guarantee.
                val context = EGL14.eglCreateContext(
                    display, config, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
                )
                check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${EGL14.eglGetError()}" }

                val windowSurface = if (tagging == HdrTagging.PRODUCER_PQ) {
                    // The ImageWriter is the surface's producer; EGL must not also own it.
                    null
                } else {
                    val created = EGL14.eglCreateWindowSurface(
                        display, config, holder.surface, surfaceAttributes(tagging), 0
                    )
                    check(created != EGL14.EGL_NO_SURFACE) {
                        "Could not create a 10-bit HDR surface ($tagging): ${EGL14.eglGetError()}"
                    }
                    created
                }

                val renderer = runCatching {
                    val warmUp = windowSurface ?: EGL14.eglCreatePbufferSurface(
                        display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
                    )
                    check(EGL14.eglMakeCurrent(display, warmUp, warmUp, context)) {
                        "eglMakeCurrent failed: ${EGL14.eglGetError()}"
                    }
                    UltraHdrGlRenderer().apply { setUp() }.also {
                        if (warmUp !== windowSurface) EGL14.eglDestroySurface(display, warmUp)
                    }
                }.getOrElse { error ->
                    windowSurface?.let { EGL14.eglDestroySurface(display, it) }
                    EGL14.eglDestroyContext(display, context)
                    throw error
                }

                Timber.i("HDR photo surface ready using %s", tagging)
                return GlSession(display, context, config, renderer, tagging, holder, layerToTag, windowSurface)
            }

            private fun surfaceAttributes(tagging: HdrTagging): IntArray = when (tagging) {
                HdrTagging.EGL_PQ ->
                    intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT, EGL14.EGL_NONE)
                HdrTagging.EGL_HLG ->
                    intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_HLG_EXT, EGL14.EGL_NONE)
                else -> intArrayOf(EGL14.EGL_NONE)
            }

            private fun chooseTenBitConfig(display: EGLDisplay, tagging: HdrTagging): EGLConfig {
                val surfaceTypes = if (tagging == HdrTagging.PRODUCER_PQ) {
                    EGL14.EGL_PBUFFER_BIT
                } else {
                    EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT
                }
                val attributes = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, surfaceTypes,
                    EGL14.EGL_RED_SIZE, 10,
                    EGL14.EGL_GREEN_SIZE, 10,
                    EGL14.EGL_BLUE_SIZE, 10,
                    EGL14.EGL_ALPHA_SIZE, 2,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                    "This device has no 10-bit (RGBA_1010102) EGL config"
                }
                return configs[0]!!
            }
        }
    }

    private companion object {
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
        const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
        const val RELEASE_TIMEOUT_SECONDS = 2L
        const val BYTES_PER_PIXEL = 4
        const val MAX_IMAGES = 2

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        val BT2020_PQ: Int = DataSpace.pack(
            DataSpace.STANDARD_BT2020, DataSpace.TRANSFER_ST2084, DataSpace.RANGE_FULL
        )
    }
}
