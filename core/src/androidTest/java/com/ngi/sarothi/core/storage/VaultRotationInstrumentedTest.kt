package com.ngi.sarothi.core.storage

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ngi.sarothi.core.crypto.EncryptedFileFormat
import com.ngi.sarothi.core.crypto.KdfParameters
import com.ngi.sarothi.core.crypto.MasterKeyManager
import com.ngi.sarothi.core.crypto.SecretStore
import com.ngi.sarothi.core.util.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Changing the vault passphrase: every memory file re-sealed under a key derived from the
 * new one, nothing left behind, and -- the part that only a device can prove -- an
 * interruption in the middle of it finishes itself without any passphrase at all.
 *
 * Run against [MemoryVaultFileSystem] because a Storage Access Framework tree cannot be
 * obtained without a person tapping through the system picker, and because the interesting
 * states here are the ones *between* writes: a test that has to ask a human to pull the
 * battery at the right instruction is not a test. The crypto underneath is
 * `VaultKeyDerivationInstrumentedTest`'s subject and the Android Keystore is
 * `SecretStoreInstrumentedTest`'s; what is being pinned down here is the file choreography
 * [VaultManager.changePassphrase] performs around them.
 */
@RunWith(AndroidJUnit4::class)
class VaultRotationInstrumentedTest {

    private lateinit var context: Context
    private lateinit var secrets: SecretStore
    private lateinit var fs: MemoryVaultFileSystem
    private lateinit var vault: VaultManager

    /** Fast but real Argon2id: the same parameters the JVM suite uses. */
    private val fastKdf = KdfParameters(memoryKiB = 64, iterations = 1, parallelism = 1)
    private val firstPassphrase = "প্রথম-passphrase-🔑"
    private val secondPassphrase = "দ্বিতীয়-passphrase-🔒"

    /** What `createFreshVault` seeds: the whole of a fresh vault's encrypted content. */
    private val seeded = listOf(
        VaultPaths.NOTES,
        VaultPaths.TODOS,
        VaultPaths.PREFERENCES,
        VaultPaths.ENABLED_PLUGINS,
    )

    @Before
    fun startFromANothingVault() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Lockout state lives in the device secrets; without this a wrong-passphrase test
        // would spend the next test's free attempts.
        context.deleteSharedPreferences(SecretStore.FILE_NAME)
        secrets = SecretStore(context)
        fs = MemoryVaultFileSystem()
        vault = VaultManager(context, secrets, MasterKeyManager(secrets))
        vault.fileSystem = fs
    }

    private suspend fun createVault(passphrase: String = firstPassphrase) {
        vault.createFreshVault(passphrase.toCharArray(), fastKdf)
    }

    private fun managerOverSameFolder(): VaultManager =
        VaultManager(context, secrets, MasterKeyManager(secrets)).also { it.fileSystem = fs }

    private fun recordOnDisk() = VaultManifest.parse(fs.readFile(VaultPaths.MANIFEST)).security

    @Test
    fun changing_the_passphrase_rekeys_every_memory_file_and_leaves_plaintext_alone() = runBlocking {
        createVault()
        val recordBefore = recordOnDisk()
        val logPath = "logs/audit.log"
        fs.writeFile(logPath, "2026-09-05T00:00:00Z ran a task\n".toByteArray(Charsets.UTF_8))
        val logBefore = fs.raw(logPath)
        val keyBefore = vault.requireKey().copyOf()

        val outcome = vault.changePassphrase(firstPassphrase.toCharArray(), secondPassphrase.toCharArray())

        assertTrue("the change was refused: $outcome", outcome is PassphraseChange.Changed)
        assertEquals(
            "the four seeded files are the whole encrypted content of a fresh vault",
            seeded.size,
            (outcome as PassphraseChange.Changed).filesRotated,
        )
        assertFalse(
            "the manifest still names the protection record the old passphrase produced",
            recordOnDisk().verifierHashHex == recordBefore.verifierHashHex,
        )

        val keyAfter = vault.requireKey()
        seeded.forEach { path ->
            val plaintext = EncryptedFileFormat.open(keyAfter, path, fs.readFile(path))
            assertTrue("'$path' re-encrypted to nothing", plaintext.isNotEmpty())
            val withOldKey = runCatching { EncryptedFileFormat.open(keyBefore, path, fs.readFile(path)) }
            assertTrue(
                "'$path' still opens with the key the old passphrase derives, so the change " +
                    "did not change what protects it",
                withOldKey.isFailure,
            )
        }

        assertArrayEquals(
            "a plaintext file was rewritten by a passphrase change",
            logBefore,
            fs.raw(logPath),
        )
    }

    @Test
    fun the_new_passphrase_opens_the_vault_and_the_old_one_no_longer_does() = runBlocking {
        createVault()
        vault.changePassphrase(firstPassphrase.toCharArray(), secondPassphrase.toCharArray())

        val stale = managerOverSameFolder()
        val refused = runCatching { stale.openExistingVault(firstPassphrase.toCharArray()) }
        assertTrue(
            "the passphrase that was replaced still unlocks the vault: ${refused.exceptionOrNull()}",
            refused.isFailure,
        )

        val reopened = managerOverSameFolder()
        reopened.openExistingVault(secondPassphrase.toCharArray())
        assertTrue("the new passphrase did not unlock the vault", reopened.isUnlocked)
        assertEquals(
            """{"notes":[]}""",
            String(reopened.readEncrypted(VaultPaths.NOTES) ?: ByteArray(0), Charsets.UTF_8),
        )
    }

    @Test
    fun nothing_is_left_on_the_card_that_existed_only_for_the_change() = runBlocking {
        createVault()
        vault.changePassphrase(firstPassphrase.toCharArray(), secondPassphrase.toCharArray())

        val leftovers = fs.paths().filter { it.endsWith(".rotating") || it.contains(".rotation") }
        assertTrue(
            "the change left ${leftovers.size} working file(s) in the vault: $leftovers",
            leftovers.isEmpty(),
        )
        assertFalse("the vault reports a pending change that has already completed", vault.rotationPending())
    }

    @Test
    fun a_wrong_current_passphrase_changes_nothing_at_all() = runBlocking {
        createVault()
        val keyBefore = vault.requireKey().copyOf()
        val recordBefore = recordOnDisk()
        val filesBefore = seeded.associateWith { fs.raw(it) }

        val outcome = vault.changePassphrase("not the passphrase".toCharArray(), secondPassphrase.toCharArray())

        assertTrue("a wrong passphrase was accepted: $outcome", outcome is PassphraseChange.Refused)
        assertTrue(
            "a refusal that does not say why leaves the user guessing: $outcome",
            (outcome as PassphraseChange.Refused).reason.isNotBlank(),
        )
        assertEquals(
            "the manifest's protection record moved despite the refusal",
            recordBefore.verifierHashHex,
            recordOnDisk().verifierHashHex,
        )
        seeded.forEach { path ->
            assertArrayEquals("'$path' was rewritten despite the refusal", filesBefore.getValue(path), fs.raw(path))
            EncryptedFileFormat.open(keyBefore, path, fs.readFile(path))
        }
        assertTrue("a refused change locked the vault", vault.isUnlocked)
        assertFalse("a refused change left a pending rotation", vault.rotationPending())
    }

    @Test
    fun a_locked_vault_refuses_rather_than_guessing_at_a_key() = runBlocking {
        createVault()
        vault.lock()

        val outcome = vault.changePassphrase(firstPassphrase.toCharArray(), secondPassphrase.toCharArray())

        assertTrue("changing the passphrase of a locked vault was allowed: $outcome", outcome is PassphraseChange.Refused)
        assertTrue(
            "the refusal does not say the vault is locked: $outcome",
            "locked" in (outcome as PassphraseChange.Refused).reason.lowercase(),
        )
        assertFalse("a refused change left a pending rotation", vault.rotationPending())
    }

    @Test
    fun an_interrupted_change_finishes_itself_without_any_passphrase() = runBlocking {
        createVault()
        val oldKey = vault.requireKey()

        // Reproduce exactly what being killed between publishing the record and finishing
        // the copy pass leaves behind: every new-key copy written beside its original, the
        // new protection record on disk, an empty progress list, and the originals still
        // sealed with the key in memory.
        val material = vault.masterKeys.createKeyMaterial(secondPassphrase.toCharArray(), fastKdf)
        seeded.forEach { path ->
            val plaintext = EncryptedFileFormat.open(oldKey, path, fs.readFile(path))
            fs.writeFile("$path.rotating", EncryptedFileFormat.seal(material.key, path, plaintext))
        }
        fs.createDirectories("memories/.rotation")
        fs.writeFile(
            "memories/.rotation/rotation.json",
            Json.pretty(material.security.toJson()).toByteArray(Charsets.UTF_8),
        )
        fs.writeFile("memories/.rotation/progress", ByteArray(0))
        assertTrue("the simulated interruption is not visible as pending", vault.rotationPending())

        val resumed = vault.resumeInterruptedRotation()

        assertTrue("a pending change was not finished", resumed)
        assertFalse(
            "the vault stayed unlocked with a key that no longer opens its files",
            vault.isUnlocked,
        )
        assertFalse("the pending change is still reported after being finished", vault.rotationPending())
        assertEquals(
            "the manifest was not moved onto the record the interrupted change published",
            material.security.verifierHashHex,
            recordOnDisk().verifierHashHex,
        )
        seeded.forEach { path ->
            assertFalse("'$path.rotating' was left beside the original", fs.exists("$path.rotating"))
            val plaintext = EncryptedFileFormat.open(material.key, path, fs.readFile(path))
            assertTrue("'$path' did not end up holding the new-key bytes", plaintext.isNotEmpty())
        }

        // And the state a person meets afterwards: the new passphrase works, the old one
        // does not, and the memories are what they were.
        val reopened = managerOverSameFolder()
        reopened.openExistingVault(secondPassphrase.toCharArray())
        assertEquals(
            """{"notes":[]}""",
            String(reopened.readEncrypted(VaultPaths.NOTES) ?: ByteArray(0), Charsets.UTF_8),
        )
    }

    @Test
    fun resuming_with_nothing_pending_changes_nothing() = runBlocking {
        createVault()
        val keyBefore = vault.requireKey().copyOf()
        val filesBefore = seeded.associateWith { fs.raw(it) }

        assertFalse("resume reported finishing a change nobody started", vault.resumeInterruptedRotation())
        assertFalse(vault.rotationPending())
        assertTrue("resume locked an unlocked vault", vault.isUnlocked)
        seeded.forEach { path ->
            assertArrayEquals("resume rewrote '$path'", filesBefore.getValue(path), fs.raw(path))
            EncryptedFileFormat.open(keyBefore, path, fs.readFile(path))
        }
    }

}
