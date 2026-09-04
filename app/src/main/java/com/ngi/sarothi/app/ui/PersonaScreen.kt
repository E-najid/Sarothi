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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ngi.sarothi.app.di.AppGraph
import com.ngi.sarothi.core.persona.Formality
import com.ngi.sarothi.core.persona.Persona
import com.ngi.sarothi.core.persona.SarothiLanguage

/**
 * Editing the persona Sarothi answers with.
 *
 * Saved through [com.ngi.sarothi.app.di.PersonaRepository], so it lands in the vault's
 * encrypted `memories/persona.json` and travels with the SD card. When the vault is
 * locked nothing can be written and the screen says so rather than appearing to save:
 * a persona that only lives in this process would be gone on the next launch, and the
 * user would have no way to know.
 */
@Composable
fun PersonaScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val saved by graph.persona.persona.collectAsStateWithLifecycle()
    val loaded by graph.persona.isLoaded.collectAsStateWithLifecycle()
    val unlocked = graph.vault.isUnlocked

    var draft by remember(saved) { mutableStateOf(saved) }
    var note by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Persona", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        !unlocked -> "Unlock the vault to change this. Until then Sarothi answers " +
                            "as the default persona and nothing is written."
                        loaded -> "Read from memories/persona.json in your vault."
                        else -> "Showing the defaults; nothing has been saved yet."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            label = { Text("What Sarothi calls itself") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        ChoiceRow(
            label = "Language",
            options = SarothiLanguage.entries.toList(),
            selected = draft.language,
            text = { it.nativeName },
        ) { draft = draft.copy(language = it) }
        ChoiceRow(
            label = "Formality",
            options = Formality.entries.toList(),
            selected = draft.formality,
            text = { it.displayName },
        ) { draft = draft.copy(formality = it) }
        ChoiceRow(
            label = "Verbosity",
            options = Persona.Verbosity.entries.toList(),
            selected = draft.verbosity,
            text = { it.name.lowercase() },
        ) { draft = draft.copy(verbosity = it) }

        OutlinedTextField(
            value = draft.tone,
            onValueChange = { draft = draft.copy(tone = it) },
            label = { Text("Tone") },
            supportingText = { Text("Goes into the system prompt verbatim.") },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.customInstructions,
            onValueChange = { draft = draft.copy(customInstructions = it) },
            label = { Text("Standing instructions") },
            supportingText = { Text("For example: \"always give prices in taka\".") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Speak replies aloud", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "On-device Piper TTS; falls back to the system voice when the " +
                        "phonemizer has no native library.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = draft.speakRepliesAloud,
                onCheckedChange = { draft = draft.copy(speakRepliesAloud = it) },
            )
        }

        Button(
            enabled = unlocked && draft != saved,
            onClick = {
                val ok = graph.persona.save(draft)
                note = if (ok) {
                    "Saved to the vault."
                } else {
                    "Not saved: the vault is locked, so there is nowhere to write it."
                }
            },
        ) { Text("Save persona") }

        note?.let { text -> Text(text, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun <T : Enum<T>> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    text: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(text(option)) },
                )
            }
        }
    }
}
