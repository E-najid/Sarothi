package com.ngi.sarothi.core.persona

import com.google.gson.JsonObject
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull

/**
 * Languages Sarothi speaks to the user. Bengali is the default because that is
 * the audience this agent is built for; English is the fallback for models whose
 * Bengali output is unreliable.
 */
enum class SarothiLanguage(val code: String, val displayName: String, val nativeName: String) {
    BENGALI("bn", "Bengali", "বাংলা"),
    ENGLISH("en", "English", "English"),
    BANGLA_IN_ENGLISH("bn-Latn", "Bangla in English script", "Banglish");

    companion object {
        val DEFAULT = BENGALI

        fun fromCode(code: String?): SarothiLanguage? =
            code?.let { value -> entries.firstOrNull { it.code.equals(value, true) } }
    }
}

/** How formal the assistant sounds. Affects the system prompt only, never facts. */
enum class Formality(val displayName: String) {
    FAMILIAR("তুই/তুমি — friendly"),
    POLITE("আপনি — formal"),
    NEUTRAL("Neutral"),
}

/**
 * The assistant's character.
 *
 * Everything here is user-editable and stored (encrypted) in the vault. It is
 * injected into the system prompt verbatim — Sarothi never lets persona text
 * override a safety instruction, so the prompt template puts persona *after* the
 * non-negotiable rules and the rules are restated at the end.
 */
data class Persona(
    val name: String,
    val language: SarothiLanguage,
    val formality: Formality,
    val tone: String,
    val verbosity: Verbosity,
    /** Extra standing instructions from the user, e.g. "always mention prices in BDT". */
    val customInstructions: String,
    val speakRepliesAloud: Boolean,
    val voiceId: String?,
) {
    enum class Verbosity { TERSE, NORMAL, DETAILED }

    /** Renders the persona block of the system prompt. */
    fun toPromptBlock(): String = buildString {
        append("You are ").append(name).append(".\n")
        append("Reply in ").append(language.displayName).append(" (").append(language.nativeName).append(")")
        if (language == SarothiLanguage.BANGLA_IN_ENGLISH) append(" written in Latin script")
        append(".\n")
        append("Address the user ").append(
            when (formality) {
                Formality.FAMILIAR -> "with তুমি"
                Formality.POLITE -> "with আপনি"
                Formality.NEUTRAL -> "neutrally"
            },
        ).append(".\n")
        append("Tone: ").append(tone).append(".\n")
        append(
            when (verbosity) {
                Verbosity.TERSE -> "Be extremely brief: at most two short sentences.\n"
                Verbosity.NORMAL -> "Be concise: a few sentences, no filler.\n"
                Verbosity.DETAILED -> "Explain your reasoning and next steps in a short paragraph.\n"
            },
        )
        if (customInstructions.isNotBlank()) {
            append("Standing instructions from the user:\n").append(customInstructions.trim()).append('\n')
        }
    }

    fun toJson(): JsonObject = Json.obj {
        addProperty("name", name)
        addProperty("language", language.code)
        addProperty("formality", formality.name.lowercase())
        addProperty("tone", tone)
        addProperty("verbosity", verbosity.name.lowercase())
        addProperty("custom_instructions", customInstructions)
        addProperty("speak_replies", speakRepliesAloud)
        voiceId?.let { addProperty("voice_id", it) }
    }

    companion object {
        val DEFAULT = Persona(
            name = "সারথি",
            language = SarothiLanguage.BENGALI,
            formality = Formality.POLITE,
            tone = "warm, practical, and direct; never sycophantic",
            verbosity = Verbosity.NORMAL,
            customInstructions = "",
            speakRepliesAloud = false,
            voiceId = null,
        )

        fun fromJson(json: JsonObject): Persona = Persona(
            name = json.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: DEFAULT.name,
            language = SarothiLanguage.fromCode(json.stringOrNull("language")) ?: DEFAULT.language,
            formality = runCatching {
                Formality.valueOf((json.stringOrNull("formality") ?: DEFAULT.formality.name).uppercase())
            }.getOrDefault(DEFAULT.formality),
            tone = json.stringOrNull("tone")?.takeIf { it.isNotBlank() } ?: DEFAULT.tone,
            verbosity = runCatching {
                Verbosity.valueOf((json.stringOrNull("verbosity") ?: DEFAULT.verbosity.name).uppercase())
            }.getOrDefault(DEFAULT.verbosity),
            customInstructions = json.stringOrNull("custom_instructions") ?: "",
            speakRepliesAloud = json.get("speak_replies")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            voiceId = json.stringOrNull("voice_id"),
        )
    }
}
