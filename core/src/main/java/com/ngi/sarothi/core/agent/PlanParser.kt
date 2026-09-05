package com.ngi.sarothi.core.agent

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ngi.sarothi.core.util.Ids
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.JsonReply
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
        val json = JsonReply.extractElement(raw)?.let(::asDecisionObject)
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
                Json.parseObject(raw.asString)
            }.getOrDefault(JsonObject())
            else -> JsonObject()
        }
    }

    /**
     * Reads the extracted JSON as a decision object.
     *
     * A bare array of step objects is what a small model emits when it answers "list
     * the steps" literally. Treating it as a plan invents nothing -- every tool and
     * every argument in it still came from the model -- whereas ignoring it loses the
     * whole task: the array's first element on its own has no kind and no steps, so it
     * used to parse as an ANSWER carrying no text, and the agent had nothing to say.
     */
    private fun asDecisionObject(element: JsonElement): JsonObject? = when {
        element.isJsonObject -> element.asJsonObject
        element.isJsonArray && element.asJsonArray.size() > 0 &&
            element.asJsonArray.all { it.isJsonObject } -> JsonObject().apply {
            addProperty("kind", "plan")
            add("steps", element.asJsonArray)
        }
        // An array of primitives, a bare string, a number: nothing here describes a
        // decision, and guessing one is what this parser exists to avoid.
        else -> null
    }
}
