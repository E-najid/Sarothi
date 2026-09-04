package com.ngi.sarothi.core.safety

import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.util.Ids
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One reversible action Sarothi has already taken. */
data class UndoableAction(
    val id: String,
    val pluginName: String,
    val undoToken: String,
    val description: String,
    val recordedAtEpochMillis: Long,
    val taskId: String?,
    /** After this the plugin can no longer promise a reversal (e.g. an edit window closed). */
    val expiresAtEpochMillis: Long?,
    val undone: Boolean = false,
) {
    val isExpired: Boolean
        get() = expiresAtEpochMillis != null && System.currentTimeMillis() > expiresAtEpochMillis
}

sealed interface UndoOutcome {
    data class Reversed(val action: UndoableAction, val detail: String) : UndoOutcome
    data class Failed(val action: UndoableAction, val reason: String) : UndoOutcome
    data class NothingToUndo(val reason: String) : UndoOutcome
}

/**
 * Tracks which actions can be taken back, and takes them back.
 *
 * Only plugins that set `supportsUndo = true` and actually returned an undo token
 * get registered, so the Undo button in the UI is never offered for something
 * Sarothi cannot reverse. The registry itself knows nothing about how to undo: it
 * calls back into the plugin through [invoker].
 */
class UndoRegistry(
    private val invoker: suspend (pluginName: String, undoToken: String) -> PluginResult,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val mutex = Mutex()
    private val actions = ArrayDeque<UndoableAction>()

    suspend fun register(
        pluginName: String,
        undoToken: String,
        description: String,
        taskId: String?,
        validForMillis: Long? = DEFAULT_VALIDITY_MILLIS,
    ): UndoableAction = mutex.withLock {
        val action = UndoableAction(
            id = Ids.newId("undo"),
            pluginName = pluginName,
            undoToken = undoToken,
            description = description,
            recordedAtEpochMillis = System.currentTimeMillis(),
            taskId = taskId,
            expiresAtEpochMillis = validForMillis?.let { System.currentTimeMillis() + it },
        )
        actions.addLast(action)
        while (actions.size > capacity) actions.removeFirst()
        action
    }

    /** Most recent reversible actions, newest first. Expired entries are dropped. */
    suspend fun available(limit: Int = 10): List<UndoableAction> = mutex.withLock {
        pruneExpiredLocked()
        actions.reversed().take(limit).toList()
    }

    /** Reverses the most recent reversible action. */
    suspend fun undoLast(taskId: String? = null): UndoOutcome {
        val candidates = available()
        val target = candidates.firstOrNull { taskId == null || it.taskId == taskId }
            ?: return UndoOutcome.NothingToUndo(
                if (candidates.isEmpty()) {
                    "There is nothing Sarothi can take back. Most actions on a phone — sending a " +
                        "message, placing a call, deleting a file — cannot be reversed, so Sarothi " +
                        "asks before doing them instead."
                } else {
                    "No reversible action belongs to task ${taskId}."
                },
            )
        return undo(target.id)
    }

    suspend fun undo(actionId: String): UndoOutcome {
        val target = mutex.withLock {
            pruneExpiredLocked()
            actions.firstOrNull { it.id == actionId && !it.undone }
        } ?: return UndoOutcome.NothingToUndo("That action is not in Sarothi's undo list (or was already undone).")

        val result = invoker(target.pluginName, target.undoToken)
        return when (result) {
            is PluginResult.Success -> {
                mutex.withLock { markUndoneLocked(target.id) }
                UndoOutcome.Reversed(target, result.summaryForUser)
            }
            is PluginResult.Failure -> UndoOutcome.Failed(target, result.summaryForUser)
            is PluginResult.NeedsUserInput -> UndoOutcome.Failed(target, result.question)
            is PluginResult.Unavailable -> UndoOutcome.Failed(target, result.summaryForUser)
        }
    }

    private fun markUndoneLocked(id: String) {
        val index = actions.indexOfFirst { it.id == id }
        if (index >= 0) actions[index] = actions[index].copy(undone = true)
    }

    private fun pruneExpiredLocked() {
        actions.removeAll { it.isExpired || it.undone }
    }

    suspend fun clear() = mutex.withLock { actions.clear() }

    companion object {
        private const val DEFAULT_CAPACITY = 40
        private const val DEFAULT_VALIDITY_MILLIS = 10 * 60 * 1000L
    }
}
