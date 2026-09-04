package com.ngi.sarothi.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * The crypto the vault's confidentiality rests on.
 *
 * These are the only parts of Sarothi where a plausible-looking implementation and a
 * correct one are indistinguishable from the outside: a KDF that is subtly wrong still
 * produces 32 bytes, and a sealed file that binds the wrong path still opens on the phone
 * that wrote it. Both fail only on a different device or a restored SD card, which is
 * exactly when the user cannot recover.
 */
class CryptoTest {

    // ------------------------------------------------------------------ Argon2id

    /**
     * RFC 9106 §5.3, the standard's own Argon2id vector: m=32 KiB, t=3, p=4, T=32.
     *
     * `scripts/verify_argon2_rfc9106.py` checks a Python reference implementation
     * against this vector and against the intermediate blocks of each pass. This checks
     * the Kotlin one the app actually runs, which is the implementation that matters: a
     * correct Python port proves nothing about the code on the phone.
     */
    @Test
    fun argon2id_matches_the_rfc_9106_test_vector() {
        val password = ByteArray(32) { 0x01 }
        val salt = ByteArray(16) { 0x02 }
        val secret = ByteArray(8) { 0x03 }
        val associatedData = ByteArray(12) { 0x04 }

        val tag = Argon2id(memoryKiB = 32, iterations = 3, parallelism = 4).deriveKey(
            password = password,
            salt = salt,
            outputLength = 32,
            secret = secret,
            associatedData = associatedData,
        )

        val expected = hex(
            "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659",
        )
        assertArrayEquals(
            "Argon2id does not match RFC 9106 §5.3 -- a vault created here could not be " +
                "opened by any other conforming implementation",
            expected,
            tag,
        )
    }

    /**
     * Sarothi passes no pepper, so the same passphrase and salt must produce the same key
     * on another device. If anything device-bound crept into the derivation, a restored SD
     * card would silently stop opening.
     */
    @Test
    fun argon2id_is_reproducible_without_any_device_input() {
        val kdf = Argon2id(memoryKiB = 32, iterations = 2, parallelism = 1)
        val password = "সারথি passphrase".toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16) { it.toByte() }

        val first = kdf.deriveKey(password, salt)
        val second = kdf.deriveKey(password, salt)

        assertArrayEquals("Same inputs must yield the same key", first, second)
        assertEquals(32, first.size)
    }

    @Test
    fun argon2id_changes_the_key_when_any_input_changes() {
        val kdf = Argon2id(memoryKiB = 32, iterations = 2, parallelism = 1)
        val salt = ByteArray(16) { it.toByte() }
        val base = kdf.deriveKey("correct horse".toByteArray(), salt)

        assertFalse(
            "A different passphrase must not derive the same key",
            base.contentEquals(kdf.deriveKey("correct horsf".toByteArray(), salt)),
        )
        assertFalse(
            "A different salt must not derive the same key",
            base.contentEquals(kdf.deriveKey("correct horse".toByteArray(), ByteArray(16) { 9 })),
        )
    }

    /** RFC 9106 §4 requires at least 8 bytes of salt; a short one must be refused. */
    @Test
    fun argon2id_refuses_a_salt_shorter_than_the_rfc_minimum() {
        val kdf = Argon2id(memoryKiB = 32, iterations = 1, parallelism = 1)
        try {
            kdf.deriveKey("pw".toByteArray(), ByteArray(7))
            fail("A 7-byte salt must be rejected, not quietly accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("salt"))
        }
    }

    // ------------------------------------------------------------------ AES-GCM

    @Test
    fun aes_gcm_round_trips_and_uses_a_fresh_nonce_each_time() {
        val key = AesGcm.generateKey()
        val plaintext = "memories/persona.json contents".toByteArray()

        val first = AesGcm.encrypt(key, plaintext)
        val second = AesGcm.encrypt(key, plaintext)

        assertArrayEquals(plaintext, AesGcm.decrypt(key, first.nonce, first.ciphertext))
        assertArrayEquals(plaintext, AesGcm.decrypt(key, second.nonce, second.ciphertext))
        assertFalse(
            "Reusing a nonce with GCM destroys confidentiality and authenticity",
            first.nonce.contentEquals(second.nonce),
        )
        assertFalse(
            "Encrypting the same plaintext twice must not give the same ciphertext",
            first.ciphertext.contentEquals(second.ciphertext),
        )
    }

    /** A wrong key must be a loud failure. Returning garbage would be far worse. */
    @Test
    fun aes_gcm_rejects_a_wrong_key() {
        val blob = AesGcm.encrypt(AesGcm.generateKey(), "secret".toByteArray())
        try {
            AesGcm.decrypt(AesGcm.generateKey(), blob.nonce, blob.ciphertext)
            fail("Decrypting with the wrong key must throw")
        } catch (expected: AEADBadTagException) {
            // the callers turn this into IncorrectPasswordException
        }
    }

    @Test
    fun aes_gcm_rejects_a_single_flipped_bit_in_the_ciphertext() {
        val key = AesGcm.generateKey()
        val blob = AesGcm.encrypt(key, "do not delete anything".toByteArray())
        val tampered = blob.ciphertext.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }

        try {
            AesGcm.decrypt(key, blob.nonce, tampered)
            fail("Tampered ciphertext must not decrypt")
        } catch (expected: AEADBadTagException) {
            // authenticity did its job
        }
    }

    /**
     * The vault-relative path is bound as additional authenticated data. Without that a
     * ciphertext could be copied over another file's name and read as if it belonged there.
     */
    @Test
    fun aes_gcm_rejects_the_same_ciphertext_under_different_associated_data() {
        val key = AesGcm.generateKey()
        val blob = AesGcm.encrypt(key, "value".toByteArray(), associatedData = "memories/a.json".toByteArray())
        try {
            AesGcm.decrypt(key, blob.nonce, blob.ciphertext, associatedData = "memories/b.json".toByteArray())
            fail("AAD is what stops a ciphertext being moved to another path")
        } catch (expected: AEADBadTagException) {
            // bound to its path
        }
    }

    @Test
    fun aes_gcm_refuses_a_key_that_is_not_256_bits() {
        try {
            AesGcm.encrypt(ByteArray(16), "x".toByteArray())
            fail("AES-256-GCM must not silently run with a 128-bit key")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("32"))
        }
    }

    // ------------------------------------------------------- the sealed file format

    @Test
    fun sealed_file_round_trips_with_and_without_compression() {
        val key = AesGcm.generateKey()
        val plaintext = buildString { repeat(400) { append("সারথি remembers this line. ") } }.toByteArray()

        for (compress in listOf(true, false)) {
            val sealed = EncryptedFileFormat.seal(key, "memories/memories.json", plaintext, compress)
            assertArrayEquals(
                "compress=$compress must not change what comes back",
                plaintext,
                EncryptedFileFormat.open(key, "memories/memories.json", sealed),
            )
            assertTrue(EncryptedFileFormat.isSealed(sealed))
        }
    }

    @Test
    fun sealed_file_is_bound_to_its_path_inside_the_vault() {
        val key = AesGcm.generateKey()
        val sealed = EncryptedFileFormat.seal(key, "memories/notes.json", "my notes".toByteArray())
        try {
            EncryptedFileFormat.open(key, "memories/todos.json", sealed)
            fail("A file sealed for one path must not open as another")
        } catch (expected: AEADBadTagException) {
            // renaming a ciphertext inside the vault gains nothing
        }
    }

    @Test
    fun sealed_file_refuses_a_foreign_header_instead_of_guessing() {
        val key = AesGcm.generateKey()
        val foreign = "SOMETHING ELSE ENTIRELY".toByteArray()
        try {
            EncryptedFileFormat.open(key, "memories/notes.json", foreign)
            fail("Another app's file must be reported, not decrypted as though it were ours")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("not a Sarothi encrypted file"))
        }
        assertFalse(EncryptedFileFormat.isSealed(foreign))
    }

    @Test
    fun sealed_file_refuses_a_truncated_file() {
        try {
            EncryptedFileFormat.open(AesGcm.generateKey(), "logs/x.json", ByteArray(3))
            fail("A file shorter than the header cannot be a sealed file")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("too small"))
        }
    }

    @Test
    fun sealed_file_refuses_an_unknown_format_version() {
        val key = AesGcm.generateKey()
        val sealed = EncryptedFileFormat.seal(key, "memories/notes.json", "x".toByteArray())
        sealed[4] = (sealed[4] + 1).toByte()
        try {
            EncryptedFileFormat.open(key, "memories/notes.json", sealed)
            fail("A version this build does not understand must be reported, not attempted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("format version"))
        }
    }

    /** Ciphertext must not leak what it holds, even for a highly repetitive plaintext. */
    @Test
    fun sealed_file_does_not_expose_the_plaintext() {
        val sealed = EncryptedFileFormat.seal(
            AesGcm.generateKey(),
            "memories/user_facts.json",
            "my mother's name is Ayesha and my bank account is 12345".toByteArray(),
        )
        val asText = String(sealed, Charsets.ISO_8859_1)
        assertFalse(asText.contains("Ayesha"))
        assertFalse(asText.contains("12345"))
        assertNotEquals(0, sealed.size)
    }

    private fun hex(value: String): ByteArray {
        val clean = value.replace(" ", "")
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
