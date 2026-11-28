package dev.klee.kernelmanager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.klee.kernelmanager.ActionLogEntry
import dev.klee.kernelmanager.DeviceInfoSnapshot
import dev.klee.kernelmanager.DisplayRefreshInfo
import dev.klee.kernelmanager.ExportedLogFile
import dev.klee.kernelmanager.LogExporter
import dev.klee.kernelmanager.ui.components.KleeCard
import dev.klee.kernelmanager.ui.components.RootWarningCard
import dev.klee.kernelmanager.ui.components.SectionHeader
import dev.klee.kernelmanager.ui.components.StatusPill
import dev.klee.kernelmanager.ui.components.formatUptime
import dev.klee.kernelmanager.ui.theme.KleeColors
import dev.klee.kernelmanager.ui.theme.KleeThemePreset

@Composable
fun SettingsLogsScreen(
    activeTheme: KleeThemePreset,
    refreshInfo: DisplayRefreshInfo,
    defaultRefreshHz: Int,
    deviceInfo: DeviceInfoSnapshot,
    logs: List<ActionLogEntry>,
    busy: Boolean,
    onSelectTheme: (KleeThemePreset) -> Unit,
    onApplyRefreshRate: (Int) -> Unit,
    onRefreshDevice: () -> Unit,
    onExportLogs: (Boolean, (ExportedLogFile, String) -> Unit) -> Unit,
    onClearLogs: () -> Unit,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    var includeDmesg by remember { mutableStateOf(true) }
    var lastSavedPath by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(
            title = "Temas, Tela & Logs",
            subtitle = "Personalização visual, taxa de atualização e exportação técnica",
            trailing = {
                FilledTonalIconButton(
                    onClick = onRefreshDevice,
                    enabled = !busy
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar")
                }
            }
        )

        if (!deviceInfo.root && deviceInfo.model.isBlank()) {
            RootWarningCard()
        }

        // 1. Themes Picker Card
        KleeCard(
            modifier = Modifier.fillMaxWidth(),
            accentBorder = KleeColors.Primary.copy(alpha = 0.35f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Palette, contentDescription = null, tint = KleeColors.Primary, modifier = Modifier.size(20.dp))
                Column {
                    Text("Temas do Aplicativo", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text("Aplica instantaneamente no app e no HUD Flutuante", color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KleeThemePreset.entries.forEach { preset ->
                    val selected = preset == activeTheme
                    val p = preset.palette
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(p.background)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) p.primary else p.border,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelectTheme(preset) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(16.dp).clip(CircleShape).background(p.primary))
                                Box(Modifier.size(16.dp).clip(CircleShape).background(p.secondary))
                            }
                            Column {
                                Text(preset.title, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(preset.subtitle, color = p.textSecondary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (selected) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = "Ativo", tint = p.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        // 2. Global Display Refresh Rate Card
        KleeCard(
            modifier = Modifier.fillMaxWidth(),
            accentBorder = KleeColors.Monitor.copy(alpha = 0.35f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Speed, contentDescription = null, tint = KleeColors.Monitor, modifier = Modifier.size(20.dp))
                    Column {
                        Text("Taxa de Atualização da Tela", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text("Global (usada como padrão ao sair de apps)", color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                StatusPill("${refreshInfo.currentHz} Hz", KleeColors.Monitor)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val options = listOf(0) + refreshInfo.supportedHz.distinct().sorted()
                options.forEach { hz ->
                    val selected = defaultRefreshHz == hz
                    FilterChip(
                        selected = selected,
                        onClick = { onApplyRefreshRate(hz) },
                        enabled = !busy,
                        label = {
                            Text(
                                if (hz == 0) "Automática" else "$hz Hz",
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            if (refreshInfo.minSetting.isNotBlank() || refreshInfo.peakSetting.isNotBlank() || refreshInfo.userSetting.isNotBlank()) {
                Text(
                    "Sistema: min=${refreshInfo.minSetting.ifBlank { "auto" }} · peak=${refreshInfo.peakSetting.ifBlank { "auto" }} · user=${refreshInfo.userSetting.ifBlank { "--" }} · miui=${refreshInfo.miuiSetting.ifBlank { "--" }}",
                    color = KleeColors.TextMuted,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 3. Export Logs Card (.txt to Downloads + Share FileProvider + Copy)
        KleeCard(
            modifier = Modifier.fillMaxWidth(),
            accentBorder = KleeColors.Device.copy(alpha = 0.45f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Description, contentDescription = null, tint = KleeColors.Device, modifier = Modifier.size(22.dp))
                Column {
                    Text("Exportar Logs para Diagnóstico", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text("Gera .txt com hardware, ações root, logcat e dmesg", color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Incluir kernel dmesg (últimas 120 linhas)", color = KleeColors.TextPrimary, style = MaterialTheme.typography.bodySmall)
                    Text("Ajuda a diagnosticar erros de sysfs, GPU e thermal", color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
                }
                Switch(checked = includeDmesg, onCheckedChange = { includeDmesg = it })
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onExportLogs(includeDmesg) { exported, _ ->
                            lastSavedPath = exported.downloadsPath ?: exported.cacheFile.absolutePath
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KleeColors.Device, contentColor = Color.Black)
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Salvar .txt", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        onExportLogs(includeDmesg) { exported, _ ->
                            lastSavedPath = exported.downloadsPath ?: exported.cacheFile.absolutePath
                            val shareIntent = LogExporter.buildShareIntent(context, exported.cacheFile)
                            context.startActivity(Intent.createChooser(shareIntent, "Enviar log .txt do KLEE"))
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KleeColors.Primary, contentColor = Color.Black)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Enviar .txt", fontWeight = FontWeight.SemiBold)
                }
            }

            OutlinedButton(
                onClick = {
                    onExportLogs(includeDmesg) { exported, content ->
                        lastSavedPath = exported.downloadsPath ?: exported.cacheFile.absolutePath
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("KLEE Log", content))
                        onNotify("Log copiado e salvo em Download/${exported.fileName}")
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Salvar e Copiar para Área de Transferência")
            }

            lastSavedPath?.let { path ->
                Text(
                    "Último arquivo exportado: $path",
                    color = KleeColors.Success,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 4. Device Identity Card
        KleeCard(modifier = Modifier.fillMaxWidth()) {
            Text("Identidade do Dispositivo", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
            SettingsInfoRow("Modelo", "${deviceInfo.brand} ${deviceInfo.model}".trim())
            SettingsInfoRow("Codinome / SoC", "${deviceInfo.device} · ${deviceInfo.soc}")
            if (deviceInfo.hyperOsVersion.isNotBlank()) SettingsInfoRow("HyperOS", deviceInfo.hyperOsVersion)
            SettingsInfoRow("Android", "${deviceInfo.androidVersion} (API ${deviceInfo.sdkInt})")
            SettingsInfoRow("SELinux / Root", "${deviceInfo.selinuxMode} · ${deviceInfo.rootProvider}")
            SettingsInfoRow("Núcleos / RAM", "${deviceInfo.cpuCores} cores · ${deviceInfo.totalRamMb} MB")
            SettingsInfoRow("Uptime", formatUptime(deviceInfo.uptimeSeconds))
            if (deviceInfo.kernelVersion.isNotBlank()) {
                HorizontalDivider(color = KleeColors.Border)
                Text(
                    deviceInfo.kernelVersion,
                    color = KleeColors.Device,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 5. Root Action Logs Card
        KleeCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Histórico de Ações Root (${logs.size})", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                if (logs.isNotEmpty()) {
                    TextButton(onClick = onClearLogs) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Limpar")
                    }
                }
            }
            if (logs.isEmpty()) {
                Text("Nenhuma ação root registrada nesta sessão.", color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            } else {
                logs.forEach { log ->
                    val accent = if (log.isError) KleeColors.Danger else KleeColors.Success
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(KleeColors.Surface)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(log.action, color = accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            Text(log.timestamp, color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                        }
                        Text(log.detail, color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Text(value.ifBlank { "--" }, color = KleeColors.TextPrimary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
