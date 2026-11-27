package dev.klee.kernelmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.PowerOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.klee.kernelmanager.PowerSnapshot
import dev.klee.kernelmanager.ThermalSnapshot
import dev.klee.kernelmanager.ui.components.KleeCard
import dev.klee.kernelmanager.ui.components.ProgressMeterBar
import dev.klee.kernelmanager.ui.components.RootWarningCard
import dev.klee.kernelmanager.ui.components.SectionHeader
import dev.klee.kernelmanager.ui.components.StatusPill
import dev.klee.kernelmanager.ui.components.tempSeverityColor
import dev.klee.kernelmanager.ui.theme.KleeColors

@Composable
fun ThermalScreen(
    state: ThermalSnapshot,
    busy: Boolean,
    onRefresh: () -> Unit
) {
    var filter by remember { mutableStateOf("ALL") }
    var search by remember { mutableStateOf("") }
    var showInactive by remember { mutableStateOf(false) }

    val activeZones = state.zones.filter { it.tempCelsius > 0.5 }
    val socMax = activeZones.firstOrNull { it.name.equals("soc_max", ignoreCase = true) }?.tempCelsius
        ?: activeZones.maxOfOrNull { it.tempCelsius }
    val bigCore = activeZones.firstOrNull { it.name.contains("cpu-big", ignoreCase = true) }?.tempCelsius
    val batteryTemp = activeZones.firstOrNull { it.name.equals("battery", ignoreCase = true) }?.tempCelsius
    val activeCoolingCount = state.coolingDevices.count { it.curState > 0 }

    val query = search.trim().lowercase()
    val baseZones = if (showInactive) state.zones else activeZones
    val filteredZones = baseZones.filter { query.isEmpty() || it.name.lowercase().contains(query) }
    val filteredCooling = state.coolingDevices.filter { query.isEmpty() || it.name.lowercase().contains(query) }
    val filteredExtras = state.mtkExtras.filter { query.isEmpty() || it.key.lowercase().contains(query) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "Monitor Térmico",
            subtitle = "Zonas térmicas MT6899, cooling devices e nós Xiaomi",
            trailing = {
                FilledTonalIconButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = KleeColors.Thermal.copy(alpha = 0.18f),
                        contentColor = KleeColors.Thermal
                    )
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar Thermal")
                }
            }
        )

        if (!state.root) {
            RootWarningCard()
        }

        // Key MT6899 Thermal KPI row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThermalKpiBox(
                label = "SoC Max",
                value = socMax?.let { "${"%.1f".format(it)}°C" } ?: "--",
                accent = socMax?.let { tempSeverityColor(it) } ?: KleeColors.Thermal,
                modifier = Modifier.weight(1f)
            )
            ThermalKpiBox(
                label = "CPU Big",
                value = bigCore?.let { "${"%.1f".format(it)}°C" } ?: "--",
                accent = bigCore?.let { tempSeverityColor(it) } ?: KleeColors.Cpu,
                modifier = Modifier.weight(1f)
            )
            ThermalKpiBox(
                label = "Bateria",
                value = batteryTemp?.let { "${"%.1f".format(it)}°C" } ?: "--",
                accent = batteryTemp?.let { tempSeverityColor(it) } ?: KleeColors.Power,
                modifier = Modifier.weight(1f)
            )
            ThermalKpiBox(
                label = "Throttling",
                value = "$activeCoolingCount/${state.coolingDevices.size}",
                accent = if (activeCoolingCount > 0) KleeColors.Warning else KleeColors.Power,
                modifier = Modifier.weight(1f)
            )
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Filtrar sensor ou cooling...") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = KleeColors.TextMuted) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "ALL" to "Todos",
                "ZONES" to "Sensores (${filteredZones.size})",
                "COOL" to "Cooling (${filteredCooling.size})",
                "MTK" to "Xiaomi (${filteredExtras.size})"
            ).forEach { (key, label) ->
                FilterChip(
                    selected = filter == key,
                    onClick = { filter = key },
                    label = { Text(label) }
                )
            }
            FilterChip(
                selected = showInactive,
                onClick = { showInactive = !showInactive },
                label = { Text("Inativos (0°C)") }
            )
        }

        if (state.message.isNotBlank() && state.zones.isEmpty()) {
            KleeCard(modifier = Modifier.fillMaxWidth()) {
                Text(state.message, color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if ((filter == "ALL" || filter == "MTK") && filteredExtras.isNotEmpty()) {
            KleeCard(modifier = Modifier.fillMaxWidth(), accentBorder = KleeColors.Thermal.copy(alpha = 0.3f)) {
                Text("Nós Xiaomi (thermal_message)", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                filteredExtras.forEach { extra ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(extra.key, color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Text(
                            extra.value.ifBlank { "--" },
                            color = KleeColors.Thermal,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        if ((filter == "ALL" || filter == "COOL") && filteredCooling.isNotEmpty()) {
            KleeCard(modifier = Modifier.fillMaxWidth()) {
                Text("Cooling Devices & Throttling", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                filteredCooling.forEach { dev ->
                    val frac = if (dev.maxState > 0) (dev.curState.toFloat() / dev.maxState.toFloat()).coerceIn(0f, 1f) else 0f
                    val barColor = when {
                        frac >= 0.6f -> KleeColors.Danger
                        dev.curState > 0 -> KleeColors.Warning
                        else -> KleeColors.Power
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                dev.name,
                                color = KleeColors.TextPrimary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${dev.curState} / ${dev.maxState}",
                                color = barColor,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        ProgressMeterBar(fraction = frac, color = barColor, heightDp = 5)
                    }
                }
            }
        }

        if ((filter == "ALL" || filter == "ZONES") && filteredZones.isNotEmpty()) {
            KleeCard(modifier = Modifier.fillMaxWidth()) {
                Text("Sensores Térmicos (${filteredZones.size})", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                filteredZones.forEach { zone ->
                    val color = tempSeverityColor(zone.tempCelsius)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(KleeColors.Surface)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Thermostat, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                            Text(
                                zone.name,
                                color = KleeColors.TextPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        StatusPill("${"%.1f".format(zone.tempCelsius)} °C", color)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ThermalKpiBox(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(KleeColors.Card)
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Text(
            value,
            color = accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}

@Composable
fun PowerScreen(
    state: PowerSnapshot,
    busy: Boolean,
    onRefresh: () -> Unit,
    onToggleBypass: (Boolean) -> Unit
) {
    var confirmBypassTarget by remember { mutableStateOf<Boolean?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "Bateria & Bypass Charging",
            subtitle = "Telemetria elétrica, saúde real e controle de carregamento",
            trailing = {
                FilledTonalIconButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = KleeColors.Power.copy(alpha = 0.18f),
                        contentColor = KleeColors.Power
                    )
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar Power")
                }
            }
        )

        if (!state.root) {
            RootWarningCard()
        }

        val battAccent = when {
            state.capacity <= 15 -> KleeColors.Danger
            state.capacity <= 35 -> KleeColors.Warning
            else -> KleeColors.Power
        }

        KleeCard(
            modifier = Modifier.fillMaxWidth(),
            accentBorder = battAccent.copy(alpha = 0.4f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(battAccent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.BatteryChargingFull,
                            contentDescription = null,
                            tint = battAccent,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Column {
                        Text(
                            "${state.capacity}%",
                            color = KleeColors.TextPrimary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "${state.status} · ${state.technology}",
                            color = KleeColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                StatusPill(
                    text = "${"%.2f".format(state.powerWatts)} W",
                    color = KleeColors.Warning,
                    icon = Icons.Rounded.Bolt
                )
            }

            ProgressMeterBar(fraction = state.capacity / 100f, color = battAccent, heightDp = 10)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PowerMetricCell("Tensão", "${"%.3f".format(state.voltageVolts)} V", KleeColors.Primary, Modifier.weight(1f))
                PowerMetricCell("Corrente", "${"%.0f".format(state.currentMa)} mA", KleeColors.Warning, Modifier.weight(1f))
                PowerMetricCell("Temp", "${"%.1f".format(state.tempCelsius)} °C", tempSeverityColor(state.tempCelsius), Modifier.weight(1f))
            }
        }

        // Bypass Charging Card (input_suspend)
        if (state.inputSuspend != null) {
            val bypassOn = state.inputSuspend == true
            val bypassColor = if (bypassOn) KleeColors.Warning else KleeColors.Power
            KleeCard(
                modifier = Modifier.fillMaxWidth(),
                accentBorder = bypassColor.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            if (bypassOn) Icons.Rounded.PowerOff else Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = bypassColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text("Bypass Charging (Pausar Carga)", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (bypassOn) "Carregamento suspenso (input_suspend=1)" else "Carregamento normal ativo (input_suspend=0)",
                                color = bypassColor,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Switch(
                        checked = bypassOn,
                        onCheckedChange = { target -> confirmBypassTarget = target },
                        enabled = !busy && state.canWriteInputSuspend
                    )
                }
                Text(
                    "Suspende a entrada de carga na bateria para reduzir aquecimento e desgaste ao jogar conectado na tomada. Lembre-se de desativar para voltar a carregar.",
                    color = KleeColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        KleeCard(modifier = Modifier.fillMaxWidth()) {
            Text("Saúde & Capacidade Real", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
            PowerInfoRow("Estado de saúde", state.health)
            state.cycleCount?.let { PowerInfoRow("Ciclos de carga", "$it ciclos") }
            state.chargeCounterMah?.let { PowerInfoRow("Carga restante", "$it mAh") }
            state.chargeFullMah?.let { PowerInfoRow("Capacidade atual máxima", "$it mAh") }
            state.chargeDesignMah?.let { design ->
                PowerInfoRow("Capacidade nominal", "$design mAh")
                state.chargeFullMah?.let { full ->
                    if (design > 0) {
                        val healthPct = ((full.toDouble() / design.toDouble()) * 100.0).coerceIn(0.0, 110.0)
                        PowerInfoRow("Vida útil estimada", "${"%.1f".format(healthPct)}%")
                    }
                }
            }
        }

        if (state.supplies.isNotEmpty()) {
            KleeCard(modifier = Modifier.fillMaxWidth()) {
                Text("Fontes de Alimentação (/sys/class/power_supply)", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                state.supplies.forEach { supply ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(KleeColors.Surface)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Power, contentDescription = null, tint = KleeColors.Power, modifier = Modifier.size(16.dp))
                            Column {
                                Text(supply.name, color = KleeColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                Text(supply.type, color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        supply.online?.let { online ->
                            StatusPill(
                                text = if (online) "Ativa" else "Inativa",
                                color = if (online) KleeColors.Success else KleeColors.TextMuted
                            )
                        }
                    }
                }
            }
        }

        if (state.message.isNotBlank()) {
            KleeCard(modifier = Modifier.fillMaxWidth()) {
                Text(state.message, color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    confirmBypassTarget?.let { enable ->
        AlertDialog(
            onDismissRequest = { confirmBypassTarget = null },
            containerColor = KleeColors.CardElevated,
            title = {
                Text(
                    if (enable) "Ativar Bypass Charging?" else "Retomar Carregamento Normal?",
                    color = KleeColors.TextPrimary
                )
            },
            text = {
                Text(
                    if (enable)
                        "Isso escreverá 1 em /sys/class/power_supply/battery/input_suspend, pausando o carregamento da bateria. Ideal para jogos na tomada sem aquecer a bateria."
                    else
                        "Isso restaurará input_suspend=0 para o aparelho voltar a carregar normalmente.",
                    color = KleeColors.TextSecondary
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmBypassTarget = null
                    onToggleBypass(enable)
                }) { Text("Confirmar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBypassTarget = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun PowerMetricCell(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(KleeColors.Surface)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
        Text(value, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun PowerInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(value.ifBlank { "--" }, color = KleeColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
