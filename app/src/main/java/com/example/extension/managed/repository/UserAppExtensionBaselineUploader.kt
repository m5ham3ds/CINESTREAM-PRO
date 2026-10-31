package com.example.extension.managed.repository

import com.example.extension.managed.model.ManagedExtension
import com.example.extension.orchestrator.ManagedMediaOrchestrator
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

/**
 * Publishes the exact bundled Users App extension definitions as the source-of-truth baseline.
 *
 * Each document is retained permanently (delete is forbidden), but its content is refreshed only
 * when the fingerprint of the locally bundled source changes. Thus app releases can correct the
 * restore baseline without allowing ordinary launches to overwrite it with identical content.
 * This does NOT write to /managed_extensions or /extensions; those remain Admin-controlled.
 */
class UserAppExtensionBaselineUploader(
    private val firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() }
) {
    data class UploadSummary(
        val createdDocuments: Int,
        val updatedDocuments: Int,
        val unchangedDocuments: Int
    )

    suspend fun uploadMissingBaseline(): UploadSummary {
        val firestore = firestoreProvider()
        val defaults = ManagedMediaOrchestrator.BUNDLED_DEFAULT_EXTENSIONS
        require(defaults.isNotEmpty()) { "Bundled Users App extension baseline is empty." }
        require(defaults.map { it.id.lowercase() }.distinct().size == defaults.size) {
            "Bundled Users App extension baseline contains duplicate IDs."
        }

        val collection = firestore.collection(COLLECTION_PATH)
        // A single transaction avoids partial updates and compares local code data to the
        // persisted source fingerprint. Identical source data is a no-op; changed source data
        // refreshes the existing document without deleting it.
        return firestore.runTransaction { transaction ->
            val refsAndSnapshots = defaults.map { extension ->
                val ref = collection.document(extension.id.lowercase())
                ref to transaction.get(ref)
            }
            var created = 0
            var updated = 0
            var unchanged = 0
            refsAndSnapshots.forEach { (ref, snapshot) ->
                val extension = defaults.first { it.id.equals(ref.id, ignoreCase = true) }
                val rawPayload = toRawBaselinePayload(extension)
                val fingerprint = sourceFingerprint(rawPayload)
                val persistedPayload = rawPayload + mapOf(
                    SOURCE_HASH_FIELD to fingerprint,
                    SOURCE_SCHEMA_VERSION_FIELD to SOURCE_SCHEMA_VERSION
                )
                when {
                    !snapshot.exists() -> {
                        transaction.set(ref, persistedPayload)
                        created++
                    }
                    snapshot.getString(SOURCE_HASH_FIELD) != fingerprint ||
                        sourcePayloadDiffers(snapshot.data.orEmpty(), rawPayload) -> {
                        // Compare both the recorded fingerprint and the actual persisted source
                        // fields. This also repairs stale/tampered fields whose old hash was left
                        // unchanged. The document is updated in place and is never deleted.
                        transaction.set(ref, persistedPayload)
                        updated++
                    }
                    else -> unchanged++
                }
            }
            UploadSummary(created, updated, unchanged)
        }.await()
    }

    companion object {
        const val COLLECTION_PATH = "extension_source_baseline"
        const val SOURCE_HASH_FIELD = "sourceHash"
        const val SOURCE_SCHEMA_VERSION_FIELD = "sourceSchemaVersion"
        const val SOURCE_SCHEMA_VERSION = 2L

        private fun sourcePayloadDiffers(
            persisted: Map<String, Any>,
            localRawPayload: Map<String, Any>
        ): Boolean {
            val persistedRaw = persisted.filterKeys { it !in setOf(SOURCE_HASH_FIELD, SOURCE_SCHEMA_VERSION_FIELD) }
            if (persistedRaw.keys != localRawPayload.keys) return true
            return sourceFingerprint(persistedRaw) != sourceFingerprint(localRawPayload)
        }

        /** Stable SHA-256 over the exact local source fields; excludes Firebase metadata. */
        fun sourceFingerprint(payload: Map<String, Any>): String {
            val canonical = payload.toSortedMap().entries.joinToString("\n") { (key, value) ->
                "$key=${when (value) {
                    is List<*> -> value.joinToString(",") { it.toString() }
                    else -> value.toString()
                }}"
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        /**
         * Serialize the same raw fields carried by BUNDLED_DEFAULT_EXTENSIONS.
         * Admin-only overrides (searchUrl/capabilities/createdAt) are deliberately omitted.
         */
        fun toRawBaselinePayload(extension: ManagedExtension): Map<String, Any> = linkedMapOf(
            "id" to extension.id.lowercase(),
            "extensionId" to extension.id.lowercase(),
            "scraperKey" to extension.scraperKey.trim().lowercase(),
            "name" to extension.name,
            "description" to extension.description,
            "baseUrl" to extension.baseUrl.trim(),
            "iconUrl" to extension.iconUrl,
            "runtimeApiVersion" to extension.runtimeApiVersion,
            "definitionVersion" to extension.definitionVersion,
            "minAppVersionCode" to extension.minAppVersionCode,
            "status" to extension.status.name,
            "priority" to extension.priority,
            "language" to extension.language,
            "contentTypes" to extension.contentTypes.map { it.name }.sorted(),
            "audioCapabilities" to extension.audioCapabilities.map { it.name }.sorted(),
            "enabled" to (extension.enabled && extension.status.name == "ACTIVE"),
            "updatedAt" to extension.updatedAt
        )
    }
}
