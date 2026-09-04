package com.ngi.sarothi.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.ngi.sarothi.app.SarothiApplication

/**
 * The one Activity. Sarothi is a single-task window: the user asks for something and
 * watches it happen, so there is no back stack to speak of and no reason to pay for one.
 *
 * `launchMode="singleTask"` in the manifest is what makes a notification tap land on the
 * running task instead of starting a second copy of the agent over the top of the first.
 */
class MainActivity : ComponentActivity() {

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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SarothiScaffold(graph: com.ngi.sarothi.app.di.AppGraph) {
    var tab by rememberSaveable { mutableStateOf(Tab.TASK) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(tab.label) }) },
        bottomBar = {
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
        },
    ) { inner ->
        val contentModifier = Modifier.padding(inner)
        when (tab) {
            Tab.TASK -> TaskScreen(graph, contentModifier)
            Tab.VAULT -> VaultScreen(graph, contentModifier)
            Tab.MODELS -> ModelsScreen(graph, contentModifier)
        }
    }
}
