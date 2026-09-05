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
import androidx.compose.runtime.LaunchedEffect
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
import com.ngi.sarothi.core.storage.PassphraseChange
import com.ngi.sarothi.core.storage.VaultAttachResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var rotationPending by remember { mutableStateOf(false) }

    // Asked of the vault off the main thread: rotationPending() stats a file behind a
    // content:// URI, and composition is not the place for a document-provider query.
    fun reloadRotationState() {
        graph.scope.launch {
            rotationPending = withContext(Dispatchers.IO) { graph.vault.rotationPending() }
        }
    }
    LaunchedEffect(Unit) { reloadRotationState() }

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

        if (rotationPending) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "A passphrase change was interrupted",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Memory files are already sealed with the new passphrase, but the " +
                            "manifest still names the old one. Finishing needs no passphrase " +
                            "at all: the new copies are on disk beside the old ones.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            graph.scope.launch {
                                val finished = withContext(Dispatchers.IO) {
                                    graph.vault.resumeInterruptedRotation()
                                }
                                message = if (finished) {
                                    "Finished. The vault is locked and now opens with the new passphrase."
                                } else {
                                    "Nothing was waiting to be finished."
                                }
                                busy = false
                                refresh()
                                reloadRotationState()
                            }
                        },
                    ) { Text("Finish it now") }
                }
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
            ChangePassphraseCard(graph, enabled = !busy) { text ->
                message = text
                refresh()
                reloadRotationState()
            }
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
private fun PassphraseField(
    value: String,
    label: String = "Passphrase",
    supporting: String = "The only thing protecting your memories. Sarothi cannot recover it.",
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        supportingText = { Text(supporting) },
        singleLine = true,
    )
}

/**
 * Rotating the vault passphrase.
 *
 * Shown only while the vault is unlocked, because re-encrypting needs the key that is in
 * memory: the vault key *is* `Argon2id(passphrase, salt)`, so a new passphrase means every
 * sealed file is opened and written again. The three fields are here rather than in a
 * dialog so that the copy explaining what happens to biometric unlock stays on screen
 * while the new passphrase is typed.
 */
@Composable
private fun ChangePassphraseCard(
    graph: AppGraph,
    enabled: Boolean,
    onResult: (String) -> Unit,
) {
    var current by rememberSaveable { mutableStateOf("") }
    var next by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Change passphrase", style = MaterialTheme.typography.titleMedium)
            Text(
                "Every memory file is decrypted with the passphrase it has and sealed again " +
                    "with the new one, on this phone, without anything leaving it. The old " +
                    "passphrase stops working the moment this finishes, and Sarothi cannot " +
                    "recover the new one either.",
                style = MaterialTheme.typography.bodyMedium,
            )
            PassphraseField(current, "Current passphrase") { current = it }
            PassphraseField(
                next,
                "New passphrase",
                "Long enough to be worth the Argon2id work behind it.",
            ) { next = it }
            PassphraseField(confirm, "Repeat the new passphrase", " ") { confirm = it }

            problem?.let { text ->
                Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                enabled = enabled && !working && current.isNotEmpty() && next.isNotEmpty() && next == confirm,
                onClick = {
                    working = true
                    problem = null
                    graph.scope.launch {
                        val outcome = runCatching {
                            graph.vault.changePassphrase(current.toCharArray(), next.toCharArray())
                        }.getOrElse { failure ->
                            PassphraseChange.Refused("${failure.javaClass.simpleName}: ${failure.message}")
                        }
                        // Typed passphrases are cleared whatever happened. changePassphrase
                        // already wiped the two arrays it was given; the Strings cannot be
                        // wiped, which is why they never outlive this click.
                        current = ""
                        next = ""
                        confirm = ""
                        working = false
                        when (outcome) {
                            is PassphraseChange.Changed -> {
                                // The biometric layer wraps the *key*, not the passphrase, so
                                // what it holds is now a key that opens nothing. Clearing it
                                // is the honest alternative to a fingerprint that unlocks an
                                // empty vault.
                                graph.biometrics.invalidate()
                                onResult(
                                    "Passphrase changed. ${outcome.filesRotated} memory file(s) " +
                                        "were re-encrypted. Biometric unlock was cleared -- set it " +
                                        "up again in Settings if you want it.",
                                )
                            }

                            is PassphraseChange.Refused -> problem = outcome.reason
                        }
                    }
                },
            ) { Text("Change passphrase and re-encrypt") }

            if (working) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                    Text("Re-encrypting the vault…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
