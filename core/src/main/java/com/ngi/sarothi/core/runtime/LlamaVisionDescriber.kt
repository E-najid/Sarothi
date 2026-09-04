package com.ngi.sarothi.core.runtime

import android.graphics.Bitmap
import com.ngi.sarothi.core.screen.VisionDescriber
import com.ngi.sarothi.core.screen.VisionDescription
import com.ngi.sarothi.core.screen.VisionTap
import com.ngi.sarothi.core.util.JsonReply
import com.ngi.sarothi.core.util.arrayOrNull
import com.ngi.sarothi.core.util.intOr
import com.ngi.sarothi.core.util.stringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The screenshot → VLM fallback, wired to the on-demand vision model.
 *
 * The model is asked for strict JSON. That is not stylistic: a 450 M model
 * describing a screenshot in prose cannot be turned into a tap coordinate
 * reliably, and Sarothi refuses to guess. When the reply is not parseable, or the
 * model says it cannot see the target, [describe] returns the raw text with no
 * taps and the agent must fall back to the accessibility tree — it never invents
 * a coordinate.
 *
 * Coordinates come back in the **bitmap's** space (the downscaled screenshot), so
 * [com.ngi.sarothi.core.screen.ScreenCaptureService.scaleToScreen] must be applied
 * before they reach `dispatchGesture`.
 */
class LlamaVisionDescriber(
    private val models: ModelSessionManager,
    private val llama: LlamaRuntime,
    private val captureWidth: Int,
    private val captureHeight: Int,
) : VisionDescriber {

    override val isAvailable: Boolean
        get() = llama.isVisionAvailable()

    override fun unavailabilityReason(): String? = when {
        !llama.isAvailable() -> llama.unavailabilityReason()
        !llama.isVisionAvailable() -> llama.unavailabilityReason()
        else -> null
    }

    override suspend fun describe(bitmap: Bitmap, question: String): VisionDescription {
        val startedAt = System.currentTimeMillis()
        val encoded = withContext(Dispatchers.Default) { encode(bitmap) }

        return models.withVision { session ->
            val prompt = buildPrompt(question, bitmap.width, bitmap.height)
            val result = llama.generate(
                session = session,
                prompt = prompt,
                params = GenerationParams.GROUNDING,
                image = encoded,
            )
            if (!result.succeeded) {
                return@withVision VisionDescription(
                    text = "",
                    proposedTaps = emptyList(),
                    modelId = session.displayName,
                    elapsedMillis = System.currentTimeMillis() - startedAt,
                    stopReason = result.errorMessage
                        ?: "The vision model stopped with reason ${result.reason}",
                )
            }
            parse(result.text, session.displayName, System.currentTimeMillis() - startedAt, result.reason.name)
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream(bitmap.width * bitmap.height / 4)
        // JPEG at moderate quality: mtmd decodes it, and a PNG of a 720p phone
        // screen would be several megabytes of JNI copy on a 3 GB device.
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
        if (!ok) {
            throw IllegalStateException(
                "Android refused to encode the screenshot as JPEG; the vision model cannot be " +
                    "given an image it cannot decode.",
            )
        }
        return stream.toByteArray()
    }

    private fun buildPrompt(question: String, width: Int, height: Int): String = buildString {
        append("You are the screen-reading module of Sarothi, an on-device assistant.\n")
        append("The image is a phone screenshot, ").append(width).append('x').append(height).append(" pixels.\n")
        append("Answer with ONE JSON object and nothing else, in exactly this shape:\n")
        append("{\"visible\": boolean, \"summary\": string, \"taps\": [{\"label\": string, ")
        append("\"x\": integer, \"y\": integer, \"confidence\": string}], \"missing\": string}\n")
        append("Rules:\n")
        append("- x and y are pixel coordinates inside this image, 0..").append(width - 1)
        append(" and 0..").append(height - 1).append(".\n")
        append("- Only propose a tap for a control you can actually see. Never estimate one.\n")
        append("- If the target is not on screen, set visible=false, leave taps empty, and say ")
        append("what is missing in \"missing\".\n")
        append("- \"summary\" is at most 40 words describing the screen and its main controls.\n")
        append("- Do not invent text that is not in the image.\n\n")
        append("Task: ").append(question).append('\n')
        append("JSON:")
    }

    private fun parse(
        raw: String,
        modelId: String,
        elapsedMillis: Long,
        stopReason: String,
    ): VisionDescription {
        val json = JsonReply.extractObject(raw)
        if (json == null) {
            return VisionDescription(
                text = raw.trim(),
                proposedTaps = emptyList(),
                modelId = modelId,
                elapsedMillis = elapsedMillis,
                stopReason = "The vision model did not return parseable JSON (stop reason: " +
                    "$stopReason). No tap coordinates are proposed.",
            )
        }

        val visible = json.get("visible")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        val summary = json.stringOrNull("summary") ?: ""
        val missing = json.stringOrNull("missing")
        val taps = mutableListOf<VisionTap>()

        json.arrayOrNull("taps")?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val tap = element.asJsonObject
            val label = tap.stringOrNull("label") ?: return@forEach
            val x = tap.intOr("x", Int.MIN_VALUE)
            val y = tap.intOr("y", Int.MIN_VALUE)
            if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) return@forEach
            if (x < 0 || y < 0 || x >= captureWidth || y >= captureHeight) return@forEach
            taps += VisionTap(label, x, y, tap.stringOrNull("confidence"))
        }

        val note = when {
            !visible -> "The vision model reported the target is not on screen" +
                (missing?.let { ": $it" } ?: ".")
            taps.isEmpty() -> "The vision model described the screen but proposed no tappable control."
            else -> stopReason
        }

        return VisionDescription(
            text = buildString {
                append(summary)
                if (!missing.isNullOrBlank()) append("\nNot found: ").append(missing)
            }.trim(),
            proposedTaps = taps,
            modelId = modelId,
            elapsedMillis = elapsedMillis,
            stopReason = note,
        )
    }
}
