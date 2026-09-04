package com.ngi.sarothi.core.plugin

import android.util.Log
import com.ngi.sarothi.core.storage.VaultManager
import com.ngi.sarothi.core.storage.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [PluginConfigStore] backed by the encrypted vault.
 *
 * Reading while the vault is locked returns an empty config rather than throwing:
 * a plugin's *availability* check runs before any confirmation dialog, and it must
 * be able to say "locked" in its own words instead of crashing the settings screen.
 * Writing while locked is an error, because silently dropping a setting the user
 * just changed would be a lie.
 */
class VaultPluginConfigStore(private val vault: VaultManager) : PluginConfigStore {

    override fun read(pluginName: String): PluginConfig {
        if (!vault.isUnlocked) return PluginConfig.fromJson(null)
        val path = VaultPaths.pluginConfigPath(pluginName)
        val json = runCatching { vault.readEncryptedJson(path) }.getOrElse { failure ->
            Log.w(TAG, "Could not read plugin config $path", failure)
            null
        }
        return PluginConfig.fromJson(json)
    }

    override suspend fun write(pluginName: String, config: PluginConfig) = withContext(Dispatchers.IO) {
        val path = VaultPaths.pluginConfigPath(pluginName)
        vault.requireFileSystem().createDirectories(VaultPaths.PLUGINS_CONFIG_DIR)
        vault.writeEncryptedJson(path, config.toJson())
    }

    companion object {
        private const val TAG = "SarothiPluginConfig"
    }
}
