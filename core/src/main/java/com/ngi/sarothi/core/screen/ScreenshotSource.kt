package com.ngi.sarothi.core.screen

/**
 * The MediaProjection fallback path.
 *
 * Implemented in `:app` (it owns the foreground service, the virtual display and
 * the user-consent flow). Core only knows how to ask for pixels and how to
 * interpret what comes back.
 */
interface ScreenshotSource {
    val isReady: Boolean
    fun unavailabilityReason(): String?

    /** Captures one frame, downscaled so the longer edge is at most [maxDimension]. */
    suspend fun capture(maxDimension: Int): CaptureResult
}

object ScreenshotSourceRegistry {
    @Volatile
    private var source: ScreenshotSource? = null

    fun attach(source: ScreenshotSource) {
        this.source = source
    }

    fun detach(source: ScreenshotSource) {
        if (this.source === source) this.source = null
    }

    val current: ScreenshotSource? get() = source
}
