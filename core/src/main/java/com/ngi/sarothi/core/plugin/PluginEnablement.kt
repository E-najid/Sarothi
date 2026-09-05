package com.ngi.sarothi.core.plugin

import com.google.gson.JsonObject
import com.ngi.sarothi.core.storage.VaultManager
import com.ngi.sarothi.core.storage.VaultPaths
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.arrayOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Which plugins the user has switched off, persisted in the vault so the choice
 * travels with the SD card.
 *
 * Disabled is stored as an explicit list rather than "enabled" because new
 * plugins must start enabled-by-default-when-possible and a vault written by an
 * older Sarothi should not silently switch off everything it does not know about.
 * `permission_guard` can never be disabled: the safety layer needs it.
 */
class PluginEnablement(private val vault: VaultManager) {

    private val mutex = Mutex()

    @Volatile
    private var cache: MutableSet<String>? = null

    private fun readLocked(): MutableSet<String> {
        cache?.let { return it }
        val names = mutableSetOf<String>()
        if (vault.isUnlocked) {
            val json = runCatching { vault.readEncryptedJson(VaultPaths.ENABLED_PLUGINS) }.getOrNull()
            json?.arrayOrNull("disabled")?.forEach { element ->
                if (element.isJsonPrimitive) names += element.asString
            }
        }
        names -= ALWAYS_ON
        cache = names
        return names
    }

    private fun writeLocked(disabled: Set<String>) {
        cache = disabled.toMutableSet()
        if (!vault.isUnlocked) return
        val document = Json.obj {
            addProperty("schema_version", 1)
            addProperty("updated_at", java.time.Instant.now().toString())
            add("disabled", Json.arr { disabled.sorted().forEach { add(it) } })
        }
        runCatching { vault.writeEncryptedJson(VaultPaths.ENABLED_PLUGINS, document) }
    }

    suspend fun disabled(): Set<String> = withContext(Dispatchers.IO) { mutex.withLock { readLocked().toSet() } }

    suspend fun isEnabled(name: String): Boolean =
        name in ALWAYS_ON || withContext(Dispatchers.IO) { mutex.withLock { name !in readLocked() } }

    suspend fun setEnabled(name: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (name in ALWAYS_ON && !enabled) return@withLock false
            val disabled = readLocked()
            if (enabled) disabled -= name else disabled += name
            writeLocked(disabled)
            true
        }
    }

    /** Called when the vault is locked; the in-memory answer must not outlive the key. */
    fun invalidate() {
        cache = null
    }

    companion object {
        val ALWAYS_ON = setOf("permission_guard", "ask_user", "safety_status")
    }
}
