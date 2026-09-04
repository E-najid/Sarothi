package com.ngi.sarothi.core.runtime

import android.content.Context
import com.ngi.sarothi.core.error.NativeRuntimeUnavailableException
import com.ngi.sarothi.core.storage.VaultFileSystem
import java.io.Closeable

data class TranscriptionResult(
    val text: String,
    val detectedLanguage: String?,
    val elapsedMillis: Long,
    val errorMessage: String?,
) {
    val succeeded: Boolean get() = errorMessage == null
}

class WhisperSession internal constructor(
    internal val handle: Long,
    internal val modelFile: VaultModelFile,
    val displayName: String,
) : Closeable {
    @Volatile internal var closed = false

    override fun close() {
        if (closed) return
        closed = true
        runCatching { NativeBridge.nativeWhisperFree(handle) }
        runCatching { modelFile.close() }
    }
}

/**
 * whisper.cpp speech-to-text.
 *
 * Audio must be 16 kHz mono float PCM in [-1, 1]; [com.ngi.sarothi.core.voice.AudioRecorder]
 * produces exactly that, so no resampling happens anywhere.
 *
 * Language handling is explicit rather than optimistic: passing `"bn"` pins
 * Bengali, passing `"auto"` runs whisper's own detector. Sarothi never claims a
 * language it did not detect — [TranscriptionResult.detectedLanguage] is whatever
 * whisper reported, or null.
 */
class WhisperRuntime(private val context: Context) {

    fun isAvailable(): Boolean =
        NativeBridge.isLoaded && runCatching { NativeBridge.nativeWhisperRuntimeAvailable() }.getOrDefault(false)

    fun unavailabilityReason(): String? = when {
        !NativeBridge.isLoaded -> NativeBridge.loadFailure ?: "libsarothi_native.so is missing"
        !isAvailable() -> "libsarothi_native.so loaded but was built without whisper.cpp. " +
            "Run scripts/setup_native.sh, then rebuild."
        else -> null
    }

    fun load(
        fileSystem: VaultFileSystem,
        modelVaultPath: String,
        displayName: String,
        policy: RamPolicy,
        expectedSizeBytes: Long = -1L,
    ): WhisperSession {
        NativeBridge.requireLoaded("whisper.cpp")
        if (!isAvailable()) {
            throw NativeRuntimeUnavailableException("whisper.cpp", unavailabilityReason() ?: "unknown")
        }
        val modelFile = VaultModelFile.open(context, fileSystem, modelVaultPath, expectedSizeBytes)
        val handle = NativeBridge.nativeWhisperLoad(modelFile.nativePath, policy.inferenceThreads())
        if (handle <= 0) {
            val reason = NativeBridge.lastErrorOr("whisper.cpp returned error code $handle")
            runCatching { modelFile.close() }
            throw NativeRuntimeUnavailableException(
                "whisper.cpp model ${modelVaultPath.substringAfterLast('/')}",
                reason,
            )
        }
        return WhisperSession(handle, modelFile, displayName)
    }

    fun transcribe(
        session: WhisperSession,
        pcm: FloatArray,
        language: String = LANGUAGE_AUTO,
        translate: Boolean = false,
        maxTokens: Int = 0,
        onProgress: ((Int) -> Unit)? = null,
    ): TranscriptionResult {
        check(!session.closed) { "This whisper session has been closed" }
        if (pcm.isEmpty()) {
            return TranscriptionResult("", null, 0, "No audio was captured")
        }
        val startedAt = System.currentTimeMillis()
        val callback = onProgress?.let { reporter -> NativeBridge.ProgressCallback { reporter(it) } }
        val text = NativeBridge.nativeWhisperTranscribe(
            handle = session.handle,
            pcm = pcm,
            language = language,
            translate = translate,
            maxTokens = maxTokens,
            progress = callback,
        )
        val elapsed = System.currentTimeMillis() - startedAt
        if (text == null) {
            return TranscriptionResult(
                text = "",
                detectedLanguage = null,
                elapsedMillis = elapsed,
                errorMessage = NativeBridge.lastErrorOr("whisper.cpp could not transcribe the audio"),
            )
        }
        return TranscriptionResult(
            text = text,
            detectedLanguage = runCatching { NativeBridge.nativeWhisperDetectedLanguage(session.handle) }.getOrNull(),
            elapsedMillis = elapsed,
            errorMessage = null,
        )
    }

    companion object {
        const val LANGUAGE_AUTO = "auto"
        const val LANGUAGE_BENGALI = "bn"
        const val LANGUAGE_ENGLISH = "en"
        const val SAMPLE_RATE = 16_000
    }
}
