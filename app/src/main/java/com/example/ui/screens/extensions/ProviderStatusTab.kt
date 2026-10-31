package com.example.ui.screens.extensions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.extension.flixquest.FlixQuestDiagnosticsCache
import com.example.extension.flixquest.FlixQuestProviderDiagnostics
import com.example.extension.flixquest.FlixQuestScraperClient
import com.example.extension.managed.health.ManagedProviderHealth
import com.example.extension.managed.health.ProviderHealthMonitor
import com.example.extension.managed.health.ProviderReachability
import com.example.extension.managed.model.ManagedExtension
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ProviderStatusTab(extensions: List<ManagedExtension>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localResults by ProviderHealthMonitor.results.collectAsState()
    val providerFingerprint = remember(extensions) {
        extensions.map { "${it.id}:${it.baseUrl}:${it.enabled}:${it.userEnabled}:${it.status.name}" }
    }
    val remoteConfigured = remember { FlixQuestScraperClient.isApiConfigured() }

    var localChecking by remember { mutableStateOf(false) }
    var remoteChecking by remember { mutableStateOf(false) }
    var remoteDiagnostics by remember { mutableStateOf<FlixQuestProviderDiagnostics?>(null) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var remoteLoadedAt by remember { mutableStateOf<Long?>(null) }

    suspend fun refreshLocal(force: Boolean) {
        if (localChecking) return
        if (!force && ProviderHealthMonitor.isFreshFor(extensions)) return
        localChecking = true
        try {
            ProviderHealthMonitor.refresh(context, extensions)
        } finally {
            localChecking = false
        }
    }

    suspend fun refreshRemote() {
        if (!remoteConfigured || remoteChecking) return
        remoteChecking = true
        remoteError = null
        try {
            val result = FlixQuestScraperClient.loadProviderDiagnostics()
            result.fold(
                onSuccess = { diagnostics ->
                    remoteDiagnostics = diagnostics
                    remoteLoadedAt = System.currentTimeMillis()
                    FlixQuestDiagnosticsCache.write(context, diagnostics)
                },
                onFailure = { error ->
                    remoteError = error.message ?: context.getString(R.string.provider_health_request_failed)
                }
            )
        } finally {
            remoteChecking = false
        }
    }

    LaunchedEffect(providerFingerprint) {
        ProviderHealthMonitor.restoreCached(context)
        refreshLocal(force = false)
    }

    LaunchedEffect(remoteConfigured) {
        remoteDiagnostics = FlixQuestDiagnosticsCache.read(context)
        refreshRemote()
    }

    val localById = remember(localResults) { localResults.associateBy { it.providerId } }
    val flixHealthById = remember(remoteDiagnostics?.health?.providers) {
        remoteDiagnostics?.health?.providers.orEmpty().associateBy { it.id }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.provider_status_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.provider_status_subtitle),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            refreshLocal(force = true)
                            refreshRemote()
                        }
                    },
                    enabled = !localChecking && !remoteChecking
                ) {
                    if (localChecking || remoteChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(21.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.provider_refresh))
                    }
                }
            }
        }

        if (localChecking || remoteChecking) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        item {
            ProviderInfoNote(
                text = stringResource(R.string.provider_health_disclaimer),
                icon = Icons.Default.Speed
            )
        }

        item {
            SectionHeading(
                title = stringResource(R.string.cinestream_provider_registry),
                count = extensions.size
            )
        }

        if (extensions.isEmpty()) {
            item {
                ProviderInfoNote(
                    text = stringResource(R.string.provider_no_configured),
                    icon = Icons.Default.CloudOff
                )
            }
        } else {
            val online = extensions.count { localById[it.id]?.reachability == ProviderReachability.ONLINE }
            val restricted = extensions.count { localById[it.id]?.reachability == ProviderReachability.PROTECTED }
            val unavailable = extensions.count {
                localById[it.id]?.reachability in setOf(ProviderReachability.OFFLINE, ProviderReachability.DEGRADED)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniMetric(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.provider_online),
                        value = online,
                        tint = Color(0xFF43A047)
                    )
                    MiniMetric(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.provider_restricted),
                        value = restricted,
                        tint = Color(0xFFFFA000)
                    )
                    MiniMetric(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.provider_unavailable),
                        value = unavailable,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            items(extensions, key = { "managed-${it.id}" }) { extension ->
                ManagedProviderHealthCard(
                    extension = extension,
                    health = localById[extension.id]
                )
            }
        }

        item {
            SectionHeading(
                title = stringResource(R.string.flixquest_provider_registry),
                count = remoteDiagnostics?.providers?.size
            )
        }

        if (!remoteConfigured) {
            item {
                ProviderInfoNote(
                    text = stringResource(R.string.flixquest_api_not_configured),
                    icon = Icons.Default.Security
                )
            }
        } else if (remoteDiagnostics == null && remoteError != null) {
            item {
                ProviderInfoNote(
                    text = remoteError ?: stringResource(R.string.provider_health_request_failed),
                    icon = Icons.Default.CloudOff
                )
            }
        } else if (remoteDiagnostics == null && remoteChecking) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        } else if (remoteDiagnostics != null) {
            val diagnostics = remoteDiagnostics!!
            val snapshot = diagnostics.health
            if (snapshot != null) {
                item {
                    FlixQuestHealthSummary(
                        diagnostics = diagnostics,
                        checkedAt = snapshot.updatedAt
                    )
                }
                item {
                    ProviderInfoNote(
                        text = stringResource(R.string.flixquest_snapshot_note),
                        icon = Icons.Default.Speed
                    )
                }
            } else {
                item {
                    ProviderInfoNote(
                        text = stringResource(R.string.flixquest_health_snapshot_unavailable),
                        icon = Icons.Default.WarningAmber
                    )
                }
            }
            items(diagnostics.providers, key = { "flixquest-${it.id}" }) { provider ->
                val health = flixHealthById[provider.id]
                FlixQuestProviderCard(provider = provider, health = health)
            }
            if (remoteLoadedAt != null) {
                item {
                    Text(
                        text = stringResource(R.string.provider_report_loaded_at, formatTimestamp(remoteLoadedAt!!)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }
            }
        }

        if (remoteError != null && remoteDiagnostics != null) {
            item {
                ProviderInfoNote(
                    text = stringResource(R.string.provider_cached_report_notice),
                    icon = Icons.Default.WarningAmber
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, count: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.width(8.dp))
        if (count != null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    text = count.toString(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: Int, tint: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = tint)
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProviderInfoNote(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .28f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ManagedProviderHealthCard(extension: ManagedExtension, health: ManagedProviderHealth?) {
    val enabled = extension.enabled && extension.userEnabled && extension.status.name == "ACTIVE"
    val (label, tint) = when (health?.reachability) {
        ProviderReachability.ONLINE -> stringResource(R.string.provider_status_online) to Color(0xFF43A047)
        ProviderReachability.PROTECTED -> stringResource(R.string.provider_status_restricted) to Color(0xFFFFA000)
        ProviderReachability.DEGRADED -> stringResource(R.string.provider_status_degraded) to Color(0xFFFFA000)
        ProviderReachability.OFFLINE -> stringResource(R.string.provider_status_offline) to MaterialTheme.colorScheme.error
        else -> stringResource(R.string.provider_status_unknown) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .32f)),
        shape = RoundedCornerShape(15.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HealthIcon(health?.reachability)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(extension.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(extension.baseUrl, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusPill(label, tint)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (enabled) stringResource(R.string.provider_admin_enabled) else stringResource(R.string.provider_admin_disabled),
                    fontSize = 10.sp,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                health?.responseTimeMs?.let {
                    Text(stringResource(R.string.provider_response_time, it), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                health?.responseCode?.let {
                    Spacer(Modifier.width(8.dp))
                    Text("HTTP $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                health?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.provider_last_checked, formatTimestamp(it.checkedAt)),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FlixQuestHealthSummary(diagnostics: FlixQuestProviderDiagnostics, checkedAt: String?) {
    val snapshot = diagnostics.health ?: return
    val percentage = (snapshot.availability * 100f).toInt()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(66.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = snapshot.availability,
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 6.dp,
                        color = if (snapshot.offline == 0) Color(0xFF43A047) else MaterialTheme.colorScheme.error,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text("$percentage%", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (snapshot.offline == 0 && snapshot.total > 0) stringResource(R.string.provider_health_all_working) else stringResource(R.string.provider_health_degraded),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    checkedAt?.let {
                        Text(stringResource(R.string.provider_last_server_check, it), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (snapshot.intervalMs > 0) {
                        Text(
                            stringResource(R.string.provider_health_interval, (snapshot.intervalMs / 60_000L).toInt()),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniMetric(stringResource(R.string.provider_online), snapshot.online, Color(0xFF43A047), Modifier.weight(1f))
                MiniMetric(stringResource(R.string.provider_offline), snapshot.offline, MaterialTheme.colorScheme.error, Modifier.weight(1f))
                MiniMetric(stringResource(R.string.provider_total), snapshot.total, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            }
            snapshot.methodology?.takeIf(String::isNotBlank)?.let {
                Text(it, fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FlixQuestProviderCard(
    provider: com.example.extension.flixquest.FlixQuestProvider,
    health: com.example.extension.flixquest.FlixQuestProviderHealthEntry?
) {
    val label: String
    val tint: Color
    when {
        !provider.enabled -> {
            label = stringResource(R.string.provider_status_disabled)
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
        health == null -> {
            label = stringResource(R.string.provider_status_unknown)
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
        health.online -> {
            label = stringResource(R.string.provider_status_online)
            tint = Color(0xFF43A047)
        }
        else -> {
            label = stringResource(R.string.provider_status_offline)
            tint = MaterialTheme.colorScheme.error
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .32f)),
        shape = RoundedCornerShape(15.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HealthIcon(if (health?.online == true) ProviderReachability.ONLINE else if (health != null) ProviderReachability.OFFLINE else null)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(provider.alias.ifBlank { provider.name }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(provider.id, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                provider.content.takeIf(String::isNotBlank)?.let {
                    Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                StatusPill(label, tint)
                if (health != null && health.requestTimeMs >= 0) {
                    Text(stringResource(R.string.provider_response_time, health.requestTimeMs), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HealthIcon(state: ProviderReachability?) {
    val (icon, tint) = when (state) {
        ProviderReachability.ONLINE -> Icons.Default.CheckCircle to Color(0xFF43A047)
        ProviderReachability.PROTECTED -> Icons.Default.Security to Color(0xFFFFA000)
        ProviderReachability.DEGRADED -> Icons.Default.WarningAmber to Color(0xFFFFA000)
        ProviderReachability.OFFLINE -> Icons.Default.CloudOff to MaterialTheme.colorScheme.error
        else -> Icons.Default.WarningAmber to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
}

@Composable
private fun StatusPill(label: String, tint: Color) {
    Surface(color = tint.copy(alpha = .12f), shape = RoundedCornerShape(7.dp)) {
        Text(label, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 10.sp, color = tint, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatTimestamp(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
