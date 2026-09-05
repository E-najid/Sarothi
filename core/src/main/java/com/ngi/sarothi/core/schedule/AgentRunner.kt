package com.ngi.sarothi.core.schedule

import com.ngi.sarothi.core.agent.AgentOutcome
import com.ngi.sarothi.core.data.TaskTrigger

/**
 * How a schedule or a notification rule reaches the agent.
 *
 * The agent lives behind the app's dependency graph; a `BroadcastReceiver` or a
 * foreground service woken by `AlarmManager` cannot constructor-inject it. This is
 * the seam: `:app` publishes an implementation when its graph is built, and the
 * scheduler refuses to run a task when none is available rather than pretending
 * the task succeeded.
 */
fun interface AgentRunner {
    suspend fun runUnattended(request: String, trigger: TaskTrigger, allowSensitiveSteps: Boolean): AgentOutcome
}

object AgentRunnerRegistry {
    @Volatile
    private var runner: AgentRunner? = null

    fun attach(runner: AgentRunner) {
        this.runner = runner
    }

    fun detach(runner: AgentRunner) {
        if (this.runner === runner) this.runner = null
    }

    val current: AgentRunner? get() = runner
}
