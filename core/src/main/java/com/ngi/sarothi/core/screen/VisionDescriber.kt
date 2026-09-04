package com.ngi.sarothi.core.screen

import android.graphics.Bitmap

/**
 * Turns a screenshot into something the text orchestrator can reason about,
 * using the on-demand vision model.
 *
 * Kept behind an interface so the agent can fall back to OCR (fast) or to the
 * accessibility tree (exact) when the vision model is not installed, does not fit
 * in RAM, or the native build has no multimodal support.
 */
interface VisionDescriber {
    val isAvailable: Boolean
    fun unavailabilityReason(): String?

    /**
     * @param question what the agent needs from the image, e.g. "Where is the
     *   Send button? Reply with its label and tap coordinates."
     */
    suspend fun describe(bitmap: Bitmap, question: String): VisionDescription
}

data class VisionDescription(
    val text: String,
    /** Tap points the model proposed, in the bitmap's coordinate space. */
    val proposedTaps: List<VisionTap>,
    val modelId: String,
    val elapsedMillis: Long,
    val stopReason: String,
)

data class VisionTap(val label: String, val x: Int, val y: Int, val confidenceNote: String?)
