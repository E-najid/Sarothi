package com.ngi.sarothi.core.screen

import android.graphics.Bitmap

/**
 * Everything Sarothi can see and do to the screen.
 *
 * Two cooperating sources feed it:
 *  - the **accessibility service** (primary): exact text, roles and bounds, and
 *    `performAction`/`dispatchGesture` for acting;
 *  - **MediaProjection screenshots** (fallback): used when the accessibility tree
 *    is empty or too poor (custom-drawn surfaces, WebView canvases, games), and
 *    handed either to on-device OCR or to the vision model.
 *
 * Implementations must never fabricate a snapshot: when nothing is available they
 * return a snapshot whose [ScreenSnapshot.source] is
 * [SnapshotSource.UNAVAILABLE] and whose `limitations` explain why.
 */
interface ScreenController {

    /** Is the accessibility service bound and enabled right now? */
    val isServiceConnected: Boolean

    /** Is a MediaProjection capture session available? */
    val hasCapturePermission: Boolean

    fun availability(): ScreenAvailability

    /** Reads the current screen from the best available source. */
    suspend fun snapshot(preferTree: Boolean = true): ScreenSnapshot

    /**
     * Captures the screen as a bitmap, downscaled so [maxDimension] is the longer
     * edge. Returns null with a reason when capture is not permitted.
     */
    suspend fun captureScreen(maxDimension: Int = 768): CaptureResult

    // --- actions -----------------------------------------------------------

    suspend fun tapNode(nodeId: String): ActionResult
    suspend fun longPressNode(nodeId: String): ActionResult
    suspend fun tapAt(x: Int, y: Int): ActionResult
    suspend fun setText(nodeId: String, text: String): ActionResult

    /** Pastes [text] into the focused editable field when no node id is known. */
    suspend fun typeIntoFocused(text: String): ActionResult

    suspend fun scroll(nodeId: String, direction: ScrollDirection): ActionResult
    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMillis: Long = 300L): ActionResult
    suspend fun back(): ActionResult
    suspend fun home(): ActionResult
    suspend fun openRecents(): ActionResult
    suspend fun openNotifications(): ActionResult
    suspend fun quickSettings(): ActionResult

    /** Launches another app by package. Returns the resolved activity when known. */
    suspend fun launchApp(packageName: String): ActionResult

    /** Finds nodes whose label contains [query], case-insensitively. */
    suspend fun findNodes(query: String, snapshot: ScreenSnapshot? = null): List<ScreenNode>

    /**
     * The vision-model fallback: capture the screen, ask the VLM what it shows and
     * where the thing described by [question] is.
     *
     * Returned taps are already converted from the downscaled capture back into real
     * screen pixels, so they can go straight to [tapAt]. When the vision model is not
     * installed, does not fit in RAM, or the capture was refused, [VisionGrounding.available]
     * is false and [VisionGrounding.reason] says why — never an empty result that
     * looks like "the screen has nothing on it".
     */
    suspend fun describeScreen(question: String, maxDimension: Int = 768): VisionGrounding
}

/** One tap the vision model proposed, in both coordinate spaces. */
data class GroundedTap(
    val label: String,
    val screenX: Int,
    val screenY: Int,
    val imageX: Int,
    val imageY: Int,
    val confidenceNote: String?,
)

data class VisionGrounding(
    val available: Boolean,
    val reason: String?,
    val description: String?,
    val taps: List<GroundedTap>,
    val modelId: String?,
    val elapsedMillis: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val screenWidth: Int,
    val screenHeight: Int,
) {
    companion object {
        fun unavailable(reason: String) = VisionGrounding(
            available = false,
            reason = reason,
            description = null,
            taps = emptyList(),
            modelId = null,
            elapsedMillis = 0,
            imageWidth = 0,
            imageHeight = 0,
            screenWidth = 0,
            screenHeight = 0,
        )
    }
}

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

data class ScreenAvailability(
    val accessibilityConnected: Boolean,
    val accessibilityEnabledInSettings: Boolean,
    val capturePermissionGranted: Boolean,
    val ocrAvailable: Boolean,
    val visionAvailable: Boolean,
    val detail: String,
) {
    val canReadScreen: Boolean get() = accessibilityConnected || capturePermissionGranted
    val canAct: Boolean get() = accessibilityConnected
}

sealed interface CaptureResult {
    data class Captured(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val capturedAtEpochMillis: Long,
        val scaledFrom: Int,
    ) : CaptureResult

    data class Denied(val reason: String, val needsUserConsent: Boolean) : CaptureResult
}
