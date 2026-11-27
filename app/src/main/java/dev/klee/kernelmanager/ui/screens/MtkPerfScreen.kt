package dev.klee.kernelmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.klee.kernelmanager.MtkPerfSnapshot
import dev.klee.kernelmanager.ui.components.KleeCard
import dev.klee.kernelmanager.ui.components.RootWarningCard
import dev.klee.kernelmanager.ui.components.SectionHeader
import dev.klee.kernelmanager.ui.components.StatusPill
import dev.klee.kernelmanager.ui.theme.KleeColors

@Composable
fun MtkPerfScreen(
    state: MtkPerfSnapshot,
    busy: Boolean,
    onRefresh: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onApplyPreset: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "MTK FPSGO & Xiaomi Metis",
            subtitle = "Motores reais de desempenho e fluidez do MT6899 / HyperOS 3",
            trailing = {
                FilledTonalIconButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = KleeColors.Gaming.copy(alpha = 0.18f),
                        contentColor = KleeColors.Gaming
                    )
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar")
                }
            }
        )

        if (!state.root) {
            RootWarningCard()
        }

        // Quick Presets Card
        KleeCard(
            modifier = Modifier.fillMaxWidth(),
            accentBorder = KleeColors.Gaming.copy(alpha = 0.35f)
        ) {
            Text("Presets Rápidos (1 Toque)", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onApplyPreset("GAMING") },
                    enabled = !busy && (state.fpsgoAvailable || state.metisAvailable),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KleeColors.Gaming, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Gaming", fontWeight = FontWeight.SemiBold)
                }
                FilledTonalButton(
                    onClick = { onApplyPreset("STOCK") },
                    enabled = !busy && (state.fpsgoAvailable || state.metisAvailable),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stock")
                }
                FilledTonalButton(
                    onClick = { onApplyPreset("COOL") },
                    enabled = !busy && (state.fpsgoAvailable || state.metisAvailable),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Rounded.AcUnit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Frio")
                }
            }
        }

        if (state.fpsgoAvailable) {
            KleeCard(modifier = Modifier.fillMaxWidth(), accentBorder = KleeColors.Cpu.copy(alpha = 0.3f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("MediaTek FPSGO", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                    StatusPill(
                        text = if (state.fpsgoEnable == true) "Ativo" else "Inativo",
                        color = if (state.fpsgoEnable == true) KleeColors.Success else KleeColors.TextMuted
                    )
                }

                MtkToggleRow(
                    title = "Motor FPSGO (fpsgo_enable)",
                    subtitle = "Escalonamento inteligente baseado em frame-time para manter FPS estáveis",
                    checked = state.fpsgoEnable,
                    enabled = !busy,
                    onCheckedChange = { onToggle("fpsgo_enable", it) }
                )
                HorizontalDivider(color = KleeColors.Border)
                MtkToggleRow(
                    title = "Boost Top-App (boost_ta)",
                    subtitle = "Prioriza recursos de CPU para o aplicativo/jogo em primeiro plano",
                    checked = state.boostTa,
                    enabled = !busy,
                    onCheckedChange = { onToggle("boost_ta", it) }
                )
                HorizontalDivider(color = KleeColors.Border)
                MtkToggleRow(
                    title = "Boost Threads VIP (boost_VIP)",
                    subtitle = "Eleva a prioridade de threads críticas de renderização",
                    checked = state.boostVip,
                    enabled = !busy,
                    onCheckedChange = { onToggle("boost_VIP", it) }
                )
                HorizontalDivider(color = KleeColors.Border)
                MtkToggleRow(
                    title = "Frame Rescue (rescue_enable)",
                    subtitle = "Sobe clocks instantaneamente ao detectar atraso de quadro (anti-stutter)",
                    checked = state.rescueEnable,
                    enabled = !busy,
                    onCheckedChange = { onToggle("rescue_enable", it) }
                )
                HorizontalDivider(color = KleeColors.Border)
                MtkToggleRow(
                    title = "Ultra Rescue (ultra_rescue)",
                    subtitle = "Resgate agressivo de quadros para jogos pesados (mais consumo/calor)",
                    checked = state.ultraRescue,
                    enabled = !busy,
                    onCheckedChange = { onToggle("ultra_rescue", it) }
                )
                HorizontalDivider(color = KleeColors.Border)
                MtkToggleRow(
                    title = "Power RL (powerRL_enable)",
                    subtitle = "Controle adaptativo de eficiência energética durante sessões longas",
                    checked = state.powerRlEnable,
                    enabled = !busy,
                    onCheckedChange = { onToggle("powerRL_enable", it) }
                )

                if (state.fpsgoStatus.isNotBlank()) {
                    HorizontalDivider(color = KleeColors.Border)
                    Text("fpsgo_status", color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
                    Text(
                        state.fpsgoStatus,
                        color = KleeColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        if (state.metisAvailable) {
            KleeCard(modifier = Modifier.fillMaxWidth(), accentBorder = KleeColors.Primary.copy(alpha = 0.3f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = KleeColors.Primary, modifier = Modifier.size(18.dp))
                        Text("Xiaomi Metis Scheduler", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                    }
                    StatusPill("HyperOS 3", KleeColors.Primary)
                }

                MtkToggleRow(
                    title = "Frequency Boost (mi_fboost_enable)",
                    subtitle = "Impulso rápido de frequência em abertura de apps e animações",
                    checked = state.miFboostEnable,
                    enabled = !busy,
                    onCheckedChange = { onToggle("mi_fboost_enable", it) }
                )
                HorizontalDivider(color = KleeColors.Border)
                MtkToggleRow(
                    title = "Speed Touch (speed_touch_enable)",
                    subtitle = "Reduz latência de resposta do escalonador ao toque na tela",
                    checked = state.speedTouchEnable,
                    enabled = !busy,
                    onCheckedChange = { onToggle("speed_touch_enable", it) }
                )
                HorizontalDivider(color = KleeColors.Border)
                MtkToggleRow(
                    title = "VIP Link (vip_link_enable)",
                    subtitle = "Encadeia threads dependentes de UI/render como VIP",
                    checked = state.vipLinkEnable,
                    enabled = !busy,
                    onCheckedChange = { onToggle("vip_link_enable", it) }
                )
                HorizontalDivider(color = KleeColors.Border)
                MtkToggleRow(
                    title = "Metis I/O Boost (metis_io_enabled)",
                    subtitle = "Prioriza operações de disco de tarefas em primeiro plano",
                    checked = state.metisIoEnabled,
                    enabled = !busy,
                    onCheckedChange = { onToggle("metis_io_enabled", it) }
                )
                HorizontalDivider(color = KleeColors.Border)
                MtkToggleRow(
                    title = "Modo Performance Metis (in_perf_mod)",
                    subtitle = "Ativa perfil interno de alta prioridade no escalonador Metis",
                    checked = state.inPerfMod,
                    enabled = !busy,
                    onCheckedChange = { onToggle("in_perf_mod", it) }
                )
                HorizontalDivider(color = KleeColors.Border)
                MtkToggleRow(
                    title = "Proteção Térmica (thermal_break_enable)",
                    subtitle = "Permite que o Metis recue o boost automaticamente sob calor alto",
                    checked = state.thermalBreakEnable,
                    enabled = !busy,
                    onCheckedChange = { onToggle("thermal_break_enable", it) }
                )
            }
        }

        if (state.message.isNotBlank()) {
            KleeCard(modifier = Modifier.fillMaxWidth()) {
                Text(state.message, color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun MtkToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean?,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = KleeColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked == true,
            onCheckedChange = onCheckedChange,
            enabled = enabled && checked != null
        )
    }
}
