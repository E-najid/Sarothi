package com.ngi.sarothi.core.storage

/**
 * Canonical layout of a Sarothi vault folder.
 *
 * The folder is chosen by the user through the Storage Access Framework and lives
 * *outside* app-private storage, which is what lets it survive an uninstall and be
 * pointed at from a different device.
 *
 * ```
 * /manifest.json                     plaintext: schema version, model metadata, salts
 * /memories/conversations.db         encrypted (AES-256-GCM) SQLite snapshot
 * /memories/notes.json               encrypted
 * /memories/preferences.json         encrypted
 * /memories/todos.json               encrypted
 * /memories/persona.json             encrypted
 * /memories/schedules.json           encrypted
 * /memories/notification_rules.json  encrypted
 * /plugins_config/enabled_plugins.json  encrypted
 * /logs/                             encrypted, one file per day
 * /task_history/                     encrypted, one file per task
 * /models/<model files>              NOT encrypted (public weights, no decrypt cost)
 * ```
 *
 * Everything under `/memories/`, `/plugins_config/`, `/logs/` and `/task_history/`
 * is sealed with [com.ngi.sarothi.core.crypto.EncryptedFileFormat]. Model files are
 * deliberately plaintext: they are public artifacts, and paying a decrypt pass over
 * a 200 MB GGUF on a 3 GB phone would buy nothing.
 */
object VaultPaths {

    const val MANIFEST = "manifest.json"

    const val MEMORIES_DIR = "memories"
    /**
     * Conversations are one sealed JSON file each rather than a single SQLite
     * database: the vault lives behind a `content://` tree URI, and SQLite cannot
     * open one. Appending a message rewrites that conversation's file, which is
     * bounded work and keeps every byte of it encrypted at rest.
     */
    const val CONVERSATIONS_DIR = "$MEMORIES_DIR/conversations"
    const val MEMORIES = "$MEMORIES_DIR/memories.json"
    const val USER_FACTS = "$MEMORIES_DIR/user_facts.json"
    const val NOTES = "$MEMORIES_DIR/notes.json"
    const val PREFERENCES = "$MEMORIES_DIR/preferences.json"
    const val TODOS = "$MEMORIES_DIR/todos.json"
    const val PERSONA = "$MEMORIES_DIR/persona.json"
    const val SCHEDULES = "$MEMORIES_DIR/schedules.json"
    const val NOTIFICATION_RULES = "$MEMORIES_DIR/notification_rules.json"
    const val GEOFENCES = "$MEMORIES_DIR/geofences.json"

    const val PLUGINS_CONFIG_DIR = "plugins_config"
    const val ENABLED_PLUGINS = "$PLUGINS_CONFIG_DIR/enabled_plugins.json"

    const val LOGS_DIR = "logs"
    const val TASK_HISTORY_DIR = "task_history"
    const val MODELS_DIR = "models"

    /** Directories created when a vault is initialised. */
    val REQUIRED_DIRECTORIES = listOf(
        MEMORIES_DIR,
        CONVERSATIONS_DIR,
        PLUGINS_CONFIG_DIR,
        LOGS_DIR,
        TASK_HISTORY_DIR,
        MODELS_DIR,
    )

    /** Every path that is encrypted at rest. `/models` and `/manifest.json` are not. */
    val ENCRYPTED_ROOTS = listOf(MEMORIES_DIR, PLUGINS_CONFIG_DIR, LOGS_DIR, TASK_HISTORY_DIR)

    fun isEncrypted(path: String): Boolean =
        ENCRYPTED_ROOTS.any { path == it || path.startsWith("$it/") }

    fun fileName(path: String): String = path.substringAfterLast('/')

    fun parentDir(path: String): String =
        path.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { "" }

    fun conversationPath(conversationId: String): String = "$CONVERSATIONS_DIR/$conversationId.json"

    /** `logs/actions-2026-09-04.jsonl` — one append-only audit file per day. */
    fun logPath(dateIso: String): String = "$LOGS_DIR/actions-$dateIso.jsonl"

    fun taskPath(taskId: String): String = "$TASK_HISTORY_DIR/$taskId.json"

    fun pluginConfigPath(pluginName: String): String = "$PLUGINS_CONFIG_DIR/$pluginName.json"

    fun join(vararg parts: String): String =
        parts.filter { it.isNotEmpty() }.joinToString("/")
}
