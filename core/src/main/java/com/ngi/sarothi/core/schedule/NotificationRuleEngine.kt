package com.ngi.sarothi.core.schedule

import android.util.Log
import com.ngi.sarothi.core.agent.AgentOutcome
import com.ngi.sarothi.core.capability.Notifier
import com.ngi.sarothi.core.data.TaskTrigger
import com.ngi.sarothi.core.screen.NotificationFeed
import com.ngi.sarothi.core.screen.ObservedNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Watches the notification feed and runs the rules that match.
 *
 * At most one rule fires per notification, and firings are serialised: two rules
 * starting two agent runs at once would fight over the single resident model and
 * the accessibility service. Rules always run unattended, which means the safety
 * gate denies every SENSITIVE and CRITICAL step — a notification cannot be used to
 * make Sarothi send money or delete something.
 */
class NotificationRuleEngine(
    private val scope: CoroutineScope,
    private val schedulerProvider: () -> TaskScheduler?,
    private val notifier: Notifier,
    private val agentRunnerProvider: () -> AgentRunner? = { AgentRunnerRegistry.current },
) {
    private var collector: Job? = null
    private val fireMutex = Mutex()

    @Volatile
    private var seenFingerprints = ArrayDeque<String>()

    val isRunning: Boolean get() = collector?.isActive == true

    fun start() {
        if (isRunning) return
        collector = scope.launch {
            NotificationFeed.notifications.collect { notification -> onNotification(notification) }
        }
        Log.i(TAG, "Notification rule engine started")
    }

    fun stop() {
        collector?.cancel()
        collector = null
    }

    private suspend fun onNotification(notification: ObservedNotification) {
        val scheduler = schedulerProvider() ?: return
        val rules = runCatching { scheduler.rules() }.getOrDefault(emptyList())
        if (rules.isEmpty()) return

        // The accessibility service can deliver the same notification more than
        // once (content-changed events follow the state-changed one). Deduplicating
        // here is what stops a rule from firing twice for one message.
        val fingerprint = notification.fingerprint
        val duplicate = fireMutex.withLock {
            if (fingerprint in seenFingerprints) {
                true
            } else {
                seenFingerprints.addLast(fingerprint)
                while (seenFingerprints.size > FINGERPRINT_CACHE) seenFingerprints.removeFirst()
                false
            }
        }
        if (duplicate) return

        val match = rules.firstOrNull { it.matches(notification) } ?: return
        val runner = agentRunnerProvider()
        if (runner == null) {
            Log.w(TAG, "Rule '${match.name}' matched but no agent runner is available")
            notifier.warning(
                "rule-${match.id}",
                "A notification rule could not run",
                "\"${match.name}\" matched a notification from ${notification.packageName}, but " +
                    "Sarothi is not ready to run a task (vault locked or app still starting).",
            )
            return
        }

        fireMutex.withLock {
            Log.i(TAG, "Rule '${match.name}' fired on ${notification.packageName}")
            val outcome = runCatching {
                runner.runUnattended(match.request, TaskTrigger.NOTIFICATION_RULE, allowSensitiveSteps = false)
            }.getOrElse { failure ->
                Log.w(TAG, "Rule '${match.name}' failed", failure)
                AgentOutcome.Failed("rule-${match.id}", failure.message ?: "Rule execution failed", failure.javaClass.simpleName)
            }
            val status = when (outcome) {
                is AgentOutcome.Completed, is AgentOutcome.DirectAnswer -> "completed"
                is AgentOutcome.Partial -> "partial"
                is AgentOutcome.WaitingForUser -> "needs your input"
                is AgentOutcome.Cancelled -> "cancelled"
                is AgentOutcome.Failed -> "failed"
            }
            runCatching { scheduler.recordRuleFire(match.id, "$status: ${outcome.message}") }
            notifier.info(
                "rule-${match.id}",
                "Rule \"${match.name}\" $status",
                outcome.message,
            )
        }
    }

    companion object {
        private const val TAG = "SarothiRules"
        private const val FINGERPRINT_CACHE = 64
    }
}
