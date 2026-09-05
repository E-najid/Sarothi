package com.ngi.sarothi.core.agent

import com.ngi.sarothi.core.data.ChatMessage
import com.ngi.sarothi.core.data.ChatRole
import com.ngi.sarothi.core.data.MemoryMatch
import com.ngi.sarothi.core.data.UserFact
import com.ngi.sarothi.core.persona.Persona
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.ToolDescriptor

/**
 * Builds the orchestrator's prompt.
 *
 * Written for a 350 M model, so three things dominate the design:
 *
 *  1. **One output shape.** A single JSON object with a `kind` discriminator.
 *     Branching output formats are where small models fail most often.
 *  2. **A hard character budget.** Everything optional (memories, facts, screen
 *     state, examples) is truncated to fit [budgetChars] with an explicit
 *     `…omitted` marker. Silently overflowing the context window would push the
 *     system prompt out of the model's view and it would stop following the rules.
 *  3. **The safety rules are stated twice** — at the top and again at the very end
 *     of the system prompt — because a persona block in between is exactly the kind
 *     of text a small model latches onto.
 */
class AgentPromptBuilder(
    private val budgetChars: Int = DEFAULT_BUDGET_CHARS,
) {

    fun systemPrompt(
        persona: Persona,
        tools: List<ToolDescriptor>,
        memories: List<MemoryMatch>,
        userFacts: Map<String, UserFact>,
        device: DeviceBrief,
        screen: String?,
    ): String {
        val out = StringBuilder(budgetChars)

        out.append(HARD_RULES)

        out.append("\n--- OUTPUT FORMAT ---\n")
        out.append(OUTPUT_FORMAT)

        out.append("\n--- DEVICE ---\n").append(device.toPromptBlock())

        val toolBudget = (budgetChars * TOOL_BUDGET_FRACTION).toInt()
        out.append("\n--- TOOLS ---\n").append(renderTools(tools, toolBudget))

        if (userFacts.isNotEmpty()) {
            out.append("\n--- USER FACTS (the user told Sarothi these; use them, do not re-ask) ---\n")
            out.append(renderFacts(userFacts, (budgetChars * FACTS_BUDGET_FRACTION).toInt()))
        }

        if (memories.isNotEmpty()) {
            out.append("\n--- RELEVANT MEMORIES ---\n")
            out.append(renderMemories(memories, (budgetChars * MEMORY_BUDGET_FRACTION).toInt()))
        }

        if (!screen.isNullOrBlank()) {
            out.append("\n--- CURRENT SCREEN ---\n")
            out.append(truncate(screen, (budgetChars * SCREEN_BUDGET_FRACTION).toInt()))
        }

        out.append("\n--- PERSONA ---\n").append(persona.toPromptBlock())

        out.append("\n--- EXAMPLES ---\n").append(EXAMPLES)

        out.append("\n--- RESTATED RULES ---\n").append(HARD_RULES_TAIL)

        return truncateToBudget(out.toString())
    }

    /**
     * The prompt used for the final "report what happened" call.
     *
     * Deliberately not the planning prompt: no tool list, no screen, no memories.
     * Giving the model tools at this point invites it to plan more work when the
     * task is already over, and it wastes context the summary needs for the reply.
     */
    fun summarySystemPrompt(persona: Persona, device: DeviceBrief): String = buildString {
        append("You are Sarothi, an on-device assistant. The task is finished.\n")
        append("Report what actually happened, in the user's language, honestly:\n")
        append("- Say what was done. Do not claim anything that did not happen.\n")
        append("- If a step failed or was skipped, say which and why.\n")
        append("- Keep it to at most three short sentences.\n")
        append("Reply with ONE JSON object: {\"kind\":\"answer\",\"answer\":\"<your reply>\"}\n")
        append("No markdown fences, no prose outside the JSON.\n\n")
        append("--- DEVICE ---\n").append(device.toPromptBlock()).append('\n')
        append("--- PERSONA ---\n").append(persona.toPromptBlock())
    }

    /** The user turn: prior conversation, then the request. */
    fun userTurn(request: String, tail: List<ChatMessage>, maxHistoryChars: Int): String = buildString {
        if (tail.isNotEmpty()) {
            append("EARLIER IN THIS CONVERSATION:\n")
            append(truncate(renderHistory(tail), maxHistoryChars)).append("\n\n")
        }
        append("USER REQUEST:\n").append(request.trim()).append('\n')
    }

    /**
     * The turn used after a step failed or a plan was unusable.
     *
     * It states exactly what happened and what has already been done, so the model
     * replans against reality instead of repeating the same failing step.
     */
    fun replanTurn(
        request: String,
        executed: List<StepOutcomeLine>,
        failure: String,
        remainingBudget: Int,
    ): String = buildString {
        append("The previous plan did not work. Replan.\n\n")
        append("ORIGINAL REQUEST:\n").append(request.trim()).append("\n\n")
        if (executed.isNotEmpty()) {
            append("ALREADY DONE (do not repeat these):\n")
            executed.forEach { append("- ").append(it.render()).append('\n') }
            append('\n')
        }
        append("WHAT WENT WRONG:\n").append(failure.trim()).append("\n\n")
        append("You may use at most ").append(remainingBudget).append(" more step(s).\n")
        append("If the failure is something only the user can fix, reply with kind=ask_user.\n")
        append("If the task is already done, reply with kind=answer and say so.\n")
    }

    /** One line per executed step, for the replan turn. */
    data class StepOutcomeLine(
        val intent: String,
        val tool: String,
        val ok: Boolean,
        val detail: String,
    ) {
        fun render(): String = "${if (ok) "OK" else "FAILED"} [$tool] $intent — ${detail.take(180)}"
    }

    private fun renderTools(tools: List<ToolDescriptor>, budget: Int): String {
        if (tools.isEmpty()) {
            return "(no tools are available; reply with kind=answer and explain that Sarothi " +
                "cannot act on the phone right now)"
        }
        val grouped = tools.groupBy { it.category }
        val out = StringBuilder()
        for ((category, categoryTools) in grouped.toSortedMap(compareBy { it.ordinal })) {
            out.append(category.displayName).append(":\n")
            for (tool in categoryTools) {
                val line = tool.toCatalogueLine()
                out.append("  ").append(line.replace("\n", "\n  ")).append('\n')
            }
        }
        val rendered = truncate(out.toString(), budget)
        return rendered + "\nUse ONLY these tool names. Only these argument names. " +
            "Never plan a tool marked UNAVAILABLE.\n"
    }

    private fun renderFacts(facts: Map<String, UserFact>, budget: Int): String {
        val lines = facts.values.sortedBy { it.key }.map { fact ->
            // Secret facts are named but never printed into the prompt: a value the
            // model can see can end up in a log line or a reply.
            val value = if (fact.secret) "<hidden: use the ${fact.key} tool parameter to request it>" else fact.value
            val provenance = if (fact.confirmedByUser) "" else " (UNCONFIRMED — verify before use)"
            "- ${fact.label}: $value$provenance"
        }
        return truncate(lines.joinToString("\n"), budget)
    }

    private fun renderMemories(memories: List<MemoryMatch>, budget: Int): String =
        truncate(
            memories.joinToString("\n") { match ->
                "- [${match.memory.kind.name.lowercase()}] ${match.memory.text}"
            },
            budget,
        )

    private fun renderHistory(tail: List<ChatMessage>): String = tail.joinToString("\n") { message ->
        val role = when (message.role) {
            ChatRole.USER -> "user"
            ChatRole.ASSISTANT -> "you"
            ChatRole.SYSTEM -> "system"
            ChatRole.TOOL -> "tool(${message.toolName ?: "?"})"
        }
        "$role: ${message.content.lineSequence().joinToString(" ").take(300)}"
    }

    private fun truncate(text: String, limit: Int): String {
        if (limit <= 0) return ""
        if (text.length <= limit) return text
        val marker = "\n…(${text.length - limit + 60} characters omitted to fit the model's context)"
        val keep = (limit - marker.length).coerceAtLeast(0)
        return text.take(keep) + marker
    }

    private fun truncateToBudget(text: String): String = truncate(text, budgetChars)

    companion object {
        /**
         * Roughly 2 048 tokens at the ~2.5 characters/token ratio Bengali+Latin
         * mixed text actually achieves. The value is a ceiling, not a target: a
         * short request gets a short prompt.
         */
        const val DEFAULT_BUDGET_CHARS = 5200
        private const val TOOL_BUDGET_FRACTION = 0.42
        private const val MEMORY_BUDGET_FRACTION = 0.12
        private const val FACTS_BUDGET_FRACTION = 0.10
        private const val SCREEN_BUDGET_FRACTION = 0.22

        private const val HARD_RULES = """You are Sarothi, an on-device assistant that operates an Android phone.
Rules you must never break:
1. NEVER invent personal data: no phone numbers, addresses, names, email, amounts,
   account numbers, dates of birth or OTP codes. If a value is not in USER FACTS,
   not in the request, and not on screen, reply with kind=ask_user.
2. Use only tools from the TOOLS list, with only the argument names shown.
3. Prefer the fewest steps that achieve the request. Maximum 8 steps per plan.
4. Never plan a step for a tool marked UNAVAILABLE.
5. Tools marked [needs your OK] will pause for the user's confirmation. That is
   expected; plan them normally.
6. Reply with ONE JSON object. No prose outside it. No markdown fences.
7. Do not claim an action succeeded before its step has run."""

        private const val HARD_RULES_TAIL = """Remember: one JSON object; only listed tools; never invent personal data
(use kind=ask_user instead); at most 8 steps; no markdown fences."""

        private const val OUTPUT_FORMAT = """{
 "kind": "plan" | "answer" | "ask_user" | "refuse",
 "thought": "<at most 12 words, why>",
 "steps": [ {"tool":"<tool name>","args":{...},"intent":"<what the user sees>","on_failure":"replan|skip|abort"} ],
 "answer": "<reply text, only when kind is answer or refuse>",
 "ask": {"field":"<arg name you need>","question":"<ask the user>","choices":["<optional>"],"secret":false},
 "assumptions": ["<anything you had to assume>"]
}
Include only the keys your kind needs. "steps" only with kind=plan."""

        private const val EXAMPLES = """Example 1 - user: "রিনাকে একটা মেসেজ পাঠাও যে আমি ১০ মিনিট দেরিতে পৌঁছাবো"
{"kind":"plan","thought":"Send an SMS to Rina","steps":[{"tool":"send_sms","args":{"recipient":"রিনা","message":"আমি ১০ মিনিট দেরিতে পৌঁছাবো"},"intent":"রিনাকে এসএমএস পাঠানো","on_failure":"abort"}],"assumptions":["রিনা কন্ট্যাক্টে আছে"]}

Example 2 - user: "আমার বিলটা দিয়ে দাও"
{"kind":"ask_user","thought":"No amount or biller given","ask":{"field":"amount","question":"কত টাকা বিল দিতে হবে, আর কোন বিল (বিদ্যুৎ/গ্যাস/ইন্টারনেট)?","choices":[],"secret":false}}

Example 3 - user: "৮০০ এর ১২% কত?"
{"kind":"answer","thought":"Arithmetic, no tool needed","answer":"৮০০ এর ১২% হলো ৯৬।"}

Example 4 - user: "স্ক্রিনে যা আছে পড়ে আমাকে বলো"
{"kind":"plan","thought":"Read the screen","steps":[{"tool":"read_screen","args":{},"intent":"স্ক্রিন পড়া","on_failure":"abort"},{"tool":"summarize_screen","args":{},"intent":"সারসংক্ষেপ","on_failure":"skip"}]}"""

        /** Scales the prompt budget to a model's real context window. */
        fun budgetFor(contextTokens: Int): Int = (contextTokens * PROMPT_TOKEN_FRACTION * CHARS_PER_TOKEN).toInt()
            .coerceIn(1200, 12_000)

        private const val PROMPT_TOKEN_FRACTION = 0.55
        private const val CHARS_PER_TOKEN = 2.5
    }
}
