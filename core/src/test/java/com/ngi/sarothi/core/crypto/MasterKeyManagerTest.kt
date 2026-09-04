package com.ngi.sarothi.core.crypto

import com.ngi.sarothi.core.error.IncorrectPasswordException
import com.ngi.sarothi.core.util.Hex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The passphrase-to-key contract, on the JVM.
 *
 * [CryptoTest] proves the primitives against RFC 9106. This proves how they are wired
 * together, which is where the failure mode is invisible: a key derived from the wrong
 * bytes is still a key, everything still encrypts and decrypts, and both halves of the
 * vault make the same mistake so nothing disagrees. The only way to catch it is to derive
 * the expected key independently and compare.
 *
 * That is not a hypothetical here. `unlock()` used to verify the passphrase and then call
 * `deriveKey()` with the same array — and verification consumes it, so the encryption key
 * came out of a run of NUL characters. The vault opened every time, and the passphrase had
 * stopped being what protected it.
 *
 * These run at a deliberately small memory cost. The production parameters are exercised
 * on a device by `VaultKeyDerivationInstrumentedTest`; what matters here is the wiring,
 * and it must be checked on every push rather than only when an emulator boots.
 */
class MasterKeyManagerTest {

    /** LockoutTracker is the only thing MasterKeyManager reads or writes. */
    private class MemoryLockoutStore : LockoutStore {
        val ints = HashMap<String, Int>()
        val longs = HashMap<String, Long>()

        override fun getInt(key: String, fallback: Int): Int = ints[key] ?: fallback
        override fun putInt(key: String, value: Int) {
            ints[key] = value
        }

        override fun getLong(key: String, fallback: Long): Long = longs[key] ?: fallback
        override fun putLong(key: String, value: Long) {
            longs[key] = value
        }
    }

    private val fastKdf = KdfParameters(memoryKiB = 64, iterations = 1, parallelism = 1)
    private val passphrase = "correct-horse-সারথি-🔑"
    private val otherPassphrase = "correct-horse-সারথি-🔒"

    private fun manager() = MasterKeyManager(MemoryLockoutStore())

    /** Derives the key the documentation promises, without going through the code under test. */
    private fun expectedKey(security: VaultSecurity, passphrase: String): ByteArray =
        security.kdf.toArgon2().deriveKey(
            PasswordBytes.encodeUtf8(passphrase.toCharArray()),
            Hex.decode(security.keySaltHex),
            MasterKeyManager.KEY_LENGTH,
        )

    private fun expectedVerifier(security: VaultSecurity, passphrase: String): ByteArray =
        security.kdf.toArgon2().deriveKey(
            PasswordBytes.encodeUtf8(passphrase.toCharArray()),
            Hex.decode(security.verifierSaltHex),
            MasterKeyManager.VERIFIER_LENGTH,
        )

    @Test
    fun unlock_returns_the_key_the_passphrase_actually_derives() = runBlocking {
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray(), fastKdf)

        val key = manager.unlock(security, passphrase.toCharArray())

        assertEquals(MasterKeyManager.KEY_LENGTH, key.size)
        assertArrayEquals(
            "unlock() did not return the key this passphrase derives from this salt",
            expectedKey(security, passphrase),
            key,
        )
    }

    @Test
    fun the_key_is_not_what_a_consumed_passphrase_would_derive() = runBlocking {
        // The exact shape of the old failure: verification wiped the array, and the
        // derivation that followed encoded NUL characters instead of the passphrase.
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray(), fastKdf)
        val key = manager.unlock(security, passphrase.toCharArray())

        val wiped = manager.unlock(security, passphrase.toCharArray())
        val salt = Hex.decode(security.keySaltHex)
        // Wiping leaves CharArray(length) of '\u0000', which encodes to `length` zero
        // bytes -- so these are the two candidate inputs the old code could have used.
        val nulsByChars = ByteArray(passphrase.length)
        val nulsByBytes = ByteArray(PasswordBytes.encodeUtf8(passphrase.toCharArray()).size)
        listOf(nulsByChars, nulsByBytes).forEach { nuls ->
            val keyFromNuls = security.kdf.toArgon2()
                .deriveKey(nuls, salt, MasterKeyManager.KEY_LENGTH)
            assertFalse(
                "unlock() returned the key an all-NUL passphrase of ${nuls.size} byte(s) " +
                    "derives, so the passphrase is not what protects the vault",
                key.contentEquals(keyFromNuls),
            )
            assertFalse(wiped.contentEquals(keyFromNuls))
        }

        // And two unlocks of the same vault agree, which is what lets it be reopened.
        assertArrayEquals(key, wiped)
    }

    @Test
    fun a_vault_created_with_the_key_material_opens_with_the_key_unlock_returns() = runBlocking {
        // The path a real vault takes: create it, seal the memory files, close the app,
        // unlock it later with the passphrase. Both keys must be the same *and* must be
        // the one the passphrase derives.
        val manager = manager()
        val material = manager.createKeyMaterial(passphrase.toCharArray(), fastKdf)

        val plaintext = """{"notes":[{"text":"the user trusted Sarothi with this"}]}"""
        val sealed = EncryptedFileFormat.seal(material.key, VAULT_RELATIVE_PATH, plaintext.toByteArray())

        val reopened = manager.unlock(material.security, passphrase.toCharArray())
        assertArrayEquals(material.key, reopened)
        assertArrayEquals(expectedKey(material.security, passphrase), reopened)
        assertEquals(
            plaintext,
            EncryptedFileFormat.open(reopened, VAULT_RELATIVE_PATH, sealed).toString(Charsets.UTF_8),
        )

        material.key.fill(0)
        reopened.fill(0)
    }

    @Test
    fun the_key_material_records_the_verifier_the_passphrase_produces() = runBlocking {
        val manager = manager()
        val material = manager.createKeyMaterial(passphrase.toCharArray(), fastKdf)

        assertEquals(
            Hex.encode(expectedVerifier(material.security, passphrase)),
            material.security.verifierHashHex,
        )
        assertTrue(manager.verifyPassword(material.security, passphrase.toCharArray()))
        assertFalse(manager.verifyPassword(material.security, otherPassphrase.toCharArray()))

        // The verifier proves the passphrase and must not be usable as the key.
        val verifier = Hex.decode(material.security.verifierHashHex)
        assertEquals(MasterKeyManager.VERIFIER_LENGTH, verifier.size)
        assertFalse(
            "The verifier hash equals the encryption key",
            verifier.contentEquals(material.key),
        )
        material.key.fill(0)
    }

    @Test
    fun two_passphrases_of_the_same_length_derive_different_keys() = runBlocking {
        // The old bug was invisible to a length check: every passphrase of a given length
        // produced the same key. Different passphrases must not.
        assertEquals(passphrase.length, otherPassphrase.length)
        val manager = manager()

        val first = manager.createKeyMaterial(passphrase.toCharArray(), fastKdf)
        val second = manager.createKeyMaterial(otherPassphrase.toCharArray(), fastKdf)

        assertNotEquals(first.security.keySaltHex, second.security.keySaltHex)
        assertNotEquals(first.security.verifierHashHex, second.security.verifierHashHex)
        assertFalse(first.key.contentEquals(second.key))

        // Crossed wires must fail in both directions.
        assertFalse(
            "The second vault accepted the first passphrase",
            manager.verifyPassword(second.security, passphrase.toCharArray()),
        )
        try {
            manager.unlock(second.security, passphrase.toCharArray())
            fail("A different passphrase unlocked the vault")
        } catch (expected: IncorrectPasswordException) {
            assertEquals(LockoutTracker.FREE_ATTEMPTS_DEFAULT - 1, expected.attemptsRemaining)
            assertNull(expected.lockoutUntilEpochMillis)
        }

        first.key.fill(0)
        second.key.fill(0)
    }

    @Test
    fun changing_the_passphrase_produces_the_key_the_new_passphrase_derives() = runBlocking {
        // The same composition trap, on the rekey path: changePassword() hands the new
        // passphrase to createSecurity(), which consumes it.
        val manager = manager()
        val original = manager.createSecurity(passphrase.toCharArray(), fastKdf)

        val updated = manager.changePassword(
            security = original,
            currentPassword = passphrase.toCharArray(),
            newPassword = otherPassphrase.toCharArray(),
            newKdf = fastKdf,
        )

        val key = manager.unlock(updated, otherPassphrase.toCharArray())
        assertArrayEquals(
            "The rekeyed vault's key is not the one the new passphrase derives",
            expectedKey(updated, otherPassphrase),
            key,
        )
        assertFalse(manager.verifyPassword(updated, passphrase.toCharArray()))
        key.fill(0)
    }

    @Test
    fun deriveKey_and_createSecurity_agree_with_each_other() = runBlocking {
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray(), fastKdf)

        val derived = manager.deriveKey(security, passphrase.toCharArray())
        assertArrayEquals(expectedKey(security, passphrase), derived)

        // Deterministic: the same passphrase and salts must give the same key every time,
        // or the vault could not be reopened after a reboot.
        assertArrayEquals(derived, manager.deriveKey(security, passphrase.toCharArray()))
        derived.fill(0)
    }

    @Test
    fun a_wrong_passphrase_counts_one_failure_and_leaves_the_tracker_able_to_escalate() = runBlocking {
        val manager = manager()
        val security = manager.createSecurity(passphrase.toCharArray(), fastKdf)

        repeat(LockoutTracker.FREE_ATTEMPTS_DEFAULT + 1) { attempt ->
            try {
                manager.unlock(security, otherPassphrase.toCharArray())
                fail("A wrong passphrase unlocked the vault on attempt ${attempt + 1}")
            } catch (expected: IncorrectPasswordException) {
                // Expected every time.
            }
        }

        val state = manager.lockout.state()
        assertEquals(LockoutTracker.FREE_ATTEMPTS_DEFAULT + 1, state.failedAttempts)
        assertTrue(
            "The backoff window never opened",
            state.lockedUntilEpochMillis != null && state.lockedUntilEpochMillis > System.currentTimeMillis(),
        )

        // Knowing the passphrase is not enough while the window is open.
        try {
            manager.unlock(security, passphrase.toCharArray())
            fail("The correct passphrase was accepted during the backoff window")
        } catch (expected: IncorrectPasswordException) {
            assertEquals(0, expected.attemptsRemaining)
            assertEquals(state.lockedUntilEpochMillis, expected.lockoutUntilEpochMillis)
        }

        // A successful unlock after the window clears the count, and the key it returns is
        // still the right one — the lockout path must not disturb derivation.
        manager.lockout.recordSuccess()
        val key = manager.unlock(security, passphrase.toCharArray())
        assertArrayEquals(expectedKey(security, passphrase), key)
        assertEquals(LockoutTracker.State.UNLOCKED, manager.lockout.state())
        key.fill(0)
    }

    @Test
    fun every_entry_point_consumes_the_array_it_is_given() = runBlocking {
        val manager = manager()

        val forCreate = passphrase.toCharArray()
        manager.createSecurity(forCreate, fastKdf)
        assertTrue("createSecurity left the passphrase in memory", forCreate.all { it == '\u0000' })

        val security = manager.createSecurity(passphrase.toCharArray(), fastKdf)

        val forMaterial = passphrase.toCharArray()
        manager.createKeyMaterial(forMaterial, fastKdf).key.fill(0)
        assertTrue("createKeyMaterial left the passphrase in memory", forMaterial.all { it == '\u0000' })

        val forUnlock = passphrase.toCharArray()
        manager.unlock(security, forUnlock).fill(0)
        assertTrue("unlock left the passphrase in memory", forUnlock.all { it == '\u0000' })

        val forDerive = passphrase.toCharArray()
        manager.deriveKey(security, forDerive).fill(0)
        assertTrue("deriveKey left the passphrase in memory", forDerive.all { it == '\u0000' })

        val forVerify = passphrase.toCharArray()
        manager.verifyPassword(security, forVerify)
        assertTrue("verifyPassword left the passphrase in memory", forVerify.all { it == '\u0000' })

        val forWrongUnlock = otherPassphrase.toCharArray()
        try {
            manager.unlock(security, forWrongUnlock)
            fail("A wrong passphrase unlocked the vault")
        } catch (expected: IncorrectPasswordException) {
            assertTrue(
                "A failed unlock left the passphrase in memory",
                forWrongUnlock.all { it == '\u0000' },
            )
        }

        val currentForChange = passphrase.toCharArray()
        val newForChange = otherPassphrase.toCharArray()
        val changed = manager.changePassword(security, currentForChange, newForChange, fastKdf)
        assertTrue("changePassword left the current passphrase in memory", currentForChange.all { it == '\u0000' })
        assertTrue("changePassword left the new passphrase in memory", newForChange.all { it == '\u0000' })

        val wrongCurrent = passphrase.toCharArray()
        val unusedNew = otherPassphrase.toCharArray()
        try {
            manager.changePassword(changed, wrongCurrent, unusedNew, fastKdf)
            fail("A password change was accepted without the current passphrase")
        } catch (expected: IncorrectPasswordException) {
            assertTrue(
                "A refused password change left the new passphrase in memory",
                unusedNew.all { it == '\u0000' },
            )
        }
    }

    @Test
    fun the_protection_record_survives_json_and_still_yields_the_same_key() = runBlocking {
        // manifest.json is plaintext on an SD card; the vault has to reopen from it on a
        // device that has never seen it before.
        val manager = manager()
        val material = manager.createKeyMaterial(passphrase.toCharArray(), fastKdf)

        val restored = VaultSecurity.fromJson(material.security.toJson())
        assertEquals(material.security, restored)

        val key = manager.unlock(restored, passphrase.toCharArray())
        assertArrayEquals(material.key, key)
        assertArrayEquals(expectedKey(restored, passphrase), key)

        material.key.fill(0)
        key.fill(0)
    }

    private companion object {
        /** The vault-relative path a sealed file is bound to through the AES-GCM AAD. */
        const val VAULT_RELATIVE_PATH = "memories/notes.json"
    }
}
