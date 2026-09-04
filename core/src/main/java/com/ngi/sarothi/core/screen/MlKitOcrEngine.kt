package com.ngi.sarothi.core.screen

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device OCR over screenshots, using ML Kit's bundled text recognizer.
 *
 * Scope is stated honestly because it matters for Bengali users: ML Kit publishes
 * Latin, Chinese, Devanagari and Japanese recognizers, and **there is no Bengali
 * text-recognition artifact** on Google Maven. Sarothi therefore:
 *
 *  - reads the accessibility tree first, which returns real Bengali text for any
 *    app that exposes one (the overwhelming majority);
 *  - uses this engine only as a screenshot fallback, and attaches a limitation to
 *    every snapshot it produces saying that Bengali script will not be
 *    recognised, so the agent never concludes "the screen is empty" when it is
 *    full of Bengali.
 *
 * The recognizer itself is bundled in the APK, so this path needs no network and
 * no Google Play Services.
 */
class MlKitOcrEngine(private val context: Context) : OcrEngine {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    @Volatile
    private var initFailure: String? = null

    override val isAvailable: Boolean
        get() = initFailure == null && runCatching {
            context.packageManager.getApplicationInfo(context.packageName, 0)
        }.isSuccess

    override fun unavailabilityReason(): String? = initFailure ?: LATIN_ONLY_NOTE

    override val supportedScripts: List<String> =
        listOf("en", "fr", "de", "es", "it", "pt", "tr", "id", "ms", "vi", "tl", "sw")

    override suspend fun recognise(bitmap: Bitmap): OcrResult {
        if (!isAvailable) {
            return OcrResult(
                text = "",
                regions = emptyList(),
                scriptsAttempted = emptyList(),
                limitation = unavailabilityReason(),
            )
        }
        val input = runCatching { InputImage.fromBitmap(bitmap, 0) }.getOrElse {
            initFailure = "Could not hand the screenshot to ML Kit: ${it.message}"
            return OcrResult("", emptyList(), emptyList(), initFailure)
        }

        val recognised = suspendCancellableCoroutine<TextRecognitionOutcome> { continuation ->
            val task = recognizer.process(input)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(TextRecognitionOutcome.Read(result))
                }
                .addOnFailureListener { failure ->
                    Log.w(TAG, "ML Kit text recognition failed", failure)
                    if (continuation.isActive) {
                        continuation.resume(TextRecognitionOutcome.Failed(failure))
                    }
                }
                .addOnCanceledListener {
                    if (continuation.isActive) {
                        continuation.resume(
                            TextRecognitionOutcome.Failed(IllegalStateException("recognition cancelled")),
                        )
                    }
                }
            continuation.invokeOnCancellation { runCatching { task.exception } }
        }

        return when (recognised) {
            is TextRecognitionOutcome.Failed -> OcrResult(
                text = "",
                regions = emptyList(),
                scriptsAttempted = supportedScripts,
                limitation = "On-device OCR failed: ${recognised.error.javaClass.simpleName}: " +
                    "${recognised.error.message}. $LATIN_ONLY_NOTE",
            )
            is TextRecognitionOutcome.Read -> {
                val regions = recognised.result.textBlocks.flatMap { block ->
                    block.lines.map { line ->
                        val box = line.boundingBox
                        if (box == null) {
                            null
                        } else {
                            OcrRegion(
                                text = line.text,
                                left = box.left,
                                top = box.top,
                                right = box.right,
                                bottom = box.bottom,
                            )
                        }
                    }.filterNotNull()
                }
                val text = recognised.result.textBlocks.joinToString("\n") { block ->
                    block.lines.joinToString("\n") { it.text }
                }
                val limitation = if (regions.isEmpty()) {
                    "OCR found no readable text in this screenshot. $LATIN_ONLY_NOTE"
                } else {
                    LATIN_ONLY_NOTE
                }
                OcrResult(
                    text = text,
                    regions = regions,
                    scriptsAttempted = supportedScripts,
                    limitation = limitation,
                )
            }
        }
    }

    private sealed interface TextRecognitionOutcome {
        data class Read(val result: com.google.mlkit.vision.text.Text) : TextRecognitionOutcome
        data class Failed(val error: Exception) : TextRecognitionOutcome
    }

    companion object {
        private const val TAG = "SarothiOcr"
        const val LATIN_ONLY_NOTE =
            "This OCR engine reads Latin-script text only: ML Kit publishes no Bengali " +
                "text-recognition model, so Bangla in a screenshot is invisible to it. " +
                "Bangla UI text is read from the accessibility tree instead, which is why " +
                "that permission is the primary screen source."
    }
}
