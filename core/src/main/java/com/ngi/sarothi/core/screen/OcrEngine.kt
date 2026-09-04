package com.ngi.sarothi.core.screen

import android.graphics.Bitmap

/**
 * On-device text recognition for screenshots.
 *
 * ML Kit ships Latin-script models plus a handful of others; there is **no**
 * Bengali text-recognition artifact published on Google Maven. Rather than
 * pretend, an implementation reports exactly which scripts it can read and this
 * interface propagates that into the snapshot's `limitations`, which the agent
 * sees. Sarothi's primary screen reading path is the accessibility tree, which
 * returns real Bengali text regardless of OCR support.
 */
interface OcrEngine {
    val isAvailable: Boolean
    fun unavailabilityReason(): String?

    /** BCP-47 scripts this engine can actually read, e.g. `["en", "bn-latn"]`. */
    val supportedScripts: List<String>

    suspend fun recognise(bitmap: Bitmap): OcrResult
}

data class OcrResult(
    val text: String,
    val regions: List<OcrRegion>,
    val scriptsAttempted: List<String>,
    /** Set when recognition ran but the model cannot read the script on screen. */
    val limitation: String?,
) {
    companion object {
        val EMPTY = OcrResult("", emptyList(), emptyList(), null)
    }
}

/** Used when no OCR artifact is present at all; keeps the UI honest. */
object NoOcrEngine : OcrEngine {
    override val isAvailable: Boolean get() = false
    override fun unavailabilityReason(): String =
        "No on-device OCR engine is bundled in this build."
    override val supportedScripts: List<String> = emptyList()
    override suspend fun recognise(bitmap: Bitmap): OcrResult = OcrResult(
        text = "",
        regions = emptyList(),
        scriptsAttempted = emptyList(),
        limitation = unavailabilityReason(),
    )
}
