package com.ngi.sarothi.core.plugin

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.ngi.sarothi.core.util.Json

/**
 * A small JSON-Schema subset, built in code rather than parsed from strings so a
 * typo in a plugin's schema is a compile error.
 *
 * It is rendered two ways: as real JSON Schema (for the plugin registry and any
 * external caller) and as a compact one-line hint injected into the prompt,
 * because a 350 M model cannot afford a full schema document per tool.
 */
data class JsonSchema(
    val properties: Map<String, Property>,
    val required: List<String> = emptyList(),
    val description: String? = null,
) {
    init {
        require(required.all { properties.containsKey(it) }) {
            "Schema lists required keys that are not declared properties: " +
                required.filterNot { properties.containsKey(it) }
        }
    }

    sealed class Property(val type: String) {
        abstract val description: String

        data class Text(
            override val description: String,
            // Fully qualified: inside `Property`, the bare name `List` resolves to
            // the nested Property.List below, which is not generic.
            val enum: kotlin.collections.List<String>? = null,
            val default: String? = null,
            val maxLength: Int? = null,
        ) : Property("string")

        data class Integer(
            override val description: String,
            val minimum: Long? = null,
            val maximum: Long? = null,
            val default: Long? = null,
        ) : Property("integer")

        data class Number(
            override val description: String,
            val minimum: Double? = null,
            val maximum: Double? = null,
            val default: Double? = null,
        ) : Property("number")

        data class Flag(
            override val description: String,
            val default: Boolean? = null,
        ) : Property("boolean")

        data class List(
            override val description: String,
            val items: Property,
            val default: kotlin.collections.List<String>? = null,
        ) : Property("array")

        data class Record(
            override val description: String,
            val fields: JsonSchema,
        ) : Property("object")
    }

    fun toJson(): JsonObject = Json.obj {
        addProperty("type", "object")
        description?.let { addProperty("description", it) }
        add("properties", Json.obj {
            properties.forEach { (name, property) -> add(name, propertyToJson(property)) }
        })
        add("required", Json.arr { required.forEach { add(it) } })
        addProperty("additionalProperties", false)
    }

    private fun propertyToJson(property: Property): JsonObject = Json.obj {
        addProperty("type", property.type)
        addProperty("description", property.description)
        when (property) {
            is Property.Text -> {
                property.enum?.let { values -> add("enum", Json.arr { values.forEach { add(it) } }) }
                property.default?.let { addProperty("default", it) }
                property.maxLength?.let { addProperty("maxLength", it) }
            }
            is Property.Integer -> {
                property.minimum?.let { addProperty("minimum", it) }
                property.maximum?.let { addProperty("maximum", it) }
                property.default?.let { addProperty("default", it) }
            }
            is Property.Number -> {
                property.minimum?.let { addProperty("minimum", it) }
                property.maximum?.let { addProperty("maximum", it) }
                property.default?.let { addProperty("default", it) }
            }
            is Property.Flag -> property.default?.let { addProperty("default", it) }
            is Property.List -> {
                add("items", propertyToJson(property.items))
                property.default?.let { values -> add("default", Json.arr { values.forEach { add(it) } }) }
            }
            is Property.Record -> {
                addProperty("type", "object")
                add("properties", Json.obj {
                    property.fields.properties.forEach { (name, child) -> add(name, propertyToJson(child)) }
                })
                add("required", Json.arr { property.fields.required.forEach { add(it) } })
            }
        }
    }

    /** Compact `name:type[=default](enum)` rendering for the prompt. */
    fun toPromptHint(): String = buildString {
        append('(')
        properties.entries.forEachIndexed { index, (name, property) ->
            if (index > 0) append(", ")
            append(name).append(':').append(shortType(property))
            if (name in required) append('*')
            when (property) {
                is Property.Text -> property.default?.let { append("=$it") }
                is Property.Integer -> property.default?.let { append("=$it") }
                is Property.Number -> property.default?.let { append("=$it") }
                is Property.Flag -> property.default?.let { append("=$it") }
                is Property.List -> Unit
                is Property.Record -> Unit
            }
            property.enumValues()?.let { values -> append('{').append(values.joinToString("|")).append('}') }
        }
        append(')')
    }

    private fun shortType(property: Property): String = when (property) {
        is Property.List -> "array<${shortType(property.items)}>"
        is Property.Record -> "object"
        else -> property.type
    }

    private fun Property.enumValues(): kotlin.collections.List<String>? = when (this) {
        is Property.Text -> enum
        else -> null
    }

    /**
     * Validates and coerces a model-produced parameter object.
     *
     * Small models emit `"3"` where an integer is wanted, `["a","b"]` where a
     * single string is wanted, and drop required keys. Coercion is allowed
     * because it is lossless and reported; invention is not — a missing required
     * value is a validation error that makes the agent ask the user.
     */
    fun validate(params: JsonObject): ValidationResult {
        val errors = mutableListOf<String>()
        val coerced = JsonObject()
        val notes = mutableListOf<String>()

        for ((name, property) in properties) {
            val raw = params.get(name)
            val isRequired = name in required
            if (raw == null || raw.isJsonNull) {
                if (isRequired) errors += "Missing required parameter '$name'"
                continue
            }
            when (val result = coerce(name, property, raw)) {
                is Coercion.Ok -> coerced.add(name, result.value)
                is Coercion.Needed -> {
                    coerced.add(name, result.value)
                    notes += "'$name' was ${result.from} and is used as ${property.type}"
                }
                is Coercion.Bad -> errors += result.message
            }
        }

        for (name in params.keySet()) {
            if (!properties.containsKey(name)) {
                errors += "Unknown parameter '$name' (accepted: ${properties.keys.joinToString()})"
            }
        }

        return if (errors.isEmpty()) ValidationResult.Valid(coerced, notes)
        else ValidationResult.Invalid(errors)
    }

    private sealed class Coercion {
        data class Ok(val value: JsonElement) : Coercion()
        data class Needed(val value: JsonElement, val from: String) : Coercion()
        data class Bad(val message: String) : Coercion()
    }

    private fun describe(element: JsonElement): String = when {
        element.isJsonPrimitive && element.asJsonPrimitive.isString -> "a string"
        element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> "a boolean"
        element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> "a number"
        element.isJsonArray -> "an array"
        element.isJsonObject -> "an object"
        else -> "null"
    }

    private fun coerce(name: String, property: Property, raw: JsonElement): Coercion = when (property) {
        is Property.Text -> coerceText(name, property, raw)
        is Property.Integer -> coerceInteger(name, property, raw)
        is Property.Number -> coerceNumber(name, property, raw)
        is Property.Flag -> coerceFlag(name, raw)
        is Property.List -> coerceList(name, property, raw)
        is Property.Record -> coerceRecord(name, property, raw)
    }

    private fun coerceText(name: String, property: Property.Text, raw: JsonElement): Coercion {
        val text = when {
            raw.isJsonPrimitive && raw.asJsonPrimitive.isString -> raw.asString
            raw.isJsonPrimitive -> raw.asString
            raw.isJsonArray && raw.asJsonArray.size() == 1 &&
                raw.asJsonArray[0].isJsonPrimitive -> raw.asJsonArray[0].asString
            else -> return Coercion.Bad("'$name' must be a string, got ${describe(raw)}")
        }
        val coerced = if (!raw.isJsonPrimitive || !raw.asJsonPrimitive.isString) {
            Coercion.Needed(JsonPrimitive(text), describe(raw))
        } else {
            Coercion.Ok(JsonPrimitive(text))
        }
        property.enum?.let { allowed ->
            if (text !in allowed) {
                return Coercion.Bad("'$name' must be one of ${allowed.joinToString()}, got \"$text\"")
            }
        }
        property.maxLength?.let { limit ->
            if (text.length > limit) {
                return Coercion.Bad("'$name' is ${text.length} characters; the limit is $limit")
            }
        }
        return coerced
    }

    private fun coerceInteger(name: String, property: Property.Integer, raw: JsonElement): Coercion {
        val value = when {
            raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber -> raw.asLong
            raw.isJsonPrimitive && raw.asJsonPrimitive.isString -> raw.asString.trim().toLongOrNull()
                ?: return Coercion.Bad("'$name' must be a whole number, got \"${raw.asString}\"")
            raw.isJsonArray && raw.asJsonArray.size() == 1 &&
                raw.asJsonArray[0].isJsonPrimitive -> raw.asJsonArray[0].asLong
            else -> return Coercion.Bad("'$name' must be a whole number, got ${describe(raw)}")
        }
        property.minimum?.let { if (value < it) return Coercion.Bad("'$name' must be at least $it, got $value") }
        property.maximum?.let { if (value > it) return Coercion.Bad("'$name' must be at most $it, got $value") }
        return if (raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber) Coercion.Ok(JsonPrimitive(value))
        else Coercion.Needed(JsonPrimitive(value), describe(raw))
    }

    private fun coerceNumber(name: String, property: Property.Number, raw: JsonElement): Coercion {
        val value = when {
            raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber -> raw.asDouble
            raw.isJsonPrimitive && raw.asJsonPrimitive.isString -> raw.asString.trim().toDoubleOrNull()
                ?: return Coercion.Bad("'$name' must be a number, got \"${raw.asString}\"")
            else -> return Coercion.Bad("'$name' must be a number, got ${describe(raw)}")
        }
        property.minimum?.let { if (value < it) return Coercion.Bad("'$name' must be at least $it, got $value") }
        property.maximum?.let { if (value > it) return Coercion.Bad("'$name' must be at most $it, got $value") }
        return if (raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber) Coercion.Ok(JsonPrimitive(value))
        else Coercion.Needed(JsonPrimitive(value), describe(raw))
    }

    private fun coerceFlag(name: String, raw: JsonElement): Coercion {
        val value = when {
            raw.isJsonPrimitive && raw.asJsonPrimitive.isBoolean -> return Coercion.Ok(raw)
            raw.isJsonPrimitive && raw.asJsonPrimitive.isString -> when (raw.asString.trim().lowercase()) {
                "true", "yes", "1", "on" -> true
                "false", "no", "0", "off" -> false
                else -> return Coercion.Bad("'$name' must be true or false, got \"${raw.asString}\"")
            }
            raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber -> raw.asInt != 0
            else -> return Coercion.Bad("'$name' must be true or false, got ${describe(raw)}")
        }
        return Coercion.Needed(JsonPrimitive(value), describe(raw))
    }

    private fun coerceList(name: String, property: Property.List, raw: JsonElement): Coercion {
        val source: JsonArray = when {
            raw.isJsonArray -> raw.asJsonArray
            // A single value where a list is wanted is the most common small-model
            // slip; wrapping it is lossless.
            raw.isJsonPrimitive -> JsonArray().also { it.add(raw) }
            else -> return Coercion.Bad("'$name' must be an array, got ${describe(raw)}")
        }
        val wrapped = !raw.isJsonArray
        val out = JsonArray()
        source.forEachIndexed { index, element ->
            if (element is JsonNull) return@forEachIndexed
            when (val result = coerce("$name[$index]", property.items, element)) {
                is Coercion.Ok -> out.add(result.value)
                is Coercion.Needed -> out.add(result.value)
                is Coercion.Bad -> return result
            }
        }
        return if (wrapped) Coercion.Needed(out, describe(raw)) else Coercion.Ok(out)
    }

    private fun coerceRecord(name: String, property: Property.Record, raw: JsonElement): Coercion {
        if (!raw.isJsonObject) return Coercion.Bad("'$name' must be an object, got ${describe(raw)}")
        return when (val nested = property.fields.validate(raw.asJsonObject)) {
            is ValidationResult.Valid -> Coercion.Ok(nested.value)
            is ValidationResult.Invalid -> Coercion.Bad(nested.errors.joinToString("; "))
        }
    }

    sealed class ValidationResult {
        data class Valid(val value: JsonObject, val notes: List<String>) : ValidationResult()
        data class Invalid(val errors: List<String>) : ValidationResult()
    }

    companion object {
        val EMPTY = JsonSchema(emptyMap())

        fun fromJson(json: JsonObject): JsonSchema {
            val properties = linkedMapOf<String, Property>()
            val required = json.getAsJsonArray("required")
                ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
                ?: emptyList()
            json.getAsJsonObject("properties")?.entrySet()?.forEach { (name, value) ->
                if (value.isJsonObject) properties[name] = propertyFromJson(value.asJsonObject)
            }
            return JsonSchema(properties, required, json.get("description")?.takeIf { it.isJsonPrimitive }?.asString)
        }

        private fun propertyFromJson(json: JsonObject): Property {
            val description = json.get("description")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            return when (json.get("type")?.takeIf { it.isJsonPrimitive }?.asString) {
                "string" -> Property.Text(
                    description = description,
                    enum = json.getAsJsonArray("enum")?.mapNotNull {
                        if (it.isJsonPrimitive) it.asString else null
                    },
                    default = json.get("default")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString,
                )
                "integer" -> Property.Integer(
                    description = description,
                    minimum = json.get("minimum")?.takeIf { it.isJsonPrimitive }?.asLong,
                    maximum = json.get("maximum")?.takeIf { it.isJsonPrimitive }?.asLong,
                    default = json.get("default")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asLong,
                )
                "number" -> Property.Number(
                    description = description,
                    minimum = json.get("minimum")?.takeIf { it.isJsonPrimitive }?.asDouble,
                    maximum = json.get("maximum")?.takeIf { it.isJsonPrimitive }?.asDouble,
                    default = json.get("default")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asDouble,
                )
                "boolean" -> Property.Flag(
                    description = description,
                    default = json.get("default")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asBoolean,
                )
                "array" -> Property.List(
                    description = description,
                    items = json.getAsJsonObject("items")?.let { propertyFromJson(it) } ?: Property.Text("item"),
                )
                "object" -> Property.Record(
                    description = description,
                    fields = fromJson(json),
                )
                else -> Property.Text(description)
            }
        }
    }
}
