package com.ngi.sarothi.core.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ngi.sarothi.core.storage.VaultManager
import com.ngi.sarothi.core.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * An encrypted JSON array document in the vault, held as a mutable collection.
 *
 * Reads and writes are serialised by a mutex and run on [Dispatchers.IO], because
 * the vault is a `content://` tree and two concurrent rewrites of the same file
 * would lose one of them. The whole document is rewritten on every mutation:
 * these files hold hundreds of small records at most, and a full rewrite is what
 * makes each write atomic (see [com.ngi.sarothi.core.storage.VaultFileSystem.writeFile]).
 */
class VaultJsonCollection<T>(
    private val vault: VaultManager,
    private val path: String,
    private val arrayKey: String,
    private val toItem: (JsonObject) -> T?,
    private val fromItem: (T) -> JsonObject,
) {
    private val mutex = Mutex()
    private var cache: MutableList<T>? = null

    private fun readLocked(): MutableList<T> {
        cache?.let { return it }
        val json = vault.readEncryptedJson(path)
        // An explicit loop rather than `mapNotNull { }.toMutableList()`: T is an
        // unbounded type parameter here, and chaining mapNotNull (whose receiver is
        // Iterable<T?> and whose result type is bounded `R : Any`) into toMutableList
        // makes the compiler infer MutableList<T? & Any>, which will not assign to
        // MutableList<T>. Building the list directly leaves nothing to infer.
        //
        // Two things get filtered, and both have to be: a non-object element in the
        // array, and a null from toItem, which is declared `(JsonObject) -> T?` because
        // a stored record may no longer parse against the current schema. Dropping such
        // an entry is right -- the alternative would be to fail the whole read over one
        // unreadable row. mapNotNull did this filtering too; replacing it with add()
        // alone passed a T? where MutableList<T> wants a T.
        val items: MutableList<T> = mutableListOf()
        json?.getAsJsonArray(arrayKey)?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val item = toItem(element.asJsonObject) ?: return@forEach
            items.add(item)
        }
        cache = items
        return items
    }

    private fun writeLocked(items: List<T>) {
        val document = Json.obj {
            addProperty("schema_version", SCHEMA_VERSION)
            addProperty("updated_at", java.time.Instant.now().toString())
            add(arrayKey, JsonArray().also { array -> items.forEach { array.add(fromItem(it)) } })
        }
        vault.writeEncryptedJson(path, document)
        cache = items.toMutableList()
    }

    suspend fun snapshot(): List<T> = withContext(Dispatchers.IO) { mutex.withLock { readLocked().toList() } }

    suspend fun size(): Int = withContext(Dispatchers.IO) { mutex.withLock { readLocked().size } }

    /** Runs [block] against the collection and persists whatever it leaves behind. */
    suspend fun <R> mutate(block: (MutableList<T>) -> R): R = withContext(Dispatchers.IO) {
        mutex.withLock {
            val items = readLocked()
            val result = block(items)
            writeLocked(items)
            result
        }
    }

    /** Reads without persisting anything. */
    suspend fun <R> read(block: (List<T>) -> R): R = withContext(Dispatchers.IO) {
        mutex.withLock { block(readLocked().toList()) }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
