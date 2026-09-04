package com.ngi.sarothi.app.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ngi.sarothi.app.di.AppGraph

/**
 * What Sarothi can and cannot do right now, and how to change that.
 *
 * Read straight from [com.ngi.sarothi.core.safety.PermissionGuard], which is the same
 * object the plugin pipeline asks before it runs anything -- so this screen cannot drift
 * from what is actually enforced. Nothing is requested in a batch on first launch; each
 * permission is asked for by the plugin that needs it, at the moment it needs it, and
 * this is where the user goes to grant the ones that need a system settings screen.
 *
 * The accessibility service is first because it is the one that decides whether Sarothi
 * can see the screen at all: without it there is no perception, and the MediaProjection
 * fallback costs a screenshot per step instead of a tree of nodes.
 */
@Composable
fun PermissionsScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val special = graph.guard.specialAccess()
    val plugins = graph.pluginManager.all()
    val blocked = plugins.map { it to graph.guard.verdictFor(it) }.filter { !it.second.allowed }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Access", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${plugins.size} plugins registered · ${plugins.size - blocked.size} ready · " +
                        "${blocked.size} waiting on something",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text("Special access", style = MaterialTheme.typography.titleSmall)
        special.forEach { access ->
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(access.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                access.notApplicable -> "not on this Android version"
                                access.granted -> "granted"
                                else -> "not granted"
                            },
                            color = when {
                                access.notApplicable -> MaterialTheme.colorScheme.onSurfaceVariant
                                access.granted -> SarothiStates.done
                                else -> SarothiStates.caution
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Text(access.purpose, style = MaterialTheme.typography.bodySmall)
                    if (!access.granted && !access.notApplicable) {
                        Text(access.consequence, style = MaterialTheme.typography.bodySmall)
                    }
                    val intent = access.settingsIntent
                    if (intent != null && !access.notApplicable) {
                        Button(onClick = { context.openSettings(intent) }) { Text("Open the setting") }
                    }
                }
            }
        }

        if (blocked.isNotEmpty()) {
            Text("Plugins waiting on a permission", style = MaterialTheme.typography.titleSmall)
            blocked.forEach { (plugin, verdict) ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(plugin.name, style = MaterialTheme.typography.titleSmall)
                        Text(verdict.explanation, style = MaterialTheme.typography.bodySmall)
                        verdict.missingRuntime.forEach { permission ->
                            val why = graph.guard.describe(permission)
                            Text("• ${why.english}", style = MaterialTheme.typography.bodySmall)
                            Text(why.bangla, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = {
                                context.openSettings(graph.guard.settingsIntentFor(permission))
                            }) { Text("Open app settings") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hands the user to a system settings screen.
 *
 * A missing or unresolvable intent is reported rather than swallowed: these are
 * third-party settings screens (accessibility details, notification listeners, battery
 * optimisation) and not every OEM ships every one of them. Failing quietly would leave
 * the user staring at nothing after tapping a button.
 */
private fun Context.openSettings(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { failure ->
            android.widget.Toast.makeText(
                this,
                "This phone has no settings screen for that (${failure.javaClass.simpleName}).",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
}
