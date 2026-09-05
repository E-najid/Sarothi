package com.ngi.sarothi.core.crypto

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [SecretStore] against a real Android Keystore.
 *
 * None of this can run on the JVM: the store is `EncryptedSharedPreferences` over a
 * hardware-backed `MasterKey`, and Robolectric's shadow Keystore would prove only that
 * the shadow works. What is being checked here is the property the whole vault rests on
 * — secrets go in, come back out unchanged, and the bytes that land on disk are neither
 * the secret nor the key name that indexes it.
 */
@RunWith(AndroidJUnit4::class)
class SecretStoreInstrumentedTest {

    private lateinit var context: Context

    /** A value distinctive enough that a false negative is impossible to miss. */
    private val secret = "sarothi-instrumented-secret-9f3c1a7e-do-not-leak"

    @Before
    fun deleteAnyLeftoverStore() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteSharedPreferences(SecretStore.FILE_NAME)
    }

    @After
    fun deleteWhatThisTestWrote() {
        context.deleteSharedPreferences(SecretStore.FILE_NAME)
    }

    private fun store(): SecretStore = SecretStore(context)

    @Test
    fun keystore_backed_storage_opens_without_having_to_rebuild_itself() {
        // wasRebuiltAfterFailure is the store's own admission that the keystore entry was
        // unreadable and it threw the device-local secrets away to recover. On a healthy
        // device that must never happen; if it does, every token in the app is silently
        // being lost between launches.
        assertFalse(
            "SecretStore had to rebuild its keystore-backed prefs on this device",
            store().wasRebuiltAfterFailure,
        )
    }

    @Test
    fun every_supported_type_round_trips_through_encrypted_storage() {
        val store = store()

        store.putString(SecretStore.KEY_GITHUB_TOKEN, secret)
        store.putInt(SecretStore.KEY_LOCKOUT_FAILURES, 7)
        store.putLong(SecretStore.KEY_LOCKOUT_UNTIL, 1_800_000_000_123L)
        store.putBoolean("instrumented.flag", true)
        val bytes = byteArrayOf(0, 1, -1, 127, -128, 42, 99)
        store.putBytes(SecretStore.KEY_BIOMETRIC_WRAPPED_KEY, bytes)

        assertEquals(secret, store.getString(SecretStore.KEY_GITHUB_TOKEN))
        assertEquals(7, store.getInt(SecretStore.KEY_LOCKOUT_FAILURES, 0))
        assertEquals(1_800_000_000_123L, store.getLong(SecretStore.KEY_LOCKOUT_UNTIL, 0L))
        assertTrue(store.getBoolean("instrumented.flag", false))
        assertArrayEquals(bytes, store.getBytes(SecretStore.KEY_BIOMETRIC_WRAPPED_KEY))
    }

    @Test
    fun reading_an_absent_key_returns_the_fallback_rather_than_inventing_a_value() {
        val store = store()

        assertNull(store.getString("instrumented.absent"))
        assertEquals("given", store.getString("instrumented.absent", "given"))
        assertEquals(3, store.getInt("instrumented.absent", 3))
        assertEquals(-9L, store.getLong("instrumented.absent", -9L))
        assertFalse(store.getBoolean("instrumented.absent", false))
        assertNull(store.getBytes("instrumented.absent"))
        assertFalse(store.contains("instrumented.absent"))
    }

    @Test
    fun storing_null_removes_the_entry_instead_of_writing_an_empty_one() {
        val store = store()
        store.putString(SecretStore.KEY_GOOGLE_REFRESH, secret)
        assertTrue(store.contains(SecretStore.KEY_GOOGLE_REFRESH))

        store.putString(SecretStore.KEY_GOOGLE_REFRESH, null)

        assertFalse(store.contains(SecretStore.KEY_GOOGLE_REFRESH))
        assertNull(store.getString(SecretStore.KEY_GOOGLE_REFRESH))

        store.putBytes(SecretStore.KEY_BIOMETRIC_WRAPPED_IV, secret.toByteArray())
        store.putBytes(SecretStore.KEY_BIOMETRIC_WRAPPED_IV, null)
        assertNull(store.getBytes(SecretStore.KEY_BIOMETRIC_WRAPPED_IV))
    }

    @Test
    fun remove_and_overwrite_both_take_effect() {
        val store = store()
        store.putString(SecretStore.KEY_GITHUB_USER, "first")
        store.putString(SecretStore.KEY_GITHUB_USER, "second")
        assertEquals("second", store.getString(SecretStore.KEY_GITHUB_USER))

        store.remove(SecretStore.KEY_GITHUB_USER)
        assertFalse(store.contains(SecretStore.KEY_GITHUB_USER))
    }

    @Test
    fun a_second_store_over_the_same_device_sees_what_the_first_one_wrote() {
        // Two SecretStore instances are what the app really has: one held by the graph,
        // another created by a component that needs the lockout counters. If they
        // disagreed, a failed unlock in one place would not count towards the lockout.
        store().putString(SecretStore.KEY_GITHUB_TOKEN, secret)
        store().putInt(SecretStore.KEY_LOCKOUT_FAILURES, 4)

        val reopened = store()
        assertEquals(secret, reopened.getString(SecretStore.KEY_GITHUB_TOKEN))
        assertEquals(4, reopened.getInt(SecretStore.KEY_LOCKOUT_FAILURES, 0))
    }

    @Test
    fun the_lockout_store_interface_reaches_the_same_encrypted_entries() {
        // LockoutTracker only ever sees SecretStore through the LockoutStore seam. The
        // overrides must land in the same keystore-backed file, or the tracker would be
        // counting failures in a store nobody else reads.
        val asSeam: LockoutStore = store()
        asSeam.putInt(SecretStore.KEY_LOCKOUT_FAILURES, 2)
        asSeam.putLong(SecretStore.KEY_LOCKOUT_UNTIL, 12_345L)
        asSeam.putInt(SecretStore.KEY_LOCKOUT_ESCALATION, 1)

        val store = store()
        assertEquals(2, store.getInt(SecretStore.KEY_LOCKOUT_FAILURES, 0))
        assertEquals(12_345L, store.getLong(SecretStore.KEY_LOCKOUT_UNTIL, 0L))
        assertEquals(1, store.getInt(SecretStore.KEY_LOCKOUT_ESCALATION, 0))
        assertEquals(2, (asSeam as SecretStore).getInt(SecretStore.KEY_LOCKOUT_FAILURES, 0))
    }

    @Test
    fun neither_the_secret_nor_its_key_name_ever_reaches_the_disk_in_the_clear() {
        val store = store()
        store.putString(SecretStore.KEY_GITHUB_TOKEN, secret)

        val file = awaitPrefsFileOnDisk()
        val onDisk = file.readText()

        assertTrue(
            "Encrypted prefs were written but contain nothing",
            onDisk.isNotBlank(),
        )
        assertFalse(
            "The secret value was found in plaintext in ${file.name}",
            onDisk.contains(secret),
        )
        assertFalse(
            "The secret value was found in plaintext (as bytes) in ${file.name}",
            file.readBytes().containsSubsequence(secret.toByteArray()),
        )
        assertFalse(
            "The preference key name was stored in the clear in ${file.name}",
            onDisk.contains(SecretStore.KEY_GITHUB_TOKEN),
        )
        // Guard against the test passing because the file is a stub: AES-SIV key
        // encryption plus AES-GCM value encryption must have produced real ciphertext.
        assertTrue(
            "Stored entry is suspiciously short to be AES-GCM ciphertext: ${onDisk.length} chars",
            onDisk.length > secret.length * 2,
        )
    }

    /**
     * `apply()` writes to disk on a background thread, so the file is polled for rather
     * than assumed to exist. Failing loudly here is the point: a test that checked an
     * empty directory would prove nothing.
     */
    private fun awaitPrefsFileOnDisk(): File {
        val dir = File(context.dataDir, "shared_prefs")
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val match = dir.listFiles()
                ?.filter { it.name.startsWith(SecretStore.FILE_NAME) && it.length() > 0 }
                ?.maxByOrNull { it.length() }
            if (match != null) return match
            Thread.sleep(50)
        }
        val listing = dir.listFiles()?.joinToString { "${it.name} (${it.length()} B)" } ?: "no directory"
        throw AssertionError(
            "No non-empty prefs file for '${SecretStore.FILE_NAME}' appeared within 15 s in " +
                "${dir.absolutePath}; found: $listing",
        )
    }

    @Test
    fun a_stored_master_key_survives_as_exact_bytes_not_as_a_string() {
        // Keys are binary. If putBytes/getBytes ever round-tripped through a String the
        // high bytes would be mangled and the vault would fail to open with a bad tag.
        val key = AesGcm.generateKey()
        assertEquals(32, key.size)

        val store = store()
        store.putBytes("instrumented.key", key)

        val readBack = store.getBytes("instrumented.key")
        assertNotNull(readBack)
        assertArrayEquals(key, readBack)
        assertArrayEquals(key, store().getBytes("instrumented.key"))
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
