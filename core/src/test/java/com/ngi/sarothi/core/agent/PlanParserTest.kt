package com.ngi.sarothi.core.agent

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning orchestrator output into something Sarothi will act on.
 *
 * This is the seam where a 350M model's habits meet the executor's expectations, and
 * the rule it has to hold is narrow: repair what cannot change meaning, refuse what
 * would require a guess. A repair that quietly adds a tool, an argument or a step is
 * worse than a refusal, because the user sees a checklist they never asked for and has
 * no way to know which part of it came from the model.
 */
class PlanParserTest {

    private val parser = PlanParser(setOf("web_search", "sms_send", "gmail_send", "calendar_add"))

    private fun PlanParseOutcome.requirePlan(): Plan = when (this) {
        is PlanParseOutcome.Parsed -> plan
        is PlanParseOutcome.Unparseable ->
            throw AssertionError("expected a usable plan, was refused: $reason")
    }

    private fun PlanParseOutcome.requireRefusal(): String = when (this) {
        is PlanParseOutcome.Unparseable -> reason
        is PlanParseOutcome.Parsed -> throw AssertionError(
            "expected a refusal, but this was accepted as a plan: kind=${plan.kind} " +
                "steps=${plan.steps.map { it.tool }}",
        )
    }

    private fun planJson(steps: String, extra: String = ""): String =
        """{"kind":"plan"$extra,"steps":[$steps]}"""

    private fun step(tool: String, args: String = "", intent: String = ""): String {
        val parts = mutableListOf("\"tool\":\"$tool\"")
        if (args.isNotEmpty()) parts += args
        if (intent.isNotEmpty()) parts += "\"intent\":\"$intent\""
        return "{${parts.joinToString()}}"
    }

    // ------------------------------------------------------------------ kind

    @Test
    fun a_plan_with_usable_steps_is_accepted() {
        val plan = parser.parse(
            planJson(step("web_search", "\"args\":{\"query\":\"weather in Sylhet\"}", "look up the weather")),
        ).requirePlan()

        assertEquals(DecisionKind.PLAN, plan.kind)
        assertEquals(1, plan.steps.size)
        assertEquals("web_search", plan.steps[0].tool)
        assertEquals("look up the weather", plan.steps[0].intent)
        assertEquals("weather in Sylhet", plan.steps[0].args.get("query").asString)
        assertTrue("a plan is actionable only with steps", plan.isActionable)
    }

    @Test
    fun an_answer_carries_its_text_and_no_steps() {
        val plan = parser.parse("""{"kind":"answer","answer":"It is 31 degrees in Sylhet."}""").requirePlan()
        assertEquals(DecisionKind.ANSWER, plan.kind)
        assertEquals("It is 31 degrees in Sylhet.", plan.answer)
        assertTrue("an answer must not smuggle in actions", plan.steps.isEmpty())
    }

    @Test
    fun every_kind_synonym_the_prompt_allows_is_understood() {
        for (word in listOf("plan", "tool", "tools", "act")) {
            val plan = parser.parse("""{"kind":"$word","steps":[${step("web_search")}]}""").requirePlan()
            assertEquals("kind=$word", DecisionKind.PLAN, plan.kind)
        }
        for (word in listOf("answer", "reply", "chat")) {
            val plan = parser.parse("""{"kind":"$word","answer":"here you go"}""").requirePlan()
            assertEquals("kind=$word", DecisionKind.ANSWER, plan.kind)
        }
        for (word in listOf("refuse", "decline")) {
            val plan = parser.parse("""{"kind":"$word","answer":"I will not do that."}""").requirePlan()
            assertEquals("kind=$word", DecisionKind.REFUSE, plan.kind)
        }
    }

    /** With no `kind` at all, the shape of the reply decides -- but nothing is invented. */
    @Test
    fun a_missing_kind_is_inferred_from_what_the_reply_contains() {
        assertEquals(
            DecisionKind.PLAN,
            parser.parse("""{"steps":[${step("web_search")}]}""").requirePlan().kind,
        )
        assertEquals(
            DecisionKind.ASK_USER,
            parser.parse("""{"ask":{"question":"Which contact?"}}""").requirePlan().kind,
        )
        assertEquals(
            DecisionKind.ANSWER,
            parser.parse("""{"answer":"done"}""").requirePlan().kind,
        )
    }

    // ------------------------------------------------------------------ asking

    @Test
    fun a_question_is_read_out_of_the_ask_object() {
        val plan = parser.parse(
            """{"kind":"ask","ask":{"field":"recipient","question":"Who should I message?",""" +
                """"choices":["Rina","Shirin"],"secret":false}}""",
        ).requirePlan()

        assertEquals(DecisionKind.ASK_USER, plan.kind)
        val ask = assertNotNull2(plan.ask)
        assertEquals("recipient", ask.field)
        assertEquals("Who should I message?", ask.question)
        assertEquals(listOf("Rina", "Shirin"), ask.choices)
        assertEquals("a secret field must not be echoed into the transcript", false, ask.secret)
        assertTrue("asking is not acting", plan.steps.isEmpty())
    }

    /**
     * The refusal that protects the user from an invented question. "The model wants to
     * ask you something" with no content would otherwise be presented as a blank prompt.
     */
    @Test
    fun saying_it_needs_to_ask_without_saying_what_is_refused_not_guessed() {
        val reason = parser.parse("""{"kind":"ask"}""").requireRefusal()
        assertTrue(reason, reason.contains("did not say what"))
        assertTrue("the refusal is shown to the user, so it must be a sentence", reason.length > 30)
    }

    @Test
    fun an_ask_given_as_a_bare_string_becomes_a_question_with_no_field() {
        val plan = parser.parse("""{"kind":"ask","ask":"Which day works for you?"}""").requirePlan()
        val ask = assertNotNull2(plan.ask)
        assertEquals("Which day works for you?", ask.question)
        assertEquals("", ask.field)
        assertTrue(ask.choices.isEmpty())
    }

    // ------------------------------------------------------------------ refusing

    @Test
    fun a_reply_with_no_json_is_refused_with_a_reason_the_user_can_read() {
        val reason = parser.parse("I am not able to help with that request.").requireRefusal()
        assertTrue(reason, reason.contains("no JSON object"))
    }

    /**
     * A plan that chose to act but produced nothing actionable must not be passed on as
     * an empty plan: the executor would report success having done nothing.
     */
    @Test
    fun a_plan_with_no_usable_steps_is_refused_rather_than_run_empty() {
        val reason = parser.parse("""{"kind":"plan","steps":[]}""").requireRefusal()
        assertTrue(reason, reason.contains("no usable steps"))
        assertTrue("the refusal names the tools that do exist", reason.contains("web_search"))
    }

    @Test
    fun steps_that_are_bare_strings_are_dropped_because_a_description_is_not_a_tool() {
        val reason = parser.parse(
            """{"kind":"plan","steps":["send a message to Rina","then check the weather"]}""",
        ).requireRefusal()
        assertTrue(
            "converting prose into a step would mean choosing the tool ourselves: $reason",
            reason.contains("no usable steps"),
        )
    }

    @Test
    fun a_step_with_no_tool_name_is_dropped_and_the_ones_after_it_keep_their_order() {
        val plan = parser.parse(
            planJson(
                """{"intent":"no tool here"},""" + step("web_search", intent = "first real step") + "," +
                    step("sms_send", intent = "second real step"),
            ),
        ).requirePlan()

        assertEquals(2, plan.steps.size)
        assertEquals(
            "a dropped entry must not leave a gap in the checklist the user reads",
            listOf(0, 1),
            plan.steps.map { it.index },
        )
        assertEquals(listOf("web_search", "sms_send"), plan.steps.map { it.tool })
    }

    // ------------------------------------------------------------------ repairing

    /** A bare array of steps is the shape a model reaches for when told to "list the steps". */
    @Test
    fun a_bare_array_of_steps_is_read_as_a_plan_instead_of_losing_the_task() {
        val plan = parser.parse(
            """[${step("web_search", intent = "look it up")},${step("sms_send", intent = "tell Rina")}]""",
        ).requirePlan()

        assertEquals(DecisionKind.PLAN, plan.kind)
        assertEquals(
            "both steps, not just the first: dropping the rest is what used to happen",
            listOf("web_search", "sms_send"),
            plan.steps.map { it.tool },
        )
        assertEquals(listOf(0, 1), plan.steps.map { it.index })
    }

    @Test
    fun an_array_that_is_not_steps_is_still_refused() {
        val reason = parser.parse("""["web_search","sms_send"]""").requireRefusal()
        assertTrue(
            "tool names alone are not a plan, and picking arguments for them would be invention: $reason",
            reason.contains("no JSON object") || reason.contains("no usable steps"),
        )
    }

    @Test
    fun a_fenced_plan_surrounded_by_prose_is_accepted() {
        val raw = "Here is what I will do:\n```json\n" +
            planJson(step("calendar_add", "\"args\":{\"title\":\"standup\"}")) +
            "\n```\nSay the word and I will start."
        val plan = parser.parse(raw).requirePlan()
        assertEquals(DecisionKind.PLAN, plan.kind)
        assertEquals("standup", plan.steps[0].args.get("title").asString)
    }

    @Test
    fun an_unclosed_brace_in_the_model_s_prose_does_not_lose_the_plan() {
        val plan = parser.parse(
            "I will use the {tool you named.\n" + planJson(step("web_search")),
        ).requirePlan()
        assertEquals(1, plan.steps.size)
    }

    @Test
    fun trailing_commas_are_repaired_because_removing_them_cannot_change_meaning() {
        val plan = parser.parse(
            """{"kind":"plan","steps":[${step("web_search")},],}""",
        ).requirePlan()
        assertEquals(1, plan.steps.size)
    }

    // ------------------------------------------------------------------ arguments

    @Test
    fun every_argument_spelling_the_model_might_use_is_read() {
        for (key in listOf("args", "arguments", "params", "parameters", "input")) {
            val plan = parser.parse(
                planJson(step("web_search", "\"$key\":{\"query\":\"tide times\"}")),
            ).requirePlan()
            assertEquals(
                "the arguments under '$key' were not read",
                "tide times",
                plan.steps[0].args.get("query").asString,
            )
        }
    }

    /** Small models stringify nested objects under a grammar constraint. */
    @Test
    fun arguments_arriving_as_a_stringified_object_are_parsed() {
        val plan = parser.parse(
            """{"kind":"plan","steps":[{"tool":"web_search","args":"{\"query\":\"tide times\"}"}]}""",
        ).requirePlan()
        assertEquals("tide times", plan.steps[0].args.get("query").asString)
    }

    @Test
    fun arguments_that_are_not_an_object_become_empty_so_validation_reports_the_gap() {
        val plan = parser.parse(
            """{"kind":"plan","steps":[{"tool":"web_search","args":"not json at all"}]}""",
        ).requirePlan()
        assertEquals(
            "an empty object makes the schema report missing required parameters; a type " +
                "error would hide the real problem",
            0,
            plan.steps[0].args.size(),
        )
    }

    @Test
    fun absent_arguments_become_an_empty_object() {
        val plan = parser.parse(planJson(step("web_search"))).requirePlan()
        assertEquals(JsonObject(), plan.steps[0].args)
    }

    // ------------------------------------------------------------------ step details

    @Test
    fun every_tool_name_spelling_is_read() {
        for (key in listOf("tool", "plugin", "name", "action")) {
            val plan = parser.parse(
                """{"kind":"plan","steps":[{"$key":"sms_send"}]}""",
            ).requirePlan()
            assertEquals("the tool under '$key' was not read", "sms_send", plan.steps[0].tool)
        }
    }

    @Test
    fun on_failure_is_read_and_defaults_to_replanning() {
        val expected = mapOf(
            "skip" to OnStepFailure.SKIP,
            "continue" to OnStepFailure.SKIP,
            "abort" to OnStepFailure.ABORT,
            "stop" to OnStepFailure.ABORT,
            "ask" to OnStepFailure.ASK_USER,
            "ask_user" to OnStepFailure.ASK_USER,
            "replan" to OnStepFailure.REPLAN,
        )
        for ((word, want) in expected) {
            val plan = parser.parse(
                """{"kind":"plan","steps":[{"tool":"web_search","on_failure":"$word"}]}""",
            ).requirePlan()
            assertEquals("on_failure=$word", want, plan.steps[0].onFailure)
        }

        val unstated = parser.parse(planJson(step("web_search"))).requirePlan()
        assertEquals(
            "an unstated failure policy means asking the model again, not pressing on",
            OnStepFailure.REPLAN,
            unstated.steps[0].onFailure,
        )
    }

    @Test
    fun an_intent_falls_back_to_the_tool_name_rather_than_to_nothing() {
        val blank = parser.parse(
            """{"kind":"plan","steps":[{"tool":"web_search","intent":"   "}]}""",
        ).requirePlan()
        assertEquals(
            "the checklist line must never be blank",
            "web_search",
            blank.steps[0].intent,
        )

        for (key in listOf("intent", "why", "description")) {
            val plan = parser.parse(
                """{"kind":"plan","steps":[{"tool":"web_search","$key":"check the tide"}]}""",
            ).requirePlan()
            assertEquals("the intent under '$key' was not read", "check the tide", plan.steps[0].intent)
        }
    }

    @Test
    fun thought_and_assumptions_are_carried_through_for_the_user_to_see() {
        val plan = parser.parse(
            """{"kind":"plan","thought":"Rina usually wants the morning summary",""" +
                """"assumptions":["the meeting is at 10","her number is unchanged"],""" +
                """"steps":[${step("sms_send")}]}""",
        ).requirePlan()

        assertEquals("Rina usually wants the morning summary", plan.thought)
        assertEquals(
            "an assumption is a guess, so it is shown rather than acted on silently",
            listOf("the meeting is at 10", "her number is unchanged"),
            plan.assumptions,
        )
    }

    /**
     * Assumptions are shown to the user as sentences. A number is a primitive and
     * arrives as its text; an object cannot be read as a sentence, so it is dropped
     * rather than rendered as JSON in the middle of a list of plain words.
     */
    @Test
    fun an_assumption_that_is_not_text_is_rendered_as_text_or_dropped() {
        val plan = parser.parse(
            """{"kind":"answer","answer":"done","assumptions":["one",3,{"two":2},null]}""",
        ).requirePlan()
        assertEquals(listOf("one", "3"), plan.assumptions)
    }

    @Test
    fun the_reasoning_key_synonyms_are_read_as_the_thought() {
        for (key in listOf("thought", "reasoning", "thinking")) {
            val plan = parser.parse("""{"kind":"answer","$key":"because it is late","answer":"no"}""").requirePlan()
            assertEquals("the thought under '$key' was not read", "because it is late", plan.thought)
        }
    }

    @Test
    fun the_raw_output_is_kept_so_a_bad_plan_can_be_diagnosed_afterwards() {
        val raw = planJson(step("web_search"))
        val plan = parser.parse(raw).requirePlan()
        assertEquals(raw, plan.rawOutput)
    }

    @Test
    fun a_tool_name_the_registry_does_not_have_is_still_passed_on_for_the_agent_to_refuse() {
        // Deciding whether a tool exists belongs to the agent, which holds the registry
        // and can report the refusal into the replan loop. The parser's job is to read
        // what the model said, and it must not silently drop a step it does not
        // recognise: that would turn "no such tool" into "nothing was attempted".
        val plan = parser.parse(planJson(step("launch_the_missiles"))).requirePlan()
        assertEquals("launch_the_missiles", plan.steps[0].tool)
    }

    @Test
    fun a_parser_with_no_tools_at_all_still_parses() {
        val plan = PlanParser(emptySet()).parse(planJson(step("web_search"))).requirePlan()
        assertEquals(1, plan.steps.size)
    }

    private fun <T : Any> assertNotNull2(value: T?): T {
        assertNotNull("expected a value, got null", value)
        return value!!
    }
}
