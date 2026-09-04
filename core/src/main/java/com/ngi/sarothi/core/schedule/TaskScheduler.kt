package com.ngi.sarothi.core.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ngi.sarothi.core.storage.VaultManager
import com.ngi.sarothi.core.storage.VaultPaths
import com.ngi.sarothi.core.util.Ids
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.arrayOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalTime

/**
 * Persists schedules and notification rules in the vault and arms the alarms.
 *
 * Alarms are armed with `setExactAndAllowWhileIdle` when Android allows it and
 * `setAndAllowWhileIdle` when it does not; the task records which happened
 * ([ScheduledTask.alarmIsApproximate]) so the UI can say "may fire a few minutes
 * late" instead of promising a time it cannot keep.
 */
class TaskScheduler(
    private val context: Context,
    private val vault: VaultManager,
) {
    private val mutex = Mutex()

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** True when Android will let Sarothi fire at the exact minute requested. */
    val canScheduleExactAlarms: Boolean
        get() {
            val manager = alarmManager ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
            } else {
                true
            }
        }

    // ------------------------------------------------------------ persistence

    suspend fun tasks(): List<ScheduledTask> = withContext(Dispatchers.IO) {
        mutex.withLock { readTasksLocked() }
    }

    suspend fun rules(): List<NotificationRule> = withContext(Dispatchers.IO) {
        mutex.withLock { readRulesLocked() }
    }

    private fun readTasksLocked(): List<ScheduledTask> {
        if (!vault.isUnlocked) return emptyList()
        val json = runCatching { vault.readEncryptedJson(VaultPaths.SCHEDULES) }.getOrNull() ?: return emptyList()
        return json.arrayOrNull("tasks")?.mapNotNull { element ->
            if (element.isJsonObject) ScheduledTask.fromJson(element.asJsonObject) else null
        } ?: emptyList()
    }

    private fun readRulesLocked(): List<NotificationRule> {
        if (!vault.isUnlocked) return emptyList()
        val json = runCatching { vault.readEncryptedJson(VaultPaths.NOTIFICATION_RULES) }.getOrNull()
            ?: return emptyList()
        return json.arrayOrNull("rules")?.mapNotNull { element ->
            if (element.isJsonObject) NotificationRule.fromJson(element.asJsonObject) else null
        } ?: emptyList()
    }

    private fun writeTasksLocked(tasks: List<ScheduledTask>) {
        val document = Json.obj {
            addProperty("schema_version", 1)
            addProperty("updated_at", Instant.now().toString())
            add("tasks", Json.arr { tasks.forEach { add(it.toJson()) } })
        }
        vault.writeEncryptedJson(VaultPaths.SCHEDULES, document)
    }

    private fun writeRulesLocked(rules: List<NotificationRule>) {
        val document = Json.obj {
            addProperty("schema_version", 1)
            addProperty("updated_at", Instant.now().toString())
            add("rules", Json.arr { rules.forEach { add(it.toJson()) } })
        }
        vault.writeEncryptedJson(VaultPaths.NOTIFICATION_RULES, document)
    }

    // --------------------------------------------------------------- mutations

    data class ScheduleResult(val task: ScheduledTask, val armedForEpochMillis: Long?, val warning: String?)

    suspend fun create(
        title: String,
        request: String,
        recurrence: Recurrence,
        timeOfDay: LocalTime? = null,
        daysOfWeek: Set<java.time.DayOfWeek> = emptySet(),
        dayOfMonth: Int? = null,
        oneShotAtEpochMillis: Long? = null,
        allowSensitiveSteps: Boolean = false,
    ): ScheduleResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(request.isNotBlank()) { "A scheduled task needs something to do" }
            if (recurrence == Recurrence.ONCE && oneShotAtEpochMillis == null) {
                throw IllegalArgumentException("A one-off task needs a date and time")
            }
            if (recurrence != Recurrence.ONCE && recurrence != Recurrence.HOURLY && timeOfDay == null) {
                throw IllegalArgumentException("A repeating task needs a time of day")
            }

            val task = ScheduledTask(
                id = Ids.newId("sched"),
                title = title.trim().ifBlank { request.take(40) },
                request = request.trim(),
                recurrence = recurrence,
                timeOfDay = timeOfDay,
                daysOfWeek = daysOfWeek,
                dayOfMonth = dayOfMonth?.coerceIn(1, 31),
                oneShotAtEpochMillis = oneShotAtEpochMillis,
                enabled = true,
                createdAt = Instant.now().toString(),
                lastRunAtEpochMillis = null,
                lastRunStatus = null,
                lastRunMessage = null,
                nextRunAtEpochMillis = null,
                allowSensitiveSteps = allowSensitiveSteps,
                runCount = 0,
                alarmIsApproximate = false,
            )

            val next = task.computeNextRun()
            val withNext = task.copy(
                nextRunAtEpochMillis = next,
                alarmIsApproximate = next != null && !canScheduleExactAlarms,
            )
            val tasks = readTasksLocked().toMutableList()
            tasks += withNext
            writeTasksLocked(tasks)
            val warning = armLocked(withNext)
            ScheduleResult(withNext, next, warning)
        }
    }

    suspend fun update(taskId: String, transform: (ScheduledTask) -> ScheduledTask): ScheduledTask? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val tasks = readTasksLocked().toMutableList()
                val index = tasks.indexOfFirst { it.id == taskId }
                if (index < 0) return@withLock null
                cancelAlarmLocked(tasks[index])
                val updated = transform(tasks[index]).let { candidate ->
                    val next = candidate.computeNextRun()
                    candidate.copy(
                        nextRunAtEpochMillis = next,
                        alarmIsApproximate = next != null && !canScheduleExactAlarms,
                    )
                }
                tasks[index] = updated
                writeTasksLocked(tasks)
                if (updated.enabled) armLocked(updated)
                updated
            }
        }

    suspend fun setEnabled(taskId: String, enabled: Boolean): Boolean =
        update(taskId) { it.copy(enabled = enabled) } != null

    suspend fun delete(taskId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tasks = readTasksLocked().toMutableList()
            val target = tasks.firstOrNull { it.id == taskId } ?: return@withLock false
            cancelAlarmLocked(target)
            tasks.removeAll { it.id == taskId }
            writeTasksLocked(tasks)
            true
        }
    }

    /** Records that a task ran, and re-arms it for its next occurrence. */
    suspend fun recordRun(taskId: String, status: String, message: String?): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tasks = readTasksLocked().toMutableList()
            val index = tasks.indexOfFirst { it.id == taskId }
            if (index < 0) return@withLock
            val now = System.currentTimeMillis()
            cancelAlarmLocked(tasks[index])
            val previous = tasks[index]
            val next = if (previous.recurrence == Recurrence.ONCE) null else previous.computeNextRun(now)
            tasks[index] = previous.copy(
                lastRunAtEpochMillis = now,
                lastRunStatus = status,
                lastRunMessage = message,
                nextRunAtEpochMillis = next,
                runCount = previous.runCount + 1,
                enabled = previous.enabled && next != null,
                alarmIsApproximate = next != null && !canScheduleExactAlarms,
            )
            writeTasksLocked(tasks)
            if (next != null) armLocked(tasks[index])
        }
    }

    /** Called by the notification feed when a rule fires. */
    suspend fun recordRuleFire(ruleId: String, result: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val rules = readRulesLocked().toMutableList()
            val index = rules.indexOfFirst { it.id == ruleId }
            if (index < 0) return@withLock
            rules[index] = rules[index].copy(
                lastFiredAtEpochMillis = System.currentTimeMillis(),
                fireCount = rules[index].fireCount + 1,
                lastResult = result,
            )
            writeRulesLocked(rules)
        }
    }

    suspend fun createRule(
        name: String,
        request: String,
        packageNames: List<String> = emptyList(),
        titleContains: List<String> = emptyList(),
        bodyContains: List<String> = emptyList(),
        match: RuleMatch = RuleMatch.ALL,
        caseSensitive: Boolean = false,
        cooldownMillis: Long = NotificationRule.DEFAULT_COOLDOWN_MILLIS,
    ): NotificationRule = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(request.isNotBlank()) { "A rule needs something for Sarothi to do" }
            if (packageNames.isEmpty() && titleContains.isEmpty() && bodyContains.isEmpty()) {
                throw IllegalArgumentException(
                    "A rule with no conditions would fire on every notification from every app. " +
                        "Give it at least one.",
                )
            }
            val rule = NotificationRule(
                id = Ids.newId("rule"),
                name = name.trim().ifBlank { request.take(40) },
                enabled = true,
                packageNames = packageNames.map { it.trim() }.filter { it.isNotEmpty() },
                titleContains = titleContains.map { it.trim() }.filter { it.isNotEmpty() },
                bodyContains = bodyContains.map { it.trim() }.filter { it.isNotEmpty() },
                match = match,
                caseSensitive = caseSensitive,
                request = request.trim(),
                cooldownMillis = cooldownMillis.coerceAtLeast(0L),
                createdAt = Instant.now().toString(),
                lastFiredAtEpochMillis = null,
                fireCount = 0,
                lastResult = null,
            )
            val rules = readRulesLocked().toMutableList()
            rules += rule
            writeRulesLocked(rules)
            rule
        }
    }

    suspend fun updateRule(ruleId: String, transform: (NotificationRule) -> NotificationRule): NotificationRule? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val rules = readRulesLocked().toMutableList()
                val index = rules.indexOfFirst { it.id == ruleId }
                if (index < 0) return@withLock null
                rules[index] = transform(rules[index])
                writeRulesLocked(rules)
                rules[index]
            }
        }

    suspend fun deleteRule(ruleId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val rules = readRulesLocked().toMutableList()
            val before = rules.size
            rules.removeAll { it.id == ruleId }
            if (rules.size == before) return@withLock false
            writeRulesLocked(rules)
            true
        }
    }

    // ------------------------------------------------------------------ alarms

    /** Re-arms every enabled task. Called after boot and after the vault unlocks. */
    suspend fun rearmAll(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tasks = readTasksLocked().toMutableList()
            var armed = 0
            tasks.forEachIndexed { index, task ->
                val next = if (task.enabled) task.computeNextRun() else null
                tasks[index] = task.copy(
                    nextRunAtEpochMillis = next,
                    alarmIsApproximate = next != null && !canScheduleExactAlarms,
                )
                if (next != null && armLocked(tasks[index]) == null) armed++
            }
            writeTasksLocked(tasks)
            armed
        }
    }

    /** Arms one alarm. Returns null on success, otherwise the reason it could not. */
    private fun armLocked(task: ScheduledTask): String? {
        val triggerAt = task.nextRunAtEpochMillis
            ?: return "This task has no next run time, so no alarm was set."
        val manager = alarmManager ?: return "This device exposes no AlarmManager."
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction(ScheduleReceiver.ACTION_RUN_TASK)
            .putExtra(ScheduleReceiver.EXTRA_TASK_ID, task.id)
        val pending = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return try {
            if (canScheduleExactAlarms) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
            null
        } catch (failure: SecurityException) {
            Log.w(TAG, "AlarmManager refused the exact alarm for ${task.id}", failure)
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending) }
                .exceptionOrNull()
                ?.let { "Android refused to schedule this task: ${it.message}" }
                ?: "Android would not allow an exact alarm, so this task may fire a few minutes late."
        } catch (failure: Exception) {
            Log.w(TAG, "Could not arm alarm for ${task.id}", failure)
            "Could not schedule this task: ${failure.javaClass.simpleName}: ${failure.message}"
        }
    }

    private fun cancelAlarmLocked(task: ScheduledTask) {
        val manager = alarmManager ?: return
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction(ScheduleReceiver.ACTION_RUN_TASK)
            .putExtra(ScheduleReceiver.EXTRA_TASK_ID, task.id)
        val pending = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { manager.cancel(pending) }
        pending.cancel()
    }

    /** Tasks whose alarm should have fired but did not (device was off, app killed). */
    suspend fun overdue(nowEpochMillis: Long = System.currentTimeMillis()): List<ScheduledTask> =
        tasks().filter { task ->
            task.enabled && task.nextRunAtEpochMillis != null &&
                task.nextRunAtEpochMillis < nowEpochMillis - OVERDUE_GRACE_MILLIS
        }

    companion object {
        private const val TAG = "SarothiScheduler"
        private const val OVERDUE_GRACE_MILLIS = 60_000L
    }
}
