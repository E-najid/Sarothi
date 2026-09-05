package com.ngi.sarothi.core.crypto

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ngi.sarothi.core.error.IncorrectPasswordException
import com.ngi.sarothi.core.util.Hex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.AEADBadTagException

/**
 * The vault's protection, exercised end to end on a device.
 *
 * This is the part of Sarothi that must never be trusted on the strength of a JVM unit
 * test alone: real Argon2id at the real parameters, a real AES-256-GCM seal written to
 * real storage, and a real brute-force backoff driven by the device clock. The unit tests
 * cover the algorithm against RFC 9106 vectors; this covers the *wiring* — that the
 * passphrase, the two salts, the verifier, the lockout counters and the file format are
 * all connected the way the README claims they are.
 */
@RunWith(AndroidJUnit4::class)
class VaultKeyDerivationInstrumentedTest {

    private lateinit var context: Context

    /** A passphrase with non-ASCII characters, to exercise the hand-written UTF-8 encoder. */
    private val passphrase = "সারথি-correct-horse-🔑"
    private val wrongPassphrase = "সারথি-wrong-horse-🔑"

    @Before
    fun startFromAnUnlockedCleanSlate() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteSharedPreferences(SecretStore.FILE_NAME)
        // Deleting the file is not the same as clearing the counters, and this cost a real
        // run to find out. Every test in an instrumentation run shares one process, and
        // SharedPreferences loads once per process: the instance the next test reads is the
        // one the previous test wrote to, file or no file. One test here opens a lockout
        // window on purpose -- that is the whole point of it -- and the next test to call
        // unlock() then died on IncorrectPasswordException from requireNotLocked() before it
        // had typed anything. recordSuccess() writes the zeros through that same live
        // instance, so the reset is real rather than merely intended.
        val lockout = manager().lockout
        lockout.recordSuccess()
        assertEquals(
            "the lockout counters survived a reset, so a wrong passphrase recorded by one " +
                "test would lock out the next one",
            LockoutTracker.State.UNLOCKED,
            lockout.state(),
        )
    }

    @After
    fun leaveNoSecretsBehind() {
        context.deleteSharedPreferences(SecretStore.FILE_NAME)
    }

    private fun manager(): MasterKeyManager = MasterKeyManager(SecretStore(context))

    @Test
    fun the_real_parameters_derive_a_stable_256_bit_key_on_this_device() = runBlocking {
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray())

        // The production parameters — 12 MiB / 3 iterations / p=1, chosen for a 3 GB phone.
        assertEquals(KdfParameters.DEFAULT, security.kdf)

        val key = manager.unlock(security, passphrase.toCharArray())
        assertEquals("AES-256 needs a 32-byte key", 32, key.size)

        // Argon2id is deterministic: the same passphrase and salt must give the same key,
        // or the vault could never be reopened after a reboot.
        val again = manager.unlock(security, passphrase.toCharArray())
        assertArrayEquals(key, again)

        key.fill(0)
        again.fill(0)
    }

    @Test
    fun unlock_returns_the_key_the_passphrase_derives_at_the_production_parameters() = runBlocking {
        // Derived independently of the code under test, because the failure this guards
        // against is invisible from inside it: a key that is wrong in the same way on both
        // sides of the vault still opens every file, every time.
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray())

        val key = manager.unlock(security, passphrase.toCharArray())
        val expected = security.kdf.toArgon2().deriveKey(
            PasswordBytes.encodeUtf8(passphrase.toCharArray()),
            Hex.decode(security.keySaltHex),
            MasterKeyManager.KEY_LENGTH,
        )
        assertArrayEquals(
            "unlock() did not return the key this passphrase derives from this salt",
            expected,
            key,
        )

        // The exact shape of that failure: a consumed passphrase is an array of NUL
        // characters, which encodes to zero bytes and still derives a usable key.
        val keyFromNuls = security.kdf.toArgon2().deriveKey(
            ByteArray(passphrase.length),
            Hex.decode(security.keySaltHex),
            MasterKeyManager.KEY_LENGTH,
        )
        assertFalse(
            "The vault key is the one an all-NUL passphrase derives, so the passphrase is " +
                "not what protects the memories on the card",
            key.contentEquals(keyFromNuls),
        )

        key.fill(0)
        expected.fill(0)
        keyFromNuls.fill(0)
    }

    @Test
    fun a_vault_created_with_its_key_material_reopens_under_the_same_key() = runBlocking {
        // The path a real vault takes: create it and seal the memory files, then later
        // unlock it with the passphrase alone. Both keys have to be the one the passphrase
        // derives, or a vault created on one device cannot be opened on another.
        val manager = manager()
        val material = manager.createKeyMaterial(passphrase.toCharArray())
        val plaintext = "{\"notes\":[\"a memory the user trusted Sarothi with\"]}".toByteArray()
        val sealed = EncryptedFileFormat.seal(material.key, "memories/notes.json", plaintext)

        assertArrayEquals(
            material.security.kdf.toArgon2().deriveKey(
                PasswordBytes.encodeUtf8(passphrase.toCharArray()),
                Hex.decode(material.security.keySaltHex),
                MasterKeyManager.KEY_LENGTH,
            ),
            material.key,
        )

        val reopened = manager.unlock(material.security, passphrase.toCharArray())
        assertArrayEquals(material.key, reopened)
        assertArrayEquals(
            plaintext,
            EncryptedFileFormat.open(reopened, "memories/notes.json", sealed),
        )

        material.key.fill(0)
        reopened.fill(0)
    }

    @Test
    fun the_two_salts_are_independent_and_neither_is_derived_from_the_passphrase() = runBlocking {
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray())

        val keySalt = Hex.decode(security.keySaltHex)
        val verifierSalt = Hex.decode(security.verifierSaltHex)
        assertEquals(MasterKeyManager.SALT_LENGTH, keySalt.size)
        assertEquals(MasterKeyManager.SALT_LENGTH, verifierSalt.size)
        assertFalse(
            "The key salt and the verifier salt must differ, otherwise the verifier hash " +
                "is one derivation away from the encryption key",
            keySalt.contentEquals(verifierSalt),
        )

        // A second vault created from the same passphrase must not reuse either salt.
        val other = manager.createSecurity(passphrase.toCharArray())
        assertNotEquals(security.keySaltHex, other.keySaltHex)
        assertNotEquals(security.verifierSaltHex, other.verifierSaltHex)
        assertNotEquals(security.verifierHashHex, other.verifierHashHex)
    }

    @Test
    fun the_verifier_hash_proves_the_passphrase_but_cannot_open_anything() = runBlocking {
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray())
        val key = manager.unlock(security, passphrase.toCharArray())

        val plaintext = "a memory the user trusted Sarothi with".toByteArray()
        val sealed = EncryptedFileFormat.seal(key, "memories/note.md", plaintext)

        // The verifier is a separate 32-byte digest. If it could decrypt, storing it in
        // manifest.json on an SD card would be storing the key in the open.
        val verifier = Hex.decode(security.verifierHashHex)
        assertEquals(MasterKeyManager.VERIFIER_LENGTH, verifier.size)
        assertFalse("The verifier hash must not equal the encryption key", verifier.contentEquals(key))
        try {
            EncryptedFileFormat.open(verifier, "memories/note.md", sealed)
            fail("The verifier hash decrypted a vault file — it must never be usable as a key")
        } catch (expected: AEADBadTagException) {
            // Correct: GCM rejected it.
        }

        // And it still does its actual job: proving the passphrase without decrypting.
        assertTrue(manager.verifyPassword(security, passphrase.toCharArray()))
        assertFalse(manager.verifyPassword(security, wrongPassphrase.toCharArray()))

        assertArrayEquals(plaintext, EncryptedFileFormat.open(key, "memories/note.md", sealed))
        key.fill(0)
    }

    @Test
    fun a_wrong_passphrase_is_rejected_and_counted_towards_the_backoff() = runBlocking {
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray())

        assertEquals(LockoutTracker.State.UNLOCKED, manager.lockout.state())
        assertEquals(LockoutTracker.FREE_ATTEMPTS_DEFAULT, manager.lockout.attemptsRemaining())

        try {
            manager.unlock(security, wrongPassphrase.toCharArray())
            fail("A wrong passphrase unlocked the vault")
        } catch (expected: IncorrectPasswordException) {
            assertEquals(LockoutTracker.FREE_ATTEMPTS_DEFAULT - 1, expected.attemptsRemaining)
            assertNull("One failure must not start a lockout window", expected.lockoutUntilEpochMillis)
        }

        assertEquals(1, manager.lockout.state().failedAttempts)

        // The right passphrase still works, and clears the counter.
        val key = manager.unlock(security, passphrase.toCharArray())
        assertEquals(32, key.size)
        assertEquals(LockoutTracker.State.UNLOCKED, manager.lockout.state())
        key.fill(0)
    }

    @Test
    fun the_backoff_blocks_even_the_correct_passphrase_while_the_window_is_open() = runBlocking {
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray())

        // Failures beyond the free allowance open a window. Four failures is the first one.
        repeat(LockoutTracker.FREE_ATTEMPTS_DEFAULT + 1) {
            try {
                manager.unlock(security, wrongPassphrase.toCharArray())
                fail("A wrong passphrase unlocked the vault")
            } catch (expected: IncorrectPasswordException) {
                // Expected every time.
            }
        }

        val state = manager.lockout.state()
        assertNotNull("The backoff window never opened", state.lockedUntilEpochMillis)
        assertTrue(
            "The window must be at least the base backoff",
            state.lockedUntilEpochMillis!! > System.currentTimeMillis(),
        )

        // The point of the lockout: knowing the passphrase is not enough while it is open.
        try {
            manager.unlock(security, passphrase.toCharArray())
            fail("The correct passphrase was accepted during an active lockout window")
        } catch (expected: IncorrectPasswordException) {
            assertEquals(0, expected.attemptsRemaining)
            assertEquals(state.lockedUntilEpochMillis, expected.lockoutUntilEpochMillis)
        }

        // The window is stored on the device, not in memory: a fresh manager — as after a
        // process restart — must see the same lockout.
        val restarted = manager()
        try {
            restarted.unlock(security, passphrase.toCharArray())
            fail("The lockout window was lost when the store was reopened")
        } catch (expected: IncorrectPasswordException) {
            assertEquals(state.lockedUntilEpochMillis, expected.lockoutUntilEpochMillis)
        }
    }

    @Test
    fun a_sealed_file_is_bound_to_the_path_it_was_sealed_for() = runBlocking {
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray())
        val key = manager.unlock(security, passphrase.toCharArray())

        val plaintext = "bound to its path".toByteArray()
        val sealed = EncryptedFileFormat.seal(key, "memories/a.md", plaintext)

        assertArrayEquals(plaintext, EncryptedFileFormat.open(key, "memories/a.md", sealed))
        try {
            EncryptedFileFormat.open(key, "memories/b.md", sealed)
            fail("A file sealed for one path opened under a different path")
        } catch (expected: AEADBadTagException) {
            // Correct: the additional authenticated data includes the vault-relative path.
        }
        key.fill(0)
    }

    @Test
    fun a_single_flipped_bit_in_a_sealed_file_is_detected() = runBlocking {
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray())
        val key = manager.unlock(security, passphrase.toCharArray())

        val sealed = EncryptedFileFormat.seal(key, "memories/tampered.md", "original".toByteArray())
        val tampered = sealed.copyOf()
        // Flip a bit in the ciphertext body, past the header.
        val index = EncryptedFileFormat.HEADER_LENGTH + tampered.size / 2
        tampered[index] = (tampered[index].toInt() xor 0x01).toByte()

        try {
            EncryptedFileFormat.open(key, "memories/tampered.md", tampered)
            fail("A tampered vault file was accepted")
        } catch (expected: AEADBadTagException) {
            // Correct.
        }

        // The untouched original still opens, so the failure above was about tampering
        // and not about the key or the test data.
        assertEquals("original", EncryptedFileFormat.open(key, "memories/tampered.md", sealed).toString(Charsets.UTF_8))
        key.fill(0)
    }

    @Test
    fun sealing_the_same_content_twice_gives_different_ciphertext_that_both_open() = runBlocking {
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray())
        val key = manager.unlock(security, passphrase.toCharArray())

        val plaintext = "the same memory written twice".toByteArray()
        val first = EncryptedFileFormat.seal(key, "memories/note.md", plaintext)
        val second = EncryptedFileFormat.seal(key, "memories/note.md", plaintext)

        assertFalse(
            "Reusing a nonce would break AES-GCM confidentiality",
            first.contentEquals(second),
        )
        assertArrayEquals(plaintext, EncryptedFileFormat.open(key, "memories/note.md", first))
        assertArrayEquals(plaintext, EncryptedFileFormat.open(key, "memories/note.md", second))
        key.fill(0)
    }

    @Test
    fun a_sealed_file_survives_real_storage_and_is_recognisable_by_its_header() = runBlocking {
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray())
        val key = manager.unlock(security, passphrase.toCharArray())

        val plaintext = ("A longer memory, long enough that compression has something to do: " +
            "সারথি ".repeat(40)).toByteArray()
        val sealed = EncryptedFileFormat.seal(key, "memories/long.md", plaintext)

        assertTrue(EncryptedFileFormat.isSealed(sealed))
        assertEquals("SRT1", sealed.copyOfRange(0, 4).toString(Charsets.US_ASCII))

        // Round-trip through a real file, because the vault lives on an SD card and the
        // bytes have to survive a write/read cycle, not just an in-memory copy.
        val file = File(context.filesDir, "instrumented-sealed.srt")
        file.writeBytes(sealed)
        val fromDisk = file.readBytes()
        assertArrayEquals(sealed, fromDisk)
        assertTrue(EncryptedFileFormat.isSealed(fromDisk))
        assertArrayEquals(plaintext, EncryptedFileFormat.open(key, "memories/long.md", fromDisk))
        assertFalse(
            "The plaintext must not appear anywhere in the sealed file",
            fromDisk.containsSubsequence(plaintext.copyOfRange(0, 24)),
        )
        file.delete()
        key.fill(0)
    }

    @Test
    fun changing_the_passphrase_rekeys_the_vault_and_invalidates_the_old_one() = runBlocking {
        val manager = manager()
        val original = manager.createSecurity(passphrase.toCharArray())
        val oldKey = manager.unlock(original, passphrase.toCharArray())
        val sealedWithOldKey = EncryptedFileFormat.seal(oldKey, "memories/note.md", "kept".toByteArray())

        val newPassphrase = "নতুন-passphrase-🔐"
        val updated = manager.changePassword(
            security = original,
            currentPassword = passphrase.toCharArray(),
            newPassword = newPassphrase.toCharArray(),
        )

        assertNotEquals(original.keySaltHex, updated.keySaltHex)
        assertNotEquals(original.verifierHashHex, updated.verifierHashHex)

        val newKey = manager.unlock(updated, newPassphrase.toCharArray())
        assertFalse("A password change must produce a different encryption key", newKey.contentEquals(oldKey))

        // Files stay sealed with the key they were written under; the rekey operation is
        // what re-seals them. Until then the old ciphertext is unreadable with the new key.
        try {
            EncryptedFileFormat.open(newKey, "memories/note.md", sealedWithOldKey)
            fail("The old ciphertext opened with the new key")
        } catch (expected: AEADBadTagException) {
            // Expected before re-sealing.
        }
        assertEquals("kept", EncryptedFileFormat.open(oldKey, "memories/note.md", sealedWithOldKey).toString(Charsets.UTF_8))

        // And the old passphrase no longer verifies.
        assertFalse(manager.verifyPassword(updated, passphrase.toCharArray()))

        oldKey.fill(0)
        newKey.fill(0)
    }

    @Test
    fun changing_the_passphrase_with_the_wrong_current_one_is_refused() = runBlocking {
        val manager = manager()
        val original = manager.createSecurity(passphrase.toCharArray())

        try {
            manager.changePassword(
                security = original,
                currentPassword = wrongPassphrase.toCharArray(),
                newPassword = "নতুন-passphrase-🔐".toCharArray(),
            )
            fail("A password change was accepted without the current passphrase")
        } catch (expected: IncorrectPasswordException) {
            assertEquals(LockoutTracker.FREE_ATTEMPTS_DEFAULT - 1, expected.attemptsRemaining)
        }
        assertEquals(1, manager.lockout.state().failedAttempts)

        // The original protection is untouched by the failed attempt.
        assertTrue(manager.verifyPassword(original, passphrase.toCharArray()))
    }

    @Test
    fun the_passphrase_array_is_wiped_by_the_calls_that_use_it() = runBlocking {
        val manager = manager()

        val forCreate = passphrase.toCharArray()
        manager.createSecurity(forCreate)
        assertTrue(
            "createSecurity left the passphrase array in memory",
            forCreate.all { it == '\u0000' },
        )

        val security = manager.createSecurity(passphrase.toCharArray())

        val forUnlock = passphrase.toCharArray()
        manager.unlock(security, forUnlock).fill(0)
        assertTrue("unlock left the passphrase array in memory", forUnlock.all { it == '\u0000' })

        val forVerify = passphrase.toCharArray()
        manager.verifyPassword(security, forVerify)
        assertTrue("verifyPassword left the passphrase array in memory", forVerify.all { it == '\u0000' })

        val forFailure = wrongPassphrase.toCharArray()
        try {
            manager.unlock(security, forFailure)
            fail("A wrong passphrase unlocked the vault")
        } catch (expected: IncorrectPasswordException) {
            assertTrue(
                "A failed unlock left the passphrase array in memory",
                forFailure.all { it == '\u0000' },
            )
        }
    }

    @Test
    fun the_kdf_parameters_survive_a_round_trip_through_the_manifest_json() = runBlocking {
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray())

        // manifest.json is written to an SD card and read back on a fresh install; if the
        // parameters did not survive, the vault could never be reopened.
        val json = security.toJson()
        val restored = VaultSecurity.fromJson(json)
        assertEquals(security, restored)
        assertEquals("argon2id", json.getAsJsonObject("kdf").get("algorithm").asString)
        assertEquals("argon2id", json.get("verifier_algorithm").asString)
        assertEquals(KdfParameters.DEFAULT.memoryKiB, json.getAsJsonObject("kdf").get("memory_kib").asInt)

        val key = manager.unlock(restored, passphrase.toCharArray())
        val sealed = EncryptedFileFormat.seal(key, "memories/portable.md", "portable".toByteArray())
        assertArrayEquals(
            "portable".toByteArray(),
            EncryptedFileFormat.open(key, "memories/portable.md", sealed),
        )
        key.fill(0)
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
