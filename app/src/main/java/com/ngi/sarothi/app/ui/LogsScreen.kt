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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ngi.sarothi.app.di.AppGraph
import com.ngi.sarothi.core.safety.AuditEntry

/**
 * The audit log: every action Sarothi took, who took it, and what happened.
 *
 * This is the record the safety layer promises -- payments, deletions, outbound messages
 * and the confirmations behind them all land here, in `logs/` inside the vault, written
 * by [com.ngi.sarothi.core.safety.VaultAuditLogger]. It is read-only in the UI: a log the
 * user could edit would not be evidence of anything.
 */
@Composable
fun LogsScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    var entries by remember { mutableStateOf<List<AuditEntry>?>(null) }
    var total by remember { mutableStateOf<Long?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick, graph.vault.isUnlocked) {
        if (graph.vault.isUnlocked) {
            entries = runCatching { graph.audit.recent(limit = 200) }.getOrNull()
            total = runCatching { graph.audit.count() }.getOrNull()
        } else {
            entries = null
            total = null
        }
    }

    when (val list = entries) {
        null -> Unlocked(
            modifier,
            "Unlock the vault to read the log. Every action Sarothi takes is recorded in " +
                "logs/ on your SD card, encrypted with the rest.",
        )

        else -> Column(modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Showing ${list.size} of ${total ?: "?"} entries, newest first",
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedButton(onClick = { tick++ }) { Text("Reload") }
            }
            if (list.isEmpty()) {
                Text("Nothing logged yet.", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list) { entry ->
                    Card {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Text(
                                "${entry.action} · ${entry.outcome.name.lowercase()}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(entry.summary, style = MaterialTheme.typography.bodySmall)
                            Text(
                                entry.timestamp +
                                    " · ${entry.actorName} (${entry.actor.name.lowercase()})" +
                                    " · ${entry.sensitivity.name.lowercase()}" +
                                    if (entry.undoable) " · reversible" else "",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            entry.errorMessage?.let { message ->
                                Text(
                                    message,
                                    color = SarothiStates.danger,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
