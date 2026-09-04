package com.ngi.sarothi.core.schedule

import android.annotation.SuppressLint

/**
 * Publishes the scheduler to components Android creates on its own.
 *
 * `BroadcastReceiver`s and foreground services started by `AlarmManager` cannot be
 * constructor-injected, so the app's graph publishes the scheduler here when it is
 * built and removes it when it is torn down. A receiver that finds nothing does not
 * guess: [ScheduleService] reports the missed run and rearms, so the user can see
 * that the task did not execute instead of finding it silently skipped.
 */
@SuppressLint("StaticFieldLeak")
object ScheduleRegistry {
    // Lint sees a static field reaching a TaskScheduler, which holds a Context, and
    // reads that as a leaked Activity. It cannot be one: AppGraph constructs the
    // scheduler with `applicationContext`, and a registry that outlives an Activity is
    // the entire point -- AlarmManager starts ScheduleService into a process that may
    // have no Activity at all. The reference is also withdrawn in detach(), so the
    // graph is not held past teardown.
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
