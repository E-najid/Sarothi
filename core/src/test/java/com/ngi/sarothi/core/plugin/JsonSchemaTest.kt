package com.ngi.sarothi.core.plugin

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate between what the model emitted and what a plugin is handed.
 *
 * A 350M model writes `"3"` where an integer is wanted, sends one value where a list is
 * wanted, and drops keys it does not know are mandatory. Coercion is allowed because it
 * is lossless and reported; invention is not -- a missing required value has to stay an
 * error so the agent asks the user instead of guessing. Both halves of that line are
 * asserted here.
 */
class JsonSchemaTest {

    private val schema = JsonSchema(
        properties = mapOf(
            "to" to JsonSchema.Property.Text("Recipient address"),
            "subject" to JsonSchema.Property.Text("Subject line", maxLength = 10),
            "body" to JsonSchema.Property.Text("Message body"),
            "importance" to JsonSchema.Property.Text(
                "How loudly to send it",
                enum = listOf("low", "normal", "high"),
            ),
            "attachments" to JsonSchema.Property.List(
                "Files to attach",
                items = JsonSchema.Property.Text("A file path"),
            ),
            "attempts" to JsonSchema.Property.Integer("Retries", minimum = 1, maximum = 5),
            "urgent" to JsonSchema.Property.Flag("Send as urgent"),
        ),
        required = listOf("to", "body"),
        description = "Send a message",
    )

    private fun params(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                is JsonElement -> add(key, value)
                is String -> addProperty(key, value)
                is Boolean -> addProperty(key, value)
                is Number -> addProperty(key, value)
                null -> add(key, com.google.gson.JsonNull.INSTANCE)
                else -> error("unsupported test value $value")
            }
        }
    }

    private fun JsonSchema.ValidationResult.requireValid(): JsonObject = when (this) {
        is JsonSchema.ValidationResult.Valid -> value
        is JsonSchema.ValidationResult.Invalid ->
            throw AssertionError("expected the call to be accepted, got errors: $errors")
    }

    private fun JsonSchema.ValidationResult.requireInvalid(): List<String> = when (this) {
        is JsonSchema.ValidationResult.Invalid -> errors
        is JsonSchema.ValidationResult.Valid ->
            throw AssertionError("expected the call to be refused, but it was accepted: $value")
    }

    private fun JsonSchema.ValidationResult.notes(): List<String> = when (this) {
        is JsonSchema.ValidationResult.Valid -> notes
        is JsonSchema.ValidationResult.Invalid -> emptyList()
    }

    // ------------------------------------------------------------------- the happy path

    @Test
    fun a_well_formed_call_passes_through_unchanged() {
        val result = schema.validate(params("to" to "rina@example.com", "body" to "hello"))
        val value = result.requireValid()

        assertEquals("rina@example.com", value.get("to").asString)
        assertEquals("hello", value.get("body").asString)
        assertTrue(
            "an already-correct call needs no coercion notes, got ${result.notes()}",
            result.notes().isEmpty(),
        )
    }

    // ------------------------------------------------------------------- what must refuse

    /** Invention is the thing that must never happen: an address the model made up is worse than a question. */
    @Test
    fun a_missing_required_parameter_is_an_error_not_a_default() {
        val errors = schema.validate(params("body" to "hello")).requireInvalid()
        assertTrue(errors.toString(), errors.any { it.contains("'to'") && it.contains("Missing required") })
    }

    @Test
    fun an_explicit_null_for_a_required_parameter_is_still_missing() {
        val errors = schema.validate(params("to" to null, "body" to "hello")).requireInvalid()
        assertTrue(errors.toString(), errors.any { it.contains("'to'") })
    }

    @Test
    fun a_parameter_the_plugin_does_not_accept_is_an_error() {
        val errors = schema.validate(
            params("to" to "rina@example.com", "body" to "hi", "cc" to "boss@example.com"),
        ).requireInvalid()
        assertTrue(errors.toString(), errors.any { it.contains("Unknown parameter 'cc'") })
    }

    @Test
    fun a_value_outside_the_declared_enum_is_refused() {
        val errors = schema.validate(
            params("to" to "rina@example.com", "body" to "hi", "importance" to "screaming"),
        ).requireInvalid()
        assertTrue(errors.toString(), errors.any { it.contains("must be one of") })
    }

    @Test
    fun a_string_longer_than_the_declared_limit_is_refused() {
        val errors = schema.validate(
            params("to" to "rina@example.com", "body" to "hi", "subject" to "far too long a subject"),
        ).requireInvalid()
        assertTrue(errors.toString(), errors.any { it.contains("the limit is 10") })
    }

    @Test
    fun an_integer_outside_its_bounds_is_refused() {
        val errors = schema.validate(
            params("to" to "rina@example.com", "body" to "hi", "attempts" to 50),
        ).requireInvalid()
        assertTrue(errors.toString(), errors.any { it.contains("must be at most 5") })
    }

    @Test
    fun a_value_that_is_not_a_number_at_all_is_refused() {
        val errors = schema.validate(
            params("to" to "rina@example.com", "body" to "hi", "attempts" to "soon"),
        ).requireInvalid()
        assertTrue(errors.toString(), errors.any { it.contains("must be a whole number") })
    }

    // ------------------------------------------------------- what is allowed to be repaired

    @Test
    fun a_number_written_as_a_string_is_coerced_and_reported() {
        val result = schema.validate(
            params("to" to "rina@example.com", "body" to "hi", "attempts" to "3"),
        )
        assertEquals(3L, result.requireValid().get("attempts").asLong)
        assertTrue(
            "the repair has to be visible in the audit trail",
            result.notes().any { it.contains("'attempts'") },
        )
    }

    /** The most common small-model slip: one value where the plugin wants a list. */
    @Test
    fun a_single_value_where_a_list_is_wanted_is_wrapped() {
        val value = schema.validate(
            params("to" to "rina@example.com", "body" to "hi", "attachments" to "photo.jpg"),
        ).requireValid()

        val array = value.getAsJsonArray("attachments")
        assertEquals(1, array.size())
        assertEquals("photo.jpg", array[0].asString)
    }

    @Test
    fun a_boolean_written_as_a_word_is_coerced() {
        for ((raw, expected) in listOf("yes" to true, "ON" to true, "0" to false, "off" to false)) {
            val value = schema.validate(
                params("to" to "rina@example.com", "body" to "hi", "urgent" to raw),
            ).requireValid()
            assertEquals("urgent=$raw", expected, value.get("urgent").asBoolean)
        }
    }

    @Test
    fun a_boolean_that_is_not_a_boolean_word_is_refused() {
        val errors = schema.validate(
            params("to" to "rina@example.com", "body" to "hi", "urgent" to "maybe"),
        ).requireInvalid()
        assertTrue(errors.toString(), errors.any { it.contains("must be true or false") })
    }

    @Test
    fun a_list_element_of_the_wrong_type_names_its_index() {
        val value = JsonObject().apply {
            addProperty("to", "rina@example.com")
            addProperty("body", "hi")
            add("attachments", JsonArray().also { it.add(JsonObject()) })
        }
        val errors = schema.validate(value).requireInvalid()
        assertTrue(errors.toString(), errors.any { it.contains("attachments[0]") })
    }

    // ------------------------------------------------------------------- nested records

    @Test
    fun a_nested_record_is_validated_by_its_own_schema() {
        val nested = JsonSchema(
            properties = mapOf(
                "when" to JsonSchema.Property.Record(
                    "When to send",
                    JsonSchema(
                        properties = mapOf(
                            "hour" to JsonSchema.Property.Integer("Hour of day", minimum = 0, maximum = 23),
                        ),
                        required = listOf("hour"),
                    ),
                ),
            ),
            required = listOf("when"),
        )

        val good = JsonObject().apply {
            add("when", JsonObject().apply { addProperty("hour", 9) })
        }
        assertEquals(9, nested.validate(good).requireValid().getAsJsonObject("when").get("hour").asInt)

        val bad = JsonObject().apply {
            add("when", JsonObject().apply { addProperty("hour", 99) })
        }
        val errors = nested.validate(bad).requireInvalid()
        assertTrue(errors.toString(), errors.any { it.contains("must be at most 23") })
    }

    // ------------------------------------------------------------------- what the model sees

    /**
     * The prompt hint is the only specification a small model gets. If it omits that a
     * key is mandatory the model will omit it too, and the plan fails at runtime.
     */
    @Test
    fun the_prompt_hint_marks_required_keys_and_leaves_optional_ones_unmarked() {
        val hint = schema.toPromptHint()
        assertTrue(hint, hint.contains("to:string*"))
        assertTrue(hint, hint.contains("body:string*"))
        assertFalse(
            "an optional key marked as required teaches the model to invent one: $hint",
            hint.contains("subject:string*"),
        )
        assertTrue("a list has to be rendered as a list: $hint", hint.contains("attachments:array<string>"))
        assertTrue("an enum has to be spelled out: $hint", hint.contains("low|normal|high"))
    }

    @Test
    fun a_schema_survives_a_json_round_trip() {
        val restored = JsonSchema.fromJson(schema.toJson())

        assertEquals(schema.properties.keys.sorted(), restored.properties.keys.sorted())
        assertEquals(schema.required.sorted(), restored.required.sorted())
        assertEquals(schema.description, restored.description)

        // Shape is not enough: a schema that survives the trip but loses its limits
        // stops enforcing them.
        val subject = restored.properties.getValue("subject") as JsonSchema.Property.Text
        assertEquals(10, subject.maxLength)
        val importance = restored.properties.getValue("importance") as JsonSchema.Property.Text
        assertEquals(listOf("low", "normal", "high"), importance.enum)
        val attempts = restored.properties.getValue("attempts") as JsonSchema.Property.Integer
        assertEquals(1L, attempts.minimum)
        assertEquals(5L, attempts.maximum)
        val attachments = restored.properties.getValue("attachments") as JsonSchema.Property.List
        assertEquals("A file path", (attachments.items as JsonSchema.Property.Text).description)

        // and the restored copy gates calls the same way
        assertTrue(restored.validate(params("body" to "hi")).requireInvalid().isNotEmpty())
        assertEquals(
            "rina@example.com",
            restored.validate(params("to" to "rina@example.com", "body" to "hi"))
                .requireValid().get("to").asString,
        )
    }

    @Test
    fun a_schema_may_not_require_a_key_it_never_declared() {
        val failure = runCatching {
            JsonSchema(
                properties = mapOf("to" to JsonSchema.Property.Text("Recipient")),
                required = listOf("body"),
            )
        }
        assertTrue("that schema is broken and must be refused at construction", failure.isFailure)
    }

    @Test
    fun the_serialised_schema_says_additional_properties_are_not_allowed() {
        val json = schema.toJson().toString()
        assertTrue(json, json.contains("additionalProperties"))
        assertTrue(json, JsonPrimitive(false) == com.google.gson.JsonParser.parseString(json)
            .asJsonObject.get("additionalProperties"))
    }
}
