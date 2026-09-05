package com.ngi.sarothi.plugins.voice

import com.google.gson.JsonObject
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginAvailability
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginContext
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.core.voice.ListenOutcome
import com.ngi.sarothi.core.voice.SpeakOutcome
import com.ngi.sarothi.plugins.common.textOrAsk

/** Speaks text aloud, on device. */
class SpeakPlugin : Plugin {
    override val name = "speak"
    override val description =
        "Say something aloud through the phone's speaker. Uses Sarothi's own Bengali Piper voice when " +
            "it is installed, otherwise the Android system text-to-speech engine. Everything is " +
            "generated on the device; no text is sent anywhere."
    override val category = PluginCategory.VOICE
    override val sensitivity = Sensitivity.NORMAL

    override val parameters = JsonSchema(
        properties = mapOf(
            "text" to JsonSchema.Property.Text("Exactly what to say."),
            "voice" to JsonSchema.Property.Text("A Piper voice id from voice_voices. Empty uses the default."),
        ),
        required = listOf("text"),
    )

    override val example = """{"text":"আপনার বিল ১২৪০ টাকা"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val availability = context.voice.ttsAvailability
        return if (availability.ready) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                reason = availability.reason ?: "Speech output is not available.",
                fixAction = availability.fix,
            )
        }
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val text = params.textOrAsk("text", "What should Sarothi say aloud?")
        val voice = params.stringOrNull("voice")?.takeIf { it.isNotBlank() }

        return when (val outcome = context.voice.speak(text, voice)) {
            is SpeakOutcome.Spoken -> PluginResult.Success(
                summaryForUser = "Said aloud with the ${outcome.engine} engine (${outcome.durationMillis} ms).",
                data = Json.obj {
                    addProperty("text", text)
                    addProperty("engine", outcome.engine)
                    addProperty("duration_millis", outcome.durationMillis)
                },
            )
            is SpeakOutcome.Failed -> PluginResult.Failure(
                summaryForUser = "Sarothi could not speak that" +
                    (outcome.engine?.let { " with $it" } ?: "") + ": ${outcome.reason}",
                errorClass = "SpeechFailedException",
                retriable = true,
                data = Json.obj {
                    outcome.engine?.let { addProperty("engine", it) }
                    addProperty("reason", outcome.reason)
                },
            )
            SpeakOutcome.Cancelled -> PluginResult.Failure(
                summaryForUser = "Speech was cancelled before it finished.",
                errorClass = "CancelledException",
                retriable = true,
            )
        }
    }
}

/** Stops speech that is in progress. */
class StopSpeakingPlugin : Plugin {
    override val name = "stop_speaking"
    override val description =
        "Stop Sarothi speaking immediately. Use it when the user says 'stop' or 'থামো' while audio is " +
            "playing."
    override val category = PluginCategory.VOICE
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(properties = emptyMap())

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val wasSpeaking = context.voice.isSpeaking
        context.voice.stopSpeaking()
        return PluginResult.Success(
            summaryForUser = if (wasSpeaking) "Stopped speaking." else "Sarothi was not speaking.",
            data = Json.obj { addProperty("was_speaking", wasSpeaking) },
        )
    }
}

/**
 * Listens through the microphone and transcribes with whisper.cpp.
 *
 * There is no cloud fallback and no `SpeechRecognizer` fallback: on many 3 GB
 * devices the only recognizer is a Google service that is not present, and routing
 * the user's voice to a cloud would break the on-device promise. When whisper.cpp
 * is not built or the model is missing, this reports unavailable.
 */
class ListenPlugin : Plugin {
    override val name = "listen"
    override val description =
        "Record from the microphone and transcribe it on device with whisper.cpp. Use it when the user " +
            "wants to dictate something — a note, a message, a search. Returns the text and the " +
            "language whisper detected, which is not always the one asked for."
    override val category = PluginCategory.VOICE
    override val sensitivity = Sensitivity.NORMAL
    override val requiredPermissions = listOf(android.Manifest.permission.RECORD_AUDIO)

    override val parameters = JsonSchema(
        properties = mapOf(
            "max_seconds" to JsonSchema.Property.Integer("Longest recording.", minimum = 2, maximum = 60, default = 15),
            "language" to JsonSchema.Property.Text("'bn' for Bengali, 'en' for English, 'auto' to detect.", enum = listOf("auto", "bn", "en"), default = "auto"),
            "purpose" to JsonSchema.Property.Text("What the recording is for, so the user knows why the microphone is on."),
        ),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val availability = context.voice.sttAvailability
        return if (availability.ready) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                reason = availability.reason ?: "Speech input is not available.",
                fixAction = availability.fix,
            )
        }
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val maxSeconds = params.get("max_seconds")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(2, 60) ?: 15
        val language = params.stringOrNull("language") ?: "auto"
        val purpose = params.stringOrNull("purpose")?.takeIf { it.isNotBlank() }

        return when (val outcome = context.voice.listen(maxSeconds = maxSeconds, language = language)) {
            is ListenOutcome.Heard -> PluginResult.Success(
                summaryForUser = "Heard (${outcome.language ?: "unknown language"}, " +
                    "${"%.1f".format(outcome.secondsOfAudio)}s of speech): \"${outcome.text}\"",
                data = Json.obj {
                    addProperty("text", outcome.text)
                    addProperty("detected_language", outcome.language ?: "unknown")
                    addProperty("requested_language", language)
                    addProperty("seconds_of_audio", outcome.secondsOfAudio)
                    addProperty("duration_millis", outcome.durationMillis)
                    purpose?.let { addProperty("purpose", it) }
                    if (outcome.language != null && language != "auto" && outcome.language != language) {
                        addProperty(
                            "language_mismatch",
                            "Asked for $language but whisper detected ${outcome.language}; the text may be wrong.",
                        )
                    }
                },
                spoken = null,
                memorable = if (purpose != null) listOf("dictated ($purpose): ${outcome.text}") else emptyList(),
            )
            is ListenOutcome.Failed -> {
                if (outcome.permissionMissing) {
                    return PluginResult.Unavailable(
                        PluginAvailability.unavailable(
                            reason = "Sarothi does not have microphone permission, so it cannot listen.",
                            fixAction = "Grant the Microphone permission (permission_guard with open=microphone).",
                        ),
                    )
                }
                PluginResult.Failure(
                    summaryForUser = "Listening failed: ${outcome.reason}",
                    errorClass = "ListenFailedException",
                    retriable = true,
                )
            }
            ListenOutcome.Cancelled -> PluginResult.Failure(
                summaryForUser = "Listening was cancelled.",
                errorClass = "CancelledException",
                retriable = true,
            )
            ListenOutcome.NothingHeard -> PluginResult.Failure(
                summaryForUser = "The microphone was open for $maxSeconds second(s) but nothing above " +
                    "the background noise was said. Sarothi will not invent a transcription.",
                errorClass = "NothingHeardException",
                retriable = true,
            )
        }
    }
}

/** Dictates a note straight into the vault. */
class VoiceNotePlugin : Plugin {
    override val name = "voice_note"
    override val description =
        "Record a note by voice and save it: listens with whisper.cpp, then stores the transcript as a " +
            "note in Sarothi's encrypted vault. Use it when the user says 'take a voice note'."
    override val category = PluginCategory.VOICE
    override val sensitivity = Sensitivity.NORMAL
    override val requiredPermissions = listOf(android.Manifest.permission.RECORD_AUDIO)

    override val parameters = JsonSchema(
        properties = mapOf(
            "title" to JsonSchema.Property.Text("Note title. Empty derives one from the transcript."),
            "max_seconds" to JsonSchema.Property.Integer("Longest recording.", minimum = 2, maximum = 120, default = 30),
            "tags" to JsonSchema.Property.List("Tags for later searching.", items = JsonSchema.Property.Text("One tag")),
        ),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability {
        val stt = context.voice.sttAvailability
        if (!stt.ready) {
            return PluginAvailability.unavailable(stt.reason ?: "Speech input is not available.", stt.fix)
        }
        if (!context.vault.isUnlocked) {
            return PluginAvailability.unavailable(
                reason = "Notes live in the encrypted vault, which is locked.",
                fixAction = "Ask the user to unlock Sarothi's vault.",
            )
        }
        return PluginAvailability.READY
    }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val maxSeconds = params.get("max_seconds")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(2, 120) ?: 30
        val title = params.stringOrNull("title")?.takeIf { it.isNotBlank() }
        val tags = (params.getAsJsonArray("tags")?.mapNotNull {
            if (it.isJsonPrimitive) it.asString.trim().takeIf { tag -> tag.isNotEmpty() } else null
        } ?: emptyList()) + "voice-note"

        val heard = when (val outcome = context.voice.listen(maxSeconds = maxSeconds, language = "auto")) {
            is ListenOutcome.Heard -> outcome
            is ListenOutcome.Failed -> return PluginResult.Failure(
                summaryForUser = "Sarothi could not listen: ${outcome.reason}",
                errorClass = "ListenFailedException",
                retriable = !outcome.permissionMissing,
            )
            ListenOutcome.Cancelled -> return PluginResult.Failure(
                "Recording was cancelled before anything was saved.", "CancelledException", retriable = true,
            )
            ListenOutcome.NothingHeard -> return PluginResult.Failure(
                summaryForUser = "Nothing was said, so no note was saved. Sarothi will not store an " +
                    "empty or invented note.",
                errorClass = "NothingHeardException",
                retriable = true,
            )
        }
        if (heard.text.isBlank()) {
            return PluginResult.Failure(
                summaryForUser = "whisper.cpp transcribed the recording as empty, so no note was saved.",
                errorClass = "EmptyTranscriptException",
                retriable = true,
            )
        }

        val derivedTitle = title ?: heard.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(48)
            ?: heard.text.take(48)
        val note = runCatching {
            context.stores.notes.create(derivedTitle, heard.text, tags.distinct())
        }.getOrElse { failure ->
            return PluginResult.Failure(
                summaryForUser = "The recording was transcribed but could not be saved: ${failure.message}",
                errorClass = failure.javaClass.simpleName,
                retriable = true,
                data = Json.obj { addProperty("transcript", heard.text) },
            )
        }

        return PluginResult.Success(
            summaryForUser = "Saved a voice note \"${note.title}\" (${heard.text.length} characters, " +
                "language ${heard.language ?: "unknown"}).",
            data = Json.obj {
                addProperty("id", note.id)
                addProperty("title", note.title)
                addProperty("text", note.body)
                addProperty("detected_language", heard.language ?: "unknown")
                addProperty("seconds_of_audio", heard.secondsOfAudio)
                add("tags", Json.arr { note.tags.forEach { add(it) } })
            },
            spoken = "ভয়েস নোট সেভ করেছি।",
            undoToken = note.id,
            memorable = listOf("voice note: ${note.title}"),
        )
    }

    override suspend fun undo(undoToken: String): PluginResult {
        val context = pluginContext()
        return if (context.stores.notes.delete(undoToken)) {
            PluginResult.Success("Deleted that voice note again.", Json.obj { addProperty("deleted", undoToken) })
        } else {
            PluginResult.Failure("That note is already gone.", "NotFoundException", retriable = false)
        }
    }
}

/** Lists the installed speech voices. */
class VoiceVoicesPlugin : Plugin {
    override val name = "voice_voices"
    override val description =
        "List the speech voices installed in Sarothi's vault and which engine is actually in use. Use " +
            "it when the user asks why Sarothi sounds the way it does, or wants a different voice."
    override val category = PluginCategory.VOICE
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(properties = emptyMap())

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val voices = context.voice.availableVoices()
        val tts = context.voice.ttsAvailability
        val stt = context.voice.sttAvailability

        val data = Json.obj {
            add("voices", Json.arr {
                voices.forEach { voice ->
                    add(Json.obj {
                        addProperty("id", voice.id)
                        addProperty("name", voice.displayName)
                        addProperty("language", voice.languageCode ?: "unknown")
                        addProperty("installed", voice.installed)
                        addProperty("detail", voice.detail)
                    })
                }
            })
            addProperty("count", voices.size)
            add("speech_output", Json.obj {
                addProperty("ready", tts.ready)
                tts.reason?.let { addProperty("reason", it) }
                tts.fix?.let { addProperty("fix", it) }
            })
            add("speech_input", Json.obj {
                addProperty("ready", stt.ready)
                stt.reason?.let { addProperty("reason", it) }
                stt.fix?.let { addProperty("fix", it) }
            })
        }
        return PluginResult.Success(
            summaryForUser = buildString {
                append(voices.count { it.installed }).append(" installed voice(s)")
                if (voices.isNotEmpty()) {
                    append(": ").append(voices.filter { it.installed }.joinToString { it.displayName })
                }
                append(". Speech output: ").append(if (tts.ready) "ready" else tts.reason ?: "unavailable")
                append(". Speech input: ").append(if (stt.ready) "ready" else stt.reason ?: "unavailable")
                append('.')
            },
            data = data,
        )
    }
}
