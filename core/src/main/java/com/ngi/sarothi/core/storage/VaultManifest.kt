package com.ngi.sarothi.core.storage

import com.google.gson.JsonObject
import com.ngi.sarothi.core.crypto.VaultSecurity
import com.ngi.sarothi.core.model.ChecksumPolicy
import com.ngi.sarothi.core.model.ModelCatalog
import com.ngi.sarothi.core.util.stringOrNull
import java.time.Instant

/** One model recorded in `manifest.json`. */
data class ManifestModelEntry(
    val catalogId: String?,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String?,
    val gitBlobSha1: String?,
    val checksumPolicy: String,
    /** Digest actually computed over the bytes on disk when the file was accepted. */
    val computedDigest: String?,
    /**
     * True only when the computed digest matched a digest published upstream.
     * A file whose policy is [ChecksumPolicy.SIZE_ONLY] is never marked verified —
     * Sarothi will not claim a checksum passed when there was nothing to compare
     * against.
     */
    val checksumVerified: Boolean,
    val downloadedAt: String?,
    val source: String?,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        catalogId?.let { addProperty("catalog_id", it) }
        addProperty("name", name)
        addProperty("path", path)
        addProperty("size_bytes", sizeBytes)
        sha256?.let { addProperty("sha256", it) }
        gitBlobSha1?.let { addProperty("git_blob_sha1", it) }
        addProperty("checksum_policy", checksumPolicy)
        computedDigest?.let { addProperty("computed_digest", it) }
        addProperty("checksum_verified", checksumVerified)
        downloadedAt?.let { addProperty("downloaded_at", it) }
        source?.let { addProperty("source", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): ManifestModelEntry = ManifestModelEntry(
            catalogId = json.stringOrNull("catalog_id"),
            name = json.stringOrNull("name") ?: "",
            path = json.stringOrNull("path") ?: "",
            sizeBytes = json.get("size_bytes")?.asLong ?: -1L,
            sha256 = json.stringOrNull("sha256"),
            gitBlobSha1 = json.stringOrNull("git_blob_sha1"),
            checksumPolicy = json.stringOrNull("checksum_policy") ?: ChecksumPolicy.SIZE_ONLY.name,
            computedDigest = json.stringOrNull("computed_digest"),
            checksumVerified = json.get("checksum_verified")?.asBoolean ?: false,
            downloadedAt = json.stringOrNull("downloaded_at"),
            source = json.stringOrNull("source"),
        )

        /** Builds the entry Sarothi expects for a catalogue model that is not yet on disk. */
        fun expectedFor(catalogId: String): ManifestModelEntry? {
            val model = ModelCatalog.byId(catalogId) ?: return null
            return ManifestModelEntry(
                catalogId = model.id,
                name = model.fileName,
                path = model.vaultPath,
                sizeBytes = model.sizeBytes,
                sha256 = model.sha256,
                gitBlobSha1 = model.gitBlobSha1,
                checksumPolicy = model.checksumPolicy.name,
                computedDigest = null,
                checksumVerified = false,
                downloadedAt = null,
                source = null,
            )
        }
    }
}

/**
 * The vault's plaintext `manifest.json`.
 *
 * Holds exactly what the storage spec asks for — schema version, per-model
 * metadata (`name`, `path`, `sha256`, `downloaded_at`) and the encryption salt —
 * plus the KDF parameters and the password verifier, all of which are
 * non-secret and must be readable *before* the vault can be unlocked.
 *
 * This file is what makes a folder recognisable as an existing Sarothi vault on a
 * fresh install, and therefore what makes the restore flow possible.
 */
data class VaultManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val createdAt: String,
    val updatedAt: String,
    val appVersion: String?,
    val deviceLabel: String?,
    val security: VaultSecurity,
    /** Keyed by catalogue id, or by file name for models added by hand. */
    val models: Map<String, ManifestModelEntry>,
) {

    fun withModels(updated: Map<String, ManifestModelEntry>): VaultManifest =
        copy(models = updated, updatedAt = Instant.now().toString())

    fun withSecurity(updated: VaultSecurity): VaultManifest =
        copy(security = updated, updatedAt = Instant.now().toString())

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("schema_version", schemaVersion)
        addProperty("created_at", createdAt)
        addProperty("updated_at", updatedAt)
        appVersion?.let { addProperty("app_version", it) }
        deviceLabel?.let { addProperty("device_label", it) }
        add("security", security.toJson())
        val modelsJson = JsonObject()
        models.forEach { (id, entry) -> modelsJson.add(id, entry.toJson()) }
        add("models", modelsJson)
    }

    fun serialize(): ByteArray =
        com.ngi.sarothi.core.util.Json.pretty(toJson()).toByteArray(Charsets.UTF_8)

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        /**
         * @throws IllegalArgumentException when the file is not a Sarothi manifest,
         *   or was written by a newer schema this build cannot interpret. Silently
         *   reading a future manifest as if it were current would risk overwriting
         *   fields this build does not understand.
         */
        fun parse(bytes: ByteArray): VaultManifest {
            val text = bytes.toString(Charsets.UTF_8)
            val json = runCatching { com.ngi.sarothi.core.util.Json.parseObject(text) }
                .getOrElse {
                    throw IllegalArgumentException(
                        "manifest.json is not valid JSON, so this folder is not a readable " +
                            "Sarothi vault: ${it.message}",
                    )
                }

            val version = json.get("schema_version")?.asInt
                ?: throw IllegalArgumentException("manifest.json has no schema_version")
            require(version <= CURRENT_SCHEMA_VERSION) {
                "This vault was written by a newer Sarothi (manifest schema $version); this " +
                    "build understands up to $CURRENT_SCHEMA_VERSION. Update the app before " +
                    "opening this folder."
            }

            val securityJson = json.getAsJsonObject("security")
                ?: throw IllegalArgumentException("manifest.json has no 'security' block")

            val modelsJson = json.getAsJsonObject("models")
            val models = linkedMapOf<String, ManifestModelEntry>()
            modelsJson?.entrySet()?.forEach { (key, value) ->
                if (value.isJsonObject) models[key] = ManifestModelEntry.fromJson(value.asJsonObject)
            }

            return VaultManifest(
                schemaVersion = version,
                createdAt = json.stringOrNull("created_at") ?: "",
                updatedAt = json.stringOrNull("updated_at") ?: "",
                appVersion = json.stringOrNull("app_version"),
                deviceLabel = json.stringOrNull("device_label"),
                security = VaultSecurity.fromJson(securityJson),
                models = models,
            )
        }

        /** Cheap check used by the folder picker before any password prompt. */
        fun looksLikeSarothiVault(fs: VaultFileSystem): Boolean =
            fs.exists(VaultPaths.MANIFEST) && runCatching {
                val head = fs.readFile(VaultPaths.MANIFEST)
                head.isNotEmpty() && parse(head).schemaVersion >= 1
            }.isSuccess
    }
}
