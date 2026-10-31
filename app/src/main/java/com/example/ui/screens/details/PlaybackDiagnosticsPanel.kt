package com.example.ui.screens.details

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.UserSecurityManager
import com.example.extension.managed.playback.PlaybackDiagnosticStage
import com.example.extension.managed.playback.PlaybackDiagnosticStatus
import com.example.extension.managed.playback.PlaybackDiagnosticsStore

/** Visible only to an authenticated canonical admin; diagnostic state remains local/in-memory. */
@Composable
fun PlaybackDiagnosticsPanel(mediaId: String, modifier: Modifier = Modifier) {
    val isAdmin by UserSecurityManager.isAdminAuthorityFlow.collectAsState()
    val state by PlaybackDiagnosticsStore.state.collectAsState()
    if (!isAdmin || state.mediaId != mediaId) return

    val successColor = Color(0xFF2EAD63)
    val failureColor = Color(0xFFE34B4B)
    val runningColor = Color(0xFFFFB020)
    val idleColor = MaterialTheme.colorScheme.outline

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تشخيص تشغيل الإضافات", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Surface(
                    color = if (state.isRunning) runningColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        if (state.isRunning) "قيد التنفيذ" else "انتهت المحاولة",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = if (state.isRunning) runningColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Text(state.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            if (state.providers.isEmpty()) {
                Text("لم تُسجّل أي إضافة ضمن ترتيب البحث المحلي لهذا النوع من المحتوى.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }

            state.providers.forEach { provider ->
                Column(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(provider.extensionName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PlaybackDiagnosticStage.values().forEach { stage ->
                            val check = provider.checks[stage]
                            val status = check?.status ?: PlaybackDiagnosticStatus.PENDING
                            val tint = when (status) {
                                PlaybackDiagnosticStatus.SUCCESS -> successColor
                                PlaybackDiagnosticStatus.FAILED -> failureColor
                                PlaybackDiagnosticStatus.RUNNING -> runningColor
                                PlaybackDiagnosticStatus.PENDING, PlaybackDiagnosticStatus.SKIPPED -> idleColor
                            }
                            Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(
                                    modifier = Modifier.size(30.dp).background(tint.copy(alpha = 0.13f), RoundedCornerShape(7.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    when (status) {
                                        PlaybackDiagnosticStatus.SUCCESS -> Text("✓", color = successColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                        PlaybackDiagnosticStatus.FAILED -> Text("×", color = failureColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                        PlaybackDiagnosticStatus.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(17.dp), color = runningColor, strokeWidth = 2.dp)
                                        PlaybackDiagnosticStatus.PENDING -> Text("·", color = idleColor, fontSize = 24.sp)
                                        PlaybackDiagnosticStatus.SKIPPED -> Text("—", color = idleColor, fontSize = 16.sp)
                                    }
                                }
                                Text(stage.labelAr, color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(check?.detail.orEmpty(), color = when (status) {
                                    PlaybackDiagnosticStatus.SUCCESS -> successColor
                                    PlaybackDiagnosticStatus.FAILED -> failureColor
                                    PlaybackDiagnosticStatus.RUNNING -> runningColor
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Text("هذه اللوحة للمسؤول فقط؛ بياناتها محلية ولا تُرفع إلى Firebase.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
    }
}
