package com.ngi.sarothi.core.runtime

import android.app.ActivityManager
import android.content.Context
import com.google.gson.JsonObject
import com.ngi.sarothi.core.model.ModelRole
import com.ngi.sarothi.core.util.intOr

/** Snapshot of `ActivityManager.getMemoryInfo()`. */
data class DeviceMemory(
    val totalBytes: Long,
    val availableBytes: Long,
    val lowMemoryThresholdBytes: Long,
    val isLowMemory: Boolean,
) {
    val totalMiB: Long get() = totalBytes / MIB
    val availableMiB: Long get() = availableBytes / MIB

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("total_mib", totalMiB)
        addProperty("available_mib", availableMiB)
        addProperty("low_memory_threshold_mib", lowMemoryThresholdBytes / MIB)
        addProperty("is_low_memory", isLowMemory)
    }

    companion object {
        const val MIB = 1024L * 1024L
    }
}

/**
 * Coarse capability tier, derived from *total* device RAM.
 *
 * The spec's headline target is a phone with 3 GB of total RAM, which lands in
 * [CONSTRAINED]. Everything the tier controls is about not being killed: how much
 * context to allocate, whether the vision model may stay resident, how quickly it
 * is released, and how many inference threads to spin up.
 */
enum class MemoryTier {
    /** Under ~2.5 GB total. Nothing optional stays resident. */
    VERY_CONSTRAINED,

    /** ~2.5–4 GB total. The 3 GB target devices. */
    CONSTRAINED,

    /** ~4–6 GB total. */
    COMFORTABLE,

    /** Above ~6 GB total. */
    AMPLE,
    ;

    companion object {
        fun forTotalRam(totalBytes: Long): MemoryTier = when {
            totalBytes < 2_500L * DeviceMemory.MIB -> VERY_CONSTRAINED
            totalBytes < 4L * 1024 * DeviceMemory.MIB -> CONSTRAINED
            totalBytes < 6L * 1024 * DeviceMemory.MIB -> COMFORTABLE
            else -> AMPLE
        }
    }
}

/**
 * Runtime tuning derived from measured device RAM.
 *
 * Nothing here is guessed at build time: [memory] is read from
 * `ActivityManager.getMemoryInfo()` on the actual device, so the same APK behaves
 * differently on a 3 GB phone and an 8 GB one. Users can override the derived
 * values in Settings; the overrides are persisted and [effective] merges them.
 */
class RamPolicy(private val context: Context) {

    val memory: DeviceMemory by lazy { readMemory() }

    val derivedTier: MemoryTier get() = MemoryTier.forTotalRam(memory.totalBytes)

    /** User override, or the derived tier when unset. */
    var tierOverride: MemoryTier? = null

    val tier: MemoryTier get() = tierOverride ?: derivedTier

    private fun readMemory(): DeviceMemory {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return DeviceMemory(
            totalBytes = info.totalMem,
            availableBytes = info.availMem,
            lowMemoryThresholdBytes = info.threshold,
            isLowMemory = info.lowMemory,
        )
    }

    /** Token budget for a context. KV cache grows linearly with this, so it is capped hard. */
    fun contextTokens(role: ModelRole): Int = when (tier) {
        MemoryTier.VERY_CONSTRAINED -> if (role == ModelRole.TEXT_ORCHESTRATOR) 1024 else 768
        MemoryTier.CONSTRAINED -> if (role == ModelRole.TEXT_ORCHESTRATOR) 2048 else 1024
        MemoryTier.COMFORTABLE -> if (role == ModelRole.TEXT_ORCHESTRATOR) 4096 else 2048
        MemoryTier.AMPLE -> 8192
    }

    fun batchTokens(): Int = when (tier) {
        MemoryTier.VERY_CONSTRAINED -> 128
        MemoryTier.CONSTRAINED -> 256
        MemoryTier.COMFORTABLE -> 512
        MemoryTier.AMPLE -> 512
    }

    /**
     * Inference threads. More threads than physical cores buys nothing on a phone
     * and costs scheduler churn, so this is capped well below `availableProcessors`
     * on small devices.
     */
    fun inferenceThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cap = when (tier) {
            MemoryTier.VERY_CONSTRAINED -> 2
            MemoryTier.CONSTRAINED -> 3
            MemoryTier.COMFORTABLE -> 4
            MemoryTier.AMPLE -> 6
        }
        return minOf(cores, cap)
    }

    /**
     * Whether the vision model may stay loaded between screen tasks.
     *
     * On constrained tiers it is unloaded immediately after each use: a resident
     * ~320 MB VLM on a 3 GB phone is what gets Sarothi killed in the background.
     */
    fun mayKeepVisionResident(): Boolean = when (tier) {
        MemoryTier.VERY_CONSTRAINED, MemoryTier.CONSTRAINED -> false
        MemoryTier.COMFORTABLE, MemoryTier.AMPLE -> true
    }

    fun visionIdleTimeoutMillis(): Long = when (tier) {
        MemoryTier.VERY_CONSTRAINED -> 0L
        MemoryTier.CONSTRAINED -> 0L
        MemoryTier.COMFORTABLE -> 60_000L
        MemoryTier.AMPLE -> 180_000L
    }

    fun speechIdleTimeoutMillis(): Long = when (tier) {
        MemoryTier.VERY_CONSTRAINED, MemoryTier.CONSTRAINED -> 0L
        MemoryTier.COMFORTABLE -> 30_000L
        MemoryTier.AMPLE -> 120_000L
    }

    /**
     * Maximum concurrent loaded models. Orchestrator (always) + one on-demand model
     * is the ceiling on 3 GB devices; anything more invites a low-memory kill.
     */
    fun maxResidentModels(): Int = when (tier) {
        MemoryTier.VERY_CONSTRAINED -> 1
        MemoryTier.CONSTRAINED -> 2
        MemoryTier.COMFORTABLE -> 3
        MemoryTier.AMPLE -> 4
    }

    /**
     * When free RAM drops below this fraction of the model's footprint plus the
     * system threshold, the session manager refuses a new load and tells the user
     * which model to unload instead of triggering a system-wide kill.
     */
    fun canLoad(modelRamBytes: Long): Boolean {
        val current = readMemory()
        if (current.isLowMemory) return false
        val headroom = current.availableBytes - current.lowMemoryThresholdBytes
        return headroom > modelRamBytes + SAFETY_MARGIN_BYTES
    }

    fun describe(): String = buildString {
        append("Total RAM ${memory.totalMiB} MiB, available ${memory.availableMiB} MiB, ")
        append("low-memory threshold ${memory.lowMemoryThresholdBytes / DeviceMemory.MIB} MiB. ")
        append("Tier: $tier")
        if (tierOverride != null) append(" (overridden; device suggests $derivedTier)")
        append(". Context ${contextTokens(ModelRole.TEXT_ORCHESTRATOR)} tokens, ")
        append("${inferenceThreads()} inference thread(s), ")
        append(if (mayKeepVisionResident()) "vision model may stay resident" else "vision model is unloaded after every use")
        append('.')
    }

    companion object {
        private const val SAFETY_MARGIN_BYTES = 96L * DeviceMemory.MIB

        fun fromJson(json: JsonObject): MemoryTier? =
            json.intOr("tier_ordinal", -1).takeIf { it >= 0 }
                ?.let { ordinal -> MemoryTier.entries.getOrNull(ordinal) }
    }
}
