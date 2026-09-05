package com.ngi.sarothi.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.ngi.sarothi.core.capability.Notifier

/**
 * Posts the agent's progress and results to the system tray.
 *
 * [Notifier] identifies a notification by a String, because that is what the agent and
 * the plugins have to hand -- a task id, a plugin name. `NotificationManager` wants an
 * Int, so the id is hashed. Two different ids could in principle collide; the effect
 * would be one notification replacing another, never a wrong result reaching the user,
 * and the alternative (keeping a growing id table for the life of the process) is worse
 * on a 3 GB phone.
 *
 * The channels created here are the agent's own. The foreground-service channels belong
 * to the services that own them (`ScheduleService`, `ModelDownloadService`,
 * `ScreenCaptureService`, `GeofenceWatcherService`) and are created there, because a
 * service must not depend on the app process having started first.
 */
class AndroidNotifier(context: Context) : Notifier {

    private val appContext = context.applicationContext
    private val manager: NotificationManager? =
        appContext.getSystemService(NotificationManager::class.java)

    init {
        createChannels()
    }

    private fun createChannels() {
        val nm = manager ?: return
        val channels = listOf(
            NotificationChannel(CHANNEL_TASK, "Task progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "What Sarothi is doing right now, step by step."
                setShowBadge(false)
            },
            NotificationChannel(CHANNEL_INFO, "Results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Finished tasks and answers."
            },
            NotificationChannel(CHANNEL_WARNING, "Needs your attention", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Confirmations and questions Sarothi cannot answer alone."
            },
            NotificationChannel(CHANNEL_ERROR, "Problems", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tasks that failed, and why."
            },
        )
        runCatching { channels.forEach { nm.createNotificationChannel(it) } }
    }

    override fun progress(taskId: String, title: String, text: String, max: Int, current: Int) {
        post(CHANNEL_TASK, taskId, title, text) { builder ->
            if (max > 0) builder.setProgress(max, current.coerceIn(0, max), false)
            else builder.setProgress(0, 0, true)
            builder.setOngoing(true)
        }
    }

    override fun info(id: String, title: String, text: String) =
        post(CHANNEL_INFO, id, title, text)

    override fun warning(id: String, title: String, text: String) =
        post(CHANNEL_WARNING, id, title, text)

    override fun error(id: String, title: String, text: String) =
        post(CHANNEL_ERROR, id, title, text)

    override fun cancel(id: String) {
        runCatching { manager?.cancel(id.hashCode()) }
    }

    override fun cancelTask(taskId: String) {
        // The progress notification is the only one keyed by task id; results and errors
        // are keyed by the notification id their caller chose, and cancel() handles those.
        cancel(taskId)
    }

    private inline fun post(
        channelId: String,
        id: String,
        title: String,
        text: String,
        extra: (androidx.core.app.NotificationCompat.Builder) -> Unit = {},
    ) {
        val nm = manager ?: return
        val builder = androidx.core.app.NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(com.ngi.sarothi.core.R.drawable.ic_sarothi_status)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        extra(builder)
        runCatching { nm.notify(id.hashCode(), builder.build()) }
    }

    companion object {
        const val CHANNEL_TASK = "sarothi_task"
        const val CHANNEL_INFO = "sarothi_info"
        const val CHANNEL_WARNING = "sarothi_warning"
        const val CHANNEL_ERROR = "sarothi_error"
    }
}
