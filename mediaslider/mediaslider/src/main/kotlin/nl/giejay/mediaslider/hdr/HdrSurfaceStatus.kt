package nl.giejay.mediaslider.hdr

/** What the HDR photo surface last managed to do, for the debug screen. */
object HdrSurfaceStatus {

    /** True while a BT.2020 PQ layer is on screen. */
    @Volatile
    var active: Boolean = false

    /** Why the HDR surface could not be used, or null if it worked. */
    @Volatile
    var lastFailure: String? = null

    fun describe(): String = when {
        active -> "yes - showing a BT.2020 PQ layer"
        lastFailure != null -> "no - $lastFailure"
        else -> "not used for the last photo"
    }
}
