package com.ngi.sarothi.core.voice

/** Whether a voice capability can be used, and if not, exactly why. */
data class VoiceAvailability(val ready: Boolean, val reason: String?, val fix: String? = null) {
    companion object {
        val READY = VoiceAvailability(true, null)
        fun unavailable(reason: String, fix: String? = null) = VoiceAvailability(false, reason, fix)
    }
}

sealed interface SpeakOutcome {
    data class Spoken(val engine: String, val durationMillis: Long) : SpeakOutcome
    data class Failed(val reason: String, val engine: String?) : SpeakOutcome
    data object Cancelled : SpeakOutcome
}

sealed interface ListenOutcome {
    /** [language] is what whisper detected, which may differ from what was asked for. */
    data class Heard(
        val text: String,
        val language: String?,
        val durationMillis: Long,
        val secondsOfAudio: Double,
    ) : ListenOutcome

    data class Failed(val reason: String, val permissionMissing: Boolean = false) : ListenOutcome
    data object Cancelled : ListenOutcome
    /** Mic was opened but nothing above the noise floor was said. */
    data object NothingHeard : ListenOutcome
}

/**
 * Speech in and speech out.
 *
 * Two engines back each direction and the choice is explicit rather than silent:
 *
 *  - **out**: Piper (the Bengali ONNX voice in the vault) when it is installed and
 *    the native runtime is present, otherwise the Android system `TextToSpeech`
 *    service — whose Bengali quality depends entirely on what the OEM bundled.
 *    The outcome names the engine that was actually used so the UI can say so.
 *  - **in**: whisper.cpp on 16 kHz PCM captured with `AudioRecord`. There is no
 *    recognizer fallback: Android's `SpeechRecognizer` requires a Google service
 *    that many 3 GB devices do not have, and quietly routing voice input to a
 *    cloud service would break the on-device promise.
 */
interface VoiceController {
    val ttsAvailability: VoiceAvailability
    val sttAvailability: VoiceAvailability

    val isSpeaking: Boolean
    val isListening: Boolean

    /**
     * Speaks [text]. Returns which engine produced the audio.
     * @param voiceId a Piper voice id from the vault, or null for the default.
     */
    suspend fun speak(text: String, voiceId: String? = null): SpeakOutcome

    fun stopSpeaking()

    /** Records and transcribes. [onLevel] reports input loudness 0..1 for a meter. */
    suspend fun listen(
        maxSeconds: Int = 20,
        language: String = "auto",
        onLevel: ((Float) -> Unit)? = null,
    ): ListenOutcome

    fun cancelListening()

    /** Piper voices present in the vault, for the settings screen. */
    suspend fun availableVoices(): List<VoiceOption>
}

data class VoiceOption(
    val id: String,
    val displayName: String,
    val languageCode: String?,
    val installed: Boolean,
    val detail: String,
)
