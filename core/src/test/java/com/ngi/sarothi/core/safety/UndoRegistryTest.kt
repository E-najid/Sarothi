package com.ngi.sarothi.core.safety

import com.ngi.sarothi.core.plugin.PluginResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Sarothi promises it can take back, and whether it can.
 *
 * The Undo button is only offered for what this registry holds, so a wrong answer here is
 * a lie to the user in one direction or the other: either Undo is offered for something
 * that cannot be reversed, or a reversible action disappears from the list before its
 * window closes.
 */
class UndoRegistryTest {

    /** Records what the registry asked for, and answers with whatever the test decides. */
    private class RecordingInvoker(var result: PluginResult) {
        val calls = mutableListOf<Pair<String, String>>()

        val invoke: suspend (String, String) -> PluginResult = { pluginName, token ->
            calls += pluginName to token
            result
        }
    }

    private fun success(text: String) = PluginResult.Success(
        summaryForUser = text,
        data = com.google.gson.JsonObject(),
    )

    private fun failure(text: String) = PluginResult.Failure(
        summaryForUser = text,
        errorClass = "TestFailure",
    )

    @Test
    fun undoing_a_registered_action_routes_to_the_plugin_that_acted() = runBlocking {
        val invoker = RecordingInvoker(success("Draft deleted"))
        val registry = UndoRegistry(invoker.invoke, capacity = 10)

        val action = registry.register(
            pluginName = "gmail",
            undoToken = "token-1",
            description = "Sent a message to rina@example.com",
            taskId = "task-1",
        )

        val outcome = registry.undo(action.id)

        assertEquals(listOf("gmail" to "token-1"), invoker.calls)
        assertTrue("expected Reversed, got $outcome", outcome is UndoOutcome.Reversed)
        assertEquals("Draft deleted", (outcome as UndoOutcome.Reversed).detail)
    }

    /** An action already taken back must not be offered again, or undo runs twice. */
    @Test
    fun the_same_action_cannot_be_undone_twice() = runBlocking {
        val invoker = RecordingInvoker(success("done"))
        val registry = UndoRegistry(invoker.invoke, capacity = 10)
        val action = registry.register("gmail", "t", "sent", "task-1")

        assertTrue(registry.undo(action.id) is UndoOutcome.Reversed)
        val second = registry.undo(action.id)

        assertTrue("expected NothingToUndo, got $second", second is UndoOutcome.NothingToUndo)
        assertEquals("the plugin must not be asked a second time", 1, invoker.calls.size)
    }

    @Test
    fun a_failed_reversal_is_reported_as_failed_not_as_reversed() = runBlocking {
        val invoker = RecordingInvoker(failure("The edit window closed"))
        val registry = UndoRegistry(invoker.invoke, capacity = 10)
        val action = registry.register("calendar", "t", "added an event", "task-1")

        val outcome = registry.undo(action.id)

        assertTrue("expected Failed, got $outcome", outcome is UndoOutcome.Failed)
        assertEquals("The edit window closed", (outcome as UndoOutcome.Failed).reason)
    }

    @Test
    fun undoing_something_that_was_never_registered_says_so() = runBlocking {
        val invoker = RecordingInvoker(success("done"))
        val registry = UndoRegistry(invoker.invoke, capacity = 10)

        val outcome = registry.undo("undo-does-not-exist")

        assertTrue(outcome is UndoOutcome.NothingToUndo)
        assertTrue(invoker.calls.isEmpty())
    }

    /**
     * A plugin's promise to reverse expires -- Gmail's undo-send window closes, an edit
     * is overwritten. Offering Undo after that would fail at the moment the user pressed it.
     */
    @Test
    fun an_action_past_its_window_is_no_longer_offered() = runBlocking {
        val registry = UndoRegistry(RecordingInvoker(success("done")).invoke, capacity = 10)
        registry.register("gmail", "t", "sent", "task-1", validForMillis = 1L)

        Thread.sleep(40)

        assertTrue(
            "an expired action must not stay in the list",
            registry.available().isEmpty(),
        )
    }

    @Test
    fun an_action_with_no_expiry_stays_available() = runBlocking {
        val registry = UndoRegistry(RecordingInvoker(success("done")).invoke, capacity = 10)
        registry.register("notes", "t", "overwrote a note", "task-1", validForMillis = null)

        assertEquals(1, registry.available().size)
        assertNull(registry.available().first().expiresAtEpochMillis)
    }

    /** Bounded so a long-lived process does not accumulate every action ever taken. */
    @Test
    fun the_registry_keeps_at_most_its_capacity() = runBlocking {
        val registry = UndoRegistry(RecordingInvoker(success("done")).invoke, capacity = 3)
        repeat(5) { i -> registry.register("plugin$i", "t$i", "action $i", "task-1") }

        val kept = registry.available(limit = 10)
        assertEquals(3, kept.size)
        assertFalse(
            "the oldest actions are the ones dropped",
            kept.any { it.pluginName == "plugin0" },
        )
    }

    @Test
    fun undo_last_can_be_scoped_to_one_task() = runBlocking {
        val invoker = RecordingInvoker(success("done"))
        val registry = UndoRegistry(invoker.invoke, capacity = 10)
        registry.register("gmail", "a", "sent for task A", "task-A")
        registry.register("calendar", "b", "added for task B", "task-B")

        val outcome = registry.undoLast(taskId = "task-A")

        assertTrue(outcome is UndoOutcome.Reversed)
        assertEquals(
            "only the task's own action may be reversed",
            listOf("gmail" to "a"),
            invoker.calls,
        )
    }

    @Test
    fun undo_last_with_no_task_takes_the_most_recent_action() = runBlocking {
        val invoker = RecordingInvoker(success("done"))
        val registry = UndoRegistry(invoker.invoke, capacity = 10)
        registry.register("gmail", "older", "first", "task-A")
        registry.register("calendar", "newer", "second", "task-A")

        registry.undoLast()

        assertEquals(1, invoker.calls.size)
        assertEquals("calendar" to "newer", invoker.calls.first())
    }

    @Test
    fun clear_empties_the_registry_without_calling_any_plugin() = runBlocking {
        val invoker = RecordingInvoker(success("done"))
        val registry = UndoRegistry(invoker.invoke, capacity = 10)
        registry.register("gmail", "t", "sent", "task-1")

        registry.clear()

        assertTrue(registry.available().isEmpty())
        assertTrue(invoker.calls.isEmpty())
    }
}
