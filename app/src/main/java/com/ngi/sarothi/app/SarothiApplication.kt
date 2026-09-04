package com.ngi.sarothi.app

import android.app.Application
import android.util.Log
import com.ngi.sarothi.app.di.AppGraph
import com.ngi.sarothi.core.schedule.AgentRunner
import com.ngi.sarothi.core.schedule.AgentRunnerRegistry
import com.ngi.sarothi.core.schedule.ScheduleRegistry

/**
 * Owns the graph for the life of the process.
 *
 * Two registries are published here and withdrawn in [onTerminate], because the
 * components that need them are created by Android rather than by this graph:
 * `AlarmManager` starts [com.ngi.sarothi.core.schedule.ScheduleService] and a boot
 * receiver, and neither can be constructor-injected. When the process is cold-started by
 * an alarm the Application runs first, so the graph is always in place before a receiver
 * looks for it -- and when it is not, the scheduler reports the missed run instead of
 * quietly skipping it.
 */
class SarothiApplication : Application() {

    lateinit var graph: AppGraph
        private set

    /** Published to [AgentRunnerRegistry] so a scheduled task can reach the agent. */
    private lateinit var agentRunner: AgentRunner

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()

        agentRunner = AgentRunner { request, trigger, allowSensitiveSteps ->
            // `unattended` is what stops a scheduled task from opening a confirmation
            // dialog nobody is looking at: the safety gate refuses sensitive steps
            // instead of waiting for an answer that will never come.
            graph.agent.run(
                request = request,
                trigger = trigger,
                unattended = true,
            )
        }
        AgentRunnerRegistry.attach(agentRunner)
        ScheduleRegistry.attach(graph.scheduler)

        // A process restarted by an alarm has no unlocked vault: the persona and every
        // store read from it. Reattaching here is what lets a scheduled task find its own
        // configuration without the user opening the app first.
        val reattached = graph.vault.reattach()
        Log.i(TAG, "Graph built; vault reattached=$reattached")
    }

    override fun onTerminate() {
        // Not called on a real device -- the process is killed -- but the withdrawals
        // belong with the attachments, and this is where a test or an emulator sees them.
        ScheduleRegistry.detach(graph.scheduler)
        AgentRunnerRegistry.detach(agentRunner)
        graph.shutdown()
        super.onTerminate()
    }

    private companion object {
        const val TAG = "SarothiApp"
    }
}
