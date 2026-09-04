package com.ngi.sarothi.core.util

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding the JSON in what a model actually wrote.
 *
 * Every reply Sarothi acts on passes through here first, from both models. A reply
 * that is dropped here is not reported as a parsing problem -- it surfaces as the
 * agent having nothing to say, which reads as a refusal. So the cases that matter are
 * the ones where usable JSON sits inside output that is not itself JSON.
 */
class JsonReplyTest {

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    @Test
    fun a_bare_object_is_returned_as_it_is() {
        val found = JsonReply.extractObject("""{"kind":"plan","steps":[]}""")
        assertNotNull("a plain object is the easy case and must work", found)
        assertEquals("plan", found!!.text("kind"))
    }

    @Test
    fun a_fenced_block_is_unwrapped_with_or_without_a_language_tag() {
        val tagged = JsonReply.extractObject("```json\n{\"kind\":\"answer\"}\n```")
        assertEquals("answer", tagged?.text("kind"))

        val untagged = JsonReply.extractObject("```\n{\"kind\":\"answer\"}\n```")
        assertEquals("a fence with no language tag is just as common", "answer", untagged?.text("kind"))
    }

    @Test
    fun prose_before_and_after_the_object_is_ignored() {
        val found = JsonReply.extractObject(
            "Sure, here is the plan you asked for:\n{\"kind\":\"plan\",\"steps\":[]}\nLet me know if that works.",
        )
        assertNotNull("the object is in there and must be found", found)
        assertEquals("plan", found!!.text("kind"))
    }

    /**
     * The bug this file exists for. Prose containing a brace that never closes used to
     * end the search, so a perfectly good plan later in the same reply was reported as
     * no JSON at all.
     */
    @Test
    fun an_unclosed_brace_in_prose_does_not_hide_the_object_after_it() {
        val found = JsonReply.extractObject(
            "I will use the {tool you named and then report back.\n{\"kind\":\"answer\",\"answer\":\"done\"}",
        )
        assertNotNull(
            "an unbalanced brace in prose must not end the search",
            found,
        )
        assertEquals("done", found!!.text("answer"))
    }

    @Test
    fun an_unclosed_bracket_in_prose_does_not_hide_the_object_after_it() {
        val found = JsonReply.extractObject("see [1 for details {\"kind\":\"answer\"}")
        assertNotNull("same failure, other bracket", found)
        assertEquals("answer", found!!.text("kind"))
    }

    @Test
    fun a_trailing_comma_is_repaired() {
        val found = JsonReply.extractObject("""{"kind":"plan","steps":[],}""")
        assertNotNull(
            "removing a comma before a closing bracket cannot change meaning",
            found,
        )
        assertEquals("plan", found!!.text("kind"))
    }

    /** A brace inside a string is text. Treating it as structure truncates the object. */
    @Test
    fun braces_inside_string_values_are_text_not_structure() {
        val found = JsonReply.extractObject("""{"answer":"wrap it in {braces} like this"}""")
        assertEquals("wrap it in {braces} like this", found?.text("answer"))
    }

    @Test
    fun an_escaped_quote_inside_a_string_does_not_end_the_string() {
        val found = JsonReply.extractObject("""{"answer":"he said \"no\" loudly"}""")
        assertEquals("he said \"no\" loudly", found?.text("answer"))
    }

    @Test
    fun a_footnote_array_does_not_stop_the_search_reaching_the_object() {
        val found = JsonReply.extractObject("Sources: [1] and [2].\n{\"kind\":\"answer\",\"answer\":\"42\"}")
        assertEquals(
            "an array of numbers is not a decision object, so the scan must move past it",
            "42",
            found?.text("answer"),
        )
    }

    @Test
    fun extractObject_skips_an_array_and_returns_nothing_when_there_is_no_object() {
        assertNull(
            "an array of objects is not an object; handing back its first element would drop the rest",
            JsonReply.extractObject("""[{"a":1},{"b":2}]"""),
        )
    }

    /** The caller that can act on an array asks for one explicitly. */
    @Test
    fun extractElement_returns_an_array_so_the_caller_can_decide_what_it_means() {
        val element = JsonReply.extractElement("""[{"tool":"web_search"},{"tool":"sms_send"}]""")
        assertNotNull("a top-level array is valid JSON and must be returned", element)
        assertTrue("expected an array, got $element", element!!.isJsonArray)
        assertEquals(2, element.asJsonArray.size())
    }

    @Test
    fun extractElement_prefers_the_outer_object_over_the_ones_inside_it() {
        val element = JsonReply.extractElement("""{"steps":[{"tool":"web_search"}]}""")
        assertTrue("the outer object is the reply; the inner one is a step", element!!.isJsonObject)
        assertTrue(element.asJsonObject.has("steps"))
    }

    @Test
    fun a_reply_with_no_json_at_all_returns_null() {
        assertNull("prose with no structure is not a decision", JsonReply.extractObject("I cannot help with that."))
        assertNull(JsonReply.extractElement(""))
        assertNull(JsonReply.extractObject("   \n  "))
    }

    @Test
    fun a_truncated_object_is_refused_rather_than_completed() {
        assertNull(
            "finishing the model's JSON would be inventing content it never wrote",
            JsonReply.extractObject("""{"kind":"plan","steps":[{"tool":"""),
        )
    }

    @Test
    fun an_object_of_the_wrong_shape_is_still_returned_for_the_caller_to_judge() {
        // Extraction is not validation: `{"hello":"world"}` is JSON, and deciding that
        // it is not a plan belongs to the parser that knows what a plan looks like.
        val found = JsonReply.extractObject("""{"hello":"world"}""")
        assertNotNull(found)
        assertEquals("world", found!!.text("hello"))
    }

    @Test
    fun fence_stripping_leaves_unfenced_text_alone() {
        assertEquals("no fences here", JsonReply.stripFences("  no fences here  "))
        assertEquals("inner", JsonReply.stripFences("```json\ninner\n```"))
        assertEquals(
            "a lone opening fence is not a fence, so the text after it stands",
            """{"a":1}""",
            JsonReply.stripFences("```json\n{\"a\":1}"),
        )
    }

    @Test
    fun trailing_comma_removal_leaves_commas_inside_strings_alone() {
        assertEquals(
            """{"a":"x, }y"}""",
            JsonReply.removeTrailingCommas("""{"a":"x, }y",}"""),
        )
    }
}
