package com.ngi.sarothi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ngi.sarothi.app.di.AppGraph
import com.ngi.sarothi.core.plugin.PluginConfig
import kotlinx.coroutines.launch

/**
 * The settings the connector plugins read, editable by hand.
 *
 * `webhook` refuses to post to a URL it was not given, and refuses plain http unless the
 * host is listed in `allow_insecure_hosts` -- both of which are only usable if there is
 * somewhere to type them. Its own error message sends the user to
 * "Settings → Connectors → Webhooks", so until this screen existed that message pointed
 * at something that was not there.
 *
 * Everything here is stored in the vault's `plugins_config/<name>.json`, encrypted with
 * the rest of your memories and travelling with the SD card. The webhook header is the one
 * value that is credential-shaped rather than a preference; it is masked below and the note
 * says where it lives, because "encrypted, and it moves with your vault" is a different
 * promise from "stays on this phone", which is what `SecretStore` would have meant.
 */
@Composable
fun ConnectorsScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    var webhook by remember { mutableStateOf<PluginConfig?>(null) }
    var homeAssistant by remember { mutableStateOf<PluginConfig?>(null) }
    var news by remember { mutableStateOf<PluginConfig?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick, graph.vault.isUnlocked) {
        if (graph.vault.isUnlocked) {
            webhook = runCatching { graph.configStore.read("webhook") }.getOrNull()
            homeAssistant = runCatching { graph.configStore.read("home_assistant") }.getOrNull()
            news = runCatching { graph.configStore.read("news") }.getOrNull()
        } else {
            webhook = null
            homeAssistant = null
            news = null
        }
    }

    val hooks = webhook
    val home = homeAssistant
    val feeds = news
    if (hooks == null || home == null || feeds == null) {
        Unlocked(
            modifier,
            "Unlock the vault to edit connectors. Their settings live in plugins_config/ " +
                "inside it, encrypted with the rest of your data.",
        )
        return
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WebhookSection(graph, hooks) { tick++ }
        HomeAssistantSection(graph, home) { tick++ }
        NewsSection(graph, feeds) { tick++ }
    }
}

@Composable
private fun WebhookSection(graph: AppGraph, config: PluginConfig, onSaved: () -> Unit) {
    // Names are whatever has a url.<name> key: the plugin derives its own list the same
    // way, so the screen and the plugin cannot disagree about what exists.
    val names = remember(config) {
        config.all().keys.filter { it.startsWith("url.") }.map { it.removePrefix("url.") }.sorted()
    }
    var newName by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Webhooks", style = MaterialTheme.typography.titleSmall)
            Text(
                "Sarothi will only post to a webhook you named here, and only over https " +
                    "unless you list the host as an exception below.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (names.isEmpty()) {
                Text("None configured.", style = MaterialTheme.typography.bodySmall)
            }
            names.forEach { name ->
                WebhookRow(graph, config, name, onSaved = onSaved, onNote = { note = it })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { input ->
                        newName = input.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
                    },
                    label = { Text("New webhook name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(
                    enabled = newName.isNotBlank() && newName !in names,
                    onClick = {
                        config.put("url.$newName", "")
                        graph.scope.launch {
                            graph.pluginManager.saveConfig("webhook", config)
                            note = "Added \"$newName\". Now give it a URL."
                            newName = ""
                            onSaved()
                        }
                    },
                ) { Text("Add") }
            }

            InsecureHostsField(graph, config, onSaved = onSaved, onNote = { note = it })
            note?.let { text -> Text(text, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun WebhookRow(
    graph: AppGraph,
    config: PluginConfig,
    name: String,
    onSaved: () -> Unit,
    onNote: (String) -> Unit,
) {
    var url by remember(name) { mutableStateOf(config.string("url.$name") ?: "") }
    var header by remember(name) { mutableStateOf(config.string("header.$name") ?: "") }
    var showHeader by remember(name) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = header,
            onValueChange = { header = it },
            label = { Text("Shared secret (sent as X-Sarothi-Webhook)") },
            supportingText = {
                Text("Optional. Stored encrypted in your vault and travels with the SD card.")
            },
            visualTransformation = if (showHeader) {
                androidx.compose.ui.text.input.VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showHeader = !showHeader }) {
                Text(if (showHeader) "Hide" else "Show")
            }
            Button(
                onClick = {
                    config.put("url.$name", url.trim())
                    config.put("header.$name", header.trim().ifEmpty { null })
                    graph.scope.launch {
                        graph.pluginManager.saveConfig("webhook", config)
                        onNote("Saved \"$name\".")
                        onSaved()
                    }
                },
            ) { Text("Save") }
            TextButton(onClick = {
                config.put("url.$name", null)
                config.put("header.$name", null)
                graph.scope.launch {
                    graph.pluginManager.saveConfig("webhook", config)
                    onNote("Removed \"$name\".")
                    onSaved()
                }
            }) { Text("Remove") }
        }
    }
}

@Composable
private fun InsecureHostsField(
    graph: AppGraph,
    config: PluginConfig,
    onSaved: () -> Unit,
    onNote: (String) -> Unit,
) {
    var hosts by remember(config) { mutableStateOf(config.string("allow_insecure_hosts") ?: "") }
    OutlinedTextField(
        value = hosts,
        onValueChange = { hosts = it },
        label = { Text("Hosts allowed over plain http, comma separated") },
        supportingText = {
            Text(
                "Leave empty. Anything listed here can be read by whoever is on the same " +
                    "network as the request -- it exists for a Home Assistant on a LAN with " +
                    "no certificate, not as a way around https.",
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        onClick = {
            config.put("allow_insecure_hosts", hosts.trim().ifEmpty { null })
            graph.scope.launch {
                graph.pluginManager.saveConfig("webhook", config)
                onNote("Saved the http exceptions.")
                onSaved()
            }
        },
    ) { Text("Save exceptions") }
}

@Composable
private fun HomeAssistantSection(graph: AppGraph, config: PluginConfig, onSaved: () -> Unit) {
    var baseUrl by remember(config) { mutableStateOf(config.string("base_url") ?: "") }
    var note by remember { mutableStateOf<String?>(null) }

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Home Assistant", style = MaterialTheme.typography.titleSmall)
            Text(
                "The address of your own instance, for example http://homeassistant.local:8123. " +
                    "Without it the home_assistant plugin reports itself unavailable rather " +
                    "than guessing an address.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = {
                    config.put("base_url", baseUrl.trim().ifEmpty { null })
                    graph.scope.launch {
                        graph.pluginManager.saveConfig("home_assistant", config)
                        note = "Saved."
                        onSaved()
                    }
                },
            ) { Text("Save") }
            note?.let { text -> Text(text, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun NewsSection(graph: AppGraph, config: PluginConfig, onSaved: () -> Unit) {
    // Stored pipe-separated; shown one per line, which is how a person reads a list of URLs.
    var feeds by remember(config) {
        mutableStateOf(
            config.string("feeds")?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.joinToString("\n") ?: "",
        )
    }
    var note by remember { mutableStateOf<String?>(null) }

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("News feeds", style = MaterialTheme.typography.titleSmall)
            Text(
                "RSS feeds the news plugin uses when you do not name a topic. One per line. " +
                    "A topic search and an explicit feed URL both take priority over these.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = feeds,
                onValueChange = { feeds = it },
                label = { Text("Feed URLs, one per line") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Button(
                onClick = {
                    val list = feeds.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                    config.put("feeds", list.joinToString("|").ifEmpty { null })
                    graph.scope.launch {
                        graph.pluginManager.saveConfig("news", config)
                        // Counted from the list that was actually stored, not from the
                        // newlines in the box: blank lines would otherwise be reported
                        // as feeds.
                        note = if (list.isEmpty()) "Cleared." else "Saved ${list.size} feed(s)."
                        onSaved()
                    }
                },
            ) { Text("Save") }
            note?.let { text -> Text(text, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
