package com.ngi.sarothi.core.util

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * Pulls JSON out of what a model actually wrote.
 *
 * Both places Sarothi reads structured output from a model -- the orchestrator's plan
 * and the vision model's screen description -- need the same thing: find the JSON in a
 * reply that also contains prose, markdown fences, or small syntax slips. They had two
 * separate implementations of it, and both were wrong in the same way: on meeting a
 * brace that never closed, in prose like "use {tool} and then", each stopped looking
 * and reported no JSON at all. A usable answer later in the same reply was thrown away,
 * which reads to the user as the model refusing rather than as a parser giving up.
 *
 * One implementation, so the two callers cannot drift and a fix reaches both.
 *
 * Extraction is deliberately lossless. It repairs what cannot change meaning -- a
 * trailing comma, a fence, surrounding prose -- and it never completes a structure the
 * model left unfinished. A reply with no parseable JSON returns null and the caller
 * decides what that means; inventing an object here would put words in the model's
 * mouth at the one place in the system where that is hardest to notice.
 */
object JsonReply {

    /**
     * The first balanced `{...}` in the reply that parses as a JSON object.
     *
     * Arrays are skipped rather than returned: a caller asking for an object wants one,
     * and silently handing back the first object *inside* an array would drop every
     * later element. Use [extractElement] when an array is also an acceptable answer.
     */
    fun extractObject(raw: String): JsonObject? {
        // Scanned span by span rather than taken from extractElement: the first
        // parseable thing in a reply is often not the object wanted -- "Sources: [1]"
        // is a valid JSON array, and stopping there would report no JSON at all even
        // though the answer sits in the next line.
        for (candidate in balancedSpans(stripFences(raw))) {
            val element = firstParseOf(candidate)
            if (element != null && element.isJsonObject) return element.asJsonObject
        }
        return null
    }

    /**
     * The first balanced `{...}` or `[...]` in the reply that parses, or null.
     *
     * Whatever that first span turns out to be -- an object, an array, a footnote like
     * `[1]` -- deciding whether it is a usable answer belongs to the caller, which
     * knows what it asked for. [extractObject] is the version that keeps looking past
     * spans which are not objects.
     */
    fun extractElement(raw: String): JsonElement? {
        for (candidate in balancedSpans(stripFences(raw))) {
            firstParseOf(candidate)?.let { return it }
        }
        return null
    }

    /**
     * Parses one span, retrying once with trailing commas removed -- the single most
     * common small-model JSON error, and the one whose repair provably cannot change
     * meaning.
     */
    private fun firstParseOf(candidate: String): JsonElement? =
        parseOrNull(candidate) ?: parseOrNull(removeTrailingCommas(candidate))

    /** Removes a surrounding markdown fence, with or without a language tag. */
    internal fun stripFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val withoutOpen = trimmed.removePrefix("```")
        val afterLanguage = withoutOpen.indexOf('\n')
            .let { if (it < 0) withoutOpen else withoutOpen.substring(it + 1) }
        return afterLanguage.substringBeforeLast("```").trim()
    }

    /**
     * Every top-level balanced JSON span, in order of appearance.
     *
     * Brackets and braces both count towards depth, so `[{"a":1}]` is one span and
     * `{"a":[1,2]}` is one span. An opener that never closes ends that span's search
     * only, not the whole scan -- that is the bug this file exists to stop recurring.
     */
    internal fun balancedSpans(text: String): List<String> {
        val found = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val opener = text[index]
            if (opener != '{' && opener != '[') {
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
                    // A brace inside a string is text, not structure. Checking this
                    // before the structural cases is what lets an intent read
                    // "send {urgent}" without ending the object early.
                    char == '"' -> inString = !inString
                    inString -> Unit
                    char == '{' || char == '[' -> depth++
                    char == '}' || char == ']' -> {
                        depth--
                        if (depth == 0) {
                            end = position
                            break
                        }
                    }
                }
            }
            if (end < 0) {
                index++
                continue
            }
            found += text.substring(index, end + 1)
            index = end + 1
        }
        return found
    }

    /** Drops commas that sit immediately before a closing bracket or brace. */
    internal fun removeTrailingCommas(text: String): String {
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

    private fun parseOrNull(text: String): JsonElement? = try {
        // Gson parses leniently and then refuses a document with content left over, so
        // "here is {\"a\":1} and more prose" is not mistaken for a complete reply: the
        // span handed in here is already balanced by construction.
        JsonParser.parseString(text).takeUnless { it.isJsonNull }
    } catch (_: JsonSyntaxException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: NumberFormatException) {
        null
    }
}
