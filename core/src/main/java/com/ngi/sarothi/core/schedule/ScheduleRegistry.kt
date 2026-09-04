package com.ngi.sarothi.core.schedule

/**
 * Publishes the scheduler to components Android creates on its own.
 *
 * `BroadcastReceiver`s and foreground services started by `AlarmManager` cannot be
 * constructor-injected, so the app's graph publishes the scheduler here when it is
 * built and removes it when it is torn down. A receiver that finds nothing does not
 * guess: [ScheduleService] reports the missed run and rearms, so the user can see
 * that the task did not execute instead of finding it silently skipped.
 */
object ScheduleRegistry {
    @Volatile
    private var scheduler: TaskScheduler? = null

    fun attach(scheduler: TaskScheduler) {
        this.scheduler = scheduler
    }

    fun detach(scheduler: TaskScheduler) {
        if (this.scheduler === scheduler) this.scheduler = null
    }

    val current: TaskScheduler? get() = scheduler
}
