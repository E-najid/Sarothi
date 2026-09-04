package com.ngi.sarothi.app.di

import android.util.Log
import com.ngi.sarothi.core.persona.Persona
import com.ngi.sarothi.core.storage.VaultManager
import com.ngi.sarothi.core.storage.VaultPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the persona lives: `memories/persona.json` inside the vault, so a customised
 * Sarothi travels with the SD card and is encrypted like every other memory.
 *
 * Until the vault is unlocked there is nothing to read, and this says so rather than
 * inventing a saved persona. [persona] always answers -- the default while the vault is
 * closed, the stored one once it is open -- because the agent, the plugin context and
 * the UI all need a value to render with, and `Persona.DEFAULT` is a real persona, not
 * a placeholder. [loaded] is what tells the UI which of the two it is showing.
 */
class PersonaRepository(private val vault: VaultManager) {

    private val current = MutableStateFlow(Persona.DEFAULT)
    private val loaded = MutableStateFlow(false)

    val persona: StateFlow<Persona> = current.asStateFlow()

    /** True once a persona has actually been read out of an unlocked vault. */
    val isLoaded: StateFlow<Boolean> = loaded.asStateFlow()

    /** Reads the stored persona. Call after the vault is unlocked; safe to call before. */
    fun refresh() {
        if (!vault.isUnlocked) {
            current.value = Persona.DEFAULT
            loaded.value = false
            return
        }
        val stored = runCatching { vault.readEncryptedJson(VaultPaths.PERSONA) }.getOrElse { failure ->
            Log.w(TAG, "Could not read the persona", failure)
            null
        }
        if (stored == null) {
            // No persona.json yet: a fresh vault. Keep the default and say it is not
            // loaded, so Settings shows the defaults as defaults rather than as a
            // customisation the user never made.
            current.value = Persona.DEFAULT
            loaded.value = false
            return
        }
        current.value = runCatching { Persona.fromJson(stored) }.getOrElse { failure ->
            Log.w(TAG, "persona.json could not be parsed", failure)
            Persona.DEFAULT
        }
        loaded.value = true
    }

    /**
     * Writes [next] to the vault and publishes it. Returns false when the vault is
     * locked, because a persona that cannot be persisted must not be shown as saved.
     */
    fun save(next: Persona): Boolean {
        current.value = next
        if (!vault.isUnlocked) return false
        val written = runCatching {
            vault.writeEncryptedJson(VaultPaths.PERSONA, next.toJson())
        }.isSuccess
        loaded.value = written
        return written
    }

    private companion object {
        const val TAG = "SarothiPersona"
    }
}
