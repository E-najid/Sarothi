package com.ngi.sarothi.core.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.ngi.sarothi.core.util.Ids
import com.ngi.sarothi.core.util.arrayOrNull
import com.ngi.sarothi.core.util.objectOrNull
import com.ngi.sarothi.core.util.stringOrNull

/**
 * Turns orchestrator output into a [Plan].
 *
 * A 350 M model under a JSON grammar still produces fenced blocks, trailing
 * commas, `"args"` as a stringified object, and steps as bare strings. Each of
 * those is repaired here *losslessly* — the repair never adds information. What
 * is never done is inventing a step, a tool name or an argument: if the model did
 * not say it, the plan does not contain it, and the caller replans or asks.
 */
class PlanParser(private val knownTools: Set<String>) {

    fun parse(raw: String): PlanParseOutcome {
        val json = extractJsonObject(raw)
            ?: return PlanParseOutcome.Unparseable(
                raw = raw,
                reason = "The model's reply contained no JSON object. Sarothi will not act on " +
                    "prose it cannot parse into steps.",
            )

        val kind = when (json.stringOrNull("kind")?.lowercase()) {
            "plan", "tool", "tools", "act" -> DecisionKind.PLAN
            "answer", "reply", "chat" -> DecisionKind.ANSWER
            "ask", "ask_user", "need_info" -> DecisionKind.ASK_USER
            "refuse", "decline" -> DecisionKind.REFUSE
            else -> inferKind(json)
        }

        val thought = json.stringOrNull("thought")
            ?: json.stringOrNull("reasoning")
            ?: json.stringOrNull("thinking")
            ?: ""

        val assumptions = json.arrayOrNull("assumptions")?.mapNotNull { element ->
            when {
                element.isJsonPrimitive -> element.asString.takeIf { it.isNotBlank() }
                else -> null
            }
        } ?: emptyList()

        val answer = json.stringOrNull("answer")
            ?: json.stringOrNull("reply")
            ?: json.stringOrNull("message")

        val ask = json.objectOrNull("ask")?.let { askJson ->
            UserQuestionSpec(
                field = askJson.stringOrNull("field") ?: "",
                question = askJson.stringOrNull("question") ?: askJson.stringOrNull("text") ?: "",
                choices = askJson.arrayOrNull("choices")?.mapNotNull { element ->
                    if (element.isJsonPrimitive) element.asString else null
                } ?: emptyList(),
                secret = askJson.get("secret")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            )
        } ?: json.stringOrNull("ask")?.takeIf { it.isNotBlank() }?.let { text ->
            UserQuestionSpec(field = "", question = text, choices = emptyList(), secret = false)
        }

        val steps = parseSteps(json, kind)

        return when {
            kind == DecisionKind.ASK_USER && ask != null -> PlanParseOutcome.Parsed(
                Plan(kind, thought, emptyList(), answer, ask, assumptions, raw),
            )
            kind == DecisionKind.ASK_USER && ask == null -> PlanParseOutcome.Unparseable(
                raw,
                "The model said it needs to ask you something but did not say what. " +
                    "Sarothi will not guess a question.",
            )
            kind == DecisionKind.PLAN && steps.isEmpty() -> PlanParseOutcome.Unparseable(
                raw,
                "The model chose to act but produced no usable steps. Every step needs a known " +
                    "tool name; the tools available were: ${knownTools.sorted().joinToString()}.",
            )
            else -> PlanParseOutcome.Parsed(Plan(kind, thought, steps, answer, ask, assumptions, raw))
        }
    }

    private fun inferKind(json: JsonObject): DecisionKind = when {
        json.has("steps") || json.has("actions") || json.has("plan") -> DecisionKind.PLAN
        json.has("ask") -> DecisionKind.ASK_USER
        json.has("answer") || json.has("reply") -> DecisionKind.ANSWER
        else -> DecisionKind.ANSWER
    }

    private fun parseSteps(json: JsonObject, kind: DecisionKind): List<PlanStep> {
        if (kind != DecisionKind.PLAN) return emptyList()
        val array = json.arrayOrNull("steps")
            ?: json.arrayOrNull("actions")
            ?: json.arrayOrNull("plan")
            ?: return emptyList()

        val steps = mutableListOf<PlanStep>()
        array.forEach { element ->
            val stepJson = when {
                element.isJsonObject -> element.asJsonObject
                // A bare string step is a description with no tool: unusable, and
                // converting it by guessing a tool is exactly what must not happen.
                else -> return@forEach
            }
            val tool = stepJson.stringOrNull("tool")
                ?: stepJson.stringOrNull("plugin")
                ?: stepJson.stringOrNull("name")
                ?: stepJson.stringOrNull("action")
                ?: return@forEach

            val args = readArgs(stepJson)
            val intent = stepJson.stringOrNull("intent")
                ?: stepJson.stringOrNull("why")
                ?: stepJson.stringOrNull("description")
                ?: "$tool"

            steps += PlanStep(
                id = Ids.stepId(),
                index = steps.size,
                intent = intent.trim().ifBlank { tool },
                tool = tool.trim(),
                args = args,
                onFailure = when (stepJson.stringOrNull("on_failure")?.lowercase()) {
                    "skip", "continue" -> OnStepFailure.SKIP
                    "abort", "stop" -> OnStepFailure.ABORT
                    "ask", "ask_user" -> OnStepFailure.ASK_USER
                    else -> OnStepFailure.REPLAN
                },
            )
            // `index` above counts surviving steps, not raw array positions, so a
            // malformed entry that was skipped does not leave a gap in the checklist.
        }
        return steps
    }

    /**
     * `args` may be an object, a stringified object, or absent. A stringified
     * object is parsed; anything else becomes an empty object so schema validation
     * reports the missing required parameters rather than a type error.
     */
    private fun readArgs(stepJson: JsonObject): JsonObject {
        val raw = stepJson.get("args") ?: stepJson.get("arguments") ?: stepJson.get("params")
            ?: stepJson.get("parameters") ?: stepJson.get("input")
        return when {
            raw == null || raw.isJsonNull -> JsonObject()
            raw.isJsonObject -> raw.asJsonObject
            raw.isJsonPrimitive && raw.asJsonPrimitive.isString -> runCatching {
                JsonParser.parseString(raw.asString).asJsonObject
            }.getOrDefault(JsonObject())
            else -> JsonObject()
        }
    }

    /**
     * Extracts the first balanced `{...}` from a reply.
     *
     * Handles markdown fences, leading prose and trailing commentary. Tries the
     * raw slice first and, if that fails, retries once with trailing commas
     * removed — the single most common small-model JSON error, and one whose fix
     * cannot change meaning.
     */
    internal fun extractJsonObject(raw: String): JsonObject? {
        val cleaned = stripFences(raw)
        val candidates = balancedObjects(cleaned)
        for (candidate in candidates) {
            parseOrNull(candidate)?.let { return it }
            parseOrNull(removeTrailingCommas(candidate))?.let { return it }
        }
        return null
    }

    private fun parseOrNull(text: String): JsonObject? = try {
        val element = JsonParser.parseString(text)
        if (element.isJsonObject) element.asJsonObject else null
    } catch (_: JsonSyntaxException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun stripFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val withoutOpen = trimmed.removePrefix("```")
        val afterLanguage = withoutOpen.indexOf('\n').let { if (it < 0) withoutOpen else withoutOpen.substring(it + 1) }
        return afterLanguage.substringBeforeLast("```").trim()
    }

    /** All top-level balanced objects, outermost first at each start position. */
    private fun balancedObjects(text: String): List<String> {
        val found = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            if (text[index] != '{') {
                index++
                continue
            }
            var depth = 0
            var inString = false
            var escaped = false
            var end = -1
            for (position in index until text.length) {
                val char = text[position]
                when {
                    escaped -> escaped = false
                    char == '\\' && inString -> escaped = true
                    char == '"' -> inString = !inString
                    inString -> Unit
                    char == '{' -> depth++
                    char == '}' -> {
                        depth--
                        if (depth == 0) {
                            end = position
                            break
                        }
                    }
                }
            }
            if (end < 0) break
            found += text.substring(index, end + 1)
            index = end + 1
        }
        return found
    }

    private fun removeTrailingCommas(text: String): String {
        val out = StringBuilder(text.length)
        var inString = false
        var escaped = false
        for (position in text.indices) {
            val char = text[position]
            if (escaped) {
                out.append(char)
                escaped = false
                continue
            }
            if (char == '\\' && inString) {
                out.append(char)
                escaped = true
                continue
            }
            if (char == '"') {
                inString = !inString
                out.append(char)
                continue
            }
            if (char == ',' && !inString) {
                val next = text.substring(position + 1).trimStart().firstOrNull()
                if (next == '}' || next == ']') continue
            }
            out.append(char)
        }
        return out.toString()
    }
}
