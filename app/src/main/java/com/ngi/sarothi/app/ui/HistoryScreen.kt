package com.ngi.sarothi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ngi.sarothi.app.di.AppGraph
import com.ngi.sarothi.core.data.TaskRecord
import com.ngi.sarothi.core.data.TaskStatus
import kotlinx.coroutines.launch

/**
 * Every task Sarothi has run, from `task_history/` in the vault.
 *
 * Read through [com.ngi.sarothi.core.data.TaskHistoryStore], which means the list is the
 * user's own record and survives a reinstall on a fresh phone with the same SD card. A
 * locked vault shows nothing rather than an empty-looking list that hides the reason.
 */
@Composable
fun HistoryScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    var records by remember { mutableStateOf<List<TaskRecord>?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick, graph.vault.isUnlocked) {
        records = if (graph.vault.isUnlocked) {
            runCatching { graph.stores.taskHistory.recent(limit = 100) }.getOrNull()
        } else {
            null
        }
    }

    when (val list = records) {
        null -> Unlocked(
            modifier,
            "Unlock the vault to read the task history. It lives in task_history/ on your " +
                "SD card, encrypted with the rest of your memories.",
        )

        else -> Column(modifier.fillMaxSize().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${list.size} task(s), newest first", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { tick++ }) { Text("Reload") }
            }
            if (list.isEmpty()) {
                Text(
                    "Nothing has run yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { record ->
                    HistoryCard(graph, record, onChanged = { tick++ })
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(graph: AppGraph, record: TaskRecord, onChanged: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(record.request, style = MaterialTheme.typography.titleSmall)
            Text(
                record.status.name.lowercase().replace('_', ' ') +
                    " · ${record.createdAt} · ${record.elapsedMillis / 1000}s · " +
                    "${record.steps.size} step(s) · ${record.totalTokens} tokens",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "trigger: ${record.trigger.name.lowercase().replace('_', ' ')} · persona: " +
                    record.personaName +
                    if (record.confirmationCount > 0) " · ${record.confirmationCount} confirmation(s)" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            if (record.neededUserInput) {
                Text(
                    "Sarothi stopped and asked for something it did not have.",
                    color = SarothiStates.caution,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            record.finalMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            record.failureReason?.let { reason ->
                Text(
                    reason,
                    color = if (record.status == TaskStatus.FAILED) SarothiStates.danger
                    else SarothiStates.caution,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = {
                    graph.scope.launch {
                        graph.stores.taskHistory.delete(record.id)
                        onChanged()
                    }
                },
            ) { Text("Delete this record") }
        }
    }
}
