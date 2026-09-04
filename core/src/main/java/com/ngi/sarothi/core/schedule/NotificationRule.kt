package com.ngi.sarothi.core.schedule

import com.google.gson.JsonObject
import com.ngi.sarothi.core.screen.ObservedNotification
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.arrayOrNull
import com.ngi.sarothi.core.util.stringOrNull
import java.time.Instant

/** How a rule's match conditions combine. */
enum class RuleMatch { ALL, ANY }

/**
 * A trigger fired by an incoming notification.
 *
 * Matching is plain substring matching on the app package, title and body. That is
 * deliberately not a model call: a rule has to fire when the screen is off and the
 * orchestrator is not loaded, and "run a 350 M model on every notification" would
 * be both slow and a battery disaster.
 */
data class NotificationRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    /** Empty means any app. */
    val packageNames: List<String>,
    val titleContains: List<String>,
    val bodyContains: List<String>,
    val match: RuleMatch,
    val caseSensitive: Boolean,
    /** The request handed to the agent, as if the user had typed it. */
    val request: String,
    /** Minimum gap between two firings, so a chatty app cannot loop the agent. */
    val cooldownMillis: Long,
    val createdAt: String,
    val lastFiredAtEpochMillis: Long?,
    val fireCount: Int,
    val lastResult: String?,
) {

    fun matches(notification: ObservedNotification, nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        if (!enabled) return false
        if (packageNames.isNotEmpty() && packageNames.none { it.equals(notification.packageName, !caseSensitive) }) {
            return false
        }
        if (lastFiredAtEpochMillis != null && nowEpochMillis - lastFiredAtEpochMillis < cooldownMillis) return false

        val title = notification.title ?: ""
        val body = notification.text ?: ""
        val conditions = buildList {
            titleContains.forEach { needle -> add(haystackContains(title, needle)) }
            bodyContains.forEach { needle -> add(haystackContains(body, needle)) }
        }
        if (conditions.isEmpty()) {
            // A rule with only a package filter matches every notification from it.
            return true
        }
        return if (match == RuleMatch.ALL) conditions.all { it } else conditions.any { it }
    }

    private fun haystackContains(haystack: String, needle: String): Boolean =
        if (needle.isBlank()) true else haystack.contains(needle, ignoreCase = !caseSensitive)

    fun describeConditions(): String = buildString {
        val parts = mutableListOf<String>()
        if (packageNames.isNotEmpty()) parts += "from ${packageNames.joinToString(" or ")}"
        if (titleContains.isNotEmpty()) parts += "title contains ${titleContains.joinToString(" / ")}"
        if (bodyContains.isNotEmpty()) parts += "message contains ${bodyContains.joinToString(" / ")}"
        append(if (parts.isEmpty()) "any notification" else parts.joinToString(if (match == RuleMatch.ALL) ", and " else ", or "))
        if (cooldownMillis > 0) append(" (at most once per ${cooldownMillis / 60000} min)")
    }

    fun toJson(): JsonObject = Json.obj {
        addProperty("id", id)
        addProperty("name", name)
        addProperty("enabled", enabled)
        add("packages", Json.arr { packageNames.forEach { add(it) } })
        add("title_contains", Json.arr { titleContains.forEach { add(it) } })
        add("body_contains", Json.arr { bodyContains.forEach { add(it) } })
        addProperty("match", match.name.lowercase())
        addProperty("case_sensitive", caseSensitive)
        addProperty("request", request)
        addProperty("cooldown_millis", cooldownMillis)
        addProperty("created_at", createdAt)
        lastFiredAtEpochMillis?.let { addProperty("last_fired_at", it) }
        addProperty("fire_count", fireCount)
        lastResult?.let { addProperty("last_result", it) }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MILLIS = 30 * 60 * 1000L

        fun fromJson(json: JsonObject): NotificationRule? {
            val id = json.stringOrNull("id") ?: return null
            val request = json.stringOrNull("request") ?: return null
            return NotificationRule(
                id = id,
                name = json.stringOrNull("name") ?: request.take(40),
                enabled = json.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                packageNames = stringList(json.arrayOrNull("packages")),
                titleContains = stringList(json.arrayOrNull("title_contains")),
                bodyContains = stringList(json.arrayOrNull("body_contains")),
                match = runCatching {
                    RuleMatch.valueOf((json.stringOrNull("match") ?: "all").uppercase())
                }.getOrDefault(RuleMatch.ALL),
                caseSensitive = json.get("case_sensitive")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                request = request,
                cooldownMillis = json.get("cooldown_millis")?.takeIf { it.isJsonPrimitive }?.asLong
                    ?: DEFAULT_COOLDOWN_MILLIS,
                createdAt = json.stringOrNull("created_at") ?: Instant.now().toString(),
                lastFiredAtEpochMillis = json.get("last_fired_at")?.takeIf { it.isJsonPrimitive }?.asLong,
                fireCount = json.get("fire_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                lastResult = json.stringOrNull("last_result"),
            )
        }

        private fun stringList(array: com.google.gson.JsonArray?): List<String> =
            array?.mapNotNull { if (it.isJsonPrimitive) it.asString else null } ?: emptyList()
    }
}
