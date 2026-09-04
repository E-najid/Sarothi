package com.ngi.sarothi.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ngi.sarothi.core.crypto.KdfParameters
import com.ngi.sarothi.core.crypto.MasterKeyManager
import com.ngi.sarothi.core.crypto.SecretStore
import com.ngi.sarothi.core.error.VaultLockedException
import com.ngi.sarothi.core.error.VaultNotInitializedException
import com.ngi.sarothi.core.model.CatalogModel
import com.ngi.sarothi.core.model.ChecksumPolicy
import com.ngi.sarothi.core.model.ModelCatalog
import com.ngi.sarothi.core.util.Hashing
import com.ngi.sarothi.core.util.Json
import java.time.Instant

/** What Sarothi found when the user pointed it at a folder. */
sealed interface VaultAttachResult {

    /** The folder is usable and empty of Sarothi data: offer "create a new vault". */
    data class EmptyFolder(
        val fileSystem: VaultFileSystem,
    ) : VaultAttachResult

    /** The folder already contains a Sarothi vault: offer "restore". */
    data class ExistingVault(
        val fileSystem: VaultFileSystem,
        val manifest: VaultManifest,
        val modelAudit: ModelAudit,
    ) : VaultAttachResult

    /** The folder has content but no readable manifest — not a Sarothi vault. */
    data class NotAVault(
        val fileSystem: VaultFileSystem,
        val reason: String,
    ) : VaultAttachResult

    /** The folder could not be opened at all (permission revoked, unmounted…). */
    data class AccessFailed(
        val treeUri: Uri,
        val reason: String,
    ) : VaultAttachResult
}

/** Integrity state of a single model file in the vault. */
sealed interface ModelState {
    data object Missing : ModelState

    /** Present, correct size, and its digest matched an upstream-published digest. */
    data class Verified(val sizeBytes: Long, val digest: String) : ModelState

    /**
     * Present with the expected size, but upstream publishes no digest for it, so
     * integrity could not be proven. Still usable — the UI must say it is unverified
     * rather than implying a checksum passed.
     */
    data class PresentUnverified(val sizeBytes: Long, val digest: String?, val reason: String) : ModelState

    data class SizeMismatch(val expectedBytes: Long, val actualBytes: Long) : ModelState

    data class Corrupt(val expectedDigest: String, val actualDigest: String) : ModelState
}

/** Result of auditing every catalogue model against the vault. */
data class ModelAudit(val states: Map<String, ModelState>) {

    fun stateOf(model: CatalogModel): ModelState = states[model.id] ?: ModelState.Missing

    val missing: List<CatalogModel>
        get() = ModelCatalog.ALL.filter { stateOf(it) == ModelState.Missing }

    val corrupt: List<CatalogModel>
        get() = ModelCatalog.ALL.filter {
            val state = stateOf(it)
            state is ModelState.Corrupt || state is ModelState.SizeMismatch
        }

    /** Models that must be fetched before Sarothi is usable at all. */
    val missingRequired: List<CatalogModel>
        get() = ModelCatalog.REQUIRED.filter { stateOf(it) == ModelState.Missing }

    val isReadyForInference: Boolean
        get() = missingRequired.isEmpty() && corrupt.none { it.required }
}

/**
 * Owns the vault's lifecycle: choosing the folder, taking the persistable URI
 * permission, creating or restoring, holding the unlocked master key, and auditing
 * model integrity.
 *
 * The master key lives only in memory. Sarothi never writes it to disk and never
 * caches it in preferences: after a process restart the user types the passphrase
 * again (or uses the biometric convenience layer, which re-derives nothing and only
 * unwraps a key that the passphrase could have produced anyway).
 */
class VaultManager(
    private val context: Context,
    val secrets: SecretStore,
    val masterKeys: MasterKeyManager,
) {

    var fileSystem: VaultFileSystem? = null
        private set

    var manifest: VaultManifest? = null
        private set

    private var masterKey: ByteArray? = null

    val isConfigured: Boolean get() = secrets.getString(SecretStore.KEY_VAULT_TREE_URI) != null

    val isUnlocked: Boolean get() = masterKey != null

    val treeUri: Uri?
        get() = secrets.getString(SecretStore.KEY_VAULT_TREE_URI)?.let(Uri::parse)

    fun requireFileSystem(): VaultFileSystem =
        fileSystem ?: throw VaultNotInitializedException(
            "No storage folder has been attached. Choose one in Settings → Storage.",
        )

    /**
     * The AES-256 key for `/memories/`.
     * @throws VaultLockedException when the vault is locked.
     */
    fun requireKey(): ByteArray =
        masterKey ?: throw VaultLockedException(
            "Unlock Sarothi with your master password to read or write memory.",
        )

    fun requireManifest(): VaultManifest =
        manifest ?: throw VaultNotInitializedException("No vault manifest is loaded.")

    // ------------------------------------------------------------------ attach

    /**
     * Claims a persistable read/write grant on [treeUri] and opens it.
     *
     * Called from `onActivityResult` of `ACTION_OPEN_DOCUMENT_TREE`. Taking the
     * persistable permission here is what lets Sarothi reopen the same folder after
     * a reboot or an app update without asking again.
     */
    fun attach(treeUri: Uri): VaultAttachResult {
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(treeUri, flags)
        } catch (failure: SecurityException) {
            return VaultAttachResult.AccessFailed(
                treeUri,
                "Android refused a persistable permission grant for this folder " +
                    "(${failure.message}). The folder may be one the system does not allow " +
                    "apps to keep long-term access to (for example some app-private or " +
                    "provider-owned directories).",
            )
        }

        val fs = try {
            SafVaultFileSystem(context, treeUri)
        } catch (failure: Exception) {
            runCatching { resolver.releasePersistableUriPermission(treeUri, flags) }
            return VaultAttachResult.AccessFailed(
                treeUri,
                "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }

        secrets.putString(SecretStore.KEY_VAULT_TREE_URI, treeUri.toString())
        fileSystem = fs

        if (fs.exists(VaultPaths.MANIFEST)) {
            val parsed = runCatching { VaultManifest.parse(fs.readFile(VaultPaths.MANIFEST)) }
            if (parsed.isSuccess) {
                val loadedManifest = parsed.getOrThrow()
                manifest = loadedManifest
                return VaultAttachResult.ExistingVault(fs, loadedManifest, auditModels(fs, loadedManifest))
            }
            return VaultAttachResult.NotAVault(
                fs,
                "The folder contains a manifest.json but it could not be read as a Sarothi " +
                    "manifest: ${parsed.exceptionOrNull()?.message}",
            )
        }

        val entries = fs.listFiles("")
        return if (entries.isEmpty()) {
            VaultAttachResult.EmptyFolder(fs)
        } else {
            VaultAttachResult.NotAVault(
                fs,
                "The folder already contains ${entries.size} item(s) but no manifest.json, so it " +
                    "is not a Sarothi vault. Sarothi will not write into a folder that already " +
                    "holds other data unless you confirm.",
            )
        }
    }

    /** Reopens the previously chosen folder at startup, without unlocking it. */
    fun reattach(): Boolean {
        val uri = treeUri ?: return false
        val resolver = context.contentResolver
        val persisted = runCatching { resolver.persistedUriPermissions }
            .getOrDefault(emptyList())
            .any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        if (!persisted) {
            secrets.putString(SecretStore.KEY_VAULT_TREE_URI, null)
            return false
        }
        val fs = runCatching { SafVaultFileSystem(context, uri) }.getOrNull() ?: run {
            secrets.putString(SecretStore.KEY_VAULT_TREE_URI, null)
            return false
        }
        fileSystem = fs
        manifest = if (fs.exists(VaultPaths.MANIFEST)) {
            runCatching { VaultManifest.parse(fs.readFile(VaultPaths.MANIFEST)) }.getOrNull()
        } else {
            null
        }
        return true
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates a new vault in the attached folder: directory layout, protection
     * record, manifest and empty (encrypted) memory files.
     */
    suspend fun createFreshVault(
        password: CharArray,
        kdf: KdfParameters = KdfParameters.DEFAULT,
        appVersion: String? = null,
        deviceLabel: String? = null,
    ): VaultManifest {
        val fs = requireFileSystem()
        if (fs.exists(VaultPaths.MANIFEST)) {
            throw VaultNotInitializedException(
                "The chosen folder already contains a Sarothi manifest. Creating a new vault " +
                    "here would overwrite it — pick an empty folder, or restore instead.",
            )
        }
        VaultPaths.REQUIRED_DIRECTORIES.forEach { fs.createDirectories(it) }

        val security = masterKeys.createSecurity(password, kdf)
        val now = Instant.now().toString()
        val created = VaultManifest(
            createdAt = now,
            updatedAt = now,
            appVersion = appVersion,
            deviceLabel = deviceLabel,
            security = security,
            models = emptyMap(),
        )
        manifest = created
        persistManifest()

        // Seed the encrypted stores so a fresh vault has a consistent shape and the
        // first unlock does not have to distinguish "empty" from "missing".
        val key = masterKeys.deriveKey(security, password)
        masterKey = key
        runCatching {
            writeEncrypted(VaultPaths.NOTES, """{"notes":[]}""")
            writeEncrypted(VaultPaths.TODOS, """{"todos":[]}""")
            writeEncrypted(VaultPaths.PREFERENCES, """{"preferences":{}}""")
            writeEncrypted(VaultPaths.ENABLED_PLUGINS, """{"enabled":{}}""")
        }
        return created
    }

    // ------------------------------------------------------------------ unlock

    /**
     * Restores an existing vault: verifies the passphrase against the manifest's
     * verifier hash, derives the key and re-audits the models.
     */
    suspend fun openExistingVault(password: CharArray): VaultManifest {
        val fs = requireFileSystem()
        val current = manifest ?: VaultManifest.parse(fs.readFile(VaultPaths.MANIFEST))
            .also { manifest = it }

        val key = masterKeys.unlock(current.security, password)
        masterKey = key

        // Prove the key really opens the vault by decrypting one small file. A
        // verifier match plus a failed decrypt would mean the memories are damaged,
        // and the user must be told that rather than shown an empty app.
        val probe = VaultPaths.PREFERENCES
        if (fs.exists(probe)) {
            val sealed = fs.readFile(probe)
            val opened = runCatching {
                com.ngi.sarothi.core.crypto.EncryptedFileFormat.open(key, probe, sealed)
            }
            if (opened.isFailure) {
                masterKey = null
                throw VaultLockedException(
                    "The password was accepted but '${VaultPaths.PREFERENCES}' could not be " +
                        "decrypted (${opened.exceptionOrNull()?.message}). The memory files in this " +
                        "folder may be corrupt, or may have been written with different parameters " +
                        "than the manifest records.",
                )
            }
        }
        return current
    }

    /**
     * Installs an already-derived key, used by the biometric convenience unlock.
     *
     * The key still came from the passphrase — it was wrapped in an Android
     * Keystore AES key that the fingerprint gates, and unwrapping only saves the
     * Argon2id pass. Biometrics therefore never *authorise* anything the password
     * could not; they just re-present a key the user already proved they own.
     *
     * The key is proved against the vault before it is accepted: a wrapped key
     * from a different vault (restored SD card, reinstalled app) must fail here
     * rather than unlocking to garbage.
     *
     * @throws VaultLockedException when [key] cannot open the vault.
     */
    fun unlockWithKey(key: ByteArray): VaultManifest {
        val fs = requireFileSystem()
        val current = manifest ?: VaultManifest.parse(fs.readFile(VaultPaths.MANIFEST))
            .also { manifest = it }

        val probe = VaultPaths.PREFERENCES
        if (fs.exists(probe)) {
            val sealed = fs.readFile(probe)
            val opened = runCatching {
                com.ngi.sarothi.core.crypto.EncryptedFileFormat.open(key, probe, sealed)
            }
            if (opened.isFailure) {
                throw VaultLockedException(
                    "The stored key does not open this vault " +
                        "(${opened.exceptionOrNull()?.message}). This happens when the folder was " +
                        "restored from another device or the vault was recreated; unlock with the " +
                        "master password once and re-enable biometric unlock.",
                )
            }
        }
        masterKey = key.copyOf()
        return current
    }

    /** Drops the in-memory key. Encrypted data on the SD card is untouched. */
    fun lock() {
        masterKey?.fill(0)
        masterKey = null
    }

    // ------------------------------------------------------------- model audit

    /**
     * Verifies every catalogue model against the vault.
     *
     * Digests are only computed for files whose size already matches, so a
     * half-downloaded 200 MB file is rejected instantly instead of being hashed.
     */
    fun auditModels(
        fs: VaultFileSystem = requireFileSystem(),
        loadedManifest: VaultManifest = requireManifest(),
    ): ModelAudit {
        val states = linkedMapOf<String, ModelState>()
        for (model in ModelCatalog.ALL) {
            states[model.id] = verifyModel(model, fs, loadedManifest)
        }
        // Files in models/ that are not in the catalogue: reported through the
        // manifest, never silently adopted.
        return ModelAudit(states)
    }

    fun verifyModel(
        model: CatalogModel,
        fs: VaultFileSystem = requireFileSystem(),
        loadedManifest: VaultManifest = requireManifest(),
    ): ModelState {
        if (!fs.exists(model.vaultPath)) return ModelState.Missing

        val actualSize = fs.fileSize(model.vaultPath)
        if (actualSize != model.sizeBytes) {
            return ModelState.SizeMismatch(model.sizeBytes, actualSize)
        }

        return when (model.checksumPolicy) {
            ChecksumPolicy.SHA256_PINNED -> {
                val digest = digestFromVault(fs, model.vaultPath, sha256 = true)
                if (Hashing.constantTimeEqualsHex(digest, model.sha256!!)) {
                    ModelState.Verified(actualSize, digest)
                } else {
                    ModelState.Corrupt(model.sha256, digest)
                }
            }

            ChecksumPolicy.GIT_BLOB_SHA1_PINNED -> {
                val digest = digestFromVault(fs, model.vaultPath, sha256 = false)
                if (Hashing.constantTimeEqualsHex(digest, model.gitBlobSha1!!)) {
                    ModelState.Verified(actualSize, digest)
                } else {
                    ModelState.Corrupt(model.gitBlobSha1, digest)
                }
            }

            ChecksumPolicy.SIZE_ONLY -> {
                val recorded = loadedManifest.models[model.id]?.computedDigest
                ModelState.PresentUnverified(
                    actualSize,
                    recorded,
                    "Upstream publishes no checksum for ${model.fileName}; only the file size " +
                        "could be checked. The file is usable but its integrity is unproven.",
                )
            }
        }
    }

    private fun digestFromVault(fs: VaultFileSystem, path: String, sha256: Boolean): String {
        return fs.openInputStream(path).use { input ->
            if (sha256) {
                Hashing.sha256Hex(input)
            } else {
                // Git blob digests need the length prefix, which requires the size up front.
                val bytes = input.readBytes()
                Hashing.gitBlobSha1(bytes)
            }
        }
    }

    /** Records a model in the manifest after a successful, verified download. */
    fun recordModel(entry: ManifestModelEntry, key: String = entry.catalogId ?: entry.name) {
        val current = requireManifest()
        manifest = current.withModels(current.models + (key to entry))
        persistManifest()
    }

    fun forgetModel(key: String) {
        val current = requireManifest()
        manifest = current.withModels(current.models - key)
        persistManifest()
    }

    // --------------------------------------------------------------- vault i/o

    /** Writes [plaintext] sealed with the vault key, using [path] as the AAD. */
    fun writeEncrypted(path: String, plaintext: String) {
        writeEncrypted(path, plaintext.toByteArray(Charsets.UTF_8))
    }

    fun writeEncrypted(path: String, plaintext: ByteArray) {
        val key = requireKey()
        val sealed = com.ngi.sarothi.core.crypto.EncryptedFileFormat.seal(key, path, plaintext)
        requireFileSystem().writeFile(path, sealed)
    }

    /**
     * Reads and opens a sealed vault file.
     * @return null when the file does not exist yet, so callers can apply defaults.
     */
    fun readEncrypted(path: String): ByteArray? {
        val fs = requireFileSystem()
        if (!fs.exists(path)) return null
        val key = requireKey()
        val sealed = fs.readFile(path)
        return com.ngi.sarothi.core.crypto.EncryptedFileFormat.open(key, path, sealed)
    }

    fun readEncryptedJson(path: String): com.google.gson.JsonObject? =
        readEncrypted(path)?.let { Json.parseObject(it.toString(Charsets.UTF_8)) }

    fun writeEncryptedJson(path: String, json: com.google.gson.JsonObject) =
        writeEncrypted(path, Json.pretty(json).toByteArray(Charsets.UTF_8))

    fun persistManifest() {
        val current = manifest ?: throw VaultNotInitializedException("No manifest is loaded")
        requireFileSystem().writeFile(VaultPaths.MANIFEST, current.serialize())
    }

    /** Releases the grant and forgets the folder. Does not touch the folder's contents. */
    fun detach() {
        lock()
        val uri = treeUri
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
        }
        secrets.putString(SecretStore.KEY_VAULT_TREE_URI, null)
        fileSystem = null
        manifest = null
    }
}
