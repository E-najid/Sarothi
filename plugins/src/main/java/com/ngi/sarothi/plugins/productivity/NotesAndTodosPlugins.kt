package com.ngi.sarothi.plugins.productivity

import com.google.gson.JsonObject
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.safety.ConfirmationPreview
import com.ngi.sarothi.core.safety.ConfirmationReason
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.plugins.common.textOrAsk
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatEpoch(epochMillis: Long?): String? = epochMillis?.let {
    LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()).format(DATE_FORMAT)
}

/** Saves a note into the encrypted vault. Reversible, so it can be undone. */
class SaveNotePlugin : Plugin {
    override val name = "save_note"
    override val description =
        "Save a note in Sarothi's encrypted vault. Use it when the user says 'remember this', 'make a " +
            "note', or when a task produces something worth keeping. Notes never leave the phone."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.NORMAL
    override val supportsUndo = true

    override val parameters = JsonSchema(
        properties = mapOf(
            "title" to JsonSchema.Property.Text("Short title."),
            "body" to JsonSchema.Property.Text("The note text."),
            "tags" to JsonSchema.Property.List("Optional tags for later searching.", items = JsonSchema.Property.Text("One tag")),
        ),
        required = listOf("body"),
    )

    override val example = """{"title":"বাসা ভাড়া","body":"প্রতি মাসের ৫ তারিখে ১২,০০০ টাকা"}"""

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val body = params.textOrAsk("body", "What should the note say?")
        val title = params.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: ""
        val tags = params.getAsJsonArray("tags")?.mapNotNull {
            if (it.isJsonPrimitive) it.asString else null
        } ?: emptyList()

        val note = context.stores.notes.create(title, body, tags)
        return PluginResult.Success(
            summaryForUser = "Saved the note \"${note.title}\".",
            data = Json.obj {
                addProperty("id", note.id)
                addProperty("title", note.title)
                addProperty("characters", note.body.length)
                add("tags", Json.arr { note.tags.forEach { add(it) } })
            },
            spoken = "নোট সেভ করে রেখেছি।",
            undoToken = note.id,
            memorable = listOf("note saved: ${note.title}"),
        )
    }

    override suspend fun undo(undoToken: String): PluginResult {
        val context = pluginContext()
        val deleted = context.stores.notes.delete(undoToken)
        return if (deleted) {
            PluginResult.Success("Deleted the note again.", Json.obj { addProperty("deleted", undoToken) })
        } else {
            PluginResult.Failure("That note is already gone.", "NotFoundException")
        }
    }
}

/** Searches notes. */
class SearchNotesPlugin : Plugin {
    override val name = "search_notes"
    override val description =
        "Search Sarothi's saved notes by keyword. Use it when the user asks 'did I note that down' or " +
            "'what did I write about …'. Leave the query empty to list the most recent notes."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "query" to JsonSchema.Property.Text("Keywords to look for. Empty lists recent notes."),
            "limit" to JsonSchema.Property.Integer("How many notes to return.", minimum = 1, maximum = 30, default = 8),
        ),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val query = params.stringOrNull("query")?.trim().orEmpty()
        val limit = params.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 30) ?: 8

        val notes = if (query.isEmpty()) context.stores.notes.all().take(limit)
        else context.stores.notes.search(query, limit)

        val data = Json.obj {
            addProperty("query", query)
            add("notes", Json.arr {
                notes.forEach { note ->
                    add(Json.obj {
                        addProperty("id", note.id)
                        addProperty("title", note.title)
                        addProperty("body", note.body.take(400))
                        addProperty("updated_at", note.updatedAt)
                        add("tags", Json.arr { note.tags.forEach { add(it) } })
                    })
                }
            })
            addProperty("count", notes.size)
        }
        return PluginResult.Success(
            if (notes.isEmpty()) {
                if (query.isEmpty()) "There are no saved notes yet." else "No note matches \"$query\"."
            } else {
                "${notes.size} note(s): " + notes.take(3).joinToString("; ") { it.title.ifBlank { it.body.take(30) } }
            },
            data,
        )
    }
}

/** Adds a to-do, optionally with a due date Sarothi will remind about. */
class AddTodoPlugin : Plugin {
    override val name = "add_todo"
    override val description =
        "Add a to-do item, optionally with a due date and time. Use it for anything the user wants to " +
            "remember to do. Give due_at as 'YYYY-MM-DD HH:mm' in local time, or leave it out."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.NORMAL
    override val supportsUndo = true

    override val parameters = JsonSchema(
        properties = mapOf(
            "title" to JsonSchema.Property.Text("What to do."),
            "notes" to JsonSchema.Property.Text("Optional extra detail."),
            "due_at" to JsonSchema.Property.Text("Due date/time as YYYY-MM-DD HH:mm, local time. Empty means no due date."),
            "priority" to JsonSchema.Property.Integer("0 low, 1 normal, 2 high, 3 urgent.", minimum = 0, maximum = 3, default = 1),
            "list" to JsonSchema.Property.Text("Which list, e.g. 'inbox', 'work', 'shopping'.", default = "inbox"),
        ),
        required = listOf("title"),
    )

    override val example = """{"title":"বিদ্যুৎ বিল দেওয়া","due_at":"2026-09-10 18:00","priority":2}"""

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val title = params.textOrAsk("title", "What is the to-do?")
        val notes = params.stringOrNull("notes")?.takeIf { it.isNotBlank() }
        val dueText = params.stringOrNull("due_at")?.takeIf { it.isNotBlank() }
        val priority = params.get("priority")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(0, 3) ?: 1
        val listName = params.stringOrNull("list")?.takeIf { it.isNotBlank() } ?: "inbox"

        val dueAt = dueText?.let { text -> parseLocalDateTime(text) }
        if (dueText != null && dueAt == null) {
            return PluginResult.Failure(
                summaryForUser = "\"$dueText\" is not a date Sarothi can read. Use YYYY-MM-DD HH:mm, " +
                    "for example 2026-09-10 18:00, or leave the due date out.",
                errorClass = "DateTimeParseException",
                retriable = true,
            )
        }
        if (dueAt != null && dueAt < System.currentTimeMillis()) {
            return PluginResult.Failure(
                summaryForUser = "That due date (${formatEpoch(dueAt)}) is already in the past. " +
                    "Sarothi will not create a to-do that looks overdue the moment it is made.",
                errorClass = "PastDueDateException",
                retriable = true,
            )
        }

        val todo = context.stores.todos.create(title, notes, dueAt, priority, listName)
        return PluginResult.Success(
            summaryForUser = "Added \"${todo.title}\" to ${todo.list}" +
                (dueAt?.let { ", due ${formatEpoch(it)}" } ?: ""),
            data = Json.obj {
                addProperty("id", todo.id)
                addProperty("title", todo.title)
                addProperty("list", todo.listName)
                addProperty("priority", todo.priority)
                todo.dueAtEpochMillis?.let { addProperty("due_at", it) }
                addProperty("due_at_text", formatEpoch(todo.dueAtEpochMillis) ?: "none")
            },
            spoken = "তালিকায় যোগ করে দিয়েছি।",
            undoToken = todo.id,
            memorable = listOf("to-do added: ${todo.title}" + (dueAt?.let { " due ${formatEpoch(it)}" } ?: "")),
        )
    }

    override suspend fun undo(undoToken: String): PluginResult {
        val context = pluginContext()
        return if (context.stores.todos.delete(undoToken)) {
            PluginResult.Success("Removed the to-do again.", Json.obj { addProperty("deleted", undoToken) })
        } else {
            PluginResult.Failure("That to-do is already gone.", "NotFoundException")
        }
    }
}

/** Lists to-dos. */
class ListTodosPlugin : Plugin {
    override val name = "list_todos"
    override val description =
        "List to-do items: open ones by default, or everything, or what is due before a date. Use it " +
            "when the user asks what they still have to do."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "include_completed" to JsonSchema.Property.Flag("Also show finished items.", default = false),
            "list" to JsonSchema.Property.Text("Only this list, e.g. 'work'."),
            "due_before" to JsonSchema.Property.Text("Only items due before YYYY-MM-DD HH:mm."),
        ),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val includeCompleted = params.get("include_completed")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val wantedList = params.stringOrNull("list")?.takeIf { it.isNotBlank() }
        val dueBeforeText = params.stringOrNull("due_before")?.takeIf { it.isNotBlank() }

        val dueBefore = dueBeforeText?.let { parseLocalDateTime(it) }
        if (dueBeforeText != null && dueBefore == null) {
            return PluginResult.Failure(
                "\"$dueBeforeText\" is not a date Sarothi can read. Use YYYY-MM-DD HH:mm.",
                "DateTimeParseException",
                retriable = true,
            )
        }

        val todos = (if (dueBefore != null) context.stores.todos.dueBefore(dueBefore, includeCompleted)
        else context.stores.todos.all(includeCompleted))
            .filter { wantedList == null || it.listName.equals(wantedList, ignoreCase = true) }

        val data = Json.obj {
            add("todos", Json.arr {
                todos.forEach { todo ->
                    add(Json.obj {
                        addProperty("id", todo.id)
                        addProperty("title", todo.title)
                        addProperty("list", todo.listName)
                        addProperty("priority", todo.priority)
                        addProperty("completed", todo.completed)
                        addProperty("due_at", formatEpoch(todo.dueAtEpochMillis) ?: "none")
                        todo.notes?.let { addProperty("notes", it) }
                    })
                }
            })
            addProperty("count", todos.size)
            addProperty("open_count", todos.count { !it.completed })
        }
        val overdue = todos.count { !it.completed && it.dueAtEpochMillis != null && it.dueAtEpochMillis < System.currentTimeMillis() }
        return PluginResult.Success(
            when {
                todos.isEmpty() -> "No to-dos match that."
                else -> {
                    val openCount = todos.count { !it.completed }
                    val overdueNote = if (overdue > 0) ", $overdue already overdue" else ""
                    val first = todos.filter { !it.completed }.take(3).joinToString("; ") { it.title }
                    "$openCount open to-do(s)$overdueNote" + if (first.isNotBlank()) ": $first" else ""
                }
            },
            data,
        )
    }
}

/** Marks a to-do done (or reopens it). */
class CompleteTodoPlugin : Plugin {
    override val name = "complete_todo"
    override val description =
        "Mark a to-do as done, or reopen one. Give the id from list_todos, or enough of the title for " +
            "Sarothi to find it — if more than one matches, it will ask rather than guess."
    override val category = PluginCategory.PRODUCTIVITY
    override val sensitivity = Sensitivity.NORMAL
    override val supportsUndo = true

    override val parameters = JsonSchema(
        properties = mapOf(
            "todo_id" to JsonSchema.Property.Text("The id from list_todos."),
            "title_contains" to JsonSchema.Property.Text("Part of the title, when the id is unknown."),
            "completed" to JsonSchema.Property.Flag("true marks it done, false reopens it.", default = true),
        ),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        val completed = params.get("completed")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        val id = params.stringOrNull("todo_id")?.takeIf { it.isNotBlank() }
        val titlePart = params.stringOrNull("title_contains")?.takeIf { it.isNotBlank() }

        val target = when {
            id != null -> context.stores.todos.byId(id)
            titlePart != null -> {
                val matches = context.stores.todos.all().filter {
                    it.title.contains(titlePart, ignoreCase = true)
                }
                when {
                    matches.isEmpty() -> return PluginResult.Failure(
                        "No to-do has \"$titlePart\" in its title.",
                        "NotFoundException",
                        retriable = true,
                    )
                    matches.size == 1 -> matches.first()
                    else -> return PluginResult.NeedsUserInput(
                        question = "${matches.size} to-dos match \"$titlePart\". Which one?",
                        field = "todo_id",
                        choices = matches.take(6).map { "${it.id}: ${it.title}" },
                    )
                }
            }
            else -> throw com.ngi.sarothi.core.error.MissingInformationException(
                field = "todo_id",
                questionForUser = "Which to-do should Sarothi ${if (completed) "mark done" else "reopen"}?",
            )
        } ?: return PluginResult.Failure("That to-do does not exist any more.", "NotFoundException", retriable = false)

        if (target.completed == completed) {
            return PluginResult.Success(
                "\"${target.title}\" is already ${if (completed) "done" else "open"}; nothing changed.",
                Json.obj { addProperty("id", target.id); addProperty("completed", target.completed) },
            )
        }
        val updated = context.stores.todos.setCompleted(target.id, completed)
            ?: return PluginResult.Failure("That to-do disappeared before Sarothi could update it.", "NotFoundException")

        return PluginResult.Success(
            summaryForUser = if (completed) "Marked \"${updated.title}\" as done." else "Reopened \"${updated.title}\".",
            data = Json.obj { addProperty("id", updated.id); addProperty("completed", updated.completed) },
            spoken = if (completed) "শেষ হিসেবে চিহ্নিত করেছি।" else "আবার খুলে দিয়েছি।",
            undoToken = "${updated.id}:${!completed}",
        )
    }

    override suspend fun undo(undoToken: String): PluginResult {
        val context = pluginContext()
        val parts = undoToken.split(':')
        if (parts.size != 2) {
            return PluginResult.Failure("That undo token is not one Sarothi issued.", "BadUndoTokenException")
        }
        val previousState = parts[1].toBooleanStrictOrNull()
            ?: return PluginResult.Failure("That undo token is not one Sarothi issued.", "BadUndoTokenException")
        val restored = context.stores.todos.setCompleted(parts[0], previousState)
            ?: return PluginResult.Failure("That to-do no longer exists.", "NotFoundException")
        return PluginResult.Success(
            "Put \"${restored.title}\" back to ${if (previousState) "done" else "open"}.",
            Json.obj { addProperty("id", restored.id) },
        )
    }
}

/**
 * Parses the local date-times a model or a user is likely to produce.
 *
 * Returns null rather than throwing so the caller can tell the user exactly what
 * format is wanted. Guessing a date from "next Friday" is not done here: the model
 * is instructed to produce an absolute date, and if it cannot, the task asks.
 */
internal fun parseLocalDateTime(text: String): Long? {
    val trimmed = text.trim()
    val patterns = listOf(
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
        "dd/MM/yyyy HH:mm",
        "dd-MM-yyyy HH:mm",
    )
    for (pattern in patterns) {
        val formatter = DateTimeFormatter.ofPattern(pattern)
        val parsed = runCatching {
            if (pattern.contains("H")) {
                LocalDateTime.parse(trimmed, formatter)
            } else {
                java.time.LocalDate.parse(trimmed, formatter).atStartOfDay()
            }
        }.getOrNull()
        if (parsed != null) {
            return parsed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }
    return runCatching { trimmed.toLong() }.getOrNull()?.takeIf { it > 1_000_000_000_000L }
}
