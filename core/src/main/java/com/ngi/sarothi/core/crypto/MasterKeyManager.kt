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
 * Derives, verifies and rotates the vault master key.
 *
 * The passphrase itself is never stored, in any form, anywhere — not in the
 * vault, not in [SecretStore], not in memory beyond the derivation. That is what
 * makes the vault genuinely portable: the same passphrase opens the same SD-card
 * folder on a brand-new device with nothing but the folder and the password.
 */
class MasterKeyManager(private val secrets: SecretStore) {

    val lockout = LockoutTracker(secrets)

    /**
     * Creates the protection record for a brand-new vault. The passphrase array
     * is wiped before returning.
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
     * Derives the AES-256 key. Does **not** verify or apply lockout — used when
     * the caller has already authenticated (e.g. re-deriving after a biometric
     * unlock, or during a password change).
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
        if (!verifyPassword(security, password, recordFailure = true)) {
            val state = lockout.state()
            // The password array is still intact here (verifyPassword only wipes
            // its own encoded copy), so wipe it before propagating.
            PasswordBytes.wipe(password)
            throw IncorrectPasswordException(
                attemptsRemaining = lockout.attemptsRemaining(),
                lockoutUntilEpochMillis = state.lockedUntilEpochMillis,
            )
        }
        lockout.recordSuccess()
        return deriveKey(security, password)
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
     * Re-encrypts nothing itself — it only produces a new [VaultSecurity]. The
     * caller must re-seal every file in `/memories/` with the new key; see
     * `VaultRekeyOperation`.
     */
    suspend fun changePassword(
        security: VaultSecurity,
        currentPassword: CharArray,
        newPassword: CharArray,
        newKdf: KdfParameters = security.kdf,
    ): VaultSecurity {
        if (!verifyPassword(security, currentPassword, recordFailure = true)) {
            PasswordBytes.wipe(currentPassword)
            PasswordBytes.wipe(newPassword)
            throw IncorrectPasswordException(lockout.attemptsRemaining(), lockout.state().lockedUntilEpochMillis)
        }
        lockout.recordSuccess()
        PasswordBytes.wipe(currentPassword)
        return createSecurity(newPassword, newKdf)
    }

    companion object {
        const val SALT_LENGTH = 16
        const val KEY_LENGTH = AesGcm.KEY_LENGTH_BYTES
        const val VERIFIER_LENGTH = 32
    }
}
