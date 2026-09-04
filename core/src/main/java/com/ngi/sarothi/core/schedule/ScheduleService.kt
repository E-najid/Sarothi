package com.ngi.sarothi.core.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.ngi.sarothi.core.R
import com.ngi.sarothi.core.agent.AgentOutcome
import com.ngi.sarothi.core.capability.Notifier
import com.ngi.sarothi.core.data.TaskTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs scheduled tasks and re-arms alarms.
 *
 * Foreground-typed `specialUse` because a task can take minutes and Android will
 * otherwise stop it part way through, leaving the phone in whatever half-operated
 * state the agent had reached. That is worse than showing a notification.
 *
 * Everything it needs comes from [ScheduleRegistry] and [AgentRunnerRegistry]. When
 * either is missing — which is the normal state right after a reboot, before the
 * user has unlocked the vault — the service says so in a notification and rearms
 * what it can. It never reports a task as run when it did not run.
 */
class ScheduleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var work: Job? = null

    @Volatile
    private var notifier: Notifier? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ScheduleReceiver.ACTION_REARM
        val taskId = intent?.getStringExtra(ScheduleReceiver.EXTRA_TASK_ID)
        startForegroundNow(action, taskId)

        work?.cancel()
        work = scope.launch {
            try {
                when (action) {
                    ScheduleReceiver.ACTION_RUN_TASK -> runTask(taskId)
                    ScheduleReceiver.ACTION_RUN_OVERDUE -> runOverdue()
                    else -> rearm()
                }
            } catch (failure: Exception) {
                Log.e(TAG, "Scheduled work failed", failure)
                notifyWarning("Scheduled task could not run", failure.message ?: failure.javaClass.simpleName)
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runTask(taskId: String?) {
        val scheduler = ScheduleRegistry.current
        if (scheduler == null) {
            notifyWarning(
                "Scheduled task postponed",
                "Sarothi's scheduler is not initialised. Open Sarothi once and unlock your vault; " +
                    "the task will be re-armed automatically.",
            )
            return
        }
        if (taskId == null) {
            notifyWarning("Scheduled task postponed", "The alarm carried no task id.")
            return
        }
        val task = scheduler.tasks().firstOrNull { it.id == taskId }
        if (task == null) {
            Log.w(TAG, "Alarm fired for unknown task $taskId; it was probably deleted")
            return
        }
        if (!task.enabled) {
            Log.i(TAG, "Task $taskId is disabled; not running it")
            return
        }

        val runner = AgentRunnerRegistry.current
        if (runner == null) {
            scheduler.recordRun(taskId, "postponed", "The agent was not available (vault locked or app not initialised).")
            notifyWarning(
                "\"${task.title}\" did not run",
                "Sarothi needs to be open and unlocked once after a reboot before scheduled tasks " +
                    "can run. It will try again at the next scheduled time.",
            )
            return
        }

        notifyProgress(task)
        val outcome = runner.runUnattended(task.request, TaskTrigger.SCHEDULE, task.allowSensitiveSteps)
        val status = when (outcome) {
            is AgentOutcome.Completed -> "completed"
            is AgentOutcome.DirectAnswer -> "completed"
            is AgentOutcome.Partial -> "partial"
            is AgentOutcome.WaitingForUser -> "needs_input"
            is AgentOutcome.Cancelled -> "cancelled"
            is AgentOutcome.Failed -> "failed"
        }
        scheduler.recordRun(taskId, status, outcome.message)
        cancelProgress()
        notifier?.let {
            when (status) {
                "completed" -> it.info("sched-${task.id}", "\"${task.title}\" finished", outcome.message)
                else -> it.warning("sched-${task.id}", "\"${task.title}\" did not finish", outcome.message)
            }
        } ?: notifyResult(task, status, outcome.message)
    }

    private suspend fun runOverdue() {
        val scheduler = ScheduleRegistry.current ?: return
        val overdue = scheduler.overdue()
        if (overdue.isEmpty()) {
            rearm()
            return
        }
        for (task in overdue) {
            Log.i(TAG, "Running overdue task ${task.id} (${task.title})")
            runTask(task.id)
        }
        rearm()
    }

    private suspend fun rearm() {
        val scheduler = ScheduleRegistry.current
        if (scheduler == null) {
            Log.w(TAG, "Cannot re-arm: no scheduler is registered")
            return
        }
        val armed = withContext(Dispatchers.IO) { scheduler.rearmAll() }
        Log.i(TAG, "Re-armed $armed scheduled task(s)")
        if (armed == 0 && scheduler.tasks().isNotEmpty()) {
            notifyWarning(
                "Scheduled tasks are not armed",
                "Sarothi could not read your schedules, usually because the vault is still locked. " +
                    "Open Sarothi and unlock it to re-arm them.",
            )
        }
    }

    /** Lets the app publish its notifier so results reach the right channels. */
    fun attachNotifier(notifier: Notifier) {
        this.notifier = notifier
    }

    private fun startForegroundNow(action: String, taskId: String?) {
        val title = when (action) {
            ScheduleReceiver.ACTION_RUN_TASK -> "Running a scheduled task"
            ScheduleReceiver.ACTION_RUN_OVERDUE -> "Catching up on scheduled tasks"
            else -> "Re-arming scheduled tasks"
        }
        val notification = buildNotification(title, taskId ?: "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyProgress(task: ScheduledTask) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            PROGRESS_NOTIFICATION_ID,
            buildNotification("Running \"${task.title}\"", task.request),
        )
    }

    private fun cancelProgress() {
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(PROGRESS_NOTIFICATION_ID) }
    }

    private fun notifyWarning(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(WARNING_NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun notifyResult(task: ScheduledTask, status: String, message: String) {
        notifyWarning("\"${task.title}\" — $status", message)
    }

    private fun createChannel() {
        // NotificationChannel is API 26 and minSdk is 26.
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sarothi_schedule_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = getString(R.string.sarothi_schedule_channel_desc) },
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        // The channel-based constructor is API 26 and minSdk is 26, so the deprecated
        // channel-less builder it fell back to was unreachable -- and a notification
        // without a channel is silently dropped on every device this app runs on.
        val builder = Notification.Builder(this, CHANNEL_ID)
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(text.take(120))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    override fun onDestroy() {
        work?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SarothiScheduleSvc"
        private const val CHANNEL_ID = "sarothi_schedule"
        private const val NOTIFICATION_ID = 0x5C11
        private const val PROGRESS_NOTIFICATION_ID = 0x5C12
        private const val WARNING_NOTIFICATION_ID = 0x5C13

        fun rearm(context: Context) {
            val intent = Intent(context, ScheduleService::class.java).setAction(ScheduleReceiver.ACTION_REARM)
            context.startForegroundService(intent)
        }

        fun runOverdue(context: Context) {
            val intent = Intent(context, ScheduleService::class.java).setAction(ScheduleReceiver.ACTION_RUN_OVERDUE)
            context.startForegroundService(intent)
        }
    }
}
