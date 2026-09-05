package com.ngi.sarothi.core.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ngi.sarothi.core.util.Hex

/**
 * Device-local secret storage backed by Android Keystore + EncryptedSharedPreferences.
 *
 * This is the *only* place Sarothi keeps device secrets. Per the storage spec:
 * OAuth tokens (GitHub/Google), the biometric key-wrapping blob, brute-force
 * lockout state and the chosen vault folder URI all live here — never inside the
 * SD-card folder, and never in plaintext. The vault folder itself is portable by
 * design and contains no device secrets.
 *
 * If EncryptedSharedPreferences cannot be created (corrupted keystore entry after
 * a backup/restore, which is a real Android failure mode) the store is rebuilt
 * from scratch and the failure is reported, because silently falling back to
 * plaintext storage would break the security model without anyone noticing.
 */
class SecretStore(private val context: Context) : LockoutStore {

    private val prefs: SharedPreferences = openOrCreate()
    private var rebuiltAfterFailure = false

    /** True when the underlying keystore-backed prefs had to be recreated. */
    val wasRebuiltAfterFailure: Boolean get() = rebuiltAfterFailure

    private fun openOrCreate(): SharedPreferences {
        return try {
            create()
        } catch (failure: Exception) {
            // Common cause: the master key was restored from a device backup it
            // cannot be decrypted with. Deleting and recreating loses only the
            // device-local secrets (tokens must be re-authorised, lockout resets);
            // the SD-card vault and its password are untouched.
            rebuiltAfterFailure = true
            context.deleteSharedPreferences(FILE_NAME)
            try {
                create()
            } catch (secondFailure: Exception) {
                throw IllegalStateException(
                    "Android Keystore-backed secret storage is unavailable on this device " +
                        "(${secondFailure.javaClass.simpleName}: ${secondFailure.message}). " +
                        "Sarothi refuses to store OAuth tokens or lockout state in plaintext.",
                    secondFailure,
                )
            }
        }
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ---------------------------------------------------------------- strings

    fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    fun getString(key: String, fallback: String? = null): String? = prefs.getString(key, fallback)

    // ------------------------------------------------------------------ bytes

    /** Stores a byte array as lowercase hex (prefs have no binary type). */
    fun putBytes(key: String, value: ByteArray?) {
        putString(key, value?.let(Hex::encode))
    }

    fun getBytes(key: String): ByteArray? =
        getString(key)?.takeIf { it.isNotEmpty() }?.let(Hex::decode)

    // ------------------------------------------------------------------ numerics
    //
    // These two are the LockoutStore seam, and in the whole of Sarothi's own code the only
    // caller is LockoutTracker, so they are written with commit() rather than apply().
    //
    // apply() queues the disk write and returns immediately, which is right for a preference
    // and wrong for a brute-force counter: kill the process in the window between a failed
    // unlock and the flush and the failure was never recorded, so the backoff restarts from
    // zero and the "cannot be reset by force-stopping Sarothi" guarantee that LockoutTracker
    // documents is true only most of the time. An attacker who can put a phone down and pick
    // it up again is exactly an attacker who can wait for that window.
    //
    // commit() reports whether the write reached the disk. A false here is not acted on:
    // throwing would turn a full disk into a locked vault, and the counter is still in memory
    // for this process, which is the protection level apply() had anyway. The two values are
    // small and written at most once per failed unlock, so the synchronous write costs
    // nothing that a user would notice.
    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).commit()
    }

    override fun getLong(key: String, fallback: Long): Long = prefs.getLong(key, fallback)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).commit()
    }

    override fun getInt(key: String, fallback: Int): Int = prefs.getInt(key, fallback)

    fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun getBoolean(key: String, fallback: Boolean = false): Boolean = prefs.getBoolean(key, fallback)

    fun remove(key: String) = prefs.edit().remove(key).apply()

    fun contains(key: String): Boolean = prefs.contains(key)

    companion object {
        const val FILE_NAME = "sarothi_device_secrets"

        /** Vault folder URI chosen through the Storage Access Framework. */
        const val KEY_VAULT_TREE_URI = "vault.treeUri"

        /** Hex of the AES-256 key wrapped by the biometric Keystore key. */
        const val KEY_BIOMETRIC_WRAPPED_KEY = "biometric.wrappedMasterKey"
        const val KEY_BIOMETRIC_WRAPPED_IV = "biometric.wrappedMasterKeyIv"
        const val KEY_BIOMETRIC_KEY_FINGERPRINT = "biometric.keystoreKeyFingerprint"

        const val KEY_GITHUB_TOKEN = "connector.github.accessToken"
        const val KEY_GITHUB_USER = "connector.github.login"
        const val KEY_GOOGLE_TOKEN = "connector.google.accessToken"
        const val KEY_GOOGLE_REFRESH = "connector.google.refreshToken"
        const val KEY_GOOGLE_EXPIRY = "connector.google.expiresAt"
        const val KEY_GOOGLE_EMAIL = "connector.google.email"
        const val KEY_GOOGLE_PKCE_VERIFIER = "connector.google.pkceVerifier"

        const val KEY_LOCKOUT_FAILURES = "security.failedAttempts"
        const val KEY_LOCKOUT_UNTIL = "security.lockedUntilEpochMillis"
        const val KEY_LOCKOUT_ESCALATION = "security.lockoutEscalation"

    }
}
