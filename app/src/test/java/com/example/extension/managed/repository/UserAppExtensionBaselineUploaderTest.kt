package com.example.extension.managed.repository

import com.example.extension.orchestrator.ManagedMediaOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAppExtensionBaselineUploaderTest {
    @Test
    fun bundledBaselineContainsExpectedFiveCanonicalSources() {
        assertEquals(
            setOf("qfilm", "egydead", "witanime", "anime4up", "animeblkom"),
            ManagedMediaOrchestrator.BUNDLED_DEFAULT_EXTENSIONS.map { it.id.lowercase() }.toSet()
        )
    }

    @Test
    fun sourceFingerprintIsStableAndChangesWhenBundledSourceChanges() {
        val extension = ManagedMediaOrchestrator.BUNDLED_DEFAULT_EXTENSIONS.first { it.id.equals("qfilm", true) }
        val payload = UserAppExtensionBaselineUploader.toRawBaselinePayload(extension)
        val first = UserAppExtensionBaselineUploader.sourceFingerprint(payload)
        assertEquals(first, UserAppExtensionBaselineUploader.sourceFingerprint(payload.toMap()))
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertFalse(first == UserAppExtensionBaselineUploader.sourceFingerprint(payload + ("name" to "Changed source name")))
    }

    @Test
    fun rawPayloadContainsOnlyOriginalSourceFields() {
        ManagedMediaOrchestrator.BUNDLED_DEFAULT_EXTENSIONS.forEach { extension ->
            val payload = UserAppExtensionBaselineUploader.toRawBaselinePayload(extension)
            assertEquals(extension.id.lowercase(), payload["id"])
            assertEquals(extension.id.lowercase(), payload["extensionId"])
            assertEquals(extension.baseUrl, payload["baseUrl"])
            assertTrue(payload.containsKey("contentTypes"))
            assertTrue(payload.containsKey("audioCapabilities"))
            assertFalse(payload.containsKey("searchUrl"))
            assertFalse(payload.containsKey("capabilities"))
            assertFalse(payload.containsKey("createdAt"))
        }
    }
}
