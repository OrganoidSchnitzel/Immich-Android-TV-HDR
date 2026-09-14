package nl.giejay.mediaslider.hdr

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shows an Ultra HDR photo on a 10-bit BT.2020 PQ surface.
 *
 * This is the way to get HDR stills onto a TV box. A TV's display pipeline does not give app
 * windows any headroom above SDR white - `Window.setColorMode(COLOR_MODE_HDR)` is a no-op there -
 * but it does switch the HDMI output into HDR whenever a layer arrives tagged as HDR, which is how
 * video gets its Dolby Vision / HDR10 output. So instead of asking the window for headroom, the
 * photo is rendered into its own surface tagged `EGL_GL_COLORSPACE_BT2020_PQ_EXT`, exactly the kind
 * of layer the compositor already knows how to put on screen in HDR.
 *
 * The view is inert until [setImage] is given a bitmap with a gain map. Any failure (no 10-bit
 * config, no PQ colour space extension, a shader that will not compile) reports through
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
        handler?.post { createSession(holder) }
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

    // --- GL thread ---

    private fun createSession(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            session = GlSession.create(holder)
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

    /** EGL context bound to the view's surface with a BT.2020 PQ colour space. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private class GlSession(
        private val display: EGLDisplay,
        private val context: EGLContext,
        private val surface: EGLSurface,
        private val renderer: UltraHdrGlRenderer
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
            renderer.draw(bitmap, width, height, weight)
            EGL14.eglSwapBuffers(display, surface)
            HdrSurfaceStatus.lastFailure = null
            HdrSurfaceStatus.active = true
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

        companion object {
            @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            fun create(holder: SurfaceHolder): GlSession {
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
                val version = IntArray(2)
                check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

                val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
                check(extensions.contains(EXT_COLORSPACE_BT2020_PQ)) {
                    "This device's GPU driver has no $EXT_COLORSPACE_BT2020_PQ"
                }

                val config = chooseTenBitConfig(display)
                val context = EGL14.eglCreateContext(
                    display, config, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
                )
                check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${EGL14.eglGetError()}" }

                val surface = EGL14.eglCreateWindowSurface(
                    display, config, holder.surface,
                    intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT, EGL14.EGL_NONE), 0
                )
                check(surface != EGL14.EGL_NO_SURFACE) {
                    "Could not create a BT.2020 PQ surface: ${EGL14.eglGetError()}"
                }

                check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    "eglMakeCurrent failed: ${EGL14.eglGetError()}"
                }
                val renderer = UltraHdrGlRenderer()
                renderer.setUp()
                return GlSession(display, context, surface, renderer)
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
        const val EXT_COLORSPACE_BT2020_PQ = "EGL_EXT_gl_colorspace_bt2020_pq"
        const val RELEASE_TIMEOUT_SECONDS = 2L
    }
}
