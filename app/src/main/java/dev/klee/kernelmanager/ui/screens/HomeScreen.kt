package dev.klee.kernelmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PermDeviceInformation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.klee.kernelmanager.BuiltInProfile
import dev.klee.kernelmanager.DashboardSummary
import dev.klee.kernelmanager.ui.components.KleeCard
import dev.klee.kernelmanager.ui.components.ProgressMeterBar
import dev.klee.kernelmanager.ui.components.StatusPill
import dev.klee.kernelmanager.ui.components.tempSeverityColor
import dev.klee.kernelmanager.ui.theme.KleeColors

private data class HomeModuleItem(
    val title: String,
    val subtitle: String,
    val route: String,
    val icon: ImageVector,
    val accent: Color
)

@Composable
fun HomeScreen(
    summary: DashboardSummary,
    activeProfile: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    onNavigate: (String) -> Unit,
    onQuickProfile: (BuiltInProfile) -> Unit
) {
    val scroll = rememberScrollState()
    val modules = listOf(
        HomeModuleItem("CPU & UCLAMP", "3 clusters, sugov_ext e UCLAMP", "CPU", Icons.Rounded.Memory, KleeColors.Cpu),
        HomeModuleItem("GPU, GED & DRAM", "Mali 1508MHz, GED e LPDDR5X", "GPU", Icons.Rounded.GraphicEq, KleeColors.Gpu),
        HomeModuleItem("FPSGO & Metis", "Motor gaming MT6899 / HyperOS", "MTK_PERF", Icons.Rounded.RocketLaunch, KleeColors.Gaming),
        HomeModuleItem("Thermal", "Sensores, zonas e throttling", "THERMAL", Icons.Rounded.Thermostat, KleeColors.Thermal),
        HomeModuleItem("Power & Bypass", "Bateria 6500mAh e Bypass", "POWER", Icons.Rounded.BatteryChargingFull, KleeColors.Power),
        HomeModuleItem("Perfis & Gaming", "Modos rápidos e perfis salvos", "PROFILES", Icons.Rounded.SportsEsports, Color(0xFFEC4899)),
        HomeModuleItem("Monitor", "Gráficos CPU, GPU e SoC", "MONITOR", Icons.AutoMirrored.Rounded.ShowChart, KleeColors.Monitor),
        HomeModuleItem("Memória & UFS", "ZRAM, MGLRU, UFS 4.0 e BBR", "TUNING", Icons.Rounded.Storage, KleeColors.Tuning)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "KLEE Kernel Manager",
                    color = KleeColors.TextPrimary,
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "${summary.deviceModel} · ${summary.soc}" +
                        if (summary.kernelShort.isNotBlank()) " · ${summary.kernelShort}" else "",
                    color = KleeColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusPill(
                    text = if (!summary.loaded) "Lendo..." else if (summary.root) "Root OK" else "Sem Root",
                    color = if (!summary.loaded) KleeColors.TextSecondary else if (summary.root) KleeColors.Success else KleeColors.Danger,
                    icon = if (summary.root) Icons.Rounded.VerifiedUser else Icons.Rounded.WarningAmber
                )
                IconButton(
                    onClick = { onNavigate("DEVICE") },
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(KleeColors.CardElevated)
                ) {
                    Icon(
                        Icons.Rounded.PermDeviceInformation,
                        contentDescription = "Dispositivo e Logs",
                        tint = KleeColors.Device,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(KleeColors.CardElevated)
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "Atualizar",
                        tint = KleeColors.Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Live Telemetry Hero Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF141E33), Color(0xFF17182B), Color(0xFF111827))
                    )
                )
                .border(1.dp, KleeColors.Primary.copy(alpha = 0.3f), RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Rounded.DeveloperBoard,
                            contentDescription = null,
                            tint = KleeColors.Primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "Telemetria MT6899",
                            color = KleeColors.TextSecondary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (summary.bypassActive) {
                            StatusPill("Bypass", KleeColors.Warning)
                        }
                        StatusPill(text = activeProfile, color = KleeColors.Primary)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeroMetricItem(
                        label = "CPU Pico",
                        value = if (summary.cpuPeakMHz > 0) "${summary.cpuPeakMHz}" else "--",
                        unit = "MHz",
                        accent = KleeColors.Cpu,
                        modifier = Modifier.weight(1f)
                    )
                    HeroMetricItem(
                        label = summary.gpuUtilization?.let { "GPU $it%" } ?: "GPU Mali",
                        value = if (summary.gpuMHz > 0) "${summary.gpuMHz}" else "--",
                        unit = "MHz",
                        accent = KleeColors.Gpu,
                        modifier = Modifier.weight(1f)
                    )
                    HeroMetricItem(
                        label = "SoC Max",
                        value = summary.socTempC?.let { "%.1f".format(it) } ?: "--",
                        unit = "°C",
                        accent = summary.socTempC?.let { tempSeverityColor(it) } ?: KleeColors.Thermal,
                        modifier = Modifier.weight(1f)
                    )
                    HeroMetricItem(
                        label = "Bateria",
                        value = if (summary.batteryPercent > 0) "${summary.batteryPercent}" else "--",
                        unit = "%",
                        accent = KleeColors.Power,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (summary.dramMHz > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("DRAM LPDDR5X (DVFSRC)", color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
                        Text(
                            "${summary.dramMHz} MHz",
                            color = KleeColors.Gpu,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (summary.ramTotalMb > 0) {
                    val ramFrac = (summary.ramUsedMb.toFloat() / summary.ramTotalMb.toFloat()).coerceIn(0f, 1f)
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Memória RAM", color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                            Text(
                                "${summary.ramUsedMb} / ${summary.ramTotalMb} MB (${(ramFrac * 100).toInt()}%)",
                                color = KleeColors.TextPrimary,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        ProgressMeterBar(fraction = ramFrac, color = KleeColors.Monitor, heightDp = 6)
                    }
                }
            }
        }

        // Quick Profile Row
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = KleeColors.Warning, modifier = Modifier.size(16.dp))
                Text("Troca Rápida de Perfil", color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BuiltInProfile.entries.forEach { profile ->
                    val selected = activeProfile.equals(profile.title, ignoreCase = true)
                    val chipAccent = when (profile) {
                        BuiltInProfile.ECO -> KleeColors.Power
                        BuiltInProfile.BALANCED -> KleeColors.Primary
                        BuiltInProfile.PERFORMANCE -> KleeColors.Thermal
                        BuiltInProfile.GAMING -> KleeColors.Gaming
                    }
                    FilterChip(
                        selected = selected,
                        onClick = { onQuickProfile(profile) },
                        enabled = !busy,
                        label = {
                            Text(
                                profile.title,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = KleeColors.Card,
                            labelColor = KleeColors.TextSecondary,
                            selectedContainerColor = chipAccent.copy(alpha = 0.22f),
                            selectedLabelColor = chipAccent
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = !busy,
                            selected = selected,
                            borderColor = KleeColors.Border,
                            selectedBorderColor = chipAccent.copy(alpha = 0.65f)
                        )
                    )
                }
            }
        }

        // Module Bento Grid (2 columns x 4 rows)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Módulos do Kernel", color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
            modules.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { item ->
                        KleeCard(
                            modifier = Modifier
                                .weight(1f)
                                .height(132.dp),
                            accentBorder = item.accent.copy(alpha = 0.28f),
                            onClick = { onNavigate(item.route) }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(item.accent.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.title,
                                    tint = item.accent,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = item.title,
                                color = KleeColors.TextPrimary,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.subtitle,
                                color = KleeColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HeroMetricItem(
    label: String,
    value: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(KleeColors.Surface.copy(alpha = 0.85f))
            .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            color = KleeColors.TextMuted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = value,
                color = accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            Text(
                text = unit,
                color = KleeColors.TextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}
