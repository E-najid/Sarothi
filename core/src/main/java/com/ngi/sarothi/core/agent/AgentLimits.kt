package com.ngi.sarothi.core.agent

import com.ngi.sarothi.core.runtime.MemoryTier

/**
 * Hard ceilings on one task.
 *
 * These exist because an on-device 350 M planner will, given enough rope, produce
 * a plan that loops. Every ceiling has a user-visible consequence: the checklist
 * shows `3/8 steps`, and hitting a ceiling ends the task with an explanation rather
 * than running until the battery dies.
 */
data class AgentLimits(
    val maxStepsPerPlan: Int,
    val maxStepsPerTask: Int,
    val maxReplans: Int,
    val maxModelCalls: Int,
    val maxRetriesPerStep: Int,
    /** Wall-clock ceiling for one task, so a background run cannot hang forever. */
    val taskTimeoutMillis: Long,
    /** Per-model-call ceiling; a stuck generation must not eat the task budget. */
    val generationTimeoutMillis: Long,
) {
    companion object {
        val DEFAULT = AgentLimits(
            maxStepsPerPlan = 8,
            maxStepsPerTask = 16,
            maxReplans = 2,
            maxModelCalls = 6,
            maxRetriesPerStep = 1,
            taskTimeoutMillis = 5 * 60 * 1000L,
            generationTimeoutMillis = 90 * 1000L,
        )

        /**
         * Tightens the budget on small devices. Fewer model calls is the right
         * trade there: each one is slower, and a long task on a 3 GB phone is more
         * likely to be killed by the system than to finish.
         */
        fun forTier(tier: MemoryTier): AgentLimits = when (tier) {
            MemoryTier.VERY_CONSTRAINED -> DEFAULT.copy(
                maxStepsPerPlan = 5,
                maxStepsPerTask = 8,
                maxReplans = 1,
                maxModelCalls = 4,
                taskTimeoutMillis = 3 * 60 * 1000L,
            )
            MemoryTier.CONSTRAINED -> DEFAULT.copy(
                maxStepsPerPlan = 6,
                maxStepsPerTask = 12,
                maxReplans = 2,
                maxModelCalls = 5,
                taskTimeoutMillis = 4 * 60 * 1000L,
            )
            MemoryTier.COMFORTABLE, MemoryTier.AMPLE -> DEFAULT
        }
    }
}
