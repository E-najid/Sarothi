package com.ngi.sarothi.core.crypto

import com.google.gson.JsonObject
import com.ngi.sarothi.core.error.IncorrectPasswordException
import com.ngi.sarothi.core.util.Hashing
import com.ngi.sarothi.core.util.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KDF parameters, recorded in the vault's plaintext `manifest.json`.
 *
 * They must travel with the vault: a vault created with one memory cost has to be
 * opened with the same one, otherwise the derived key differs and decryption
 * fails with no useful explanation. Storing parameters is standard practice and
 * leaks nothing — only the passphrase is secret.
 */
data class KdfParameters(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    init {
        require(memoryKiB >= 8 * parallelism) {
            "Argon2 requires memoryKiB >= 8 * parallelism"
        }
        require(iterations >= 1) { "iterations must be >= 1" }
        require(parallelism >= 1) { "parallelism must be >= 1" }
    }

    fun toArgon2(): Argon2id = Argon2id(memoryKiB, iterations, parallelism)

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("algorithm", ALGORITHM)
        addProperty("memory_kib", memoryKiB)
        addProperty("iterations", iterations)
        addProperty("parallelism", parallelism)
    }

    companion object {
        const val ALGORITHM = "argon2id"

        val DEFAULT = KdfParameters(
            memoryKiB = Argon2id.DEFAULT_MEMORY_KIB,
            iterations = Argon2id.DEFAULT_ITERATIONS,
            parallelism = Argon2id.DEFAULT_PARALLELISM,
        )

        fun fromJson(json: JsonObject): KdfParameters = KdfParameters(
            memoryKiB = json.get("memory_kib")?.asInt ?: DEFAULT.memoryKiB,
            iterations = json.get("iterations")?.asInt ?: DEFAULT.iterations,
            parallelism = json.get("parallelism")?.asInt ?: DEFAULT.parallelism,
        )
    }
}

/**
 * Everything the vault stores about its own protection, all of it non-secret.
 *
 * Two independent salts are used on purpose:
 *  - [keySaltHex] feeds the Argon2id output that becomes the AES-256-GCM key.
 *  - [verifierSaltHex] feeds a *separate* Argon2id run whose output is stored as
 *    [verifierHashHex]. Checking a password against the verifier never requires
 *    attempting a real decryption, so a wrong password is detected without
 *    touching a single encrypted file, and the verifier hash cannot be used as
 *    the encryption key.
 */
data class VaultSecurity(
    val kdf: KdfParameters,
    val keySaltHex: String,
    val verifierSaltHex: String,
    val verifierHashHex: String,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        add("kdf", kdf.toJson())
        addProperty("key_salt", keySaltHex)
        addProperty("verifier_salt", verifierSaltHex)
        addProperty("verifier_hash", verifierHashHex)
        addProperty("verifier_algorithm", KdfParameters.ALGORITHM)
    }

    companion object {
        fun fromJson(json: JsonObject): VaultSecurity = VaultSecurity(
            kdf = json.getAsJsonObject("kdf")?.let(KdfParameters::fromJson) ?: KdfParameters.DEFAULT,
            keySaltHex = json.get("key_salt").asString,
            verifierSaltHex = json.get("verifier_salt").asString,
            verifierHashHex = json.get("verifier_hash").asString,
        )
    }
}

/**
 * What a brand-new vault needs: the record that goes into `manifest.json` and the key
 * that seals `/memories/`.
 *
 * They are produced together because producing them in two calls is what broke this once.
 * [MasterKeyManager.createSecurity] consumes the passphrase — wiping it is the documented
 * contract, and the right one for a call that is the last to use it — so a caller that
 * then derived the key from the same array was deriving it from a run of NUL characters.
 * Both sides of the vault agreed, so everything still opened, and the encryption key
 * stopped depending on the passphrase at all.
 */
data class VaultKeyMaterial(
    val security: VaultSecurity,
    val key: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is VaultKeyMaterial && security == other.security && key.contentEquals(other.key)

    override fun hashCode(): Int = 31 * security.hashCode() + key.contentHashCode()
}

/**
 * Derives, verifies and rotates the vault master key.
 *
 * The passphrase itself is never stored, in any form, anywhere — not in the
 * vault, not in [SecretStore], not in memory beyond the derivation. That is what
 * makes the vault genuinely portable: the same passphrase opens the same SD-card
 * folder on a brand-new device with nothing but the folder and the password.
 */
class MasterKeyManager(private val secrets: LockoutStore) {
    // LockoutStore rather than SecretStore: the lockout tracker is the only thing this
    // class reads or writes, and taking the interface lets the whole key-derivation
    // contract be tested on the JVM instead of only on a device.

    val lockout = LockoutTracker(secrets)

    /**
     * Creates a protection record on its own. The passphrase array is wiped before
     * returning, so this must be the caller's last use of it — which is why creating a
     * vault calls [createKeyMaterial] instead: that one needs the record *and* the key,
     * and getting the key afterwards from the same array yields a key derived from NULs.
     * What remains for this method is rotation, where the new record is the whole result.
     */
    suspend fun createSecurity(
        password: CharArray,
        kdf: KdfParameters = KdfParameters.DEFAULT,
    ): VaultSecurity = withContext(Dispatchers.Default) {
        val keySalt = AesGcm.generateSalt(SALT_LENGTH)
        val verifierSalt = AesGcm.generateSalt(SALT_LENGTH)
        val argon2 = kdf.toArgon2()

        PasswordBytes.withPasswordBytes(password) { bytes ->
            val verifier = argon2.deriveKey(bytes, verifierSalt, VERIFIER_LENGTH)
            VaultSecurity(
                kdf = kdf,
                keySaltHex = Hex.encode(keySalt),
                verifierSaltHex = Hex.encode(verifierSalt),
                verifierHashHex = Hex.encode(verifier),
            ).also { verifier.fill(0) }
        }
    }

    /**
     * Creates a vault's protection record *and* its encryption key from one passphrase,
     * consuming the array exactly once. This is what creating a vault must call; see
     * [VaultKeyMaterial].
     */
    suspend fun createKeyMaterial(
        password: CharArray,
        kdf: KdfParameters = KdfParameters.DEFAULT,
    ): VaultKeyMaterial = withContext(Dispatchers.Default) {
        val keySalt = AesGcm.generateSalt(SALT_LENGTH)
        val verifierSalt = AesGcm.generateSalt(SALT_LENGTH)
        val argon2 = kdf.toArgon2()

        PasswordBytes.withPasswordBytes(password) { bytes ->
            val verifier = argon2.deriveKey(bytes, verifierSalt, VERIFIER_LENGTH)
            val key = argon2.deriveKey(bytes, keySalt, KEY_LENGTH)
            VaultKeyMaterial(
                security = VaultSecurity(
                    kdf = kdf,
                    keySaltHex = Hex.encode(keySalt),
                    verifierSaltHex = Hex.encode(verifierSalt),
                    verifierHashHex = Hex.encode(verifier),
                ),
                key = key,
            ).also { verifier.fill(0) }
        }
    }

    /**
     * Derives the AES-256 key. Does **not** verify or apply lockout — used when
     * the caller has already authenticated (e.g. re-deriving after a biometric
     * unlock, or during a password change).
     *
     * Consumes [password]: the array is all NUL characters when this returns, so it must
     * be the caller's last use of the passphrase.
     */
    suspend fun deriveKey(security: VaultSecurity, password: CharArray): ByteArray =
        withContext(Dispatchers.Default) {
            PasswordBytes.withPasswordBytes(password) { bytes ->
                security.kdf.toArgon2().deriveKey(bytes, Hex.decode(security.keySaltHex), KEY_LENGTH)
            }
        }

    /**
     * Verifies the passphrase and returns the key, applying brute-force backoff.
     *
     * @throws IncorrectPasswordException when the passphrase is wrong or a lockout
     *   window is still active.
     */
    suspend fun unlock(security: VaultSecurity, password: CharArray): ByteArray {
        lockout.requireNotLocked()

        // Verification and derivation run inside one encoding of the passphrase. They
        // cannot be two calls: verifyPassword() consumes the array it is given, so the
        // derivation that followed it used to receive an array of NUL characters and
        // return a key that did not depend on the passphrase. Wiping still happens on
        // every path out of here, because withPasswordBytes wipes in a finally block.
        val key = withContext(Dispatchers.Default) {
            PasswordBytes.withPasswordBytes(password) { bytes ->
                val argon2 = security.kdf.toArgon2()
                val candidate = argon2.deriveKey(bytes, Hex.decode(security.verifierSaltHex), VERIFIER_LENGTH)
                val expected = Hex.decode(security.verifierHashHex)
                val matches = Hashing.constantTimeEquals(candidate, expected)
                candidate.fill(0)
                expected.fill(0)
                if (matches) argon2.deriveKey(bytes, Hex.decode(security.keySaltHex), KEY_LENGTH) else null
            }
        }

        if (key == null) {
            lockout.recordFailure()
            val state = lockout.state()
            throw IncorrectPasswordException(
                attemptsRemaining = lockout.attemptsRemaining(),
                lockoutUntilEpochMillis = state.lockedUntilEpochMillis,
            )
        }
        lockout.recordSuccess()
        return key
    }

    /**
     * Checks the passphrase against the stored verifier hash in constant time.
     *
     * @param recordFailure when true, a mismatch advances the lockout tracker.
     */
    suspend fun verifyPassword(
        security: VaultSecurity,
        password: CharArray,
        recordFailure: Boolean = false,
    ): Boolean = withContext(Dispatchers.Default) {
        val candidate = PasswordBytes.withPasswordBytes(password) { bytes ->
            security.kdf.toArgon2()
                .deriveKey(bytes, Hex.decode(security.verifierSaltHex), VERIFIER_LENGTH)
        }
        val expected = Hex.decode(security.verifierHashHex)
        val matches = Hashing.constantTimeEquals(candidate, expected)
        candidate.fill(0)
        expected.fill(0)
        if (!matches && recordFailure) lockout.recordFailure()
        matches
    }

    /**
     * Re-encrypts nothing itself — it only produces a new [VaultSecurity].
     *
     * Rotating a vault's passphrase needs one more step this class cannot perform: every
     * file in `/memories/` has to be re-sealed with the key the *new* passphrase derives,
     * and the new record written into `manifest.json`. That operation is not written, so
     * this method has no caller in the app and the vault screen offers no way to change a
     * passphrase. It is here, and covered by `MasterKeyManagerTest`, because the primitive
     * is the part that has to be correct before anything is built on top of it — a rekey
     * built on a wrong derivation would rewrite every memory under a key nobody can
     * reproduce.
     */
    suspend fun changePassword(
        security: VaultSecurity,
        currentPassword: CharArray,
        newPassword: CharArray,
        newKdf: KdfParameters = security.kdf,
    ): VaultSecurity {
        if (!verifyPassword(security, currentPassword, recordFailure = true)) {
            // verifyPassword already consumed currentPassword; the new one is this
            // function's to wipe, and it is not consumed by the throw below.
            PasswordBytes.wipe(newPassword)
            throw IncorrectPasswordException(lockout.attemptsRemaining(), lockout.state().lockedUntilEpochMillis)
        }
        lockout.recordSuccess()
        return createSecurity(newPassword, newKdf)
    }

    companion object {
        const val SALT_LENGTH = 16
        const val KEY_LENGTH = AesGcm.KEY_LENGTH_BYTES
        const val VERIFIER_LENGTH = 32
    }
}
