package com.ngi.sarothi.app.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.ngi.sarothi.app.SarothiApplication
import com.ngi.sarothi.app.di.AppGraph

/**
 * The one Activity. Sarothi is a single-task window: the user asks for something and
 * watches it happen, so there is no back stack to speak of and no reason to pay for one.
 *
 * `launchMode="singleTask"` in the manifest is what makes a notification tap land on the
 * running task instead of starting a second copy of the agent over the top of the first.
 */
class MainActivity : FragmentActivity() {
    // FragmentActivity rather than ComponentActivity: androidx.biometric's BiometricPrompt
    // only attaches to a FragmentActivity, and Settings drives the biometric unlock from
    // here. FragmentActivity extends ComponentActivity, so enableEdgeToEdge and
    // setContent behave exactly as before.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as SarothiApplication).graph
        setContent {
            SarothiTheme {
                SarothiScaffold(graph)
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    TASK("Task", Icons.Filled.Home),
    VAULT("Vault", Icons.Filled.Lock),
    MODELS("Models", Icons.Filled.Download),
    MORE("More", Icons.Filled.MoreHoriz),
}

/**
 * The screens that are destinations rather than daily drivers.
 *
 * Seven tabs do not fit in a bottom bar, and hiding the persona editor or the audit log
 * behind a gesture would make them impossible to find. They live one tap down instead,
 * each with a line saying what it is for.
 */
private enum class Sub(val title: String, val blurb: String) {
    PERSONA(
        "Persona",
        "How Sarothi names itself, which language it answers in, and any standing " +
            "instructions you want it to keep.",
    ),
    HISTORY("Task history", "Every task that has run, from task_history/ in your vault."),
    LOGS("Audit log", "Every action Sarothi took and what happened. Read-only."),
    PERMISSIONS("Access", "What Sarothi can do right now, and the settings screens that change it."),
    SCHEDULES(
        "Schedules and rules",
        "Every trigger that exists: what it asks Sarothi to do, when it last fired, and " +
            "how to pause or delete it.",
    ),
    SETTINGS(
        "Settings",
        "Mobile-data downloads, and biometric unlock as a convenience over your passphrase.",
    ),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SarothiScaffold(graph: AppGraph) {
    var tab by rememberSaveable { mutableStateOf(Tab.TASK) }
    var sub by rememberSaveable { mutableStateOf<Sub?>(null) }

    val title = sub?.title ?: tab.label

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (sub != null) {
                        IconButton(onClick = { sub = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
        bottomBar = {
            // Hidden while a sub-screen is open: leaving the bar on screen would suggest
            // the tabs are still peers of what is in front of the user.
            if (sub == null) {
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        val contentModifier = Modifier.padding(inner)
        val current = sub
        if (current != null) {
            when (current) {
                Sub.PERSONA -> PersonaScreen(graph, contentModifier)
                Sub.HISTORY -> HistoryScreen(graph, contentModifier)
                Sub.LOGS -> LogsScreen(graph, contentModifier)
                Sub.PERMISSIONS -> PermissionsScreen(graph, contentModifier)
                Sub.SCHEDULES -> ScheduleScreen(graph, contentModifier)
                Sub.SETTINGS -> SettingsScreen(graph, contentModifier)
            }
        } else {
            when (tab) {
                Tab.TASK -> TaskScreen(graph, contentModifier)
                Tab.VAULT -> VaultScreen(graph, contentModifier)
                Tab.MODELS -> ModelsScreen(graph, contentModifier)
                Tab.MORE -> MoreScreen(contentModifier) { sub = it }
            }
        }
    }
}

@Composable
private fun MoreScreen(modifier: Modifier, onOpen: (Sub) -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sub.entries.forEach { entry ->
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleSmall)
                    Text(entry.blurb, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onOpen(entry) }) { Text("Open") }
                }
            }
        }
    }
}
