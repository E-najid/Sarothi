package com.ngi.sarothi.plugins.common

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets

/**
 * Packing for undo tokens.
 *
 * [com.ngi.sarothi.core.safety.UndoRegistry] hands a plugin back exactly one
 * opaque string, minutes later, possibly after the process died and was
 * restarted. So everything needed to reverse an action has to travel *inside*
 * that string — there is no "look up what I did last" to fall back on.
 *
 * Deleting a calendar event is the clearest case: to put it back Sarothi needs
 * the title, the start time and the calendar it lived in, and none of that
 * exists anywhere once the row is gone. Encoding it into the token is what makes
 * the Undo button real instead of decorative.
 *
 * The payload is URL-safe Base64 of UTF-8 JSON with a version prefix, so a token
 * from an older build fails to decode and is reported as un-undoable rather than
 * being misread.
 */
object UndoToken {

    private const val PREFIX = "srt1:"
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    /** Packs [payload] into a token. Prefix with a short discriminator if useful. */
    fun encode(kind: String, payload: JsonObject): String {
        val json = JsonObject().apply {
            addProperty("kind", kind)
            add("payload", payload)
        }
        val encoded = Base64.encodeToString(
            json.toString().toByteArray(StandardCharsets.UTF_8),
            FLAGS,
        )
        return PREFIX + encoded
    }

    /** A plain token with no payload, for actions reversed by id alone. */
    fun simple(kind: String, id: String): String =
        encode(kind, JsonObject().apply { addProperty("id", id) })

    /**
     * Unpacks a token, or null when it is not one Sarothi issued — a corrupt
     * token must produce "cannot undo", never a partially restored record.
     */
    fun decode(token: String, expectedKind: String): JsonObject? {
        if (!token.startsWith(PREFIX)) return null
        val decoded = runCatching {
            Base64.decode(token.removePrefix(PREFIX), FLAGS)
        }.getOrNull() ?: return null
        val parsed = runCatching {
            JsonParser.parseString(String(decoded, StandardCharsets.UTF_8))
        }.getOrNull() ?: return null
        if (!parsed.isJsonObject) return null
        val wrapper = parsed.asJsonObject
        if (wrapper.get("kind")?.takeIf { it.isJsonPrimitive }?.asString != expectedKind) return null
        return wrapper.get("payload")?.takeIf { it.isJsonObject }?.asJsonObject
    }
}
