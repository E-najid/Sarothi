package com.ngi.sarothi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ngi.sarothi.app.di.AppGraph
import com.ngi.sarothi.core.schedule.NotificationRule
import com.ngi.sarothi.core.schedule.Recurrence
import com.ngi.sarothi.core.schedule.RuleMatch
import com.ngi.sarothi.core.schedule.ScheduledTask
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Schedules and notification rules, by hand.
 *
 * The agent's `schedule_task` and `notification_rules` plugins create these from a
 * sentence, which is how most of them should come into being. This screen is the other
 * half: seeing every trigger that exists, what it will ask Sarothi to do, when it last
 * fired, and turning one off without having to phrase that as a request.
 *
 * A schedule runs unattended, so nothing here can reach a confirmation dialog. That is
 * why `allowSensitiveSteps` is off by default and why the switch says what turning it on
 * actually means: the safety gate refuses sensitive steps when no human is watching
 * rather than waiting for an answer that will never come.
 */
@Composable
fun ScheduleScreen(graph: AppGraph, modifier: Modifier = Modifier) {
    var tasks by remember { mutableStateOf<List<ScheduledTask>?>(null) }
    var rules by remember { mutableStateOf<List<NotificationRule>?>(null) }
    var tick by remember { mutableStateOf(0) }
    val exactAlarms = remember(tick) { graph.scheduler.canScheduleExactAlarms }

    LaunchedEffect(tick, graph.vault.isUnlocked) {
        if (graph.vault.isUnlocked) {
            tasks = runCatching { graph.scheduler.tasks() }.getOrNull()
            rules = runCatching { graph.scheduler.rules() }.getOrNull()
        } else {
            tasks = null
            rules = null
        }
    }

    val currentTasks = tasks
    val currentRules = rules
    if (currentTasks == null || currentRules == null) {
        Unlocked(
            modifier,
            "Unlock the vault to see schedules. They live in memories/schedules.json and " +
                "memories/notification_rules.json, and the alarms that fire them are armed " +
                "again after a reboot.",
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            if (!exactAlarms) {
                Card {
                    Text(
                        "Android is not letting Sarothi set exact alarms, so every schedule " +
                            "below may fire a little late. Grant it in Settings → Apps → " +
                            "Sarothi → Alarms & reminders. Each task says so where it is " +
                            "listed rather than quietly drifting.",
                        color = SarothiStates.caution,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            NewTaskForm(graph) { tick++ }
        }

        item { Text("Schedules (${currentTasks.size})", style = MaterialTheme.typography.titleSmall) }
        if (currentTasks.isEmpty()) {
            item { Text("None yet.", style = MaterialTheme.typography.bodySmall) }
        }
        items(currentTasks, key = { it.id }) { task ->
            TaskCard(graph, task, onChanged = { tick++ })
        }

        item { NewRuleForm(graph) { tick++ } }
        item { Text("Notification rules (${currentRules.size})", style = MaterialTheme.typography.titleSmall) }
        if (currentRules.isEmpty()) {
            item { Text("None yet.", style = MaterialTheme.typography.bodySmall) }
        }
        items(currentRules, key = { it.id }) { rule ->
            RuleCard(graph, rule, onChanged = { tick++ })
        }
    }
}

private val ONE_SHOT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
private fun NewTaskForm(graph: AppGraph, onDone: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var request by remember { mutableStateOf("") }
    var recurrence by remember { mutableStateOf(Recurrence.DAILY) }
    var timeText by remember { mutableStateOf("07:30") }
    var onceText by remember { mutableStateOf("") }
    var dayOfMonth by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(setOf<DayOfWeek>()) }
    var sensitive by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (!open) {
        Button(onClick = { open = true }) { Text("New schedule") }
        return
    }

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New schedule", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = request,
                onValueChange = { request = it },
                label = { Text("What Sarothi should do") },
                placeholder = { Text("আজকের আবহাওয়া দেখে আমাকে এসএমএস করো") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Recurrence.entries.forEach { option ->
                    FilterChip(
                        selected = recurrence == option,
                        onClick = { recurrence = option },
                        label = { Text(option.displayName) },
                    )
                }
            }

            when (recurrence) {
                Recurrence.ONCE -> OutlinedTextField(
                    value = onceText,
                    onValueChange = { onceText = it },
                    label = { Text("When (yyyy-MM-dd HH:mm)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Recurrence.HOURLY -> Text(
                    "Runs at the top of every hour; no time of day needed.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Recurrence.WEEKLY -> Column {
                    Text("Days", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(
                                selected = day in days,
                                onClick = {
                                    days = if (day in days) days - day else days + day
                                },
                                label = { Text(day.name.take(2)) },
                            )
                        }
                    }
                    TimeField(timeText) { timeText = it }
                }

                Recurrence.MONTHLY -> Column {
                    OutlinedTextField(
                        value = dayOfMonth,
                        onValueChange = { dayOfMonth = it.filter(Char::isDigit).take(2) },
                        label = { Text("Day of month (1-31)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TimeField(timeText) { timeText = it }
                }

                Recurrence.DAILY -> TimeField(timeText) { timeText = it }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Allow sensitive steps", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Off by default. A schedule runs with nobody watching, so a step " +
                            "that spends money or deletes something is refused rather than " +
                            "held waiting for a confirmation that cannot be answered.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = sensitive, onCheckedChange = { sensitive = it })
            }

            error?.let { text ->
                Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = request.isNotBlank(),
                    onClick = {
                        error = null
                        graph.scope.launch {
                            error = runCatching {
                                val time = if (recurrence == Recurrence.ONCE || recurrence == Recurrence.HOURLY) {
                                    null
                                } else {
                                    LocalTime.parse(timeText)
                                }
                                val once = if (recurrence == Recurrence.ONCE) {
                                    LocalDateTime.parse(onceText, ONE_SHOT_FORMAT)
                                        .atZone(ZoneId.systemDefault())
                                        .toInstant()
                                        .toEpochMilli()
                                } else {
                                    null
                                }
                                graph.scheduler.create(
                                    title = title,
                                    request = request,
                                    recurrence = recurrence,
                                    timeOfDay = time,
                                    daysOfWeek = days,
                                    dayOfMonth = dayOfMonth.toIntOrNull(),
                                    oneShotAtEpochMillis = once,
                                    allowSensitiveSteps = sensitive,
                                )
                            }.fold(
                                onSuccess = {
                                    request = ""
                                    title = ""
                                    onceText = ""
                                    dayOfMonth = ""
                                    days = emptySet()
                                    open = false
                                    onDone()
                                    null
                                },
                                onFailure = { "${it.javaClass.simpleName}: ${it.message}" },
                            )
                        }
                    },
                ) { Text("Create") }
                OutlinedButton(onClick = { open = false; error = null }) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun TimeField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Time of day (HH:mm)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun TaskCard(graph: AppGraph, task: ScheduledTask, onChanged: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleSmall)
            Text(task.request, style = MaterialTheme.typography.bodyMedium)
            Text(
                task.recurrence.displayName +
                    (task.timeOfDay?.let { " at $it" } ?: "") +
                    (task.daysOfWeek.takeIf { it.isNotEmpty() }
                        ?.let { d -> " on " + d.joinToString(",") { it.name.take(3) } } ?: "") +
                    (task.dayOfMonth?.let { " on day $it" } ?: "") +
                    (task.oneShotAtEpochMillis?.let { " at ${LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault())}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "next: ${task.nextRunAtEpochMillis?.let { LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault()).toString() } ?: "not armed"}" +
                    " · run ${task.runCount} time(s)" +
                    (task.lastRunStatus?.let { " · last: $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            task.lastRunMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (task.alarmIsApproximate) {
                Text(
                    "This alarm is approximate: the OS has not granted exact alarms.",
                    color = SarothiStates.caution,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (task.allowSensitiveSteps) {
                Text(
                    "Sensitive steps are allowed for this schedule.",
                    color = SarothiStates.danger,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(if (task.enabled) "Enabled" else "Paused", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = task.enabled,
                    onCheckedChange = { next ->
                        graph.scope.launch { graph.scheduler.setEnabled(task.id, next); onChanged() }
                    },
                )
                TextButton(onClick = {
                    graph.scope.launch { graph.scheduler.delete(task.id); onChanged() }
                }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun NewRuleForm(graph: AppGraph, onDone: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var request by remember { mutableStateOf("") }
    var packages by remember { mutableStateOf("") }
    var titles by remember { mutableStateOf("") }
    var bodies by remember { mutableStateOf("") }
    var match by remember { mutableStateOf(RuleMatch.ALL) }
    var caseSensitive by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (!open) {
        Button(onClick = { open = true }) { Text("New notification rule") }
        return
    }

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New notification rule", style = MaterialTheme.typography.titleSmall)
            Text(
                "Matching is plain substring matching, not a model call: a rule has to fire " +
                    "with the screen off and no model resident.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = request,
                onValueChange = { request = it },
                label = { Text("What Sarothi should do when it matches") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = packages,
                onValueChange = { packages = it },
                label = { Text("App package names, comma separated") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = titles,
                onValueChange = { titles = it },
                label = { Text("Title contains, comma separated") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = bodies,
                onValueChange = { bodies = it },
                label = { Text("Body contains, comma separated") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RuleMatch.entries.forEach { option ->
                    FilterChip(
                        selected = match == option,
                        onClick = { match = option },
                        label = { Text(if (option == RuleMatch.ALL) "match all" else "match any") },
                    )
                }
                FilterChip(
                    selected = caseSensitive,
                    onClick = { caseSensitive = !caseSensitive },
                    label = { Text("case sensitive") },
                )
            }
            error?.let { text ->
                Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = request.isNotBlank(),
                    onClick = {
                        error = null
                        graph.scope.launch {
                            error = runCatching {
                                graph.scheduler.createRule(
                                    name = name,
                                    request = request,
                                    packageNames = packages.split(","),
                                    titleContains = titles.split(","),
                                    bodyContains = bodies.split(","),
                                    match = match,
                                    caseSensitive = caseSensitive,
                                )
                            }.fold(
                                onSuccess = {
                                    request = ""; name = ""; packages = ""; titles = ""; bodies = ""
                                    open = false
                                    onDone()
                                    null
                                },
                                onFailure = { "${it.javaClass.simpleName}: ${it.message}" },
                            )
                        }
                    },
                ) { Text("Create") }
                OutlinedButton(onClick = { open = false; error = null }) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun RuleCard(graph: AppGraph, rule: NotificationRule, onChanged: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(rule.name, style = MaterialTheme.typography.titleSmall)
            Text(rule.request, style = MaterialTheme.typography.bodyMedium)
            Text(
                (if (rule.packageNames.isEmpty()) "any app" else rule.packageNames.joinToString(", ")) +
                    " · " + (if (rule.match == RuleMatch.ALL) "all of" else "any of") +
                    (rule.titleContains.takeIf { it.isNotEmpty() }?.let { " titles: ${it.joinToString(", ")}" } ?: "") +
                    (rule.bodyContains.takeIf { it.isNotEmpty() }?.let { " bodies: ${it.joinToString(", ")}" } ?: "") +
                    (if (rule.caseSensitive) " · case sensitive" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "fired ${rule.fireCount} time(s) · cooldown ${rule.cooldownMillis / 60000} min" +
                    (rule.lastResult?.let { " · last: $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(if (rule.enabled) "Enabled" else "Paused", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { next ->
                        graph.scope.launch {
                            graph.scheduler.updateRule(rule.id) { it.copy(enabled = next) }
                            onChanged()
                        }
                    },
                )
                TextButton(onClick = {
                    graph.scope.launch { graph.scheduler.deleteRule(rule.id); onChanged() }
                }) { Text("Delete") }
            }
        }
    }
}
