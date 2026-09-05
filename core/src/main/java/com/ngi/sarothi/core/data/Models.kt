package com.ngi.sarothi.core.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import java.time.Instant

/** What kind of thing a memory records. Drives both storage and retrieval. */
enum class MemoryKind {
    /** A durable fact about the user: "works at BRAC Bank", "lives in Habiganj". */
    FACT,

    /** A stated preference: "prefers bKash over Nagad", "hates long replies". */
    PREFERENCE,

    /** Something that happened at a time: "paid the electricity bill on 3 Sept". */
    EVENT,

    /** A person: "Rina is my sister, phone 017…". */
    PERSON,

    /** A learned how-to: "to top up, open bKash → Send Money → …". */
    PROCEDURE,

    /** Anything the agent noticed that did not fit the above. */
    OBSERVATION;

    companion object {
        fun fromJson(value: String?): MemoryKind? = value?.let { raw ->
            entries.firstOrNull { it.name.equals(raw, true) }
        }
    }
}

data class Memory(
    val id: String,
    val kind: MemoryKind,
    val text: String,
    val createdAt: String,
    val updatedAt: String,
    val sourceTaskId: String?,
    val tags: List<String>,
    /** 1 (incidental) … 5 (core). Weighted into search ranking. */
    val importance: Int,
    val pinned: Boolean,
) {
    fun toJson(): JsonObject = Json.obj {
        addProperty("id", id)
        addProperty("kind", kind.name.lowercase())
        addProperty("text", text)
        addProperty("created_at", createdAt)
        addProperty("updated_at", updatedAt)
        sourceTaskId?.let { addProperty("task_id", it) }
        add("tags", Json.arr { tags.forEach { add(it) } })
        addProperty("importance", importance)
        addProperty("pinned", pinned)
    }

    companion object {
        fun fromJson(json: JsonObject): Memory? {
            val id = json.stringOrNull("id") ?: return null
            val text = json.stringOrNull("text") ?: return null
            return Memory(
                id = id,
                kind = MemoryKind.fromJson(json.stringOrNull("kind")) ?: MemoryKind.OBSERVATION,
                text = text,
                createdAt = json.stringOrNull("created_at") ?: Instant.now().toString(),
                updatedAt = json.stringOrNull("updated_at") ?: Instant.now().toString(),
                sourceTaskId = json.stringOrNull("task_id"),
                tags = json.getAsJsonArray("tags")?.mapNotNull {
                    if (it.isJsonPrimitive) it.asString else null
                } ?: emptyList(),
                importance = json.get("importance")?.takeIf { it.isJsonPrimitive }?.asInt ?: 3,
                pinned = json.get("pinned")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            )
        }
    }
}

/** A memory match with why it matched; surfaced in the UI so ranking is not opaque. */
data class MemoryMatch(val memory: Memory, val score: Double, val matchedTerms: List<String>)

data class Note(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: String,
    val updatedAt: String,
    val tags: List<String>,
    val pinned: Boolean,
) {
    fun toJson(): JsonObject = Json.obj {
        addProperty("id", id)
        addProperty("title", title)
        addProperty("body", body)
        addProperty("created_at", createdAt)
        addProperty("updated_at", updatedAt)
        add("tags", Json.arr { tags.forEach { add(it) } })
        addProperty("pinned", pinned)
    }

    companion object {
        fun fromJson(json: JsonObject): Note? {
            val id = json.stringOrNull("id") ?: return null
            return Note(
                id = id,
                title = json.stringOrNull("title") ?: "",
                body = json.stringOrNull("body") ?: "",
                createdAt = json.stringOrNull("created_at") ?: Instant.now().toString(),
                updatedAt = json.stringOrNull("updated_at") ?: Instant.now().toString(),
                tags = json.getAsJsonArray("tags")?.mapNotNull {
                    if (it.isJsonPrimitive) it.asString else null
                } ?: emptyList(),
                pinned = json.get("pinned")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            )
        }
    }
}

data class Todo(
    val id: String,
    val title: String,
    val notes: String?,
    val dueAtEpochMillis: Long?,
    val completed: Boolean,
    val completedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val priority: Int,
    val listName: String,
    val reminderSet: Boolean,
) {
    fun toJson(): JsonObject = Json.obj {
        addProperty("id", id)
        addProperty("title", title)
        notes?.let { addProperty("notes", it) }
        dueAtEpochMillis?.let { addProperty("due_at", it) }
        addProperty("completed", completed)
        completedAtEpochMillis?.let { addProperty("completed_at", it) }
        addProperty("created_at", createdAtEpochMillis)
        addProperty("priority", priority)
        addProperty("list", listName)
        addProperty("reminder_set", reminderSet)
    }

    companion object {
        fun fromJson(json: JsonObject): Todo? {
            val id = json.stringOrNull("id") ?: return null
            return Todo(
                id = id,
                title = json.stringOrNull("title") ?: return null,
                notes = json.stringOrNull("notes"),
                dueAtEpochMillis = json.get("due_at")?.takeIf { it.isJsonPrimitive }?.asLong,
                completed = json.get("completed")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                completedAtEpochMillis = json.get("completed_at")?.takeIf { it.isJsonPrimitive }?.asLong,
                createdAtEpochMillis = json.get("created_at")?.takeIf { it.isJsonPrimitive }?.asLong
                    ?: System.currentTimeMillis(),
                priority = json.get("priority")?.takeIf { it.isJsonPrimitive }?.asInt ?: 2,
                listName = json.stringOrNull("list") ?: "inbox",
                reminderSet = json.get("reminder_set")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            )
        }
    }
}

/**
 * A personal fact the user supplied when Sarothi asked.
 *
 * This store is the reason the agent never has to invent an address, a phone
 * number or a bKash id: it asks once, the answer is stored here (encrypted), and
 * later tasks read it. [confirmedByUser] distinguishes a value the user typed
 * from one Sarothi inferred — inferred values are never used for payments or
 * messages without asking again.
 */
data class UserFact(
    val key: String,
    val value: String,
    val label: String,
    val updatedAt: String,
    val sourceTaskId: String?,
    val confirmedByUser: Boolean,
    val secret: Boolean,
) {
    fun toJson(): JsonObject = Json.obj {
        addProperty("key", key)
        addProperty("value", value)
        addProperty("label", label)
        addProperty("updated_at", updatedAt)
        sourceTaskId?.let { addProperty("task_id", it) }
        addProperty("confirmed_by_user", confirmedByUser)
        addProperty("secret", secret)
    }

    companion object {
        /** Well-known keys, so different tasks agree on what "phone" means. */
        val KNOWN_KEYS = mapOf(
            "full_name" to "Full name",
            "nickname" to "What to call you",
            "phone" to "Phone number",
            "email" to "Email address",
            "home_address" to "Home address",
            "work_address" to "Work address",
            "city" to "City",
            "bkash_number" to "bKash number",
            "nagad_number" to "Nagad number",
            "upi_id" to "UPI id",
            "bank_account" to "Bank account number",
            "nid_number" to "National ID number",
            "date_of_birth" to "Date of birth",
            "occupation" to "Occupation",
            "preferred_language" to "Preferred language",
        )

        fun labelFor(key: String): String = KNOWN_KEYS[key] ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }

        fun fromJson(json: JsonObject): UserFact? {
            val key = json.stringOrNull("key") ?: return null
            val value = json.stringOrNull("value") ?: return null
            return UserFact(
                key = key,
                value = value,
                label = json.stringOrNull("label") ?: labelFor(key),
                updatedAt = json.stringOrNull("updated_at") ?: Instant.now().toString(),
                sourceTaskId = json.stringOrNull("task_id"),
                confirmedByUser = json.get("confirmed_by_user")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                secret = json.get("secret")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            )
        }
    }
}

enum class ChatRole { USER, ASSISTANT, SYSTEM, TOOL }

data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val timestamp: String,
    /** For TOOL messages: the plugin name. */
    val toolName: String?,
    /** For TOOL messages: the plugin's result summary. */
    val toolSummary: String?,
    /** True when the content was produced from a screenshot rather than the tree. */
    val fromVision: Boolean = false,
) {
    fun toJson(): JsonObject = Json.obj {
        addProperty("role", role.name.lowercase())
        addProperty("content", content)
        addProperty("ts", timestamp)
        toolName?.let { addProperty("tool", it) }
        toolSummary?.let { addProperty("tool_summary", it) }
        if (fromVision) addProperty("from_vision", true)
    }

    companion object {
        fun fromJson(json: JsonObject): ChatMessage? {
            val content = json.stringOrNull("content") ?: return null
            return ChatMessage(
                role = runCatching {
                    ChatRole.valueOf((json.stringOrNull("role") ?: "user").uppercase())
                }.getOrDefault(ChatRole.USER),
                content = content,
                timestamp = json.stringOrNull("ts") ?: Instant.now().toString(),
                toolName = json.stringOrNull("tool"),
                toolSummary = json.stringOrNull("tool_summary"),
                fromVision = json.get("from_vision")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            )
        }
    }
}

data class Conversation(
    val id: String,
    val title: String?,
    val createdAt: String,
    val updatedAt: String,
    val messages: List<ChatMessage>,
) {
    fun toJson(): JsonObject = Json.obj {
        addProperty("id", id)
        title?.let { addProperty("title", it) }
        addProperty("created_at", createdAt)
        addProperty("updated_at", updatedAt)
        add("messages", JsonArray().also { array -> messages.forEach { array.add(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JsonObject): Conversation? {
            val id = json.stringOrNull("id") ?: return null
            return Conversation(
                id = id,
                title = json.stringOrNull("title"),
                createdAt = json.stringOrNull("created_at") ?: Instant.now().toString(),
                updatedAt = json.stringOrNull("updated_at") ?: Instant.now().toString(),
                messages = json.getAsJsonArray("messages")?.mapNotNull {
                    if (it.isJsonObject) ChatMessage.fromJson(it.asJsonObject) else null
                } ?: emptyList(),
            )
        }
    }
}
