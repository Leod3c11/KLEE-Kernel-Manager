package dev.klee.kernelmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.klee.kernelmanager.MonitorSample
import dev.klee.kernelmanager.ui.components.KleeCard
import dev.klee.kernelmanager.ui.components.LiveLineChart
import dev.klee.kernelmanager.ui.components.ProgressMeterBar
import dev.klee.kernelmanager.ui.components.RootWarningCard
import dev.klee.kernelmanager.ui.components.SectionHeader
import dev.klee.kernelmanager.ui.components.StatusPill
import dev.klee.kernelmanager.ui.components.formatUptime
import dev.klee.kernelmanager.ui.components.tempSeverityColor
import dev.klee.kernelmanager.ui.theme.KleeColors

@Composable
fun MonitorScreen(
    sample: MonitorSample,
    history: List<MonitorSample>,
    paused: Boolean,
    intervalMs: Long,
    onTogglePause: () -> Unit,
    onSelectInterval: (Long) -> Unit,
    onManualRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "Monitor ao Vivo",
            subtitle = if (paused) "Coleta pausada" else "Atualizando a cada ${intervalMs / 1000.0} s",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(
                        onClick = onTogglePause,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = (if (paused) KleeColors.Power else KleeColors.Warning).copy(alpha = 0.18f),
                            contentColor = if (paused) KleeColors.Power else KleeColors.Warning
                        )
                    ) {
                        Icon(
                            if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = if (paused) "Retomar" else "Pausar"
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onManualRefresh,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = KleeColors.Monitor.copy(alpha = 0.18f),
                            contentColor = KleeColors.Monitor
                        )
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Coletar agora")
                    }
                }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Intervalo:", color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
            listOf(1000L to "1s", 2000L to "2s", 5000L to "5s").forEach { (ms, label) ->
                FilterChip(
                    selected = intervalMs == ms,
                    onClick = { onSelectInterval(ms) },
                    label = { Text(label) }
                )
            }
        }

        if (!sample.root && history.isEmpty()) {
            RootWarningCard()
        }

        // Live CPU Usage Graph
        LiveLineChart(
            title = "Uso de CPU (/proc/stat)",
            unit = "%",
            values = history.map { it.cpuUsagePercent },
            accent = KleeColors.Cpu,
            minBound = 0f,
            maxBound = 100f
        )

        // Live GPU Utilization Graph (MTK GED)
        val gpuPoints = history.mapNotNull { it.gpuUtilization?.toFloat() }
        if (gpuPoints.isNotEmpty() || sample.gpuUtilization != null) {
            LiveLineChart(
                title = "Uso de GPU Mali (GED)",
                unit = "%",
                values = gpuPoints,
                accent = KleeColors.Gpu,
                minBound = 0f,
                maxBound = 100f
            )
        }

        // Live Temperature Graph
        val tempPoints = history.mapNotNull { it.socTempC?.toFloat() }
        LiveLineChart(
            title = "Temperatura SoC Max",
            unit = "°C",
            values = tempPoints,
            accent = sample.socTempC?.let { tempSeverityColor(it) } ?: KleeColors.Thermal,
            minBound = 20f,
            maxBound = 85f
        )

        // Live Clusters, GPU, DRAM & UFS Clocks card
        KleeCard(modifier = Modifier.fillMaxWidth()) {
            Text("Clocks em Tempo Real", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
            sample.clusters.forEach { c ->
                val frac = if (c.maxMHz > 0) (c.currentMHz.toFloat() / c.maxMHz.toFloat()).coerceIn(0f, 1f) else 0f
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Policy ${c.policyId} · ${c.governor}", color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${c.currentMHz} / ${c.maxMHz} MHz",
                            color = KleeColors.Cpu,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    ProgressMeterBar(fraction = frac, color = KleeColors.Cpu, heightDp = 6)
                }
            }

            HorizontalDivider(color = KleeColors.Border)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("GPU Mali", color = KleeColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        sample.gpuGovernor.ifBlank { "devfreq" } +
                            (sample.gpuUtilization?.let { " · carga $it%" } ?: ""),
                        color = KleeColors.TextMuted,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                StatusPill("${sample.gpuMHz} MHz", KleeColors.Gpu)
            }

            if (sample.dramMHz > 0 || sample.ufsMHz > 0) {
                HorizontalDivider(color = KleeColors.Border)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (sample.dramMHz > 0) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(KleeColors.Surface)
                                .padding(10.dp)
                        ) {
                            Text("DRAM LPDDR5X", color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
                            Text("${sample.dramMHz} MHz", color = KleeColors.Monitor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (sample.ufsMHz > 0) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(KleeColors.Surface)
                                .padding(10.dp)
                        ) {
                            Text("UFS 4.0 Clock", color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
                            Text("${sample.ufsMHz} MHz", color = KleeColors.Tuning, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Memory & Load Average Card
        KleeCard(modifier = Modifier.fillMaxWidth()) {
            Text("Memória & Carga do Sistema", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)

            if (sample.ramTotalMb > 0) {
                val ramFrac = (sample.ramUsedMb.toFloat() / sample.ramTotalMb.toFloat()).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("RAM", color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${sample.ramUsedMb} / ${sample.ramTotalMb} MB (${(ramFrac * 100).toInt()}%)",
                            color = KleeColors.Monitor,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    ProgressMeterBar(fraction = ramFrac, color = KleeColors.Monitor, heightDp = 6)
                }
            }

            if (sample.swapTotalMb > 0) {
                val swapFrac = (sample.swapUsedMb.toFloat() / sample.swapTotalMb.toFloat()).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Swap / ZRAM", color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${sample.swapUsedMb} / ${sample.swapTotalMb} MB",
                            color = KleeColors.Tuning,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    ProgressMeterBar(fraction = swapFrac, color = KleeColors.Tuning, heightDp = 6)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(KleeColors.Surface)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Load Average (1m · 5m · 15m)", color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
                    Text(
                        sample.loadAvg.ifBlank { "--" },
                        color = KleeColors.TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Uptime", color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
                    Text(
                        formatUptime(sample.uptimeSeconds),
                        color = KleeColors.Primary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}
