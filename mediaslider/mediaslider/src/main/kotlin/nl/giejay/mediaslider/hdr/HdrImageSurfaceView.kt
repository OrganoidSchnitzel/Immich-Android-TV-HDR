package nl.giejay.mediaslider.hdr

import android.content.Context
import android.graphics.Bitmap
import android.hardware.DataSpace
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shows an Ultra HDR photo on a 10-bit BT.2020 surface.
 *
 * This is the way to get HDR stills onto a TV box. A TV's display pipeline does not give app
 * windows any headroom above SDR white - `Window.setColorMode(COLOR_MODE_HDR)` is a no-op there -
 * but it does switch the HDMI output into HDR whenever a layer arrives tagged as HDR, which is how
 * video gets its Dolby Vision / HDR10 output. So instead of asking the window for headroom, the
 * photo is rendered into its own surface that announces itself the same way a video layer does.
 *
 * How that announcement is made depends on the driver, see [EglHdrCapabilities]: an EGL surface
 * created in a BT.2020 colour space where the extension exists, otherwise an ordinary 10-bit
 * surface whose layer is tagged with `SurfaceControl.Transaction.setDataSpace`.
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
    private var tagging = HdrTagging.NONE

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
        // The layer tag has to be applied from the main thread's SurfaceControl, before any
        // buffer is posted, so the compositor sees the first frame as HDR.
        tagging = EglHdrCapabilities.probe().preferredTagging()
        val control = if (tagging == HdrTagging.SURFACE_CONTROL_PQ) surfaceControl else null
        if (control != null) {
            tagLayerAsPq(control)
        }
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

    /** Label the layer before the first buffer so the compositor sees it as HDR from the start. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun tagLayerAsPq(control: SurfaceControl) {
        if (!control.isValid) {
            Timber.w("No valid SurfaceControl to tag as HDR")
            return
        }
        val dataSpace = DataSpace.pack(
            DataSpace.STANDARD_BT2020, DataSpace.TRANSFER_ST2084, DataSpace.RANGE_FULL
        )
        SurfaceControl.Transaction().use { it.setDataSpace(control, dataSpace).apply() }
        Timber.i("Tagged the photo layer as BT.2020 PQ")
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

    /** EGL context bound to the view's surface, producing BT.2020 HDR frames. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private class GlSession(
        private val display: EGLDisplay,
        private val context: EGLContext,
        private val surface: EGLSurface,
        private val renderer: UltraHdrGlRenderer,
        private val tagging: HdrTagging,
        private val layerToTag: SurfaceControl?
    ) {
        private var uploadedBitmap: Bitmap? = null

        fun render(bitmap: Bitmap, width: Int, height: Int, weight: Float) {
            makeCurrent()
            if (uploadedBitmap !== bitmap) {
                // A recycled bitmap or a gain map that went away is this photo's problem, not
                // the device's: skip the frame rather than tearing the HDR path down for good.
                if (!renderer.uploadImage(bitmap)) {
                    Timber.w("Skipping HDR render: the bitmap no longer has a usable gain map")
                    return
                }
                uploadedBitmap = bitmap
            }
            renderer.draw(bitmap, width, height, weight, usePq = tagging != HdrTagging.EGL_HLG)
            EGL14.eglSwapBuffers(display, surface)
            // Again after the frame, on this thread: the buffer that just went through the
            // BufferQueue carries its own dataspace, which would otherwise replace the tag set
            // before any buffer existed.
            layerToTag?.let(::tagAsPq)
            HdrSurfaceStatus.recordRender(tagging.name)
        }

        fun release() {
            makeCurrentSafely()
            renderer.release()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            // Deliberately no eglTerminate: this is the process-wide default display, shared
            // with the view system's own renderer and with ExoPlayer.
            uploadedBitmap = null
            HdrSurfaceStatus.active = false
        }

        private fun makeCurrent() {
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "eglMakeCurrent failed: ${EGL14.eglGetError()}"
            }
        }

        private fun makeCurrentSafely() {
            runCatching { makeCurrent() }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun tagAsPq(control: SurfaceControl) {
            if (!control.isValid) return
            val dataSpace = DataSpace.pack(
                DataSpace.STANDARD_BT2020, DataSpace.TRANSFER_ST2084, DataSpace.RANGE_FULL
            )
            SurfaceControl.Transaction().use { it.setDataSpace(control, dataSpace).apply() }
        }

        companion object {
            @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            fun create(holder: SurfaceHolder, tagging: HdrTagging, layerToTag: SurfaceControl?): GlSession {
                check(tagging != HdrTagging.NONE) { "This device cannot present an HDR layer" }
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
                val version = IntArray(2)
                check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

                val config = chooseTenBitConfig(display)
                val context = EGL14.eglCreateContext(
                    display, config, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
                )
                check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${EGL14.eglGetError()}" }

                val surface = EGL14.eglCreateWindowSurface(
                    display, config, holder.surface, surfaceAttributes(tagging), 0
                )
                check(surface != EGL14.EGL_NO_SURFACE) {
                    "Could not create a 10-bit HDR surface ($tagging): ${EGL14.eglGetError()}"
                }

                check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    "eglMakeCurrent failed: ${EGL14.eglGetError()}"
                }
                val renderer = UltraHdrGlRenderer()
                renderer.setUp()
                Timber.i("HDR photo surface ready using %s", tagging)
                return GlSession(display, context, surface, renderer, tagging, layerToTag)
            }

            /**
             * With a colour space extension the surface itself is tagged; with layer tagging the
             * surface is an ordinary one and `setDataSpace` has already labelled the layer.
             */
            private fun surfaceAttributes(tagging: HdrTagging): IntArray = when (tagging) {
                HdrTagging.EGL_PQ ->
                    intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT, EGL14.EGL_NONE)
                HdrTagging.EGL_HLG ->
                    intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_HLG_EXT, EGL14.EGL_NONE)
                else -> intArrayOf(EGL14.EGL_NONE)
            }

            private fun chooseTenBitConfig(display: EGLDisplay): EGLConfig {
                val attributes = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
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
    }
}
