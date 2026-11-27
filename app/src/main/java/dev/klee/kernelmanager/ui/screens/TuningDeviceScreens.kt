package dev.klee.kernelmanager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storage
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
import dev.klee.kernelmanager.IoDevice
import dev.klee.kernelmanager.TuningSnapshot
import dev.klee.kernelmanager.UfsInfo
import dev.klee.kernelmanager.ui.components.KleeCard
import dev.klee.kernelmanager.ui.components.ProgressMeterBar
import dev.klee.kernelmanager.ui.components.RootWarningCard
import dev.klee.kernelmanager.ui.components.SectionHeader
import dev.klee.kernelmanager.ui.components.SelectorBox
import dev.klee.kernelmanager.ui.components.StatusPill
import dev.klee.kernelmanager.ui.components.formatUptime
import dev.klee.kernelmanager.ui.theme.KleeColors
import kotlin.math.roundToInt

@Composable
fun TuningScreen(
    state: TuningSnapshot,
    busy: Boolean,
    onRefresh: () -> Unit,
    onApplyVm: (String, Int) -> Unit,
    onApplyMglru: (Boolean) -> Unit,
    onApplyUfsBool: (String, Boolean) -> Unit,
    onApplyIo: (String, String?, Int?, Boolean?) -> Unit,
    onApplyTcp: (String) -> Unit,
    onApplyTcpFastOpen: (Int) -> Unit,
    onDropCaches: () -> Unit
) {
    var confirmDrop by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "Memória, UFS 4.0 & Rede",
            subtitle = "ZRAM, MGLRU, VM, WriteBooster UFS, I/O e TCP BBR",
            trailing = {
                FilledTonalIconButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = KleeColors.Tuning.copy(alpha = 0.18f),
                        contentColor = KleeColors.Tuning
                    )
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar")
                }
            }
        )

        if (!state.root) {
            RootWarningCard()
        }

        // Memory, ZRAM Analytics & MGLRU
        state.memory?.let { mem ->
            val ramFrac = if (mem.totalMb > 0) (mem.usedMb.toFloat() / mem.totalMb.toFloat()).coerceIn(0f, 1f) else 0f
            val swapFrac = if (mem.swapTotalMb > 0) (mem.swapUsedMb.toFloat() / mem.swapTotalMb.toFloat()).coerceIn(0f, 1f) else 0f

            KleeCard(modifier = Modifier.fillMaxWidth(), accentBorder = KleeColors.Tuning.copy(alpha = 0.35f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("RAM & ZRAM (${mem.zramAlgo})", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                    if (mem.zramCompressionRatio > 0.1) {
                        StatusPill("${"%.2f".format(mem.zramCompressionRatio)}x comp.", KleeColors.Success)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("RAM Usada", color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Text("${mem.usedMb} / ${mem.totalMb} MB", color = KleeColors.TextPrimary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    ProgressMeterBar(fraction = ramFrac, color = KleeColors.Monitor, heightDp = 6)
                }

                if (mem.swapTotalMb > 0 || mem.zramDiskSizeMb > 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("ZRAM (${mem.zramDiskSizeMb} MB)", color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            Text("${mem.swapUsedMb} / ${mem.swapTotalMb} MB", color = KleeColors.TextPrimary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        ProgressMeterBar(fraction = swapFrac, color = KleeColors.Tuning, heightDp = 6)
                    }
                    if (mem.zramOrigMb > 0 && mem.zramComprMb > 0) {
                        val saved = (mem.zramOrigMb - mem.zramComprMb).coerceAtLeast(0L)
                        Text(
                            "Dados comprimidos: ${mem.zramOrigMb} MB -> ${mem.zramComprMb} MB (economia real de ~$saved MB de RAM)",
                            color = KleeColors.TextMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                OutlinedButton(
                    onClick = { confirmDrop = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Liberar Page Cache (Drop Caches)")
                }
            }

            // MGLRU Card (CONFIG_LRU_GEN=y confirmed in Linux 6.6.89!)
            if (mem.mglruAvailable) {
                KleeCard(
                    modifier = Modifier.fillMaxWidth(),
                    accentBorder = (if (mem.mglruActive) KleeColors.Success else KleeColors.Border).copy(alpha = 0.5f)
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
                            Icon(Icons.Rounded.Memory, contentDescription = null, tint = KleeColors.Success, modifier = Modifier.size(20.dp))
                            Column {
                                Text("Multi-Gen LRU (MGLRU)", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Estado: ${mem.mglruEnabledHex} · min_ttl=${mem.mglruMinTtlMs}ms",
                                    color = KleeColors.TextMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        Switch(
                            checked = mem.mglruActive,
                            onCheckedChange = { onApplyMglru(it) },
                            enabled = !busy
                        )
                    }
                    Text(
                        "Algoritmo moderno de gerenciamento de páginas do Linux 6.6. Ativar MGLRU melhora multitarefa e reduz travamentos sob uso intenso de RAM.",
                        color = KleeColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // VM Sliders Card
            KleeCard(modifier = Modifier.fillMaxWidth()) {
                Text("Parâmetros de Memória Virtual (/proc/sys/vm)", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)

                VmSliderRow(
                    title = "vm.swappiness",
                    subtitle = "Tendência do kernel de mover páginas para ZRAM (0–200)",
                    currentValue = mem.swappiness,
                    range = 0f..200f,
                    enabled = !busy && mem.canWriteSwappiness,
                    onApply = { onApplyVm("swappiness", it) }
                )
                HorizontalDivider(color = KleeColors.Border)
                VmSliderRow(
                    title = "vm.vfs_cache_pressure",
                    subtitle = "Pressão para reciclar caches de diretórios/inodes (10–300)",
                    currentValue = mem.vfsCachePressure.coerceIn(10, 300),
                    range = 10f..300f,
                    enabled = !busy && mem.canWriteVfs,
                    onApply = { onApplyVm("vfs_cache_pressure", it) }
                )
                HorizontalDivider(color = KleeColors.Border)
                VmSliderRow(
                    title = "vm.dirty_ratio",
                    subtitle = "Limite máximo (%) de páginas sujas antes de gravação síncrona",
                    currentValue = mem.dirtyRatio.coerceIn(5, 80),
                    range = 5f..80f,
                    enabled = !busy && mem.canWriteDirty,
                    onApply = { onApplyVm("dirty_ratio", it) }
                )
                HorizontalDivider(color = KleeColors.Border)
                VmSliderRow(
                    title = "vm.watermark_scale_factor",
                    subtitle = "Antecipação de kswapd para liberar RAM em segundo plano (10–300)",
                    currentValue = mem.watermarkScaleFactor.coerceIn(10, 300),
                    range = 10f..300f,
                    enabled = !busy && mem.canWriteWatermark,
                    onApply = { onApplyVm("watermark_scale_factor", it) }
                )
            }
        }

        // UFS 4.0 Storage Card (112b0000.ufshci)
        if (state.ufs.available) {
            UfsControlCard(state.ufs, busy, onApplyUfsBool)
        }

        // Block I/O Devices
        if (state.ioDevices.isNotEmpty()) {
            Text("Escalonadores de I/O (Blocos)", color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
            state.ioDevices.forEach { io ->
                IoDeviceCard(io, busy, onApplyIo)
            }
        }

        // TCP Congestion Control + Fast Open
        state.tcp?.let { tcp ->
            if (tcp.available.isNotEmpty()) {
                var selectedTcp by remember(tcp.current) { mutableStateOf(tcp.current) }
                KleeCard(modifier = Modifier.fillMaxWidth(), accentBorder = KleeColors.Primary.copy(alpha = 0.28f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.NetworkCheck, contentDescription = null, tint = KleeColors.Primary, modifier = Modifier.size(20.dp))
                            Text("Rede & TCP", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                        }
                        if (tcp.defaultQdisc.isNotBlank()) {
                            StatusPill(tcp.defaultQdisc, KleeColors.Primary)
                        }
                    }
                    Text(
                        "Algoritmo ativo: ${tcp.current} (BBR recomendado para menor latência em Wi-Fi/5G)",
                        color = KleeColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    SelectorBox(
                        label = "Controle de Congestionamento TCP",
                        selectedText = selectedTcp,
                        options = tcp.available,
                        enabled = tcp.canWrite,
                        accent = KleeColors.Primary,
                        onSelected = { selectedTcp = it }
                    )
                    Button(
                        onClick = { onApplyTcp(selectedTcp) },
                        enabled = !busy && tcp.canWrite && selectedTcp.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Aplicar TCP ($selectedTcp)")
                    }

                    HorizontalDivider(color = KleeColors.Border)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("TCP Fast Open (TFO)", color = KleeColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                            Text("Atual: ${tcp.tcpFastOpen} (3 = Cliente + Servidor)", color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0, 1, 3).forEach { mode ->
                                FilterChip(
                                    selected = tcp.tcpFastOpen == mode,
                                    onClick = { onApplyTcpFastOpen(mode) },
                                    enabled = !busy && tcp.canWriteFastOpen,
                                    label = { Text("$mode") }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    if (confirmDrop) {
        AlertDialog(
            onDismissRequest = { confirmDrop = false },
            containerColor = KleeColors.CardElevated,
            title = { Text("Liberar Caches de Memória?", color = KleeColors.TextPrimary) },
            text = {
                Text(
                    "Executa sync seguido de drop_caches=3. Seguro, mas apps em cache podem recarregar mais devagar na próxima abertura.",
                    color = KleeColors.TextSecondary
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmDrop = false
                    onDropCaches()
                }) { Text("Confirmar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDrop = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun UfsControlCard(
    ufs: UfsInfo,
    busy: Boolean,
    onToggle: (String, Boolean) -> Unit
) {
    KleeCard(modifier = Modifier.fillMaxWidth(), accentBorder = KleeColors.Device.copy(alpha = 0.35f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.SdStorage, contentDescription = null, tint = KleeColors.Device, modifier = Modifier.size(20.dp))
                Column {
                    Text("Armazenamento UFS 4.0", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text("112b0000.ufshci", color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                }
            }
            if (ufs.currentMHz > 0) {
                StatusPill("${ufs.currentMHz} / ${ufs.maxMHz} MHz", KleeColors.Device)
            }
        }

        UfsSwitchRow("WriteBooster (wb_on)", "Cache SLC de alta velocidade para gravações rápidas", ufs.wbOn, !busy) {
            onToggle("wb_on", it)
        }
        HorizontalDivider(color = KleeColors.Border)
        UfsSwitchRow("Clock Scaling (clkscale_enable)", "Escalonamento dinâmico de clock UFS (273–499 MHz)", ufs.clkscaleEnable, !busy) {
            onToggle("clkscale_enable", it)
        }
        HorizontalDivider(color = KleeColors.Border)
        UfsSwitchRow("Clock Gating (clkgate_enable)", "Reduz consumo desligando clock UFS em repouso", ufs.clkgateEnable, !busy) {
            onToggle("clkgate_enable", it)
        }
    }
}

@Composable
private fun UfsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean?,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = KleeColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked == true, onCheckedChange = onChange, enabled = enabled && checked != null)
    }
}

@Composable
private fun VmSliderRow(
    title: String,
    subtitle: String,
    currentValue: Int,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onApply: (Int) -> Unit
) {
    var sliderVal by remember(currentValue) { mutableFloatStateOf(currentValue.toFloat().coerceIn(range.start, range.endInclusive)) }
    val intVal = sliderVal.roundToInt()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = KleeColors.TextPrimary, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                Text(subtitle, color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            StatusPill(intVal.toString(), KleeColors.Tuning)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Slider(
                value = sliderVal,
                onValueChange = { sliderVal = it },
                valueRange = range,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(
                onClick = { onApply(intVal) },
                enabled = enabled && intVal != currentValue,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Salvar")
            }
        }
    }
}

@Composable
private fun IoDeviceCard(
    io: IoDevice,
    busy: Boolean,
    onApply: (String, String?, Int?, Boolean?) -> Unit
) {
    var selectedSched by remember(io.name, io.currentScheduler) { mutableStateOf(io.currentScheduler) }
    var selectedRa by remember(io.name, io.readAheadKb) { mutableIntStateOf(io.readAheadKb) }
    var ioStats by remember(io.name, io.ioStatsEnabled) { mutableStateOf(io.ioStatsEnabled) }
    val raOptions = listOf(128, 256, 512, 1024, 2048).let { base ->
        if (base.contains(io.readAheadKb)) base else (base + io.readAheadKb).sorted()
    }

    KleeCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Storage, contentDescription = null, tint = KleeColors.Tuning, modifier = Modifier.size(18.dp))
                Text("/dev/block/${io.name}", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
            }
            StatusPill("${io.currentScheduler} · ${io.readAheadKb} KB", KleeColors.Tuning)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectorBox(
                label = "Scheduler",
                selectedText = selectedSched,
                options = io.availableSchedulers,
                enabled = io.canWriteScheduler && io.availableSchedulers.size > 1,
                accent = KleeColors.Tuning,
                onSelected = { selectedSched = it },
                modifier = Modifier.weight(1f)
            )
            SelectorBox(
                label = "Read-Ahead",
                selectedText = "$selectedRa KB",
                options = raOptions.map { "$it KB" },
                enabled = io.canWriteReadAhead,
                accent = KleeColors.Tuning,
                onSelected = { label ->
                    selectedRa = label.removeSuffix(" KB").trim().toIntOrNull() ?: selectedRa
                },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("I/O Stats (desativar reduz overhead)", color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = ioStats,
                onCheckedChange = { ioStats = it },
                enabled = !busy && io.canWriteIoStats
            )
        }

        Button(
            onClick = {
                onApply(
                    io.name,
                    selectedSched.takeIf { io.canWriteScheduler && io.availableSchedulers.size > 1 },
                    selectedRa.takeIf { io.canWriteReadAhead },
                    ioStats.takeIf { io.canWriteIoStats }
                )
            },
            enabled = !busy && (io.canWriteScheduler || io.canWriteReadAhead || io.canWriteIoStats),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = KleeColors.Tuning, contentColor = Color.White)
        ) {
            Text("Aplicar I/O em ${io.name}")
        }
    }
}

@Composable
fun DeviceScreen(
    info: DeviceInfoSnapshot,
    logs: List<ActionLogEntry>,
    busy: Boolean,
    onRefresh: () -> Unit,
    onExportReport: ((String) -> Unit) -> Unit,
    onClearLogs: () -> Unit,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "Dispositivo & Logs",
            subtitle = "Identidade de hardware, relatório técnico e histórico root",
            trailing = {
                FilledTonalIconButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = KleeColors.Device.copy(alpha = 0.18f),
                        contentColor = KleeColors.Device
                    )
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar Dispositivo")
                }
            }
        )

        if (!info.root) {
            RootWarningCard()
        }

        // Device Identity Card
        KleeCard(modifier = Modifier.fillMaxWidth(), accentBorder = KleeColors.Device.copy(alpha = 0.35f)) {
            Text("Identidade do Sistema", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
            DeviceRow("Modelo", "${info.brand} ${info.model}".trim())
            DeviceRow("Codinome", info.device)
            DeviceRow("SoC / Plataforma", "${info.soc} (${info.hardware.ifBlank { "MTK" }})")
            if (info.hyperOsVersion.isNotBlank()) {
                DeviceRow("HyperOS", info.hyperOsVersion)
            }
            DeviceRow("Android", "${info.androidVersion} (API ${info.sdkInt})")
            DeviceRow("Patch de Segurança", info.securityPatch)
            DeviceRow("SELinux", info.selinuxMode)
            DeviceRow("Provedor Root", info.rootProvider)
            DeviceRow("Arquitetura / Núcleos", "${info.abi} · ${info.cpuCores} cores")
            DeviceRow("RAM Total", if (info.totalRamMb > 0) "${info.totalRamMb} MB" else "--")
            DeviceRow("Uptime", formatUptime(info.uptimeSeconds))
            if (info.kernelVersion.isNotBlank()) {
                HorizontalDivider(color = KleeColors.Border)
                Text("Versão do Kernel", color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
                Text(
                    info.kernelVersion,
                    color = KleeColors.Device,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Export Diagnostic Report Card
        KleeCard(modifier = Modifier.fillMaxWidth()) {
            Text("Relatório de Diagnóstico Completo", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text(
                "Coleta CPU, UCLAMP, GPU/GED, DRAM, FPSGO/Metis, sensores térmicos, bateria, ZRAM/MGLRU e UFS em texto formatado.",
                color = KleeColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        onExportReport { report ->
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("KLEE Diagnostic Report", report))
                            onNotify("Relatório copiado para a área de transferência!")
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copiar")
                }
                Button(
                    onClick = {
                        onExportReport { report ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "KLEE Kernel Manager - Diagnóstico")
                                putExtra(Intent.EXTRA_TEXT, report)
                            }
                            context.startActivity(Intent.createChooser(intent, "Compartilhar diagnóstico"))
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KleeColors.Device, contentColor = Color.Black)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Compartilhar")
                }
            }
        }

        // Action Log Section
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
                Text(
                    "Nenhuma escrita no kernel realizada nesta sessão.",
                    color = KleeColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                logs.forEach { log ->
                    val accent = if (log.isError) KleeColors.Danger else KleeColors.Success
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(KleeColors.Surface)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(log.action, color = accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            Text(log.timestamp, color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            log.detail,
                            color = KleeColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DeviceRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Text(
            value.ifBlank { "--" },
            color = KleeColors.TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}
