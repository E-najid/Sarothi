package com.ngi.sarothi.core.data

import com.google.gson.JsonObject
import com.ngi.sarothi.core.storage.VaultManager
import com.ngi.sarothi.core.storage.VaultPaths
import com.ngi.sarothi.core.util.Ids
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Vault-backed implementations of every store in [DataStores].
 *
 * They all read and write through [VaultManager], which enforces the lock: with no
 * passphrase-derived key in memory these calls throw
 * [com.ngi.sarothi.core.error.VaultLockedException] rather than quietly writing
 * plaintext somewhere else.
 */
class VaultMemoryStore(vault: VaultManager) : MemoryStore {

    private val collection = VaultJsonCollection(
        vault = vault,
        path = VaultPaths.MEMORIES,
        arrayKey = "memories",
        toItem = { Memory.fromJson(it) },
        fromItem = { it.toJson() },
    )

    override suspend fun all(): List<Memory> = collection.snapshot()

    override suspend fun byId(id: String): Memory? = collection.read { items -> items.firstOrNull { it.id == id } }

    override suspend fun add(
        kind: MemoryKind,
        text: String,
        tags: List<String>,
        importance: Int,
        sourceTaskId: String?,
    ): Memory {
        require(text.isNotBlank()) { "A memory needs some text" }
        val now = Instant.now().toString()
        val memory = Memory(
            id = Ids.memoryId(),
            kind = kind,
            text = text.trim(),
            createdAt = now,
            updatedAt = now,
            sourceTaskId = sourceTaskId,
            tags = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
            importance = importance.coerceIn(1, 5),
            pinned = false,
        )
        return collection.mutate { items ->
            // The same fact learned twice should update, not duplicate: a memory
            // store that grows with every task becomes noise the model drowns in.
            val existingIndex = items.indexOfFirst {
                it.kind == memory.kind && it.text.equals(memory.text, ignoreCase = true)
            }
            if (existingIndex >= 0) {
                val previous = items[existingIndex]
                items[existingIndex] = previous.copy(
                    updatedAt = memory.updatedAt,
                    sourceTaskId = memory.sourceTaskId ?: previous.sourceTaskId,
                    tags = (previous.tags + memory.tags).distinct(),
                    importance = maxOf(previous.importance, memory.importance),
                )
                items[existingIndex]
            } else {
                items += memory
                memory
            }
        }
    }

    override suspend fun update(
        id: String,
        text: String?,
        tags: List<String>?,
        importance: Int?,
        pinned: Boolean?,
    ): Memory? = collection.mutate { items ->
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@mutate null
        val updated = items[index].copy(
            text = text?.trim()?.takeIf { it.isNotEmpty() } ?: items[index].text,
            tags = tags?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.distinct() ?: items[index].tags,
            importance = importance?.coerceIn(1, 5) ?: items[index].importance,
            pinned = pinned ?: items[index].pinned,
            updatedAt = Instant.now().toString(),
        )
        items[index] = updated
        updated
    }

    override suspend fun delete(id: String): Boolean = collection.mutate { items ->
        val before = items.size
        items.removeAll { it.id == id }
        items.size < before
    }

    override suspend fun search(query: String, limit: Int): List<MemoryMatch> =
        collection.read { items -> rank(items, query, limit) }

    override suspend fun byKind(kind: MemoryKind): List<Memory> =
        collection.read { items -> items.filter { it.kind == kind }.sortedByDescending { it.updatedAt } }

    override suspend fun recent(limit: Int): List<Memory> =
        collection.read { items -> items.sortedByDescending { it.updatedAt }.take(limit) }

    override suspend fun count(): Int = collection.size()

    companion object {
        /**
         * Ranks memories by term overlap, then importance, recency and pinning.
         *
         * This is deliberately a transparent keyword scorer rather than an
         * embedding lookup: an embedding model would cost hundreds of megabytes
         * Sarothi does not have on a 3 GB phone, and a scorer whose arithmetic can
         * be read is one whose mistakes can be diagnosed.
         */
        fun rank(memories: List<Memory>, query: String, limit: Int): List<MemoryMatch> {
            val terms = tokenize(query)
            if (terms.isEmpty()) {
                return memories.sortedWith(
                    compareByDescending<Memory> { it.pinned }
                        .thenByDescending { it.importance }
                        .thenByDescending { it.updatedAt },
                ).take(limit).map { MemoryMatch(it, 0.0, emptyList()) }
            }
            val now = Instant.now().toEpochMilli()
            return memories.mapNotNull { memory ->
                val haystack = tokenize(memory.text) + memory.tags.flatMap { tokenize(it) } +
                    tokenize(memory.kind.name)
                if (haystack.isEmpty()) return@mapNotNull null
                val matched = terms.filter { term -> haystack.any { it.contains(term) } }
                if (matched.isEmpty()) return@mapNotNull null

                val coverage = matched.size.toDouble() / terms.size.toDouble()
                val density = matched.size.toDouble() / haystack.size.toDouble()
                val ageDays = ((now - parseEpoch(memory.updatedAt)) / 86_400_000.0).coerceAtLeast(0.0)
                val recency = 1.0 / (1.0 + ageDays / 30.0)
                val score = coverage * 3.0 +
                    density * 1.5 +
                    memory.importance * 0.25 +
                    recency * 0.5 +
                    if (memory.pinned) 1.0 else 0.0
                MemoryMatch(memory, score, matched)
            }
                .sortedByDescending { it.score }
                .take(limit)
        }

        private fun parseEpoch(iso: String): Long =
            runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

        /** Lowercased alphanumeric runs; keeps Bengali characters intact. */
        fun tokenize(text: String): List<String> =
            text.lowercase().split { it.isLetterOrDigit().not() }.filter { it.length > 1 }
    }
}

class VaultNotesStore(vault: VaultManager) : NotesStore {

    private val collection = VaultJsonCollection(
        vault = vault,
        path = VaultPaths.NOTES,
        arrayKey = "notes",
        toItem = { Note.fromJson(it) },
        fromItem = { it.toJson() },
    )

    override suspend fun all(): List<Note> = collection.read { items ->
        items.sortedWith(
            compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt },
        )
    }

    override suspend fun byId(id: String): Note? = collection.read { items -> items.firstOrNull { it.id == id } }

    override suspend fun create(title: String, body: String, tags: List<String>): Note {
        require(title.isNotBlank() || body.isNotBlank()) { "A note needs a title or a body" }
        val now = Instant.now().toString()
        val note = Note(
            id = Ids.noteId(),
            title = title.trim().ifBlank { body.trim().lineSequence().first().take(60) },
            body = body.trim(),
            createdAt = now,
            updatedAt = now,
            tags = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
            pinned = false,
        )
        return collection.mutate { items ->
            items += note
            note
        }
    }

    override suspend fun update(
        id: String,
        title: String?,
        body: String?,
        tags: List<String>?,
        pinned: Boolean?,
    ): Note? = collection.mutate { items ->
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@mutate null
        val updated = items[index].copy(
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: items[index].title,
            body = body?.trim() ?: items[index].body,
            tags = tags?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.distinct() ?: items[index].tags,
            pinned = pinned ?: items[index].pinned,
            updatedAt = Instant.now().toString(),
        )
        items[index] = updated
        updated
    }

    override suspend fun delete(id: String): Boolean = collection.mutate { items ->
        val before = items.size
        items.removeAll { it.id == id }
        items.size < before
    }

    override suspend fun search(query: String, limit: Int): List<Note> {
        val terms = VaultMemoryStore.tokenize(query)
        if (terms.isEmpty()) return all().take(limit)
        return collection.read { items ->
            items.mapNotNull { note ->
                val haystack = VaultMemoryStore.tokenize(note.title) +
                    VaultMemoryStore.tokenize(note.body) + note.tags
                val matched = terms.count { term -> haystack.any { it.contains(term) } }
                if (matched == 0) null else matched to note
            }
                .sortedWith(
                    compareByDescending<Pair<Int, Note>> { it.first }
                        .thenByDescending { it.second.pinned }
                        .thenByDescending { it.second.updatedAt },
                )
                .take(limit)
                .map { it.second }
        }
    }
}

class VaultTodoStore(vault: VaultManager) : TodoStore {

    private val collection = VaultJsonCollection(
        vault = vault,
        path = VaultPaths.TODOS,
        arrayKey = "todos",
        toItem = { Todo.fromJson(it) },
        fromItem = { it.toJson() },
    )

    override suspend fun all(includeCompleted: Boolean): List<Todo> = collection.read { items ->
        items.filter { includeCompleted || !it.completed }.sortedWith(
            compareBy<Todo> { it.completed }
                .thenBy { it.dueAtEpochMillis ?: Long.MAX_VALUE }
                .thenByDescending { it.priority }
                .thenByDescending { it.createdAtEpochMillis },
        )
    }

    override suspend fun byId(id: String): Todo? = collection.read { items -> items.firstOrNull { it.id == id } }

    override suspend fun create(
        title: String,
        notes: String?,
        dueAtEpochMillis: Long?,
        priority: Int,
        listName: String,
    ): Todo {
        require(title.isNotBlank()) { "A to-do needs a title" }
        val todo = Todo(
            id = Ids.todoId(),
            title = title.trim(),
            notes = notes?.trim()?.takeIf { it.isNotEmpty() },
            dueAtEpochMillis = dueAtEpochMillis,
            completed = false,
            completedAtEpochMillis = null,
            createdAtEpochMillis = System.currentTimeMillis(),
            priority = priority.coerceIn(0, 3),
            listName = listName.trim().ifBlank { "inbox" },
            reminderSet = false,
        )
        return collection.mutate { items ->
            items += todo
            todo
        }
    }

    override suspend fun setCompleted(id: String, completed: Boolean): Todo? = collection.mutate { items ->
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@mutate null
        val updated = items[index].copy(
            completed = completed,
            completedAtEpochMillis = if (completed) System.currentTimeMillis() else null,
        )
        items[index] = updated
        updated
    }

    override suspend fun update(
        id: String,
        title: String?,
        dueAtEpochMillis: Long?,
        priority: Int?,
        listName: String?,
    ): Todo? = collection.mutate { items ->
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@mutate null
        val updated = items[index].copy(
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: items[index].title,
            dueAtEpochMillis = dueAtEpochMillis ?: items[index].dueAtEpochMillis,
            priority = priority?.coerceIn(0, 3) ?: items[index].priority,
            listName = listName?.trim()?.takeIf { it.isNotEmpty() } ?: items[index].listName,
        )
        items[index] = updated
        updated
    }

    override suspend fun delete(id: String): Boolean = collection.mutate { items ->
        val before = items.size
        items.removeAll { it.id == id }
        items.size < before
    }

    override suspend fun markReminderSet(id: String): Boolean = collection.mutate { items ->
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@mutate false
        items[index] = items[index].copy(reminderSet = true)
        true
    }

    override suspend fun dueBefore(epochMillis: Long, includeCompleted: Boolean): List<Todo> =
        collection.read { items ->
            items.filter { (includeCompleted || !it.completed) && it.dueAtEpochMillis != null && it.dueAtEpochMillis <= epochMillis }
                .sortedBy { it.dueAtEpochMillis }
        }
}

class VaultUserFactsStore(vault: VaultManager) : UserFactsStore {

    private val collection = VaultJsonCollection(
        vault = vault,
        path = VaultPaths.USER_FACTS,
        arrayKey = "facts",
        toItem = { UserFact.fromJson(it) },
        fromItem = { it.toJson() },
    )

    override suspend fun all(): Map<String, UserFact> = collection.read { items ->
        items.associateBy { it.key }
    }

    override suspend fun get(key: String): UserFact? =
        collection.read { items -> items.firstOrNull { it.key == key } }

    override suspend fun put(
        key: String,
        value: String,
        sourceTaskId: String?,
        confirmedByUser: Boolean,
        secret: Boolean,
    ): UserFact {
        val normalisedKey = key.trim().lowercase().replace(' ', '_')
        require(normalisedKey.isNotEmpty()) { "A fact needs a key" }
        require(value.isNotBlank()) { "Refusing to store an empty value for '$normalisedKey'" }
        val fact = UserFact(
            key = normalisedKey,
            value = value.trim(),
            label = UserFact.labelFor(normalisedKey),
            updatedAt = Instant.now().toString(),
            sourceTaskId = sourceTaskId,
            confirmedByUser = confirmedByUser,
            secret = secret,
        )
        return collection.mutate { items ->
            items.removeAll { it.key == normalisedKey }
            items += fact
            fact
        }
    }

    override suspend fun delete(key: String): Boolean = collection.mutate { items ->
        val before = items.size
        items.removeAll { it.key == key.trim().lowercase() }
        items.size < before
    }

    override suspend fun missingKnownKeys(): List<String> = collection.read { items ->
        val present = items.map { it.key }.toSet()
        UserFact.KNOWN_KEYS.keys.filterNot { it in present }
    }
}

class VaultConversationStore(private val vault: VaultManager) : ConversationStore {

    override suspend fun listConversations(): List<ConversationSummary> = withContext(Dispatchers.IO) {
        val fs = vault.requireFileSystem()
        fs.createDirectories(VaultPaths.CONVERSATIONS_DIR)
        fs.listFiles(VaultPaths.CONVERSATIONS_DIR)
            .filter { !it.isDirectory && it.name.endsWith(".json") }
            .mapNotNull { entry ->
                val path = "${VaultPaths.CONVERSATIONS_DIR}/${entry.name}"
                val json = runCatching { vault.readEncryptedJson(path) }.getOrNull() ?: return@mapNotNull null
                Conversation.fromJson(json)?.let { conversation ->
                    ConversationSummary(
                        id = conversation.id,
                        title = conversation.title,
                        updatedAt = conversation.updatedAt,
                        messageCount = conversation.messages.size,
                        lastMessagePreview = conversation.messages.lastOrNull()?.content?.take(80),
                    )
                }
            }
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun load(conversationId: String): Conversation? = withContext(Dispatchers.IO) {
        readConversation(conversationId)
    }

    private fun readConversation(conversationId: String): Conversation? {
        val path = VaultPaths.conversationPath(conversationId)
        val fs = vault.requireFileSystem()
        if (!fs.exists(path)) return null
        val json = vault.readEncryptedJson(path) ?: return null
        return Conversation.fromJson(json)
    }

    private fun writeConversation(conversation: Conversation) {
        vault.writeEncryptedJson(VaultPaths.conversationPath(conversation.id), conversation.toJson())
    }

    override suspend fun create(title: String?): Conversation = withContext(Dispatchers.IO) {
        val conversation = Conversation(
            id = Ids.conversationId(),
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString(),
            messages = emptyList(),
        )
        vault.requireFileSystem().createDirectories(VaultPaths.CONVERSATIONS_DIR)
        writeConversation(conversation)
        conversation
    }

    override suspend fun append(conversationId: String, message: ChatMessage): Conversation? =
        withContext(Dispatchers.IO) {
            val existing = readConversation(conversationId) ?: return@withContext null
            val updated = existing.copy(
                messages = existing.messages + message,
                title = existing.title ?: deriveTitle(message),
                updatedAt = Instant.now().toString(),
            )
            writeConversation(updated)
            updated
        }

    private fun deriveTitle(message: ChatMessage): String? = when (message.role) {
        ChatRole.USER -> message.content.lineSequence().first().take(48).ifBlank { null }
        else -> null
    }

    override suspend fun setTitle(conversationId: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val existing = readConversation(conversationId) ?: return@withContext false
        writeConversation(existing.copy(title = title.trim(), updatedAt = Instant.now().toString()))
        true
    }

    override suspend fun delete(conversationId: String): Boolean = withContext(Dispatchers.IO) {
        vault.requireFileSystem().deleteFile(VaultPaths.conversationPath(conversationId))
    }

    override suspend fun tail(conversationId: String, maxMessages: Int): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            val conversation = readConversation(conversationId) ?: return@withContext emptyList()
            if (maxMessages <= 0) return@withContext emptyList()
            conversation.messages.takeLast(maxMessages)
        }
}

class VaultTaskHistoryStore(private val vault: VaultManager) : TaskHistoryStore {

    override suspend fun save(record: TaskRecord): Unit = withContext(Dispatchers.IO) {
        vault.requireFileSystem().createDirectories(VaultPaths.TASK_HISTORY_DIR)
        vault.writeEncryptedJson(VaultPaths.taskPath(record.id), TaskRecords.toJson(record))
    }

    override suspend fun load(taskId: String): TaskRecord? = withContext(Dispatchers.IO) {
        val path = VaultPaths.taskPath(taskId)
        if (!vault.requireFileSystem().exists(path)) return@withContext null
        val json = runCatching { vault.readEncryptedJson(path) }.getOrNull() ?: return@withContext null
        TaskRecords.fromJson(json)
    }

    override suspend fun recent(limit: Int): List<TaskRecord> = withContext(Dispatchers.IO) {
        val fs = vault.requireFileSystem()
        fs.createDirectories(VaultPaths.TASK_HISTORY_DIR)
        fs.listFiles(VaultPaths.TASK_HISTORY_DIR)
            .filter { !it.isDirectory && it.name.endsWith(".json") }
            .sortedByDescending { it.lastModifiedEpochMillis }
            .take(limit)
            .mapNotNull { entry ->
                val json = runCatching {
                    vault.readEncryptedJson("${VaultPaths.TASK_HISTORY_DIR}/${entry.name}")
                }.getOrNull()
                json?.let { TaskRecords.fromJson(it) }
            }
    }

    override suspend fun delete(taskId: String): Boolean = withContext(Dispatchers.IO) {
        vault.requireFileSystem().deleteFile(VaultPaths.taskPath(taskId))
    }

    override suspend fun count(): Int = withContext(Dispatchers.IO) {
        vault.requireFileSystem().listFiles(VaultPaths.TASK_HISTORY_DIR).count { !it.isDirectory }
    }

    override suspend fun clearOlderThan(epochMillis: Long): Int = withContext(Dispatchers.IO) {
        val fs = vault.requireFileSystem()
        fs.listFiles(VaultPaths.TASK_HISTORY_DIR)
            .filter { !it.isDirectory && it.lastModifiedEpochMillis < epochMillis }
            .count { entry -> fs.deleteFile("${VaultPaths.TASK_HISTORY_DIR}/${entry.name}") }
    }
}

/** Serialisation for [TaskRecord], kept beside the store that persists it. */
object TaskRecords {

    fun toJson(record: TaskRecord): JsonObject = Json.obj {
        addProperty("schema_version", 1)
        addProperty("id", record.id)
        addProperty("created_at", record.createdAt)
        record.finishedAt?.let { addProperty("finished_at", it) }
        addProperty("request", record.request)
        addProperty("language", record.language)
        addProperty("trigger", record.trigger.name.lowercase())
        addProperty("persona", record.personaName)
        addProperty("status", record.status.name.lowercase())
        record.finalMessage?.let { addProperty("final_message", it) }
        record.failureReason?.let { addProperty("failure_reason", it) }
        addProperty("replan_count", record.replanCount)
        addProperty("total_tokens", record.totalTokens)
        addProperty("elapsed_millis", record.elapsedMillis)
        addProperty("needed_user_input", record.neededUserInput)
        addProperty("confirmation_count", record.confirmationCount)
        add("steps", Json.arr {
            record.steps.forEach { step ->
                add(Json.obj {
                    addProperty("id", step.id)
                    addProperty("index", step.index)
                    addProperty("intent", step.intent)
                    step.plugin?.let { addProperty("plugin", it) }
                    step.parametersDigest?.let { addProperty("params_digest", it) }
                    addProperty("status", step.status.name.lowercase())
                    step.resultSummary?.let { addProperty("result", it) }
                    step.errorSummary?.let { addProperty("error", it) }
                    step.startedAt?.let { addProperty("started_at", it) }
                    step.finishedAt?.let { addProperty("finished_at", it) }
                    addProperty("sensitivity", step.sensitivity)
                    step.confirmation?.let { addProperty("confirmation", it) }
                    step.undoToken?.let { addProperty("undo_token", it) }
                })
            }
        })
    }

    fun fromJson(json: JsonObject): TaskRecord? {
        val id = json.stringOrNull("id") ?: return null
        val request = json.stringOrNull("request") ?: ""
        val steps = json.getAsJsonArray("steps")?.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val step = element.asJsonObject
            TaskStepRecord(
                id = step.stringOrNull("id") ?: return@mapNotNull null,
                index = step.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                intent = step.stringOrNull("intent") ?: "",
                plugin = step.stringOrNull("plugin"),
                parametersDigest = step.stringOrNull("params_digest"),
                status = enumOrDefault(
                    step.stringOrNull("status"),
                    StepStatus.PENDING,
                ),
                resultSummary = step.stringOrNull("result"),
                errorSummary = step.stringOrNull("error"),
                startedAt = step.stringOrNull("started_at"),
                finishedAt = step.stringOrNull("finished_at"),
                sensitivity = step.stringOrNull("sensitivity") ?: "normal",
                confirmation = step.stringOrNull("confirmation"),
                undoToken = step.stringOrNull("undo_token"),
            )
        } ?: emptyList()

        return TaskRecord(
            id = id,
            createdAt = json.stringOrNull("created_at") ?: Instant.now().toString(),
            finishedAt = json.stringOrNull("finished_at"),
            request = request,
            language = json.stringOrNull("language") ?: "bn",
            trigger = enumOrDefault(json.stringOrNull("trigger"), TaskTrigger.USER_TEXT),
            personaName = json.stringOrNull("persona") ?: "সারথি",
            steps = steps,
            status = enumOrDefault(json.stringOrNull("status"), TaskStatus.FAILED),
            finalMessage = json.stringOrNull("final_message"),
            failureReason = json.stringOrNull("failure_reason"),
            replanCount = json.get("replan_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
            totalTokens = json.get("total_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
            elapsedMillis = json.get("elapsed_millis")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            neededUserInput = json.get("needed_user_input")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            confirmationCount = json.get("confirmation_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, fallback: T): T =
        value?.let { raw -> enumValues<T>().firstOrNull { it.name.equals(raw, true) } } ?: fallback
}
