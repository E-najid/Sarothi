package com.ngi.sarothi.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM through the platform JCE provider.
 *
 * Unlike the KDF, this is *not* hand-written: Conscrypt's AES-GCM is hardware
 * accelerated and audited, and re-implementing it would only add risk.
 *
 * Every encryption uses a fresh 96-bit random nonce, and the vault-relative path
 * is bound as additional authenticated data so a ciphertext cannot be moved or
 * renamed to impersonate another file in the vault.
 */
object AesGcm {

    const val KEY_LENGTH_BYTES = 32
    const val NONCE_LENGTH_BYTES = 12
    const val TAG_LENGTH_BITS = 128

    private val random = SecureRandom()

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * Fails loudly at startup rather than at first unlock if the platform cannot
     * provide authenticated AES-256. A silent downgrade here would be far worse
     * than a crash, so this performs a real encrypt/decrypt round trip.
     */
    fun selfTest() {
        val key = generateKey()
        val plaintext = "sarothi-self-test".toByteArray(Charsets.UTF_8)
        val blob = encrypt(key, plaintext)
        val recovered = decrypt(key, blob.nonce, blob.ciphertext)
        check(recovered.contentEquals(plaintext)) {
            "AES-256-GCM self test failed: round trip did not reproduce the plaintext"
        }
        // A tampered ciphertext must be rejected, not silently decrypted.
        val tampered = blob.ciphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val rejected = runCatching { decrypt(key, blob.nonce, tampered) }.isFailure
        check(rejected) { "AES-256-GCM self test failed: a tampered ciphertext was accepted" }
    }

    fun generateKey(): ByteArray = ByteArray(KEY_LENGTH_BYTES).also { random.nextBytes(it) }

    fun generateNonce(): ByteArray = ByteArray(NONCE_LENGTH_BYTES).also { random.nextBytes(it) }

    fun generateSalt(length: Int = 16): ByteArray = ByteArray(length).also { random.nextBytes(it) }

    fun encrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray? = null): EncryptedBlob {
        require(key.size == KEY_LENGTH_BYTES) {
            "AES-256-GCM needs a $KEY_LENGTH_BYTES-byte key, got ${key.size}"
        }
        val nonce = generateNonce()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_LENGTH_BITS, nonce),
        )
        if (associatedData != null) cipher.updateAAD(associatedData)
        return EncryptedBlob(nonce, cipher.doFinal(plaintext))
    }

    /**
     * @throws javax.crypto.AEADBadTagException if the key is wrong or the data was
     *   tampered with. Callers turn that into [com.ngi.sarothi.core.error.IncorrectPasswordException]
     *   only for the verifier path; for stored files it means corruption.
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray? = null,
    ): ByteArray {
        require(key.size == KEY_LENGTH_BYTES) {
            "AES-256-GCM needs a $KEY_LENGTH_BYTES-byte key, got ${key.size}"
        }
        require(nonce.size == NONCE_LENGTH_BYTES) {
            "GCM nonce must be $NONCE_LENGTH_BYTES bytes, got ${nonce.size}"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_LENGTH_BITS, nonce),
        )
        if (associatedData != null) cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext)
    }

    /** Nonce plus ciphertext||tag, kept separate so callers control the on-disk layout. */
    data class EncryptedBlob(val nonce: ByteArray, val ciphertext: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is EncryptedBlob && nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext)

        override fun hashCode(): Int = 31 * nonce.contentHashCode() + ciphertext.contentHashCode()
    }
}
