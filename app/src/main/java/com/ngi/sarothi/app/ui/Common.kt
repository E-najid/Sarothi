package com.ngi.sarothi.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What a screen shows when everything it would list lives in a locked vault.
 *
 * Shared so that "no data" and "cannot read the data" never look the same. An empty list
 * on a locked vault reads as "Sarothi has done nothing", which is a lie the user would
 * reasonably believe.
 */
@Composable
fun Unlocked(modifier: Modifier = Modifier, message: String) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Vault locked", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
}
