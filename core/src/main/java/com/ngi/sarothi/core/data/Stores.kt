package com.ngi.sarothi.core.data

/**
 * The durable data Sarothi keeps for the user.
 *
 * Everything here lives under the vault's `memories/` directory and is therefore
 * encrypted at rest with a key derived from the user's passphrase. Nothing in
 * this layer may fall back to unencrypted storage when the vault is locked: the
 * stores throw [com.ngi.sarothi.core.error.VaultLockedException] instead, and the
 * UI shows the lock screen.
 */
interface MemoryStore {
    suspend fun all(): List<Memory>
    suspend fun byId(id: String): Memory?
    suspend fun add(kind: MemoryKind, text: String, tags: List<String>, importance: Int, sourceTaskId: String?): Memory
    suspend fun update(id: String, text: String?, tags: List<String>?, importance: Int?, pinned: Boolean?): Memory?
    suspend fun delete(id: String): Boolean

    /**
     * Ranked keyword search. Returns matches with their score and the terms that
     * produced it, because a memory system that cannot explain its ranking is one
     * the user cannot trust.
     */
    suspend fun search(query: String, limit: Int = 8): List<MemoryMatch>

    suspend fun byKind(kind: MemoryKind): List<Memory>
    suspend fun recent(limit: Int = 20): List<Memory>
    suspend fun count(): Int
}

interface NotesStore {
    suspend fun all(): List<Note>
    suspend fun byId(id: String): Note?
    suspend fun create(title: String, body: String, tags: List<String>): Note
    suspend fun update(id: String, title: String?, body: String?, tags: List<String>?, pinned: Boolean?): Note?
    suspend fun delete(id: String): Boolean
    suspend fun search(query: String, limit: Int = 10): List<Note>
}

interface TodoStore {
    suspend fun all(includeCompleted: Boolean = true): List<Todo>
    suspend fun byId(id: String): Todo?
    suspend fun create(
        title: String,
        notes: String?,
        dueAtEpochMillis: Long?,
        priority: Int,
        listName: String,
    ): Todo

    suspend fun setCompleted(id: String, completed: Boolean): Todo?
    suspend fun update(id: String, title: String?, dueAtEpochMillis: Long?, priority: Int?, listName: String?): Todo?
    suspend fun delete(id: String): Boolean
    suspend fun markReminderSet(id: String): Boolean
    suspend fun dueBefore(epochMillis: Long, includeCompleted: Boolean = false): List<Todo>
}

interface UserFactsStore {
    suspend fun all(): Map<String, UserFact>
    suspend fun get(key: String): UserFact?
    suspend fun put(key: String, value: String, sourceTaskId: String?, confirmedByUser: Boolean, secret: Boolean): UserFact
    suspend fun delete(key: String): Boolean

    /** Keys Sarothi knows about but the user has never answered. */
    suspend fun missingKnownKeys(): List<String>
}

interface ConversationStore {
    suspend fun listConversations(): List<ConversationSummary>
    suspend fun load(conversationId: String): Conversation?
    suspend fun create(title: String?): Conversation
    suspend fun append(conversationId: String, message: ChatMessage): Conversation?
    suspend fun setTitle(conversationId: String, title: String): Boolean
    suspend fun delete(conversationId: String): Boolean

    /**
     * The trailing window of a conversation, oldest first, for building a prompt.
     * Bounded by [maxMessages] because a 350 M model has a small context and
     * silently overflowing it would corrupt the reply.
     */
    suspend fun tail(conversationId: String, maxMessages: Int): List<ChatMessage>
}

data class ConversationSummary(
    val id: String,
    val title: String?,
    val updatedAt: String,
    val messageCount: Int,
    val lastMessagePreview: String?,
)

/**
 * Persisted record of one task: what was asked, what was planned, what each step
 * did, and how it ended. Written to `task_history/` and shown verbatim in the
 * History screen — this is the "what did the agent do while I wasn't looking"
 * answer, so it must be complete rather than summarised.
 */
data class TaskRecord(
    val id: String,
    val createdAt: String,
    val finishedAt: String?,
    val request: String,
    val language: String,
    val trigger: TaskTrigger,
    val personaName: String,
    val steps: List<TaskStepRecord>,
    val status: TaskStatus,
    val finalMessage: String?,
    val failureReason: String?,
    val replanCount: Int,
    val totalTokens: Int,
    val elapsedMillis: Long,
    val neededUserInput: Boolean,
    val confirmationCount: Int,
)

enum class TaskTrigger { USER_TEXT, USER_VOICE, SCHEDULE, NOTIFICATION_RULE, QUICK_ACTION, CONTINUED }
enum class TaskStatus { PLANNING, RUNNING, WAITING_FOR_USER, COMPLETED, FAILED, CANCELLED, PARTIALLY_COMPLETED }

data class TaskStepRecord(
    val id: String,
    val index: Int,
    val intent: String,
    val plugin: String?,
    val parametersDigest: String?,
    val status: StepStatus,
    val resultSummary: String?,
    val errorSummary: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val sensitivity: String,
    val confirmation: String?,
    val undoToken: String?,
)

enum class StepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED, WAITING_FOR_USER, DENIED }

interface TaskHistoryStore {
    suspend fun save(record: TaskRecord)
    suspend fun load(taskId: String): TaskRecord?
    suspend fun recent(limit: Int = 50): List<TaskRecord>
    suspend fun delete(taskId: String): Boolean
    suspend fun count(): Int
    suspend fun clearOlderThan(epochMillis: Long): Int
}

/**
 * The bundle handed to plugins and to the agent.
 *
 * One object rather than seven constructor parameters keeps `PluginContext`
 * readable and makes it obvious that these stores share the same vault and the
 * same encryption key.
 */
data class DataStores(
    val memories: MemoryStore,
    val notes: NotesStore,
    val todos: TodoStore,
    val userFacts: UserFactsStore,
    val conversations: ConversationStore,
    val taskHistory: TaskHistoryStore,
)
