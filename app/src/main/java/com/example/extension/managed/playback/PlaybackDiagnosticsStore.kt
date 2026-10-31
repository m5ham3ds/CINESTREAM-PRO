package com.example.extension.managed.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Admin-only playback diagnostics. This is in-memory only and never writes to Firebase. */
enum class PlaybackDiagnosticStage(val labelAr: String) {
    SOURCE_REACHABLE("استجابة الموقع"),
    WORK_FOUND("العثور على العمل"),
    EPISODE_FOUND("العثور على الحلقة المطلوبة"),
    SERVERS_FOUND("العثور على سيرفرات التشغيل"),
    QUALITIES_EXTRACTED("استخراج المصدر والجودات")
}

enum class PlaybackDiagnosticStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

data class PlaybackDiagnosticCheck(
    val status: PlaybackDiagnosticStatus = PlaybackDiagnosticStatus.PENDING,
    val detail: String = "بانتظار هذه المرحلة"
)

data class PlaybackProviderDiagnostic(
    val extensionId: String,
    val extensionName: String,
    val checks: Map<PlaybackDiagnosticStage, PlaybackDiagnosticCheck> = PlaybackDiagnosticStage.values().associateWith { PlaybackDiagnosticCheck() },
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

data class PlaybackDiagnosticsState(
    val mediaId: String = "",
    val mediaTitle: String = "",
    val isRunning: Boolean = false,
    val summary: String = "",
    val providers: List<PlaybackProviderDiagnostic> = emptyList(),
    val startedAt: Long = 0L
)

object PlaybackDiagnosticsStore {
    private val _state = MutableStateFlow(PlaybackDiagnosticsState())
    val state: StateFlow<PlaybackDiagnosticsState> = _state.asStateFlow()

    /** Prevents duplicate player entry points from resetting an active diagnostic run. */
    @Synchronized
    fun begin(mediaId: String, title: String): Boolean {
        val current = _state.value
        // A completed run for the same media is sticky. Recomposition, duplicate player
        // entry points, network callbacks, and late coroutines must not silently start it
        // again. Only an explicit user retry may clear this terminal state.
        if (current.isRunning) return false
        val sameMediaId = current.mediaId.isNotBlank() && current.mediaId == mediaId
        val normalizeTitle: (String) -> String = { value ->
            value.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        }
        val sameTitle = current.mediaTitle.isNotBlank() &&
            normalizeTitle(current.mediaTitle) == normalizeTitle(title)
        // Some entry points provide a database ID while others provide an episode/media ID.
        // Treat the same normalized title as the same diagnostic target so a duplicate
        // callback cannot silently replace terminal results when IDs differ.
        if (current.startedAt > 0L && (sameMediaId || sameTitle)) return false
        _state.value = PlaybackDiagnosticsState(
            mediaId = mediaId,
            mediaTitle = title,
            isRunning = true,
            summary = "بدأ فحص مصادر التشغيل",
            startedAt = System.currentTimeMillis()
        )
        return true
    }

    /** Call only from an explicit Retry button, never from network/recomposition callbacks. */
    @Synchronized
    fun allowExplicitRetry(mediaId: String) {
        val current = _state.value
        // This method is invoked only by explicit retry controls. Do not reject a retry
        // because the UI's media ID differs from the ID captured by the prior entry point.
        if (current.isRunning || mediaId.isBlank()) return
        _state.value = PlaybackDiagnosticsState()
    }

    @Synchronized
    fun registerProviders(providers: List<Pair<String, String>>) {
        val current = _state.value
        if (!current.isRunning) return
        _state.value = current.copy(
            providers = providers.distinctBy { it.first }.map { (id, name) ->
                PlaybackProviderDiagnostic(extensionId = id, extensionName = name)
            },
            summary = "تم ترتيب ${providers.distinctBy { it.first }.size} مصدرًا حسب إعدادات الإضافات"
        )
    }

    @Synchronized
    fun skipProvider(extensionId: String, detail: String) {
        val current = _state.value
        if (!current.isRunning) return
        _state.value = current.copy(providers = current.providers.map { provider ->
            if (provider.extensionId != extensionId) provider else provider.copy(
                checks = PlaybackDiagnosticStage.values().associateWith { PlaybackDiagnosticCheck(PlaybackDiagnosticStatus.SKIPPED, detail) },
                lastUpdatedAt = System.currentTimeMillis()
            )
        })
    }

    /** Reachability is a one-way fact for this run: later title-query failures must not
     * rewrite a successful site response into a connection failure. A later success may
     * still correct an earlier transient failure. */
    @Synchronized
    fun markSourceReachability(extensionId: String, status: PlaybackDiagnosticStatus, detail: String) {
        val current = _state.value
        if (!current.isRunning) return
        val existing = current.providers.firstOrNull { it.extensionId == extensionId }
            ?.checks?.get(PlaybackDiagnosticStage.SOURCE_REACHABLE)?.status
        if (existing == PlaybackDiagnosticStatus.SUCCESS && status == PlaybackDiagnosticStatus.FAILED) return
        mark(extensionId, PlaybackDiagnosticStage.SOURCE_REACHABLE, status, detail)
    }

    @Synchronized
    fun mark(extensionId: String, stage: PlaybackDiagnosticStage, status: PlaybackDiagnosticStatus, detail: String) {
        val current = _state.value
        if (!current.isRunning) return
        val previousStatus = current.providers.firstOrNull { it.extensionId == extensionId }?.checks?.get(stage)?.status
        // Stage results are monotonic within one run: a later fallback-query failure
        // cannot rewrite a previously verified success into a failure.
        if (previousStatus == PlaybackDiagnosticStatus.SUCCESS && status == PlaybackDiagnosticStatus.FAILED) return
        _state.value = current.copy(
            providers = current.providers.map { provider ->
                if (provider.extensionId != extensionId) provider
                else provider.copy(
                    checks = provider.checks + (stage to PlaybackDiagnosticCheck(status, detail)),
                    lastUpdatedAt = System.currentTimeMillis()
                )
            },
            summary = detail
        )
    }

    @Synchronized
    fun markRunningChecksFailed(extensionId: String, detail: String) {
        val current = _state.value
        if (!current.isRunning) return
        _state.value = current.copy(providers = current.providers.map { provider ->
            if (provider.extensionId != extensionId) provider else provider.copy(
                checks = provider.checks.mapValues { (_, check) ->
                    if (check.status == PlaybackDiagnosticStatus.RUNNING) PlaybackDiagnosticCheck(PlaybackDiagnosticStatus.FAILED, detail) else check
                },
                lastUpdatedAt = System.currentTimeMillis()
            )
        })
    }

    @Synchronized
    fun finish(success: Boolean, summary: String) {
        val current = _state.value
        // A terminal transition is final. Duplicate completion callbacks must not
        // overwrite the summary or mutate results after the run has ended.
        if (!current.isRunning) return
        _state.value = current.copy(
            isRunning = false,
            summary = summary,
            providers = current.providers.map { provider -> provider.copy(
                checks = provider.checks.mapValues { (_, check) ->
                    when (check.status) {
                        PlaybackDiagnosticStatus.PENDING -> PlaybackDiagnosticCheck(PlaybackDiagnosticStatus.SKIPPED, if (success) "لم تعد هناك حاجة لتجربة هذا المصدر" else "لم تصل العملية إلى هذه المرحلة")
                        PlaybackDiagnosticStatus.RUNNING -> PlaybackDiagnosticCheck(PlaybackDiagnosticStatus.FAILED, "انتهت العملية قبل اكتمال هذه المرحلة")
                        else -> check
                    }
                }
            ) }
        )
    }
}
