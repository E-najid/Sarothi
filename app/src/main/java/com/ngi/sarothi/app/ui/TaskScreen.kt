package com.ngi.sarothi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ngi.sarothi.app.di.AppGraph
import com.ngi.sarothi.core.agent.TaskState
import com.ngi.sarothi.core.data.TaskStatus
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.safety.ConfirmationDecision
import com.ngi.sarothi.core.safety.UndoOutcome
import com.ngi.sarothi.core.safety.UndoableAction
import com.ngi.sarothi.core.data.StepStatus
import kotlinx.coroutines.launch

/**
 * The live checklist.
 *
 * This is a view of [TaskState], which the agent publishes at every transition -- so
 * what is on screen is what the agent is actually doing, not a summary written after the
 * fact. Nothing here is invented: when the vault is locked or the model is missing the
 * screen says so and the input is disabled, because a task cannot run.
 */
@Composable
fun TaskScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val state by graph.agent.state.collectAsStateWithLifecycle()
    val confirmations by graph.safety.pending.collectAsStateWithLifecycle()
    val vaultUnlocked = graph.vault.isUnlocked
    val modelReason = graph.llama.unavailabilityReason()
        ?: if (graph.models.status().orchestrator == null) {
            "The orchestrator model is not installed yet."
        } else {
            null
        }

    val blockedReason = when {
        !vaultUnlocked -> "Unlock the vault first (Vault tab)."
        modelReason != null -> modelReason
        else -> null
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        TaskInput(
            enabled = blockedReason == null && state?.status?.isRunning() != true,
            blockedReason = blockedReason,
            running = state?.status?.isRunning() == true,
            onSubmit = { request -> graph.scope.launch { graph.agent.run(request) } },
            onCancel = { graph.agent.cancel() },
        )

        Spacer(Modifier.height(12.dp))

        val current = state
        if (current == null) {
            Text(
                "No task has run yet. Sarothi plans each request on the device, shows every " +
                    "step here as it happens, and stops to ask whenever it needs something " +
                    "personal it does not have.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Checklist(graph, current)
        }
    }

    // The agent stops here and waits. Nothing else runs until this is answered.
    state?.question?.let { question ->
        AlertDialog(
            onDismissRequest = { graph.scope.launch { graph.agent.dismissQuestion() } },
            title = { Text("Sarothi needs something from you") },
            text = {
                Column {
                    Text(question.question)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Written into '${question.field}'. Sarothi will not guess this value.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                QuestionAnswer(question.choices, question.secret) { answer ->
                    graph.scope.launch { graph.agent.answerQuestion(answer) }
                }
            },
            dismissButton = {
                TextButton(onClick = { graph.scope.launch { graph.agent.dismissQuestion() } }) {
                    Text("Cancel the task")
                }
            },
        )
    }

    confirmations.firstOrNull()?.let { pending ->
        val request = pending.request
        AlertDialog(
            // Dismissing is a decision, not a shrug: the gate is waiting on an answer and
            // a timeout would leave the task hanging, so this records an explicit denial.
            onDismissRequest = { graph.safety.answer(pending.id, ConfirmationDecision.DENY) },
            title = { Text(request.title) },
            text = {
                Column {
                    request.detailLines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "From '${request.pluginName}' · ${request.sensitivity.name.lowercase()}" +
                            if (request.undoable) " · reversible" else " · not reversible",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { graph.safety.answer(pending.id, ConfirmationDecision.ALLOW_ONCE) }) {
                    Text("Allow once")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (request.allowRemember) {
                        TextButton(
                            onClick = {
                                graph.safety.answer(pending.id, ConfirmationDecision.ALLOW_FOR_SESSION)
                            },
                        ) { Text("Allow for this session") }
                    }
                    TextButton(onClick = { graph.safety.answer(pending.id, ConfirmationDecision.DENY) }) {
                        Text("Deny")
                    }
                }
            },
        )
    }
}

private fun TaskStatus.isRunning(): Boolean = when (this) {
    TaskStatus.PLANNING, TaskStatus.RUNNING, TaskStatus.WAITING_FOR_USER -> true
    TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED,
    TaskStatus.PARTIALLY_COMPLETED -> false
}

@Composable
private fun TaskInput(
    enabled: Boolean,
    blockedReason: String?,
    running: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var request by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = request,
            onValueChange = { request = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What should Sarothi do?") },
            placeholder = { Text("আজকের আবহাওয়া দেখে আমাকে এসএমএস করো") },
            enabled = enabled,
            minLines = 2,
        )
        blockedReason?.let { reason ->
            Text(reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val text = request.trim()
                    if (text.isNotEmpty()) {
                        onSubmit(text)
                        request = ""
                    }
                },
                enabled = enabled && request.isNotBlank() && !running,
            ) { Text("Run") }
            if (running) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                OutlinedButton(onClick = onCancel) { Text("Stop") }
            }
        }
    }
}

/**
 * The steps, and underneath them the actions Sarothi can still take back.
 *
 * A step saying `canUndo` is the agent's claim; the list below is the registry's own
 * record, and Undo is keyed by the registry's action id rather than the step id -- the
 * two are different things and only the registry knows how to reverse an action.
 */
@Composable
private fun Checklist(graph: AppGraph, state: TaskState) {
    var undoable by remember(state.taskId, state.status) { mutableStateOf(listOf<UndoableAction>()) }
    var undoNote by remember(state.taskId) { mutableStateOf<String?>(null) }

    LaunchedEffect(state.taskId, state.status) {
        undoable = graph.undo.available().filter { it.taskId == state.taskId && !it.undone }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${state.status.name.replace('_', ' ').lowercase()} · step ${state.stepsUsed}/${state.stepBudget}" +
                (if (state.replanCount > 0) " · replanned ${state.replanCount}×" else ""),
            style = MaterialTheme.typography.labelLarge,
        )
        state.finalMessage?.let { message ->
            Card { Text(message, modifier = Modifier.padding(12.dp)) }
        }
        state.failureReason?.let { reason ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(reason, modifier = Modifier.padding(12.dp))
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.steps, key = { it.id }) { step ->
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${step.index + 1}. ", style = MaterialTheme.typography.labelLarge)
                            Text(step.intent, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (step.status == StepStatus.RUNNING) {
                                CircularProgressIndicator(modifier = Modifier.height(14.dp), strokeWidth = 2.dp)
                            }
                        }
                        step.tool?.let { tool ->
                            Text("tool: $tool", style = MaterialTheme.typography.bodySmall)
                        }
                        step.detail?.let { detail ->
                            Text(detail, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (step.sensitivity != Sensitivity.READ_ONLY) {
                                Text(
                                    step.sensitivity.name.lowercase().replace('_', ' ') +
                                        if (step.confirmed) " · you approved this" else "",
                                    color = if (step.sensitivity == Sensitivity.CRITICAL) SarothiStates.danger
                                    else SarothiStates.caution,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (step.canUndo) {
                                Text(
                                    "reversible",
                                    color = SarothiStates.done,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (step.status == StepStatus.FAILED || step.status == StepStatus.DENIED) {
                                Text(
                                    step.status.name.lowercase(),
                                    color = SarothiStates.danger,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        undoNote?.let { note ->
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
        undoable.forEach { action ->
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(action.description, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${action.pluginName} · ${if (action.isExpired) "window closed" else "can still be reversed"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        enabled = !action.isExpired,
                        onClick = {
                            graph.scope.launch {
                                undoNote = when (val outcome = graph.undo.undo(action.id)) {
                                    is UndoOutcome.Reversed -> outcome.detail
                                    is UndoOutcome.Failed -> outcome.reason
                                    is UndoOutcome.NothingToUndo -> outcome.reason
                                }
                                undoable = graph.undo.available()
                                    .filter { it.taskId == state.taskId && !it.undone }
                            }
                        },
                    ) { Text("Undo") }
                }
            }
        }
    }
}

@Composable
private fun QuestionAnswer(choices: List<String>, secret: Boolean, onAnswer: (String) -> Unit) {
    var answer by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (secret) PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            label = { Text(if (secret) "Answer (not written to the log)" else "Answer") },
        )
        choices.forEach { choice ->
            TextButton(onClick = { onAnswer(choice) }) { Text(choice) }
        }
        Button(onClick = { onAnswer(answer.trim()) }, enabled = answer.isNotBlank()) { Text("Send") }
    }
}
