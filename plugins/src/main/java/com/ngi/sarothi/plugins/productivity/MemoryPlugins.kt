package com.ngi.sarothi.plugins.productivity

import com.google.gson.JsonObject
import com.ngi.sarothi.core.data.MemoryKind
import com.ngi.sarothi.core.data.UserFact
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginAvailability
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginContext
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.plugin.ConfirmationPreview
import com.ngi.sarothi.core.safety.ConfirmationReason
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.plugins.common.textOrAsk

private const val VAULT_LOCKED = "This is stored in Sarothi's encrypted vault, which is locked."
private const val VAULT_LOCKED_FIX = "Ask the user to unlock Sarothi's vault, then try again."

/** Writes a durable memory about the user, into the encrypted vault. */
class MemorySavePlugin : Plugin {
    override val name = "memory_save"
    override val description =
        "Remember something about the user for future tasks — a preference, a person, an event, a " +
            "learned procedure. Stored encrypted in Sarothi's vault and ranked into later plans. Only " +
            "save what the user actually said; never a guess, and never a fact invented to fill a gap."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.NORMAL
    override val supportsUndo = true

    override val parameters = JsonSchema(
        properties = mapOf(
            "text" to JsonSchema.Property.Text("The memory, one sentence, in the user's own words where possible."),
            "kind" to JsonSchema.Property.Text(
                "What sort of memory this is.",
                enum = MemoryKind.entries.map { it.name.lowercase() },
                default = "fact",
            ),
            "tags" to JsonSchema.Property.List("Words that make this findable later.", items = JsonSchema.Property.Text("One tag")),
            "importance" to JsonSchema.Property.Integer("1 incidental … 5 core. Weighted into search ranking.", minimum = 1, maximum = 5, default = 3),
            "pin" to JsonSchema.Property.Flag("Pin it so it always ranks first.", default = false),
        ),
        required = listOf("text"),
    )

    override val example = """{"text":"Rina is my sister; her number is 01712345678","kind":"person","importance":4}"""

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.vault.isUnlocked) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(VAULT_LOCKED, VAULT_LOCKED_FIX)
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val text = params.textOrAsk("text", "What exactly should Sarothi remember?")
        val kindName = (params.stringOrNull("kind") ?: "fact").lowercase()
        val kind = MemoryKind.fromJson(kindName)
            ?: return PluginResult.Failure(
                summaryForUser = "\"$kindName\" is not a kind of memory Sarothi stores. Use one of: " +
                    MemoryKind.entries.joinToString { it.name.lowercase() } + ".",
                errorClass = "UnknownMemoryKindException",
                retriable = true,
            )
        val tags = params.getAsJsonArray("tags")?.mapNotNull {
            if (it.isJsonPrimitive) it.asString.trim().takeIf { tag -> tag.isNotEmpty() } else null
        } ?: emptyList()
        val importance = params.get("importance")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 5) ?: 3

        val memory = runCatching {
            context.stores.memories.add(
                kind = kind,
                text = text,
                tags = tags,
                importance = importance,
                sourceTaskId = context.task.taskId.takeIf { it != "none" },
            )
        }.getOrElse { failure ->
            return PluginResult.Failure(
                summaryForUser = "Sarothi could not save that memory: ${failure.message}",
                errorClass = failure.javaClass.simpleName,
                retriable = true,
            )
        }
        if (params.get("pin")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
            context.stores.memories.update(memory.id, null, null, null, true)
        }

        return PluginResult.Success(
            summaryForUser = "Remembered: \"${memory.text}\" (${memory.kind.name.lowercase()}, " +
                "importance ${memory.importance}/5).",
            data = Json.obj {
                addProperty("id", memory.id)
                addProperty("text", memory.text)
                addProperty("kind", memory.kind.name.lowercase())
                addProperty("importance", memory.importance)
                addProperty("pinned", memory.pinned)
                add("tags", Json.arr { memory.tags.forEach { add(it) } })
                addProperty("total_memories", context.stores.memories.count())
            },
            spoken = "মনে রাখলাম।",
            undoToken = memory.id,
            memorable = listOf(memory.text),
        )
    }

    override suspend fun undo(undoToken: String): PluginResult {
        val context = pluginContext()
        return if (context.stores.memories.delete(undoToken)) {
            PluginResult.Success("Forgot that memory again.", Json.obj { addProperty("deleted", undoToken) })
        } else {
            PluginResult.Failure("That memory is already gone.", "NotFoundException", retriable = false)
        }
    }
}

/** Recalls memories relevant to what is being done now. */
class MemorySearchPlugin : Plugin {
    override val name = "memory_search"
    override val description =
        "Search what Sarothi remembers about the user. Use it when a task needs something the user said " +
            "earlier — a preference, a person, how to do something in an app — instead of asking again. " +
            "An empty query returns the most important memories."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "query" to JsonSchema.Property.Text("Words to look for. Empty returns the most important memories."),
            "limit" to JsonSchema.Property.Integer("How many to return.", minimum = 1, maximum = 25, default = 8),
            "kind" to JsonSchema.Property.Text("Only this kind.", enum = MemoryKind.entries.map { it.name.lowercase() }),
        ),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.vault.isUnlocked) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(VAULT_LOCKED, VAULT_LOCKED_FIX)
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val query = params.stringOrNull("query")?.trim().orEmpty()
        val limit = params.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 25) ?: 8
        val kindName = params.stringOrNull("kind")?.takeIf { it.isNotBlank() }?.lowercase()
        val kindFilter = kindName?.let { MemoryKind.fromJson(it) }

        // An empty query still goes through the ranker: with no terms it orders by
        // pinned, then importance, then recency, which is exactly "most important".
        val matches = runCatching { context.stores.memories.search(query, limit * 2) }.getOrElse { failure ->
            return PluginResult.Failure(
                summaryForUser = "Sarothi could not search its memories: ${failure.message}",
                errorClass = failure.javaClass.simpleName,
                retriable = true,
            )
        }.filter { kindFilter == null || it.memory.kind == kindFilter }.take(limit)

        val data = Json.obj {
            addProperty("query", query)
            add("memories", Json.arr {
                matches.forEach { match ->
                    add(Json.obj {
                        addProperty("id", match.memory.id)
                        addProperty("text", match.memory.text)
                        addProperty("kind", match.memory.kind.name.lowercase())
                        addProperty("importance", match.memory.importance)
                        addProperty("pinned", match.memory.pinned)
                        addProperty("score", match.score)
                        add("matched_terms", Json.arr { match.matchedTerms.forEach { add(it) } })
                        add("tags", Json.arr { match.memory.tags.forEach { add(it) } })
                        addProperty("updated_at", match.memory.updatedAt)
                    })
                }
            })
            addProperty("count", matches.size)
            addProperty("total_stored", context.stores.memories.count())
        }
        return PluginResult.Success(
            summaryForUser = when {
                matches.isNotEmpty() -> "${matches.size} memorie(s): " +
                    matches.take(3).joinToString("; ") { it.memory.text }
                query.isEmpty() -> "Sarothi has not been told anything worth remembering yet."
                else -> "Sarothi remembers nothing matching \"$query\". Ask the user rather than guessing."
            },
            data = data,
        )
    }
}

/** Deletes a memory. Confirmed, because forgetting is not reversible. */
class MemoryForgetPlugin : Plugin {
    override val name = "memory_forget"
    override val description =
        "Delete something Sarothi remembers, by id from memory_search or by part of its text. Use it " +
            "when the user says 'forget that' or when a saved memory has become wrong."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.SENSITIVE

    override val parameters = JsonSchema(
        properties = mapOf(
            "memory_id" to JsonSchema.Property.Text("The id from memory_search."),
            "text_contains" to JsonSchema.Property.Text("Part of the memory's text, when the id is unknown."),
        ),
    )

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview = ConfirmationPreview(
        title = "Delete this memory?",
        detailLines = listOf(
            "Memory: " + (params.stringOrNull("memory_id") ?: params.stringOrNull("text_contains") ?: "(unspecified)"),
            "Sarothi will stop knowing this. It cannot be recovered.",
        ),
        reason = ConfirmationReason.DELETION,
        allowRemember = false,
    )

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.vault.isUnlocked) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(VAULT_LOCKED, VAULT_LOCKED_FIX)
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val id = params.stringOrNull("memory_id")?.takeIf { it.isNotBlank() }
        val textPart = params.stringOrNull("text_contains")?.takeIf { it.isNotBlank() }

        val memory = when {
            id != null -> context.stores.memories.byId(id)
            textPart != null -> {
                val matches = context.stores.memories.all().filter {
                    it.text.contains(textPart, ignoreCase = true)
                }
                when {
                    matches.isEmpty() -> return PluginResult.Failure(
                        summaryForUser = "Sarothi remembers nothing containing \"$textPart\".",
                        errorClass = "NotFoundException",
                        retriable = true,
                    )
                    matches.size == 1 -> matches.first()
                    else -> return PluginResult.NeedsUserInput(
                        question = "${matches.size} memories contain \"$textPart\". Which one should go?",
                        field = "memory_id",
                        choices = matches.take(6).map { "${it.id}: ${it.text.take(60)}" },
                    )
                }
            }
            else -> throw com.ngi.sarothi.core.error.MissingInformationException(
                field = "memory_id",
                questionForUser = "Which memory should Sarothi forget? Use memory_search to see them.",
            )
        } ?: return PluginResult.Failure(
            summaryForUser = "That memory no longer exists.",
            errorClass = "NotFoundException",
            retriable = false,
        )

        val deleted = context.stores.memories.delete(memory.id)
        return if (deleted) {
            PluginResult.Success(
                summaryForUser = "Forgot \"${memory.text.take(80)}\".",
                data = Json.obj {
                    addProperty("id", memory.id)
                    addProperty("text", memory.text)
                    addProperty("kind", memory.kind.name.lowercase())
                },
                spoken = "ভুলে গেছি।",
            )
        } else {
            PluginResult.Failure("That memory was already gone.", "NotFoundException", retriable = false)
        }
    }
}

/** Saves a structured personal fact that later tasks substitute in automatically. */
class SaveUserFactPlugin : Plugin {
    override val name = "save_user_fact"
    override val description =
        "Save a personal fact under a stable name — full_name, phone, home_address, bkash_number and so " +
            "on. These are what Sarothi fills into later tasks instead of asking again, so always save " +
            "exactly what the user gave and never something you worked out yourself."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.SENSITIVE
    override val supportsUndo = true

    override val parameters = JsonSchema(
        properties = mapOf(
            "key" to JsonSchema.Property.Text(
                "A short stable name. Known keys: " + UserFact.KNOWN_KEYS.keys.joinToString(),
            ),
            "value" to JsonSchema.Property.Text("The fact itself, exactly as the user gave it."),
            "secret" to JsonSchema.Property.Flag(
                "Mark it as secret: it is redacted from logs and from the confirmation preview.",
                default = false,
            ),
        ),
        required = listOf("key", "value"),
    )

    override val example = """{"key":"home_address","value":"বাড়ি ১২, রোড ৫, ধানমন্ডি, ঢাকা"}"""

    override fun describeForConfirmation(params: JsonObject): ConfirmationPreview {
        val key = (params.stringOrNull("key") ?: "(unnamed)").lowercase()
        val value = params.stringOrNull("value") ?: ""
        val isSecret = params.get("secret")?.takeIf { it.isJsonPrimitive }?.asBoolean == true ||
            SENSITIVE_KEY_HINTS.any { key.contains(it) }
        return ConfirmationPreview(
            title = "Save this personal detail?",
            detailLines = listOf(
                "Field: $key (${UserFact.labelFor(key)})",
                if (isSecret) {
                    "Value: hidden, because this looks like an account or payment number."
                } else {
                    "Value: $value"
                },
                "Stored encrypted in Sarothi's vault, and filled into future tasks automatically.",
            ),
            reason = ConfirmationReason.PERSONAL_DATA,
            allowRemember = false,
        )
    }

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.vault.isUnlocked) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(VAULT_LOCKED, VAULT_LOCKED_FIX)
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val key = params.textOrAsk("key", "What should this fact be called? Something short like 'home_address'.")
        val value = params.textOrAsk("value", "What is the exact value? Sarothi will not fill anything in itself.")
        val secretFlag = params.get("secret")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val normalisedKey = key.trim().lowercase().replace(' ', '_')
        val secret = secretFlag || SENSITIVE_KEY_HINTS.any { normalisedKey.contains(it) }

        val previous = context.stores.userFacts.get(normalisedKey)
        val saved = runCatching {
            context.stores.userFacts.put(
                key = normalisedKey,
                value = value,
                sourceTaskId = context.task.taskId.takeIf { it != "none" },
                confirmedByUser = true,
                secret = secret,
            )
        }.getOrElse { failure ->
            return PluginResult.Failure(
                summaryForUser = "Sarothi could not save that: ${failure.message}",
                errorClass = failure.javaClass.simpleName,
                retriable = true,
            )
        }

        return PluginResult.Success(
            summaryForUser = if (previous == null) {
                "Saved ${saved.label}." + if (secret) " (kept secret: it will not appear in logs.)" else ""
            } else {
                "Updated ${saved.label}." + if (secret) " The old value is not repeated here." else
                    " It used to be \"${previous.value}\"."
            },
            data = Json.obj {
                addProperty("key", saved.key)
                addProperty("label", saved.label)
                if (!secret) addProperty("value", saved.value)
                addProperty("secret", secret)
                addProperty("replaced_previous", previous != null)
                addProperty("previous_value", if (previous != null && !secret) previous.value else "(hidden)")
            },
            spoken = "তথ্যটি সেভ করে রেখেছি।",
            undoToken = com.ngi.sarothi.plugins.common.UndoToken.encode(
                "user_fact",
                Json.obj {
                    addProperty("key", saved.key)
                    addProperty("secret", secret)
                    if (previous != null && !previous.secret) addProperty("previous_value", previous.value)
                    addProperty("had_previous", previous != null)
                },
            ),
            memorable = listOf("${saved.key} = ${if (secret) "(secret)" else saved.value}"),
        )
    }

    override suspend fun undo(undoToken: String): PluginResult {
        val context = pluginContext()
        val payload = com.ngi.sarothi.plugins.common.UndoToken.decode(undoToken, "user_fact")
            ?: return PluginResult.Failure(
                "That undo token is not one Sarothi issued.", "BadUndoTokenException", retriable = false,
            )
        val key = payload.get("key")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return PluginResult.Failure("The undo token has no key.", "BadUndoTokenException", retriable = false)
        val hadPrevious = payload.get("had_previous")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        return if (!hadPrevious) {
            if (context.stores.userFacts.delete(key)) {
                PluginResult.Success("Removed \"$key\" again.", Json.obj { addProperty("removed", key) })
            } else {
                PluginResult.Failure("\"$key\" was already gone.", "NotFoundException", retriable = false)
            }
        } else {
            val previousValue = payload.get("previous_value")?.takeIf { it.isJsonPrimitive }?.asString
            if (previousValue == null) {
                // The previous value was secret, so it was deliberately not kept in
                // the token. Say so instead of restoring something wrong.
                return PluginResult.Failure(
                    summaryForUser = "The value Sarothi replaced for \"$key\" was marked secret, so no " +
                        "copy was kept and it cannot be put back. Enter it again with save_user_fact.",
                    errorClass = "UndoUnavailableException",
                    retriable = false,
                )
            }
            val restored = context.stores.userFacts.put(key, previousValue, null, true, false)
            PluginResult.Success(
                "Put \"${restored.key}\" back to \"${restored.value}\".",
                Json.obj { addProperty("key", restored.key); addProperty("value", restored.value) },
            )
        }
    }

    private companion object {
        val SENSITIVE_KEY_HINTS = listOf(
            "bkash", "nagad", "rocket", "card", "account", "nid", "passport",
            "password", "pin", "upi", "bank", "secret", "otp",
        )
    }
}

/** Lists the personal facts Sarothi holds, so a task can use them instead of asking. */
class ReadUserFactsPlugin : Plugin {
    override val name = "read_user_facts"
    override val description =
        "List the personal facts Sarothi has saved. Use it at the start of any task that needs an " +
            "address, a number or a name — checking first is what stops Sarothi asking the user for " +
            "something they already told it."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "keys" to JsonSchema.Property.List("Only these keys. Empty returns everything saved.", items = JsonSchema.Property.Text("One key")),
            "missing_only" to JsonSchema.Property.Flag("Return only the well-known keys Sarothi does not have yet.", default = false),
        ),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.vault.isUnlocked) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(VAULT_LOCKED, VAULT_LOCKED_FIX)
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val wanted = params.getAsJsonArray("keys")?.mapNotNull {
            if (it.isJsonPrimitive) it.asString.trim().lowercase().takeIf { key -> key.isNotEmpty() } else null
        }?.toSet() ?: emptySet()
        val missingOnly = params.get("missing_only")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        if (missingOnly) {
            val missing = context.stores.userFacts.missingKnownKeys()
            return PluginResult.Success(
                if (missing.isEmpty()) "Sarothi has every well-known personal field saved."
                else "Sarothi is still missing: ${missing.joinToString { UserFact.labelFor(it) }}.",
                Json.obj {
                    add("missing", Json.arr { missing.forEach { add(it) } })
                    add("missing_labels", Json.arr { missing.forEach { add(UserFact.labelFor(it)) } })
                    addProperty("count", missing.size)
                },
            )
        }

        val all = context.stores.userFacts.all()
        val facts = all.values.filter { wanted.isEmpty() || it.key in wanted }
            .sortedBy { it.key }

        val data = Json.obj {
            add("facts", Json.arr {
                facts.forEach { fact ->
                    add(Json.obj {
                        addProperty("key", fact.key)
                        addProperty("label", fact.label)
                        if (fact.secret) {
                            addProperty("value", "(hidden: marked secret)")
                        } else {
                            addProperty("value", fact.value)
                        }
                        addProperty("secret", fact.secret)
                        addProperty("updated_at", fact.updatedAt)
                        addProperty("confirmed_by_user", fact.confirmedByUser)
                    })
                }
            })
            addProperty("count", facts.size)
        }
        return PluginResult.Success(
            summaryForUser = when {
                facts.isEmpty() && wanted.isEmpty() -> "Sarothi has no saved personal facts yet."
                facts.isEmpty() -> "Sarothi has no saved fact for: ${wanted.joinToString()}. Ask the user."
                else -> "${facts.size} saved fact(s): " + facts.joinToString("; ") { fact ->
                    "${fact.label} = ${if (fact.secret) "(hidden)" else fact.value}"
                }
            },
            data = data,
        )
    }
}
