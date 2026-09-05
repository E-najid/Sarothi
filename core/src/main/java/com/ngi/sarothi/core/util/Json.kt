package com.ngi.sarothi.core.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Single Gson configuration for the whole app.
 *
 * Gson (rather than kotlinx.serialization) is used because the plugin contract
 * in the project spec is expressed in Gson types: `execute(params: JsonObject)`
 * and `parameters: JsonSchema`. Third-party plugin authors therefore only need
 * Gson on the classpath, which Android already ships nothing conflicting with.
 */
object Json {
    val gson: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    val prettyGson: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().setPrettyPrinting().create()

    fun stringify(element: JsonElement): String = gson.toJson(element)

    fun pretty(element: JsonElement): String = prettyGson.toJson(element)

    fun parse(text: String): JsonElement = JsonParser.parseString(text)

    fun parseObject(text: String): JsonObject = parse(text).asJsonObject

    fun obj(builder: JsonObject.() -> Unit): JsonObject = JsonObject().apply(builder)

    fun arr(builder: JsonArray.() -> Unit): JsonArray = JsonArray().apply(builder)

    inline fun <reified T> fromJson(text: String): T = gson.fromJson(text, T::class.java)

    fun toJson(value: Any?): String = gson.toJson(value)
}

/** `JsonObject.optString` that treats JSON null as absent instead of the literal "null". */
fun JsonObject.stringOrNull(key: String): String? {
    val element = get(key) ?: return null
    if (element.isJsonNull) return null
    return if (element is JsonPrimitive && element.isString) element.asString else element.toString()
}

fun JsonObject.stringOr(key: String, fallback: String): String = stringOrNull(key) ?: fallback

fun JsonObject.intOr(key: String, fallback: Int): Int {
    val element = get(key) ?: return fallback
    if (element.isJsonNull) return fallback
    return runCatching { element.asInt }.getOrDefault(fallback)
}

fun JsonObject.longOr(key: String, fallback: Long): Long {
    val element = get(key) ?: return fallback
    if (element.isJsonNull) return fallback
    return runCatching { element.asLong }.getOrDefault(fallback)
}

fun JsonObject.doubleOr(key: String, fallback: Double): Double {
    val element = get(key) ?: return fallback
    if (element.isJsonNull) return fallback
    return runCatching { element.asDouble }.getOrDefault(fallback)
}

fun JsonObject.boolOr(key: String, fallback: Boolean): Boolean {
    val element = get(key) ?: return fallback
    if (element.isJsonNull) return fallback
    return runCatching { element.asBoolean }.getOrDefault(fallback)
}

fun JsonObject.objectOrNull(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonObject.arrayOrNull(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

fun JsonObject.requireString(key: String): String =
    stringOrNull(key) ?: throw IllegalArgumentException("Required string parameter '$key' is missing")

fun JsonObject.requireBoolean(key: String): Boolean =
    get(key)?.takeIf { !it.isJsonNull }?.asBoolean
        ?: throw IllegalArgumentException("Required boolean parameter '$key' is missing")
