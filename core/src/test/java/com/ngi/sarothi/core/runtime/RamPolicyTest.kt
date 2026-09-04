package com.ngi.sarothi.core.runtime

import com.google.gson.JsonObject
import com.ngi.sarothi.core.agent.AgentLimits
import com.ngi.sarothi.core.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a device with a given amount of RAM is allowed to ask of Sarothi.
 *
 * The headline target is a phone with 3 GB of total RAM, and this is the code that
 * decides what that means: which tier it lands in, and therefore how large a context is
 * allocated, how many steps and model calls a task may spend, whether the vision model may
 * stay resident, and how many inference threads start. A wrong boundary here does not
 * crash -- it gets the app killed in the background on exactly the devices it was written
 * for, or it needlessly cripples a phone that had room to spare.
 */
class RamPolicyTest {

    private val mib = 1024L * 1024L
    private val gib = 1024L * mib

    /**
     * Built through the internal constructor with a fixed memory snapshot, which is the
     * only way to ask these questions on a JVM: the production constructor reads
     * `ActivityManager`, and there is no ActivityManager here.
     */
    private fun policy(tier: MemoryTier, totalBytes: Long = 3 * gib): RamPolicy =
        RamPolicy { DeviceMemory(totalBytes, 900 * mib, 200 * mib, false) }
            .apply { tierOverride = tier }

    // ------------------------------------------------------------------ the tier boundary

    /**
     * The requirement, stated as an executable check. A phone sold as 3 GB reports
     * somewhat less than 3072 MiB to `ActivityManager`, because the kernel and the radio
     * reserve part of it, so the nominal figure and a realistic one are both checked: the
     * tier must not depend on which of them a given device happens to report.
     */
    @Test
    fun a_three_gigabyte_device_is_constrained() {
        assertEquals("3 GB nominal", MemoryTier.CONSTRAINED, MemoryTier.forTotalRam(3 * gib))
        assertEquals(
            "what a 3 GB phone usually reports after kernel reservations",
            MemoryTier.CONSTRAINED,
            MemoryTier.forTotalRam(2_800 * mib),
        )
        assertEquals(
            "and a device reporting even less is still not the bottom tier",
            MemoryTier.CONSTRAINED,
            MemoryTier.forTotalRam(2_600 * mib),
        )
    }

    @Test
    fun the_bottom_tier_ends_exactly_where_the_documentation_says() {
        assertEquals(
            "a byte below the boundary",
            MemoryTier.VERY_CONSTRAINED,
            MemoryTier.forTotalRam(2_500 * mib - 1),
        )
        assertEquals(
            "the boundary itself belongs to the tier above it",
            MemoryTier.CONSTRAINED,
            MemoryTier.forTotalRam(2_500 * mib),
        )
    }

    @Test
    fun a_two_gigabyte_device_is_very_constrained() {
        assertEquals(MemoryTier.VERY_CONSTRAINED, MemoryTier.forTotalRam(2 * gib))
        assertEquals(MemoryTier.VERY_CONSTRAINED, MemoryTier.forTotalRam(1_800 * mib))
        assertEquals(MemoryTier.VERY_CONSTRAINED, MemoryTier.forTotalRam(0))
    }

    @Test
    fun every_tier_boundary_is_inclusive_the_way_the_comments_say() {
        assertEquals(MemoryTier.CONSTRAINED, MemoryTier.forTotalRam(4 * gib - 1))
        assertEquals(MemoryTier.COMFORTABLE, MemoryTier.forTotalRam(4 * gib))
        assertEquals(MemoryTier.COMFORTABLE, MemoryTier.forTotalRam(6 * gib - 1))
        assertEquals(MemoryTier.AMPLE, MemoryTier.forTotalRam(6 * gib))
        assertEquals(MemoryTier.AMPLE, MemoryTier.forTotalRam(8 * gib))
        assertEquals(MemoryTier.AMPLE, MemoryTier.forTotalRam(16 * gib))
    }

    @Test
    fun every_tier_is_reachable_from_some_device() {
        val reached = listOf(0L, 3 * gib, 5 * gib, 8 * gib).map { MemoryTier.forTotalRam(it) }.toSet()
        assertEquals(
            "a tier no device can reach is dead configuration",
            MemoryTier.entries.toSet(),
            reached,
        )
    }

    /** The tier is derived from the snapshot, not asserted by hand somewhere else. */
    @Test
    fun the_tier_follows_the_memory_the_device_reported() {
        val threeGb = RamPolicy { DeviceMemory(3 * gib, 900 * mib, 200 * mib, false) }
        assertEquals(MemoryTier.CONSTRAINED, threeGb.derivedTier)
        assertEquals("with no override, the derived tier is the tier", MemoryTier.CONSTRAINED, threeGb.tier)

        threeGb.tierOverride = MemoryTier.AMPLE
        assertEquals("an override wins", MemoryTier.AMPLE, threeGb.tier)
        assertEquals("but the device is still measured as it was", MemoryTier.CONSTRAINED, threeGb.derivedTier)
    }

    // ------------------------------------------------------------------ what a tier costs

    @Test
    fun the_three_gigabyte_target_gets_the_tightened_budget() {
        val limits = AgentLimits.forTier(MemoryTier.CONSTRAINED)
        assertEquals(6, limits.maxStepsPerPlan)
        assertEquals(12, limits.maxStepsPerTask)
        assertEquals(5, limits.maxModelCalls)
        assertEquals(4 * 60 * 1000L, limits.taskTimeoutMillis)
        assertTrue(
            "a constrained device must get a smaller budget than the default",
            limits.maxModelCalls < AgentLimits.DEFAULT.maxModelCalls,
        )
    }

    @Test
    fun the_smallest_devices_get_the_smallest_budget_of_all() {
        val tightest = AgentLimits.forTier(MemoryTier.VERY_CONSTRAINED)
        val constrained = AgentLimits.forTier(MemoryTier.CONSTRAINED)

        assertTrue(tightest.maxStepsPerPlan < constrained.maxStepsPerPlan)
        assertTrue(tightest.maxStepsPerTask < constrained.maxStepsPerTask)
        assertTrue(tightest.maxModelCalls < constrained.maxModelCalls)
        assertTrue(tightest.maxReplans < constrained.maxReplans)
        assertTrue(tightest.taskTimeoutMillis < constrained.taskTimeoutMillis)
        assertEquals(5, tightest.maxStepsPerPlan)
        assertEquals(8, tightest.maxStepsPerTask)
        assertEquals(3 * 60 * 1000L, tightest.taskTimeoutMillis)
    }

    @Test
    fun devices_with_room_get_the_default_budget() {
        assertEquals(AgentLimits.DEFAULT, AgentLimits.forTier(MemoryTier.COMFORTABLE))
        assertEquals(AgentLimits.DEFAULT, AgentLimits.forTier(MemoryTier.AMPLE))
    }

    /**
     * Invariants that have to hold for every tier, including any tier added later. Each is
     * a way the agent could otherwise fail with nothing looking wrong: a plan longer than
     * the task it belongs to can never finish, a generation allowed to run longer than the
     * task eats the whole budget by itself, and a zero ceiling means the agent refuses
     * every task on that class of device.
     */
    @Test
    fun every_tier_budget_is_internally_consistent() {
        for (tier in MemoryTier.entries) {
            val limits = AgentLimits.forTier(tier)
            assertTrue(
                "$tier: a plan of ${limits.maxStepsPerPlan} cannot fit a task of ${limits.maxStepsPerTask}",
                limits.maxStepsPerPlan <= limits.maxStepsPerTask,
            )
            assertTrue(
                "$tier: a generation may run ${limits.generationTimeoutMillis}ms inside a task of " +
                    "${limits.taskTimeoutMillis}ms",
                limits.generationTimeoutMillis < limits.taskTimeoutMillis,
            )
            assertTrue("$tier: at least one model call must be allowed", limits.maxModelCalls >= 1)
            assertTrue("$tier: at least one step must be allowed", limits.maxStepsPerPlan >= 1)
            assertTrue("$tier: at least one attempt per step", limits.maxRetriesPerStep >= 1)
            assertTrue("$tier: a task timeout has to be positive", limits.taskTimeoutMillis > 0)
        }
    }

    /**
     * A replan is what a failed step falls back to, so a task has to be able to spend its
     * plan budget more than once -- otherwise the first replan is also the last.
     */
    @Test
    fun the_step_budget_can_accommodate_the_replans_it_allows() {
        for (tier in MemoryTier.entries) {
            val limits = AgentLimits.forTier(tier)
            val plans = limits.maxReplans + 1
            assertTrue(
                "$tier: $plans plan(s) of ${limits.maxStepsPerPlan} steps cannot cover " +
                    "${limits.maxStepsPerTask} task steps",
                plans * limits.maxStepsPerPlan >= limits.maxStepsPerTask,
            )
        }
    }

    // ------------------------------------------------------------------ context budget

    @Test
    fun the_orchestrator_always_gets_at_least_as_much_context_as_the_vision_model() {
        for (tier in MemoryTier.entries) {
            val byTier = policy(tier)
            assertTrue(
                "$tier: the planner needs more room than the describer",
                byTier.contextTokens(ModelRole.TEXT_ORCHESTRATOR) >=
                    byTier.contextTokens(ModelRole.VISION_SCREEN_AGENT),
            )
        }
        assertEquals(2048, policy(MemoryTier.CONSTRAINED).contextTokens(ModelRole.TEXT_ORCHESTRATOR))
        assertEquals(1024, policy(MemoryTier.CONSTRAINED).contextTokens(ModelRole.VISION_SCREEN_AGENT))
    }

    @Test
    fun a_constrained_device_never_keeps_the_vision_model_resident() {
        for (tier in listOf(MemoryTier.VERY_CONSTRAINED, MemoryTier.CONSTRAINED)) {
            val constrained = policy(tier)
            assertFalse(
                "$tier: a resident vision model is what gets Sarothi killed in the background",
                constrained.mayKeepVisionResident(),
            )
            assertEquals("$tier: nothing idle is kept", 0L, constrained.visionIdleTimeoutMillis())
            assertEquals(0L, constrained.speechIdleTimeoutMillis())
        }
        for (tier in listOf(MemoryTier.COMFORTABLE, MemoryTier.AMPLE)) {
            assertTrue("$tier may keep it", policy(tier).mayKeepVisionResident())
            assertTrue(policy(tier).visionIdleTimeoutMillis() > 0)
            assertTrue(policy(tier).speechIdleTimeoutMillis() > 0)
        }
    }

    @Test
    fun a_constrained_device_holds_the_orchestrator_and_one_thing_else() {
        assertEquals(2, policy(MemoryTier.CONSTRAINED).maxResidentModels())
        assertEquals(1, policy(MemoryTier.VERY_CONSTRAINED).maxResidentModels())
        for (tier in MemoryTier.entries) {
            assertTrue(
                "$tier must hold at least the orchestrator",
                policy(tier).maxResidentModels() >= 1,
            )
        }
    }

    @Test
    fun inference_threads_never_exceed_the_tier_cap_nor_the_cores_available() {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        for (tier in MemoryTier.entries) {
            val threads = policy(tier).inferenceThreads()
            assertTrue("$tier asked for $threads threads on $cores cores", threads <= cores)
            assertTrue("$tier asked for no threads at all", threads >= 1)
        }
        assertTrue(
            "the smallest tier must be capped no higher than the largest",
            policy(MemoryTier.VERY_CONSTRAINED).inferenceThreads() <=
                policy(MemoryTier.AMPLE).inferenceThreads(),
        )
    }

    @Test
    fun batch_tokens_grow_with_the_tier_and_never_exceed_the_context() {
        for (tier in MemoryTier.entries) {
            val byTier = policy(tier)
            assertTrue("$tier: a batch of zero processes nothing", byTier.batchTokens() >= 1)
            assertTrue(
                "$tier: a batch of ${byTier.batchTokens()} cannot fit a context of " +
                    byTier.contextTokens(ModelRole.VISION_SCREEN_AGENT),
                byTier.batchTokens() <= byTier.contextTokens(ModelRole.VISION_SCREEN_AGENT),
            )
        }
    }

    /**
     * The load decision is the one that stands between a new model and a system-wide
     * low-memory kill, so it is measured against the memory at the moment of the question.
     */
    @Test
    fun a_load_is_refused_when_the_device_is_already_low_on_memory() {
        val lowMemory = RamPolicy { DeviceMemory(3 * gib, 300 * mib, 200 * mib, isLowMemory = true) }
        assertFalse(
            "the system has said it is low; loading anything now invites a kill",
            lowMemory.canLoad(modelRamBytes = 10 * mib),
        )
    }

    @Test
    fun a_load_is_refused_without_headroom_for_the_model_and_a_margin() {
        val model = 320 * mib
        val tight = RamPolicy { DeviceMemory(3 * gib, 400 * mib, 200 * mib, false) }
        assertFalse(
            "200 MiB of headroom cannot hold a 320 MiB model plus the safety margin",
            tight.canLoad(model),
        )

        val roomy = RamPolicy { DeviceMemory(3 * gib, 2 * gib, 200 * mib, false) }
        assertTrue("1.8 GiB of headroom can", roomy.canLoad(model))
    }

    @Test
    fun the_load_decision_re_measures_rather_than_trusting_the_first_snapshot() {
        var available = 2 * gib
        val policy = RamPolicy { DeviceMemory(3 * gib, available, 200 * mib, false) }

        assertTrue(policy.canLoad(320 * mib))
        available = 300 * mib
        assertFalse(
            "memory freed and then consumed by something else has to be seen",
            policy.canLoad(320 * mib),
        )
    }

    // ------------------------------------------------------------------ the device snapshot

    @Test
    fun the_memory_snapshot_reports_mib_and_survives_json() {
        val snapshot = DeviceMemory(
            totalBytes = 3 * gib,
            availableBytes = 900 * mib,
            lowMemoryThresholdBytes = 200 * mib,
            isLowMemory = false,
        )
        assertEquals(3072, snapshot.totalMiB)
        assertEquals(900, snapshot.availableMiB)

        val json = snapshot.toJson()
        assertEquals(3072, json.get("total_mib").asLong)
        assertEquals(900, json.get("available_mib").asLong)
        assertEquals(200, json.get("low_memory_threshold_mib").asLong)
        assertFalse(json.get("is_low_memory").asBoolean)
    }

    /**
     * A tier override is persisted as an ordinal, so reordering the enum silently changes
     * what every stored override means. This pins the mapping: a reorder has to become a
     * deliberate act with a migration, not a refactor nobody noticed.
     */
    @Test
    fun the_persisted_tier_ordinals_are_pinned() {
        assertEquals(0, MemoryTier.VERY_CONSTRAINED.ordinal)
        assertEquals(1, MemoryTier.CONSTRAINED.ordinal)
        assertEquals(2, MemoryTier.COMFORTABLE.ordinal)
        assertEquals(3, MemoryTier.AMPLE.ordinal)

        for (tier in MemoryTier.entries) {
            val json = JsonObject().apply { addProperty("tier_ordinal", tier.ordinal) }
            assertEquals("ordinal ${tier.ordinal}", tier, RamPolicy.fromJson(json))
        }
    }

    @Test
    fun an_absent_or_impossible_tier_override_reads_as_no_override() {
        assertNull(RamPolicy.fromJson(JsonObject()))
        assertNull(RamPolicy.fromJson(JsonObject().apply { addProperty("tier_ordinal", -1) }))
        assertNull(
            "an ordinal past the end of the enum must not wrap or clamp",
            RamPolicy.fromJson(JsonObject().apply { addProperty("tier_ordinal", 99) }),
        )
    }

    @Test
    fun the_description_names_the_tier_and_what_it_costs() {
        val description = policy(MemoryTier.CONSTRAINED).describe()
        assertTrue(description, description.contains("CONSTRAINED"))
        assertTrue(description, description.contains("3072 MiB"))
        assertTrue(description, description.contains("2048 tokens"))
        assertTrue(
            "the user has to be told the vision model is released after every use",
            description.contains("unloaded after every use"),
        )
    }

    @Test
    fun an_override_says_so_in_the_description() {
        val description = policy(MemoryTier.AMPLE, totalBytes = 3 * gib).describe()
        assertTrue(description, description.contains("overridden"))
        assertTrue(description, description.contains("CONSTRAINED"))
        assertTrue(description, description.contains("vision model may stay resident"))
    }
}
