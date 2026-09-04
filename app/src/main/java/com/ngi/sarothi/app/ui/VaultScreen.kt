package com.ngi.sarothi.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ngi.sarothi.app.di.AppGraph
import com.ngi.sarothi.core.storage.VaultAttachResult
import kotlinx.coroutines.launch

/**
 * Choosing, creating, restoring and locking the vault.
 *
 * The folder is picked through `ACTION_OPEN_DOCUMENT_TREE` so the grant is persistable and
 * survives a reboot; everything Sarothi writes there travels with the SD card. The
 * passphrase is typed here and nowhere else, and it is never stored -- `createFreshVault`
 * and `openExistingVault` derive the key from it with Argon2id and the derived key is what
 * `SecretStore` keeps in the Android Keystore.
 */
@Composable
fun VaultScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    var configured by remember { mutableStateOf(graph.vault.isConfigured) }
    var unlocked by remember { mutableStateOf(graph.vault.isUnlocked) }
    var attached by remember { mutableStateOf<VaultAttachResult?>(null) }
    var password by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            message = "No folder was chosen."
        } else {
            attached = graph.vault.attach(uri)
            configured = graph.vault.isConfigured
            message = null
        }
    }

    fun refresh() {
        configured = graph.vault.isConfigured
        unlocked = graph.vault.isUnlocked
        graph.persona.refresh()
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Vault", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        unlocked -> "Unlocked. Memories, schedules and history are readable."
                        configured -> "A folder is attached but locked."
                        else -> "No folder chosen yet. Nothing is stored until you pick one."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                graph.vault.treeUri?.let { uri ->
                    Text(uri.toString(), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Persona: ${graph.persona.persona.value.name}" +
                        if (graph.persona.isLoaded.value) "" else " (default -- nothing saved yet)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Button(onClick = { picker.launch(null) }, enabled = !busy) {
            Text(if (configured) "Change the vault folder" else "Choose the vault folder")
        }

        when (val result = attached) {
            is VaultAttachResult.NotAVault -> Text(
                result.reason,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            is VaultAttachResult.AccessFailed -> Text(
                result.reason,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            is VaultAttachResult.EmptyFolder -> {
                Text(
                    "This folder is empty. Sarothi will create manifest.json, memories/, " +
                        "plugins_config/, logs/, task_history/ and models/ inside it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PassphraseField(password) { password = it }
                Button(
                    enabled = password.isNotEmpty() && !busy,
                    onClick = {
                        busy = true
                        graph.scope.launch {
                            message = runCatching { graph.vault.createFreshVault(password.toCharArray()) }
                                .fold(
                                    onSuccess = { "Vault created and unlocked." },
                                    onFailure = { "${it.javaClass.simpleName}: ${it.message}" },
                                )
                            password = ""
                            busy = false
                            refresh()
                        }
                    },
                ) { Text("Create the vault") }
            }

            is VaultAttachResult.ExistingVault -> {
                Text(
                    "A Sarothi vault is already here. Unlocking verifies your passphrase " +
                        "against the stored hash and checks every model's digest.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PassphraseField(password) { password = it }
                Button(
                    enabled = password.isNotEmpty() && !busy,
                    onClick = {
                        busy = true
                        graph.scope.launch {
                            message = runCatching { graph.vault.openExistingVault(password.toCharArray()) }
                                .fold(
                                    onSuccess = { "Vault restored and unlocked." },
                                    onFailure = { "${it.javaClass.simpleName}: ${it.message}" },
                                )
                            password = ""
                            busy = false
                            refresh()
                        }
                    },
                ) { Text("Restore this vault") }
            }

            null -> Unit
        }

        message?.let { text ->
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                Text("Working…", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (unlocked) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    graph.vault.lock()
                    refresh()
                    message = "Locked. The derived key is gone from memory."
                },
            ) { Text("Lock now") }
        }
        if (configured) {
            OutlinedButton(
                onClick = {
                    graph.vault.detach()
                    attached = null
                    refresh()
                    message = "Detached. The folder and everything in it are untouched."
                },
            ) { Text("Detach the folder") }
        }
    }
}

@Composable
private fun PassphraseField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Passphrase") },
        visualTransformation = PasswordVisualTransformation(),
        supportingText = {
            Text("The only thing protecting your memories. Sarothi cannot recover it.")
        },
        singleLine = true,
    )
}
