package com.ngi.sarothi.core.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Alarms do not survive a reboot, and neither does an app update.
 *
 * This receiver is registered for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` and
 * asks [ScheduleService] to re-arm everything. Note what it cannot do: the vault
 * is locked after a reboot, so schedules cannot be read until the user unlocks
 * Sarothi once. [ScheduleService] reports that instead of pretending the tasks are
 * still armed, and the app re-arms on unlock as well.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "Re-arming schedules after $action")
        val service = Intent(context, ScheduleService::class.java)
            .setAction(ScheduleReceiver.ACTION_REARM)
            .putExtra(EXTRA_REASON, action)
        // startForegroundService is API 26 and minSdk is 26.
        context.startForegroundService(service)
    }

    companion object {
        private const val TAG = "SarothiBoot"
        const val EXTRA_REASON = ScheduleReceiver.EXTRA_REASON
    }
}
