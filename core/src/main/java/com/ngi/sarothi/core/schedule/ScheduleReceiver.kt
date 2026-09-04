package com.ngi.sarothi.core.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receives the alarms [TaskScheduler] arms, plus the re-arm request after boot.
 *
 * It does no work of its own: `onReceive` runs on the main thread with a hard
 * timeout, and a task can take minutes. It only hands off to [ScheduleService].
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: ACTION_RUN_TASK
        Log.i(TAG, "Received $action")
        val service = Intent(context, ScheduleService::class.java)
            .setAction(action)
            .putExtra(EXTRA_TASK_ID, intent.getStringExtra(EXTRA_TASK_ID))
            .putExtra(EXTRA_REASON, intent.getStringExtra(EXTRA_REASON))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }

    companion object {
        private const val TAG = "SarothiScheduleRx"
        const val ACTION_RUN_TASK = "com.ngi.sarothi.core.schedule.action.RUN_TASK"
        const val ACTION_REARM = "com.ngi.sarothi.core.schedule.action.REARM"
        const val ACTION_RUN_OVERDUE = "com.ngi.sarothi.core.schedule.action.RUN_OVERDUE"
        const val EXTRA_TASK_ID = "com.ngi.sarothi.core.schedule.extra.TASK_ID"
        const val EXTRA_REASON = "com.ngi.sarothi.core.schedule.extra.REASON"
    }
}
