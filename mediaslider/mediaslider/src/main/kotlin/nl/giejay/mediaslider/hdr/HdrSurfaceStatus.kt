package nl.giejay.mediaslider.hdr

/**
 * What the HDR photo layer has managed to do, for the debug screen.
 *
 * [photosDrawn] is deliberately sticky. The layer only exists while its photo is on screen, so by
 * the time anyone opens the diagnostics it has been released - and "nothing on screen right now"
 * must not read the same as "this never worked".
 */
object HdrSurfaceStatus {

    /** True while an HDR layer is on screen. */
    @Volatile
    var active: Boolean = false

    /** How many photos have been drawn onto an HDR layer since the app started. */
    @Volatile
    var photosDrawn: Int = 0
        private set

    /** How the layer announced itself as HDR, e.g. EGL_PQ or PRODUCER_PQ. */
    @Volatile
    var method: String? = null
        private set

    /** Why the HDR layer could not be used, or null if it worked. */
    @Volatile
    var lastFailure: String? = null

    fun recordRender(method: String) {
        this.method = method
        photosDrawn++
        active = true
        lastFailure = null
    }

    fun describe(): String = when {
        lastFailure != null -> "no - $lastFailure"
        active -> "yes - a $method layer is on screen now ($photosDrawn drawn so far)"
        photosDrawn > 0 ->
            "yes - $photosDrawn photo(s) drawn on a $method layer " +
                "(the layer is released when the photo leaves the screen)"
        else -> "never used - no Ultra HDR photo has reached it yet"
    }
}
