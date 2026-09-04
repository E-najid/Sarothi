package com.ngi.sarothi.core.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.ngi.sarothi.core.model.ModelRole
import androidx.core.content.ContextCompat
import com.ngi.sarothi.core.model.ModelCatalog
import com.ngi.sarothi.core.runtime.EspeakPhonemizer
import com.ngi.sarothi.core.runtime.PiperRuntime
import com.ngi.sarothi.core.runtime.ModelSessionManager
import com.ngi.sarothi.core.runtime.WhisperRuntime
import com.ngi.sarothi.core.storage.ModelState
import com.ngi.sarothi.core.storage.VaultManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Speech in, speech out.
 *
 * **Out** prefers the Piper Bengali voice from the vault (real ONNX inference on
 * device, see [PiperRuntime]) and falls back to Android's `TextToSpeech` service
 * only when Piper is not usable. Every outcome names the engine that actually
 * produced the audio, so the UI never implies Bengali neural TTS when the user is
 * hearing a system voice.
 *
 * **In** is whisper.cpp over 16 kHz mono PCM captured with `AudioRecord`, with a
 * simple energy VAD so recording stops when the user stops talking instead of
 * always burning the full [maxSeconds]. There is no cloud fallback: routing voice
 * to a remote recognizer would break Sarothi's on-device promise.
 */
class AndroidVoiceController(
    private val context: Context,
    private val vault: VaultManager,
    private val models: ModelSessionManager,
    private val whisper: WhisperRuntime,
    private val piper: PiperRuntime,
    private val phonemizer: EspeakPhonemizer,
) : VoiceController {

    private val speaking = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    private val cancelListening = AtomicBoolean(false)

    @Volatile
    private var systemTts: TextToSpeech? = null

    @Volatile
    private var piperVoice: com.ngi.sarothi.core.runtime.PiperVoice? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    override val isSpeaking: Boolean get() = speaking.get()
    override val isListening: Boolean get() = listening.get()

    override val ttsAvailability: VoiceAvailability
        get() {
            val phonemiser = phonemizer.availability()
            val voiceModel = ModelCatalog.byId(VOICE_MODEL_ID)
            val configModel = ModelCatalog.byId(VOICE_CONFIG_ID)
            val installed = voiceModel != null && configModel != null && vault.isUnlocked &&
                isUsable(vault.verifyModel(voiceModel)) && isUsable(vault.verifyModel(configModel))

            return when {
                installed && phonemiser.isReady -> VoiceAvailability.READY
                installed -> VoiceAvailability.unavailable(
                    reason = phonemiser.detail,
                    fix = "Build espeak-ng with scripts/build_espeak_ng.sh, or use the system voice.",
                )
                else -> VoiceAvailability.unavailable(
                    reason = "The Bengali Piper voice is not installed and verified in the vault.",
                    fix = "Settings → Models → download 'Piper Bengali (bn_BD, medium)'. " +
                        "Until then Sarothi uses the Android system voice if one is available.",
                )
            }
        }

    override val sttAvailability: VoiceAvailability
        get() {
            if (!hasRecordPermission()) {
                return VoiceAvailability.unavailable(
                    reason = "Microphone permission has not been granted.",
                    fix = "Allow the Microphone permission in Android settings for Sarothi.",
                )
            }
            val model = ModelCatalog.byId(models.speechModelId)
            if (model == null || !vault.isUnlocked || !isUsable(vault.verifyModel(model))) {
                return VoiceAvailability.unavailable(
                    reason = "The whisper.cpp speech model is not installed and verified in the vault.",
                    fix = "Settings → Models → download 'Whisper base (multilingual, q5_1)'.",
                )
            }
            val reason = whisper.unavailabilityReason()
            return if (reason == null) {
                VoiceAvailability.READY
            } else {
                VoiceAvailability.unavailable(reason, fix = "Run scripts/setup_native.sh and rebuild.")
            }
        }

    /** Verified, or present-without-a-published-digest: both are safe to load. */
    private fun isUsable(state: ModelState): Boolean =
        state is ModelState.Verified || state is ModelState.PresentUnverified

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // --------------------------------------------------------------- speech out

    override suspend fun speak(text: String, voiceId: String?): SpeakOutcome {
        if (text.isBlank()) return SpeakOutcome.Failed("There is nothing to say.", engine = null)
        stopSpeaking()

        val piperAttempt = if (voiceId == null || voiceId == PIPER_VOICE_ID) runPiper(text) else null
        if (piperAttempt != null) return piperAttempt

        return runSystemTts(text, voiceId)
    }

    private suspend fun runPiper(text: String): SpeakOutcome? {
        if (!ttsAvailability.ready) return null
        return try {
            val voice = obtainPiperVoice() ?: return null
            val startedAt = System.currentTimeMillis()
            val result = withContext(Dispatchers.Default) { piper.synthesize(voice, text) }
            val played = playPcm(result.pcm, result.sampleRate)
            if (!played) {
                return SpeakOutcome.Failed(
                    reason = "Piper synthesised ${result.durationMillis}ms of audio but AudioTrack " +
                        "could not play it.",
                    engine = "piper",
                )
            }
            SpeakOutcome.Spoken(
                engine = "piper:${voice.displayName}",
                durationMillis = System.currentTimeMillis() - startedAt,
            )
        } catch (failure: Exception) {
            Log.w(TAG, "Piper synthesis failed", failure)
            // Returning null lets speak() fall through to the system voice, which
            // is the honest degradation: the user hears something, and the log
            // records why it was not Piper.
            null
        }
    }

    private suspend fun obtainPiperVoice(): com.ngi.sarothi.core.runtime.PiperVoice? {
        piperVoice?.let { return it }
        val voiceModel = ModelCatalog.byId(VOICE_MODEL_ID) ?: return null
        val configModel = ModelCatalog.byId(VOICE_CONFIG_ID) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                piper.loadVoice(
                    fileSystem = vault.requireFileSystem(),
                    modelVaultPath = voiceModel.vaultPath,
                    configVaultPath = configModel.vaultPath,
                    displayName = voiceModel.displayName,
                    expectedSizeBytes = voiceModel.sizeBytes,
                )
            }.onFailure { Log.w(TAG, "Could not load the Piper voice", it) }
                .getOrNull()
                ?.also { piperVoice = it }
        }
    }

    /** Streams PCM to [AudioTrack] and waits for it to finish. */
    private suspend fun playPcm(pcm: ByteArray, sampleRate: Int): Boolean {
        if (pcm.isEmpty()) return false
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioTrack reports no buffer size for ${sampleRate}Hz mono PCM")
            return false
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, sampleRate))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrElse {
            Log.w(TAG, "Could not create an AudioTrack", it)
            return false
        }

        val loaded = runCatching { track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) }.getOrDefault(-1)
        if (loaded < 0) {
            runCatching { track.release() }
            return false
        }

        activeTrack = track
        speaking.set(true)
        return try {
            track.play()
            val durationMillis = pcm.size.toLong() * 1000L / (sampleRate.toLong() * 2L)
            val deadline = System.currentTimeMillis() + durationMillis + PLAYBACK_SLACK_MILLIS
            while (System.currentTimeMillis() < deadline && speaking.get()) {
                val position = runCatching { track.playbackHeadPosition }.getOrDefault(Int.MAX_VALUE)
                if (position * 2L >= loaded.toLong()) break
                delay(PLAYBACK_POLL_MILLIS)
            }
            speaking.get()
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            activeTrack = null
            speaking.set(false)
        }
    }

    private suspend fun runSystemTts(text: String, voiceId: String?): SpeakOutcome {
        val tts = obtainSystemTts()
            ?: return SpeakOutcome.Failed(
                reason = "Piper is unavailable and this device has no Android text-to-speech engine.",
                engine = null,
            )

        val language = Locale.forLanguageTag("bn-BD")
        val support = runCatching { tts.setLanguage(language) }.getOrDefault(TextToSpeech.LANG_MISSING_DATA)
        val languageNote = when {
            support == TextToSpeech.LANG_NOT_SUPPORTED || support == TextToSpeech.LANG_MISSING_DATA ->
                "This device's system voice has no Bengali data, so the reply will be read in the " +
                    "engine's default language."
            support == TextToSpeech.LANG_AVAILABLE ->
                "Bengali is supported by the system voice but with country variants missing."
            else -> null
        }

        val utteranceId = "sarothi-${System.currentTimeMillis()}"
        val done = CompletableDeferred<Boolean>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) {
                if (id == utteranceId) done.complete(true)
            }

            @Deprecated("Required by the abstract class; replaced by onError(id, errorCode)")
            override fun onError(id: String?) {
                if (id == utteranceId) done.complete(false)
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) done.complete(false)
            }

            override fun onStop(id: String?, interrupted: Boolean) {
                if (id == utteranceId) done.complete(false)
            }
        })

        speaking.set(true)
        val startedAt = System.currentTimeMillis()
        val status = runCatching {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }.getOrDefault(TextToSpeech.ERROR)

        if (status != TextToSpeech.SUCCESS) {
            speaking.set(false)
            return SpeakOutcome.Failed(
                reason = "The system text-to-speech engine refused the utterance (code $status).",
                engine = "system",
            )
        }

        val finished = withContext(Dispatchers.IO) {
            val limit = text.length * MILLIS_PER_CHARACTER + PLAYBACK_SLACK_MILLIS * 4
            withTimeoutOrNull(limit) { done.await() } ?: false
        }
        speaking.set(false)

        return if (finished) {
            SpeakOutcome.Spoken(
                engine = "system" + (languageNote?.let { " ($it)" } ?: ""),
                durationMillis = System.currentTimeMillis() - startedAt,
            )
        } else {
            SpeakOutcome.Failed(
                reason = "The system voice started but never reported completion" +
                    (languageNote?.let { "; $it" } ?: ""),
                engine = "system",
            )
        }
    }

    private suspend fun obtainSystemTts(): TextToSpeech? {
        systemTts?.let { return it }
        val initialised = CompletableDeferred<Int>()
        val engine = runCatching {
            TextToSpeech(context) { status -> initialised.complete(status) }
        }.getOrElse {
            Log.w(TAG, "Could not construct TextToSpeech", it)
            return null
        }
        val status = withTimeoutOrNull(TTS_INIT_TIMEOUT_MILLIS) { initialised.await() }
        if (status == null) {
            runCatching { engine.shutdown() }
            Log.w(TAG, "TextToSpeech initialisation timed out")
            return null
        }
        if (status != TextToSpeech.SUCCESS) {
            runCatching { engine.shutdown() }
            Log.w(TAG, "TextToSpeech init failed with status $status")
            return null
        }
        return engine.also { ttsRef = it }
    }

    @Volatile
    private var ttsRef: TextToSpeech? = null

    override fun stopSpeaking() {
        speaking.set(false)
        runCatching { activeTrack?.let { it.pause(); it.flush() } }
        runCatching { ttsRef?.stop() }
    }

    // ---------------------------------------------------------------- speech in

    override suspend fun listen(
        maxSeconds: Int,
        language: String,
        onLevel: ((Float) -> Unit)?,
    ): ListenOutcome {
        if (!hasRecordPermission()) {
            return ListenOutcome.Failed(
                reason = "Microphone permission is not granted, so Sarothi cannot listen.",
                permissionMissing = true,
            )
        }
        if (!sttAvailability.ready) {
            return ListenOutcome.Failed(reason = sttAvailability.reason ?: "Speech recognition is unavailable.")
        }
        if (!listening.compareAndSet(false, true)) {
            return ListenOutcome.Failed("Sarothi is already listening.", permissionMissing = false)
        }
        cancelListening.set(false)

        val seconds = maxSeconds.coerceIn(1, MAX_LISTEN_SECONDS)
        return try {
            val pcm = withContext(Dispatchers.IO) { record(seconds, onLevel) }
            if (cancelListening.get()) return ListenOutcome.Cancelled
            if (pcm == null) {
                return ListenOutcome.Failed(
                    "The microphone could not be opened. Another app may be holding it.",
                )
            }
            if (pcm.isEmpty()) return ListenOutcome.NothingHeard

            val startedAt = System.currentTimeMillis()
            val transcription = models.withWhisper { session ->
                withContext(Dispatchers.Default) {
                    whisper.transcribe(session, pcm, language = language)
                }
            }
            val text = transcription.text.trim()
            if (text.isEmpty()) return ListenOutcome.NothingHeard
            ListenOutcome.Heard(
                text = text,
                language = transcription.detectedLanguage,
                durationMillis = System.currentTimeMillis() - startedAt,
                secondsOfAudio = pcm.size.toDouble() / SAMPLE_RATE.toDouble(),
            )
        } catch (failure: Exception) {
            Log.w(TAG, "Listening failed", failure)
            ListenOutcome.Failed("${failure.javaClass.simpleName}: ${failure.message}")
        } finally {
            listening.set(false)
        }
    }

    override fun cancelListening() {
        cancelListening.set(true)
    }

    /**
     * Captures 16 kHz mono PCM and applies an energy VAD: recording stops after
     * [TRAILING_SILENCE_MILLIS] of quiet following at least [MIN_SPEECH_MILLIS] of
     * sound, so the user is not made to wait out the full window.
     *
     * @return the samples, or null when the recorder could not be opened.
     */
    private fun record(maxSeconds: Int, onLevel: ((Float) -> Unit)?): FloatArray? {
        // Checked at the call, not only by whoever started the listen: RECORD_AUDIO can be
        // revoked mid-session, and AudioRecord's constructor throws SecurityException
        // without it. Returning null is this function's documented "could not open the
        // recorder" path, which the caller already turns into a ListenOutcome.Failed.
        if (!hasRecordPermission()) {
            Log.w(TAG, "RECORD_AUDIO is not granted, so the recorder cannot be opened")
            return null
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioRecord reports no viable buffer size for ${SAMPLE_RATE}Hz mono")
            return null
        }
        val frameBytes = SAMPLE_RATE / FRAMES_PER_SECOND * 2
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, frameBytes * 4),
            )
        }.getOrElse {
            Log.w(TAG, "AudioRecord could not be constructed", it)
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return null
        }

        val samples = ArrayList<Float>(SAMPLE_RATE * maxSeconds)
        val buffer = ShortArray(frameBytes / 2)
        val startedAt = System.currentTimeMillis()
        var lastVoiceMillis = startedAt
        var heardVoice = false

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null

            while (System.currentTimeMillis() - startedAt < maxSeconds * 1000L) {
                if (cancelListening.get()) return FloatArray(0)
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        return null
                    }
                    continue
                }
                var sumSquares = 0.0
                for (index in 0 until read) {
                    val normalised = buffer[index] / 32768.0f
                    samples += normalised
                    sumSquares += normalised.toDouble() * normalised.toDouble()
                }
                val rms = sqrt(sumSquares / read).toFloat()
                onLevel?.invoke(rms.coerceIn(0f, 1f))

                if (rms >= VOICE_THRESHOLD) {
                    heardVoice = true
                    lastVoiceMillis = System.currentTimeMillis()
                } else if (heardVoice &&
                    System.currentTimeMillis() - lastVoiceMillis > TRAILING_SILENCE_MILLIS &&
                    samples.size > SAMPLE_RATE * MIN_SPEECH_MILLIS / 1000
                ) {
                    break
                }
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            onLevel?.invoke(0f)
        }

        if (!heardVoice) return FloatArray(0)
        return samples.toFloatArray()
    }

    override suspend fun availableVoices(): List<VoiceOption> = withContext(Dispatchers.IO) {
        ModelCatalog.forRole(ModelRole.TEXT_TO_SPEECH).map { model ->
            val state = if (vault.isUnlocked) vault.verifyModel(model) else ModelState.Missing
            VoiceOption(
                id = model.id,
                displayName = model.displayName,
                languageCode = model.language,
                installed = state is ModelState.Verified || state is ModelState.PresentUnverified,
                detail = when (state) {
                    is ModelState.Verified -> "Installed and checksum-verified."
                    is ModelState.PresentUnverified ->
                        "Present but its checksum could not be verified (${state.reason})."
                    is ModelState.SizeMismatch ->
                        "File is the wrong size (${state.actualBytes} of ${state.expectedBytes} bytes)."
                    is ModelState.Corrupt -> "Checksum mismatch: the file is corrupt and will not be used."
                    ModelState.Missing -> "Not downloaded yet."
                },
            )
        }
    }

    /** Releases the Piper voice and the system engine. Called when the vault locks. */
    fun release() {
        stopSpeaking()
        runCatching { piperVoice?.close() }
        piperVoice = null
        runCatching { ttsRef?.shutdown() }
        ttsRef = null
        systemTts = null
    }

    companion object {
        private const val TAG = "SarothiVoice"
        const val SAMPLE_RATE = 16_000
        private const val FRAMES_PER_SECOND = 20
        private const val VOICE_THRESHOLD = 0.012f
        private const val TRAILING_SILENCE_MILLIS = 900L
        private const val MIN_SPEECH_MILLIS = 400L
        private const val MAX_LISTEN_SECONDS = 60
        private const val PLAYBACK_POLL_MILLIS = 40L
        private const val PLAYBACK_SLACK_MILLIS = 400L
        private const val MILLIS_PER_CHARACTER = 95L
        private const val TTS_INIT_TIMEOUT_MILLIS = 5000L

        const val PIPER_VOICE_ID = "tts.piper.bn_bd.medium"
        private const val VOICE_MODEL_ID = PIPER_VOICE_ID
        private const val VOICE_CONFIG_ID = "$PIPER_VOICE_ID.config"
    }
}
