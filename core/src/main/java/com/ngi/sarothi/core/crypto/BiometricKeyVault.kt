package com.ngi.sarothi.core.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.ngi.sarothi.core.util.Hex
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Biometric unlock — a *device-local convenience layer only*.
 *
 * The Argon2id-derived key is wrapped with an Android Keystore AES-GCM key that
 * is bound to strong biometric authentication. The wrapped blob never leaves this
 * device and never enters the SD-card vault.
 *
 * It must never replace password-based decryption, and by construction it cannot:
 * the wrapped blob is only a cache of a key that is re-derivable from
 * `passphrase + manifest.json salt` on any device. A brand-new phone with only
 * the SD card and the passphrase opens the vault without any biometrics. If the
 * Keystore key is invalidated (new fingerprint enrolled, secure lock screen
 * removed) the cache is dropped and Sarothi falls back to the passphrase — it
 * does not fail closed, and it does not silently weaken anything.
 */
class BiometricKeyVault(
    private val context: Context,
    private val secrets: SecretStore,
) {

    enum class Availability {
        /** Hardware present, at least one credential enrolled, usable. */
        AVAILABLE,

        /** No biometric hardware. */
        NO_HARDWARE,

        /** Hardware exists but the user has not enrolled any biometric. */
        NOT_ENROLLED,

        /** Device security (lock screen) is not set up, which Keystore requires. */
        NO_DEVICE_CREDENTIAL,

        /** Something else; the human-readable reason is in [statusReason]. */
        UNKNOWN,
    }

    var statusReason: String? = null
        private set

    fun availability(): Availability {
        val manager = BiometricManager.from(context)
        return when (val result = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Availability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.UNKNOWN
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> Availability.UNKNOWN
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> Availability.UNKNOWN
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> Availability.UNKNOWN
            else -> {
                statusReason = "BiometricManager returned $result"
                Availability.UNKNOWN
            }
        }
    }

    /** True when a wrapped key exists *and* its Keystore key is still usable. */
    fun hasCachedKey(): Boolean {
        if (secrets.getString(SecretStore.KEY_BIOMETRIC_WRAPPED_KEY) == null) return false
        return runCatching { getOrCreateKeystoreKey() }.isSuccess
    }

    /**
     * Builds the [BiometricPrompt.CryptoObject] the UI must pass to
     * `authenticate()`. Returns null when biometrics are unavailable or no key has
     * been cached, in which case the caller shows the passphrase prompt instead.
     */
    fun unlockCryptoObject(): BiometricPrompt.CryptoObject? {
        if (!hasCachedKey()) return null
        return try {
            val key = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, wrappedIv()))
            BiometricPrompt.CryptoObject(cipher)
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            // A new biometric was enrolled; the cache is worthless by design.
            invalidate()
            null
        } catch (failure: Exception) {
            statusReason = "${failure.javaClass.simpleName}: ${failure.message}"
            null
        }
    }

    /**
     * Unwraps the cached master key using the biometric-authenticated cipher.
     * Only call this from `BiometricPrompt.AuthenticationCallback.onAuthenticationSucceeded`.
     */
    fun unwrap(cryptoObject: BiometricPrompt.CryptoObject?): ByteArray? {
        val cipher = cryptoObject?.cipher ?: return null
        val wrapped = secrets.getBytes(SecretStore.KEY_BIOMETRIC_WRAPPED_KEY) ?: return null
        return try {
            cipher.doFinal(wrapped)
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            invalidate()
            null
        } catch (failure: Exception) {
            statusReason = "biometric unwrap failed: ${failure.javaClass.simpleName}: ${failure.message}"
            null
        }
    }

    /**
     * Wraps and caches a freshly derived master key. Requires an
     * ENCRYPT-mode cipher obtained from a successful biometric prompt.
     */
    fun wrap(cryptoObject: BiometricPrompt.CryptoObject?, masterKey: ByteArray): Boolean {
        val cipher = cryptoObject?.cipher ?: return false
        return try {
            val wrapped = cipher.doFinal(masterKey)
            val iv = cipher.iv
            secrets.putBytes(SecretStore.KEY_BIOMETRIC_WRAPPED_KEY, wrapped)
            secrets.putBytes(SecretStore.KEY_BIOMETRIC_WRAPPED_IV, iv)
            secrets.putString(
                SecretStore.KEY_BIOMETRIC_KEY_FINGERPRINT,
                Hex.encode(java.security.MessageDigest.getInstance("SHA-256").digest(wrapped + iv)),
            )
            true
        } catch (failure: Exception) {
            statusReason = "biometric wrap failed: ${failure.javaClass.simpleName}: ${failure.message}"
            false
        }
    }

    /** Cipher used to *create* the cache, from a successful biometric prompt. */
    fun encryptCryptoObject(): BiometricPrompt.CryptoObject? {
        return try {
            val key = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            BiometricPrompt.CryptoObject(cipher)
        } catch (failure: Exception) {
            statusReason = "${failure.javaClass.simpleName}: ${failure.message}"
            null
        }
    }

    /** Drops the cached key. Called on logout, on invalidation, and on password change. */
    fun invalidate() {
        secrets.remove(SecretStore.KEY_BIOMETRIC_WRAPPED_KEY)
        secrets.remove(SecretStore.KEY_BIOMETRIC_WRAPPED_IV)
        secrets.remove(SecretStore.KEY_BIOMETRIC_KEY_FINGERPRINT)
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun wrappedIv(): ByteArray =
        secrets.getBytes(SecretStore.KEY_BIOMETRIC_WRAPPED_IV)
            ?: throw IllegalStateException("biometric cache has no IV; it is corrupt and must be discarded")

    private fun getOrCreateKeystoreKey(): javax.crypto.SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // New enrolments must invalidate the cache: an attacker who adds their
            // own fingerprint should not inherit access to the previous user's key.
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 0 seconds => the key is only usable inside an authenticated
            // CryptoObject, never on a time window.
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        generator.init(builder.build())
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "sarothi.biometric.masterKeyWrap"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        /** Reusable secret key for the passphrase path (never wrapped). */
        fun aesKey(raw: ByteArray) = SecretKeySpec(raw, "AES")
    }
}
