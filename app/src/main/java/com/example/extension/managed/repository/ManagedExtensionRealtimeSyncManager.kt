package com.example.extension.managed.repository

import com.example.extension.managed.model.*
import com.example.extension.managed.registry.ManagedExtensionRuntimeRegistry
import com.example.extension.managed.searchorder.SearchOrder
import com.example.extension.managed.trace.Phase05GLogger
import com.example.extension.managed.trace.Phase05HLogger
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 05M: Realtime Firestore Extension Sync Manager.
 *
 * Enforces Phase 05M Architecture:
 * 1. Single Listener Architecture (Rule 31 & 32): Exactly ONE listener at application/repository scope.
 * 2. Realtime sync for /managed_extensions and /config/search_order without app restarts or manual refresh.
 * 3. Observability tokens: EXT_SYNC_LISTENER_STARTED, EXT_SYNC_REMOTE_RECEIVED, EXT_SYNC_VALIDATION_STARTED,
 *    EXT_SYNC_VALIDATION_PASSED, EXT_SYNC_VALIDATION_FAILED, EXT_SYNC_SNAPSHOT_COMMITTED, EXT_SYNC_SNAPSHOT_REJECTED,
 *    EXT_SYNC_LKG_LOADED, EXT_SYNC_RECONNECT, EXT_SYNC_PERMISSION_DENIED, EXT_SYNC_NETWORK_FAILURE, EXT_SYNC_STATE_CHANGED.
 * 4. Empty data protection (Rule 11): Transient network/permission errors NEVER replace valid LKG with empty data.
 * 5. Admin authority (Rule 06 & 12): Disabled extensions remain strictly DISABLED.
 * 6. Search order canonicality (Rule 04 & 14): Search order is the sole source for playback order.
 */
class ManagedExtensionRealtimeSyncManager(
    private val registry: ManagedExtensionRuntimeRegistry = ManagedExtensionRuntimeRegistry.INSTANCE,
    private val cache: ManagedExtensionCache = SafeLocalMetadataCache(),
    private val firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
    private val syncScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        const val CANONICAL_COLLECTION = "managed_extensions"
        const val CONFIG_COLLECTION = "config"
        const val SEARCH_ORDER_DOC = "search_order"

        @Volatile
        private var instance: ManagedExtensionRealtimeSyncManager? = null

        fun getInstance(): ManagedExtensionRealtimeSyncManager =
            instance ?: synchronized(this) {
                instance ?: ManagedExtensionRealtimeSyncManager().also { instance = it }
            }

        fun setTestInstance(manager: ManagedExtensionRealtimeSyncManager) {
            instance = manager
        }
    }

    /** True while this manager is observing Firebase Auth state, even before sign-in. */
    private val isListening = AtomicBoolean(false)
    /** Firestore rules require an authenticated reader; do not attach the listeners before sign-in. */
    private val firestoreListenersAttached = AtomicBoolean(false)
    /** A transient startup/offline failure must not permanently skip source-baseline reconciliation. */
    private val baselineUploadInProgress = AtomicBoolean(false)
    @Volatile private var authStateListener: FirebaseAuth.AuthStateListener? = null
    @Volatile private var idTokenListener: FirebaseAuth.IdTokenListener? = null
    private var extensionsListenerRegistration: ListenerRegistration? = null
    private var searchOrderListenerRegistration: ListenerRegistration? = null

    /** The registry snapshot contains both extension and search-order data, so its revision
     * must increase monotonically across updates from either Firestore listener. */
    private fun nextRemoteRevision(sourceRevision: Long? = null): Long {
        val currentRevision = registry.getCurrentSnapshot().revision
        return maxOf(System.currentTimeMillis(), currentRevision + 1L, sourceRevision ?: 0L)
    }

    fun isListeningActive(): Boolean = firestoreListenersAttached.get()

    /**
     * Observes authentication from application startup, then attaches Firestore listeners only
     * after Firebase Auth has restored or established a session. The app's Firestore contract
     * intentionally requires authenticated reads; attaching at Application.onCreate before
     * login would otherwise produce PERMISSION_DENIED and leave the listeners stale forever.
     */
    fun startListening() {
        if (!isListening.compareAndSet(false, true)) return

        Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_LISTENER_STARTED; waiting for authenticated session")

        val auth = try {
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            Phase05HLogger.log("SYNC", "realtime", "Firebase Auth unavailable while starting listeners: ${e.message}")
            isListening.set(false)
            return
        }

        val stateObserver = FirebaseAuth.AuthStateListener { currentAuth ->
            syncWithAuthState(currentAuth)
        }
        val tokenObserver = FirebaseAuth.IdTokenListener { currentAuth ->
            // Re-evaluate after token refresh, including a delayed sign-in restoration.
            syncWithAuthState(currentAuth)
        }
        authStateListener = stateObserver
        idTokenListener = tokenObserver
        auth.addAuthStateListener(stateObserver)
        auth.addIdTokenListener(tokenObserver)
    }

    private fun syncWithAuthState(auth: FirebaseAuth) {
        if (auth.currentUser == null) {
            detachFirestoreListeners()
            firestoreListenersAttached.set(false)
            // Keep bundled/LKG data intact while signed out. If startup cache only carried
            // SearchOrder metadata, seed the same local fallback used by the orchestrator.
            val current = registry.getCurrentSnapshot()
            if (current.extensions.isEmpty()) {
                val fallback = com.example.extension.orchestrator.ManagedMediaOrchestrator.BUNDLED_DEFAULT_EXTENSIONS
                if (fallback.isNotEmpty()) {
                    registry.commitSnapshot(
                        current.copy(
                            extensions = fallback,
                            state = GlobalExtensionConfigState.READY,
                            source = SnapshotSource.LOCAL_LKG
                        )
                    )
                }
            }
            return
        }

        // Keep the baseline upload independent from realtime listener registration. A temporary
        // offline/rules startup failure retries with bounded backoff instead of silently leaving
        // Restore Original unusable until the next user login.
        scheduleBaselineUpload(auth)

        if (!firestoreListenersAttached.compareAndSet(false, true)) return
        syncScope.launch {
            // Sign-out can race with dispatch to the IO scope; re-check before registering.
            if (auth.currentUser == null) {
                firestoreListenersAttached.set(false)
                return@launch
            }
            if (!attachListeners()) {
                firestoreListenersAttached.set(false)
            }
        }
    }

    private fun scheduleBaselineUpload(auth: FirebaseAuth) {
        if (!baselineUploadInProgress.compareAndSet(false, true)) return
        syncScope.launch {
            var attempt = 0
            val retryDelaysMs = longArrayOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
            try {
                while (isActive && auth.currentUser != null) {
                    try {
                        val result = UserAppExtensionBaselineUploader(firestoreProvider).uploadMissingBaseline()
                        Phase05HLogger.log(
                            "SYNC",
                            "source-baseline",
                            "EXT_SOURCE_BASELINE_UPLOAD: created=${result.createdDocuments}, updated=${result.updatedDocuments}, unchanged=${result.unchangedDocuments}"
                        )
                        return@launch
                    } catch (e: Exception) {
                        // Permission/configuration failures are not transient network failures.
                        // Stop quietly and retry on a later Auth/token event after rules are fixed.
                        if (e is FirebaseFirestoreException && e.code in setOf(
                                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                                FirebaseFirestoreException.Code.UNAUTHENTICATED
                            )
                        ) {
                            Phase05HLogger.log(
                                "SYNC",
                                "source-baseline",
                                "EXT_SOURCE_BASELINE_UPLOAD_FAILED: ${e.code}: ${e.message}; retry after Auth/rules change"
                            )
                            return@launch
                        }
                        val retryDelay = retryDelaysMs[attempt.coerceAtMost(retryDelaysMs.lastIndex)]
                        attempt++
                        Phase05HLogger.log(
                            "SYNC",
                            "source-baseline",
                            "EXT_SOURCE_BASELINE_UPLOAD_RETRY: attempt=$attempt delayMs=$retryDelay " +
                                "${e.javaClass.simpleName}: ${e.message}"
                        )
                        delay(retryDelay)
                    }
                }
            } finally {
                baselineUploadInProgress.set(false)
            }
        }
    }

    /** Returns false only if Firestore/listener registration could not be established. */
    private fun attachListeners(): Boolean {
        val firestore = try {
            firestoreProvider()
        } catch (e: Exception) {
            Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_NETWORK_FAILURE while initializing Firestore: ${e.message}")
            return false
        }

        var registrationFailed = false
        try {
            extensionsListenerRegistration?.remove()
            extensionsListenerRegistration = firestore.collection(CANONICAL_COLLECTION)
                .addSnapshotListener { snapshot, error ->
                    handleExtensionsSnapshot(snapshot, error)
                }
        } catch (e: Exception) {
            registrationFailed = true
            Phase05HLogger.log("SYNC", "realtime", "Failed to attach extensions listener: ${e.message}")
        }

        try {
            searchOrderListenerRegistration?.remove()
            searchOrderListenerRegistration = firestore.collection(CONFIG_COLLECTION)
                .document(SEARCH_ORDER_DOC)
                .addSnapshotListener { snapshot, error ->
                    handleSearchOrderSnapshot(snapshot, error)
                }
        } catch (e: Exception) {
            registrationFailed = true
            Phase05HLogger.log("SYNC", "realtime", "Failed to attach search_order listener: ${e.message}")
        }

        if (registrationFailed) {
            detachFirestoreListeners()
            return false
        }
        Phase05HLogger.log("SYNC", "realtime", "Authenticated Firestore listeners attached")
        return true
    }

    private fun detachFirestoreListeners() {
        extensionsListenerRegistration?.remove()
        extensionsListenerRegistration = null
        searchOrderListenerRegistration?.remove()
        searchOrderListenerRegistration = null
    }

    private fun handleExtensionsSnapshot(
        snapshot: com.google.firebase.firestore.QuerySnapshot?,
        error: FirebaseFirestoreException?
    ) {
        if (error != null) {
            val code = error.code
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_PERMISSION_DENIED: ${error.message}")
                detachFirestoreListeners()
                firestoreListenersAttached.set(false)
            } else {
                Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_NETWORK_FAILURE: ${error.message}")
            }

            // Rule 11: Empty Data Protection — NEVER wipe out local registry on sync errors!
            val current = registry.getCurrentSnapshot()
            if (current.extensions.isEmpty()) {
                val lkg = cache.loadSnapshot()
                if (lkg != null && lkg.extensions.isNotEmpty()) {
                    Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_LKG_LOADED: retaining LKG during sync error")
                    registry.commitSnapshot(lkg.copy(state = GlobalExtensionConfigState.READY))
                } else {
                    registry.commitSnapshot(current.copy(state = GlobalExtensionConfigState.GLOBAL_CONFIG_UNAVAILABLE))
                }
            }
            return
        }

        if (snapshot == null) return

        Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_REMOTE_RECEIVED: docs=${snapshot.documents.size}")
        Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_VALIDATION_STARTED")

        val rawDtos = mutableListOf<ManagedExtensionDto>()
        for (doc in snapshot.documents) {
            try {
                val dto = ManagedExtensionDto.fromDocument(doc)
                if (dto.id != null) {
                    rawDtos.add(dto)
                }
            } catch (e: Exception) {
                Phase05HLogger.log("SYNC", "realtime", "Malformed extension document ${doc.id}: ${e.message}")
            }
        }

        // Validate remote entries strictly while preserving device-local enable/disable preferences.
        val existingById = registry.getAllExtensions().associateBy { it.id.trim().lowercase() }
        val validExtensions = mutableListOf<ManagedExtension>()
        var validationFailedCount = 0

        for (dto in rawDtos) {
            val localEnabled = existingById[dto.id.orEmpty().trim().lowercase()]?.userEnabled ?: true
            val domainExt = ManagedExtensionMapper.toDomain(dto, localUserEnabled = localEnabled)
            when (val result = ManagedExtensionValidator.validateRemoteEntry(domainExt)) {
                is ManagedExtensionValidator.ValidationResult.Valid -> {
                    validExtensions.add(domainExt)
                }
                is ManagedExtensionValidator.ValidationResult.Invalid -> {
                    validationFailedCount++
                    Phase05HLogger.log("SYNC", "realtime", "Rejected extension ${domainExt.id}: ${result.error}")
                }
            }
        }

        if (validationFailedCount > 0) {
            Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_VALIDATION_FAILED: rejected=$validationFailedCount")
        }

        if (validExtensions.isNotEmpty()) {
            Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_VALIDATION_PASSED: valid=${validExtensions.size}")
            
            val remoteIds = rawDtos.mapNotNull { it.id?.trim()?.lowercase() }.toSet()
            val currentIds = existingById.keys
            val remotelyDisabledIds = rawDtos
                .filter { it.status.equals("DISABLED", ignoreCase = true) || it.status.equals("DEPRECATED", ignoreCase = true) }
                .mapNotNull { it.id?.trim()?.lowercase() }
                .toSet()
            val removedIds = currentIds.minus(remoteIds)
            cache.recordTombstones(remotelyDisabledIds, removedIds)

            // Deduplicate and retain priority sorting
            val deduplicated = validExtensions
                .groupBy { it.id }
                .map { (_, group) ->
                    group.maxWithOrNull(compareBy({ it.updatedAt }, { it.priority })) ?: group.first()
                }

            val maxRevision = snapshot.documents.mapNotNull { doc ->
                doc.getLong("updatedAt")
            }.maxOrNull() ?: System.currentTimeMillis()

            registry.commitRemoteUpdate(
                newExtensions = deduplicated,
                revision = nextRemoteRevision(maxRevision),
                timestamp = System.currentTimeMillis()
            )
        } else if (snapshot.documents.isEmpty()) {
            // Rule 11: Only accept genuine empty catalog if authoritative from server
            if (!snapshot.metadata.isFromCache) {
                Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_VALIDATION_PASSED: genuine 0 extensions from remote")
                registry.commitRemoteUpdate(
                    newExtensions = emptyList(),
                    revision = nextRemoteRevision(),
                    timestamp = System.currentTimeMillis()
                )
            }
        }
    }

    private fun handleSearchOrderSnapshot(
        snapshot: com.google.firebase.firestore.DocumentSnapshot?,
        error: FirebaseFirestoreException?
    ) {
        if (error != null) {
            val code = error.code
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_PERMISSION_DENIED (search_order): ${error.message}")
                detachFirestoreListeners()
                firestoreListenersAttached.set(false)
            } else {
                Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_NETWORK_FAILURE (search_order): ${error.message}")
            }
            return
        }

        if (snapshot == null) return
        if (!snapshot.exists() && snapshot.metadata.isFromCache) {
            // An absent document in the device cache is not proof of a server-side deletion.
            // Preserve the last-known order and wait for the authoritative server snapshot.
            Phase05HLogger.log("SYNC", "realtime", "Ignoring cache-only missing search_order; awaiting server snapshot")
            return
        }
        if (!snapshot.exists()) {
            // Deleting the Admin order document is a remote configuration change too.
            // Commit an explicitly empty, marked order only after the server confirms deletion.
            val deletedOrder = SearchOrder(updatedAt = System.currentTimeMillis())
            registry.commitRemoteUpdate(
                newSearchOrder = deletedOrder,
                revision = nextRemoteRevision(),
                timestamp = System.currentTimeMillis()
            )
            Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_REMOTE_RECEIVED (search_order deleted; empty order committed)")
            return
        }

        val data = snapshot.data ?: return
        Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_REMOTE_RECEIVED (search_order)")

        fun parseStringList(key: String): List<String>? {
            if (!data.containsKey(key) || data[key] == null) return null
            val raw = data[key] as? List<*> ?: return emptyList()
            return raw.mapNotNull { item ->
                val str = item?.toString()?.trim()
                if (!str.isNullOrBlank()) str else null
            }
        }

        val arabicDubbedList = parseStringList("arabicDubbed")
            ?: parseStringList("arabicDub")
            ?: parseStringList("arabic_dub")
            ?: emptyList()

        val englishDubbedList = parseStringList("englishDubbed")
            ?: parseStringList("englishDub")
            ?: parseStringList("english_dub")
            ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val dubbingConfig = data["dubbingConfig"] as? Map<String, Any?>

        val rawArabicStatus = (dubbingConfig?.get("arabicDubbedStatus") as? String)
            ?: (data["arabicDubbedStatus"] as? String)
        val arabicDubbedStatus = when (rawArabicStatus?.trim()?.uppercase()) {
            "MAINTENANCE" -> com.example.domain.models.GroupStatus.MAINTENANCE
            "DISABLED" -> com.example.domain.models.GroupStatus.DISABLED
            else -> com.example.domain.models.GroupStatus.ACTIVE
        }

        val rawEnglishStatus = (dubbingConfig?.get("englishDubbedStatus") as? String)
            ?: (data["englishDubbedStatus"] as? String)
        val englishDubbedStatus = when (rawEnglishStatus?.trim()?.uppercase()) {
            "ACTIVE" -> com.example.domain.models.GroupStatus.ACTIVE
            "DISABLED" -> com.example.domain.models.GroupStatus.DISABLED
            else -> com.example.domain.models.GroupStatus.MAINTENANCE
        }

        val searchOrder = SearchOrder(
            movie = parseStringList("movie") ?: emptyList(),
            tv = parseStringList("tv"),
            series = parseStringList("series") ?: emptyList(),
            anime = parseStringList("anime") ?: emptyList(),
            arabicDubbed = arabicDubbedList,
            englishDubbed = englishDubbedList,
            arabicDubbedStatus = arabicDubbedStatus,
            englishDubbedStatus = englishDubbedStatus,
            // Record that a remote SearchOrder document was received even when all
            // category lists are intentionally empty.
            updatedAt = when (val rawUpdatedAt = data["updatedAt"]) {
                is com.google.firebase.Timestamp -> rawUpdatedAt.toDate().time
                is Number -> rawUpdatedAt.toLong()
                else -> System.currentTimeMillis()
            },
            updatedBy = data["updatedBy"] as? String
        )

        val revision = nextRemoteRevision(searchOrder.updatedAt)
        Phase05HLogger.log(
            "SYNC",
            "realtime",
            "EXT_SYNC_VALIDATION_PASSED (search_order): anime=${searchOrder.anime}, movie=${searchOrder.movie}"
        )

        registry.commitRemoteUpdate(
            newSearchOrder = searchOrder,
            revision = revision,
            timestamp = System.currentTimeMillis()
        )
    }

    fun reconnect() {
        stopListening()
        Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_RECONNECT")
        startListening()
    }

    fun stopListening() {
        detachFirestoreListeners()
        firestoreListenersAttached.set(false)
        try {
            FirebaseAuth.getInstance().also { auth ->
                authStateListener?.let { auth.removeAuthStateListener(it) }
                idTokenListener?.let { auth.removeIdTokenListener(it) }
            }
        } catch (_: Exception) {
            // Firebase may already have been torn down in tests or during process shutdown.
        }
        authStateListener = null
        idTokenListener = null
        isListening.set(false)
        Phase05HLogger.log("SYNC", "realtime", "EXT_SYNC_LISTENER_STOPPED")
    }
}
