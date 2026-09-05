package com.ngi.sarothi.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ngi.sarothi.core.crypto.KdfParameters
import com.ngi.sarothi.core.crypto.MasterKeyManager
import com.ngi.sarothi.core.crypto.EncryptedFileFormat
import com.ngi.sarothi.core.crypto.PasswordBytes
import com.ngi.sarothi.core.crypto.SecretStore
import com.ngi.sarothi.core.crypto.VaultSecurity
import com.ngi.sarothi.core.error.VaultLockedException
import com.ngi.sarothi.core.error.VaultNotInitializedException
import com.ngi.sarothi.core.model.CatalogModel
import com.ngi.sarothi.core.model.ChecksumPolicy
import com.ngi.sarothi.core.model.ModelCatalog
import com.ngi.sarothi.core.util.Hashing
import com.ngi.sarothi.core.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * What [VaultManager.changePassphrase] did.
 *
 * A refusal is a value rather than an exception because the common refusals -- a wrong
 * passphrase, a locked vault -- are ordinary outcomes the UI shows inline, and because
 * an interruption halfway through re-encrypting has to be reported with instructions
 * rather than surfacing as a crash.
 */
sealed interface PassphraseChange {

    /** Every sealed file was re-encrypted and the manifest now points at the new record. */
    data class Changed(val filesRotated: Int) : PassphraseChange

    /** Nothing was changed, or the change was interrupted and must be finished. */
    data class Refused(val reason: String) : PassphraseChange
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

    /**
     * The attached folder.
     *
     * The setter is `internal` rather than private so a test can put a
     * [VaultFileSystem] implementation of its own behind a real [VaultManager] -- the
     * interface exists for exactly that, and the alternative is that nothing but a
     * person holding a phone can ever exercise vault lifecycle code. Production code
     * sets it in [attach] and [reattach] only.
     */
    var fileSystem: VaultFileSystem? = null
        internal set

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
                var loadedManifest = parsed.getOrThrow()
                manifest = loadedManifest
                // A passphrase change killed halfway through is finished here, before the
                // user is asked for a passphrase: it may already be the new one.
                if (resumeInterruptedRotation()) {
                    loadedManifest = VaultManifest.parse(fs.readFile(VaultPaths.MANIFEST))
                    manifest = loadedManifest
                }
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
        if (resumeInterruptedRotation()) {
            manifest = runCatching { VaultManifest.parse(fs.readFile(VaultPaths.MANIFEST)) }.getOrNull()
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

        // Both halves of the vault's protection come out of one call, because creating
        // them in two steps consumed the passphrase in the first one: createSecurity()
        // wipes the array it is given, so the deriveKey() that used to follow it derived
        // the vault's encryption key from a run of NUL characters. The vault still opened
        // -- unlocking made the same mistake -- and the passphrase stopped being what
        // protected the memories on the SD card.
        val material = masterKeys.createKeyMaterial(password, kdf)
        val security = material.security
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
        val key = material.key
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

    // ------------------------------------------------------- passphrase change

    /**
     * Changes the vault passphrase by re-sealing every encrypted file under a key derived
     * from the new one.
     *
     * The vault key *is* `Argon2id(passphrase, key_salt)` -- there is no wrapped data key
     * sitting behind it -- so changing the passphrase means rewriting every sealed file.
     * That has to survive being interrupted, because a phone with 3 GB of RAM can be
     * killed at any instruction. The order below is what makes that safe:
     *
     *  1. Every sealed file is opened with the key in memory and written *beside itself*
     *     as `<path>.rotating`, then read back and compared. No original is touched, so a
     *     failure here costs time and nothing else.
     *  2. The new protection record is published to `memories/.rotation/rotation.json`.
     *     From this moment the remaining work needs no key at all.
     *  3. Each original is overwritten with its new copy, and its path appended to
     *     `memories/.rotation/progress` as it lands.
     *  4. The manifest is pointed at the new record.
     *
     * A kill during 3 or 4 leaves files sealed with the new passphrase while the manifest
     * still names the old one, which is exactly the state [resumeInterruptedRotation]
     * finishes -- from the temp files and the published record, without any passphrase. It
     * runs when the folder is attached, so the vault repairs itself before the user is
     * asked to type anything.
     *
     * Both arrays are consumed: [MasterKeyManager.verifyPassword] wipes
     * [currentPassword] and [MasterKeyManager.createKeyMaterial] wipes [newPassword].
     * Neither may be used again after this call.
     */
    suspend fun changePassphrase(
        currentPassword: CharArray,
        newPassword: CharArray,
    ): PassphraseChange = withContext(Dispatchers.IO) {
        // Refusals before the passphrase is used are this function's to clean up: nothing
        // downstream consumed either array, and a passphrase left sitting in a CharArray
        // is one heap dump away from being readable.
        fun refuse(reason: String): PassphraseChange.Refused {
            PasswordBytes.wipe(currentPassword)
            PasswordBytes.wipe(newPassword)
            return PassphraseChange.Refused(reason)
        }

        val fs = fileSystem ?: return@withContext refuse(
            "No folder is attached, so there is nothing to re-encrypt.",
        )
        val currentManifest = manifest ?: return@withContext refuse(
            "No vault is loaded. Attach the folder and unlock it first.",
        )
        val oldKey = masterKey ?: return@withContext refuse(
            "The vault is locked. Unlock it before changing the passphrase.",
        )

        if (!masterKeys.verifyPassword(currentManifest.security, currentPassword, recordFailure = true)) {
            val state = masterKeys.lockout.state()
            // verifyPassword consumed the current passphrase; the new one was nobody's yet.
            PasswordBytes.wipe(newPassword)
            return@withContext PassphraseChange.Refused(
                "That is not the passphrase this vault was sealed with. " +
                    if (state.lockedUntilEpochMillis != null) {
                        "Too many attempts -- wait ${state.lockedUntilEpochMillis - System.currentTimeMillis()} " +
                            "ms before trying again."
                    } else {
                        "${masterKeys.lockout.attemptsRemaining()} attempt(s) remain before a wait is imposed."
                    },
            )
        }
        masterKeys.lockout.recordSuccess()

        // One pass over the new passphrase, producing the record and the key together;
        // deriving the key from the array afterwards would derive it from NULs.
        val material = masterKeys.createKeyMaterial(newPassword)
        val paths = vaultFiles(fs).filterNot { it.endsWith(Rotation.TEMP_SUFFIX) }
        val temps = mutableListOf<String>()
        var overwriting = false

        try {
            for (path in paths) {
                val sealed = fs.readFile(path)
                // Logs and task history are deliberately plaintext, and models are not
                // encrypted at all: only files that carry the sealed-format header are
                // this function's business.
                if (!EncryptedFileFormat.isSealed(sealed)) continue

                val plaintext = runCatching { EncryptedFileFormat.open(oldKey, path, sealed) }
                    .getOrElse { failure ->
                        throw VaultLockedException(
                            "'$path' carries the sealed-format header but the key in memory " +
                                "cannot open it (${failure.javaClass.simpleName}: ${failure.message}). " +
                                "The passphrase cannot be changed until that file is restored.",
                        )
                    }
                val temp = "$path${Rotation.TEMP_SUFFIX}"
                fs.writeFile(temp, EncryptedFileFormat.seal(material.key, path, plaintext))

                // Prove the new copy before the original is overwritten. A re-encrypted
                // file that does not read back is silent data loss.
                val proved = EncryptedFileFormat.open(material.key, path, fs.readFile(temp))
                val identical = proved.contentEquals(plaintext)
                plaintext.fill(0)
                proved.fill(0)
                if (!identical) {
                    throw VaultLockedException(
                        "The re-encrypted copy of '$path' did not read back as the same bytes. " +
                            "The original has been left untouched.",
                    )
                }
                temps += temp
            }

            fs.createDirectories(Rotation.DIR)
            fs.writeFile(
                Rotation.RECORD,
                Json.pretty(material.security.toJson()).toByteArray(Charsets.UTF_8),
            )
            fs.writeFile(Rotation.PROGRESS, ByteArray(0))

            overwriting = true
            for (temp in temps) {
                val path = temp.removeSuffix(Rotation.TEMP_SUFFIX)
                fs.writeFile(path, fs.readFile(temp))
                fs.openOutputStream(Rotation.PROGRESS, append = true).use { out ->
                    out.write((path + "\n").toByteArray(Charsets.UTF_8))
                }
            }

            // Files are new-key sealed from here, so the key in memory is swapped before
            // the manifest write that makes it official.
            masterKey = material.key
            manifest = currentManifest.withSecurity(material.security)
            persistManifest()
        } catch (failure: Exception) {
            material.key.fill(0)
            if (overwriting) {
                oldKey.fill(0)
                masterKey = null
                return@withContext PassphraseChange.Refused(
                    "The passphrase change was interrupted (${failure.javaClass.simpleName}: " +
                        "${failure.message}). Memory files are already sealed with the new " +
                        "passphrase. Reopen the vault folder and Sarothi will finish the change " +
                        "on its own; until then the vault stays locked.",
                )
            }
            temps.forEach { runCatching { fs.deleteFile(it) } }
            runCatching { deleteRecursively(fs, Rotation.DIR) }
            return@withContext PassphraseChange.Refused(
                "Nothing was changed (${failure.javaClass.simpleName}: ${failure.message}). " +
                    "The vault is still sealed with the passphrase it had.",
            )
        }

        temps.forEach { runCatching { fs.deleteFile(it) } }
        runCatching { deleteRecursively(fs, Rotation.DIR) }
        oldKey.fill(0)
        PassphraseChange.Changed(temps.size)
    }

    /** Whether an interrupted passphrase change is waiting to be finished. */
    fun rotationPending(): Boolean = fileSystem?.exists(Rotation.RECORD) == true

    /**
     * Finishes a passphrase change that was interrupted, and clears up after one that was
     * abandoned before it overwrote anything.
     *
     * Needs no passphrase and no key: the `<path>.rotating` files already hold the bytes
     * sealed with the new key, `rotation.json` already holds the new protection record, and
     * `progress` says which originals were already replaced. That is deliberate -- the
     * alternative is a vault that can only be repaired by someone who remembers two
     * passphrases.
     *
     * Called from [attach] and [reattach], so a vault left half-changed repairs itself the
     * next time its folder is opened, before anyone is asked to unlock it. Idempotent: with
     * nothing pending it returns false and changes nothing.
     *
     * @return true when a pending change was finished, false when there was nothing to do.
     */
    fun resumeInterruptedRotation(): Boolean {
        val fs = fileSystem ?: return false
        val record = if (fs.exists(Rotation.RECORD)) {
            runCatching {
                VaultSecurity.fromJson(Json.parseObject(fs.readFile(Rotation.RECORD).toString(Charsets.UTF_8)))
            }.getOrNull()
        } else {
            null
        }

        val temps = vaultFiles(fs).filter { it.endsWith(Rotation.TEMP_SUFFIX) }
        if (record == null) {
            // No published record means the change never got past writing temps, so no
            // original was overwritten and the temps are only rubbish. A record that
            // exists but cannot be parsed is left alone: guessing here would mean
            // choosing which of two passphrases protects the vault.
            if (!fs.exists(Rotation.RECORD)) {
                temps.forEach { runCatching { fs.deleteFile(it) } }
            }
            return false
        }

        val done = runCatching {
            fs.readFile(Rotation.PROGRESS).toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.toSet()
        }.getOrDefault(emptySet())

        for (temp in temps) {
            val path = temp.removeSuffix(Rotation.TEMP_SUFFIX)
            if (path in done) {
                runCatching { fs.deleteFile(temp) }
                continue
            }
            fs.writeFile(path, fs.readFile(temp))
            runCatching { fs.openOutputStream(Rotation.PROGRESS, append = true) }
                .onSuccess { out -> out.use { it.write((path + "\n").toByteArray(Charsets.UTF_8)) } }
            runCatching { fs.deleteFile(temp) }
        }

        val current = manifest
            ?: runCatching { VaultManifest.parse(fs.readFile(VaultPaths.MANIFEST)) }.getOrNull()
        if (current != null) {
            manifest = current.withSecurity(record)
            persistManifest()
        }
        // Whatever key was in memory came from the passphrase that no longer protects
        // these files.
        masterKey?.fill(0)
        masterKey = null
        runCatching { deleteRecursively(fs, Rotation.DIR) }
        return true
    }

    /**
     * Every file in the vault except the models directory, which holds 60-220 MB of
     * unencrypted GGUF that reading would only waste time on, and the rotation directory
     * itself. Relative paths, in the form the sealed format binds as additional data.
     */
    private fun vaultFiles(fs: VaultFileSystem, directory: String = ""): List<String> {
        val out = mutableListOf<String>()
        for (entry in fs.listFiles(directory)) {
            val path = if (directory.isEmpty()) entry.name else "$directory/${entry.name}"
            if (entry.isDirectory) {
                if (path == VaultPaths.MODELS_DIR || path == Rotation.DIR) continue
                out += vaultFiles(fs, path)
            } else {
                out += path
            }
        }
        return out
    }

    private fun deleteRecursively(fs: VaultFileSystem, directory: String) {
        for (entry in fs.listFiles(directory)) {
            val path = "$directory/${entry.name}"
            if (entry.isDirectory) deleteRecursively(fs, path) else runCatching { fs.deleteFile(path) }
        }
        runCatching { fs.deleteFile(directory) }
    }

    /** Where an interrupted passphrase change keeps the state that lets it be finished. */
    private object Rotation {
        const val DIR = "${VaultPaths.MEMORIES_DIR}/.rotation"
        const val RECORD = "$DIR/rotation.json"
        const val PROGRESS = "$DIR/progress"
        const val TEMP_SUFFIX = ".rotating"
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
