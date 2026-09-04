package com.ngi.sarothi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ngi.sarothi.app.di.AppGraph
import com.ngi.sarothi.core.model.CatalogModel
import com.ngi.sarothi.core.model.ModelCatalog
import com.ngi.sarothi.core.storage.ModelAudit
import com.ngi.sarothi.core.storage.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What is installed, what is missing, and downloading the rest.
 *
 * The download runs in `ModelDownloadService` behind ModelDownloadRegistry; this screen
 * starts it and shows the progress the service reports, so closing the app does not stop a
 * half-finished 219 MB file and reopening it resumes rather than restarting.
 */
@Composable
fun ModelsScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    var tick by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    val status = remember(tick) { graph.models.status() }
    val runtimeReason = remember(tick) { graph.llama.unavailabilityReason() }

    // Integrity is proven by digesting the files, so it is read off the main thread and
    // only when the vault is open; auditModels() requires both a filesystem and a
    // manifest. A locked vault therefore shows every model as "not downloaded" being
    // unknown rather than inventing a state.
    var audit by remember { mutableStateOf<ModelAudit?>(null) }
    LaunchedEffect(tick, graph.vault.isUnlocked) {
        audit = withContext(Dispatchers.IO) {
            if (graph.vault.isUnlocked) runCatching { graph.vault.auditModels() }.getOrNull() else null
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("On-device runtimes", style = MaterialTheme.typography.titleMedium)
                Text(
                    runtimeReason ?: "llama.cpp is available; inference runs on this phone.",
                    color = if (runtimeReason == null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Memory tier: ${status.tier}", style = MaterialTheme.typography.bodySmall)
                Text(status.description, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Loaded now — orchestrator: ${status.orchestrator ?: "none"}" +
                        ", vision: ${status.vision ?: "none"}, speech: ${status.speech ?: "none"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        note?.let { text -> Text(text, style = MaterialTheme.typography.bodyMedium) }

        ModelCatalog.ALL.forEach { model ->
            ModelRow(
                graph = graph,
                model = model,
                state = audit?.stateOf(model) ?: ModelState.Missing,
                onFinished = { result ->
                    note = result
                    tick++
                },
            )
        }
    }
}

/** Megabytes with one decimal, enough to compare against a data allowance. */
private fun Long.asMegabytes(): String = "%.1f MB".format(this / 1_048_576.0)

@Composable
private fun ModelRow(
    graph: AppGraph,
    model: CatalogModel,
    state: ModelState,
    onFinished: (String) -> Unit,
) {
    var fraction by remember(model.id) { mutableStateOf<Float?>(null) }
    var running by remember(model.id) { mutableStateOf(false) }

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${model.role.name.lowercase().replace('_', ' ')} · " +
                            model.sizeBytes.asMegabytes() +
                            if (model.required) " · required" else " · optional",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(model.description, style = MaterialTheme.typography.bodySmall)
            Text(
                when (state) {
                    is ModelState.Missing -> "Not downloaded."
                    is ModelState.SizeMismatch -> "Wrong size on disk: ${state.actualBytes} of " +
                        "${state.expectedBytes} bytes -- the download did not finish."
                    is ModelState.Corrupt -> "Digest does not match: this file is not what was " +
                        "published, so it will not be loaded."
                    is ModelState.PresentUnverified -> "Present, ${state.sizeBytes.asMegabytes()}, " +
                        "but its integrity could not be proven (${state.reason})."
                    else -> state.javaClass.simpleName
                },
                style = MaterialTheme.typography.bodySmall,
            )

            fraction?.let { value ->
                LinearProgressIndicator(
                    progress = { value },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }

            if (!graph.vault.isUnlocked) {
                Text(
                    "Unlock the vault first: models are written into its models/ folder.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Button(
                    enabled = !running,
                    onClick = {
                        running = true
                        fraction = 0f
                        graph.scope.launch {
                            val outcome = runCatching {
                                graph.downloader.download(
                                    model = model,
                                    // Wi-Fi by default; a 219 MB download over mobile data is
                                    // the user's decision, and it is offered in Settings, not
                                    // assumed here.
                                    allowMobileData = false,
                                    onProgress = { progress -> fraction = progress.fraction },
                                )
                            }.fold(
                                onSuccess = { result -> "${model.displayName}: $result" },
                                onFailure = { "${model.displayName} failed: ${it.javaClass.simpleName}: ${it.message}" },
                            )
                            running = false
                            fraction = null
                            onFinished(outcome)
                        }
                    },
                ) { Text(if (state is ModelState.Missing) "Download" else "Re-download and verify") }
            }

            Spacer(Modifier.height(2.dp))
        }
    }
}
