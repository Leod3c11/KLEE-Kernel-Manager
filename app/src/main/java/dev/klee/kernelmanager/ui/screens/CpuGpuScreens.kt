package dev.klee.kernelmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.klee.kernelmanager.ControlSnapshot
import dev.klee.kernelmanager.CpuCoreStatus
import dev.klee.kernelmanager.CpuPolicy
import dev.klee.kernelmanager.DramDevfreqInfo
import dev.klee.kernelmanager.GedControlInfo
import dev.klee.kernelmanager.GpuControl
import dev.klee.kernelmanager.UclampInfo
import dev.klee.kernelmanager.ui.components.KleeCard
import dev.klee.kernelmanager.ui.components.ProgressMeterBar
import dev.klee.kernelmanager.ui.components.RootWarningCard
import dev.klee.kernelmanager.ui.components.SectionHeader
import dev.klee.kernelmanager.ui.components.SelectorBox
import dev.klee.kernelmanager.ui.components.StatusPill
import dev.klee.kernelmanager.ui.components.governorDescription
import dev.klee.kernelmanager.ui.theme.KleeColors
import kotlin.math.roundToInt

@Composable
fun CpuGpuTabScreen(
    cpuState: ControlSnapshot,
    gpuState: ControlSnapshot,
    busy: Boolean,
    initialTab: Int = 0,
    onRefreshCpu: () -> Unit,
    onApplyCpu: (String, Long, Long, String?) -> Unit,
    onResetCpu: (CpuPolicy) -> Unit,
    onApplyUclamp: (Int, Int, Boolean) -> Unit,
    onRefreshGpu: () -> Unit,
    onApplyGpu: (String, Long, Long, String?) -> Unit,
    onResetGpu: (GpuControl) -> Unit,
    onApplyGedToggle: (String, Boolean) -> Unit,
    onApplyGedInt: (String, Int) -> Unit,
    onApplyDram: (Long, Long) -> Unit,
    onResetDram: (DramDevfreqInfo) -> Unit
) {
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 0 && cpuState.cpuPolicies.isEmpty()) onRefreshCpu()
        if (selectedTab == 1 && gpuState.gpu == null) onRefreshGpu()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = KleeColors.Surface,
            contentColor = KleeColors.Primary
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("CPU & UCLAMP", fontWeight = FontWeight.SemiBold) },
                icon = { Icon(Icons.Rounded.Memory, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("GPU, GED & DRAM", fontWeight = FontWeight.SemiBold) },
                icon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }

        if (selectedTab == 0) {
            CpuScreen(cpuState, busy, onRefreshCpu, onApplyCpu, onResetCpu, onApplyUclamp)
        } else {
            GpuScreen(
                gpuState, busy, onRefreshGpu, onApplyGpu, onResetGpu,
                onApplyGedToggle, onApplyGedInt, onApplyDram, onResetDram
            )
        }
    }
}

@Composable
fun CpuScreen(
    state: ControlSnapshot,
    busy: Boolean,
    onRefresh: () -> Unit,
    onApply: (String, Long, Long, String?) -> Unit,
    onReset: (CpuPolicy) -> Unit,
    onApplyUclamp: (Int, Int, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "Controle de CPU & UCLAMP",
            subtitle = "3 clusters MT6899 (sugov_ext) com rollback seguro",
            trailing = {
                FilledTonalIconButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = KleeColors.Cpu.copy(alpha = 0.18f),
                        contentColor = KleeColors.Cpu
                    )
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar CPU")
                }
            }
        )

        if (!state.root) {
            RootWarningCard()
        }

        if (state.cpuCores.isNotEmpty()) {
            CpuCoresOverviewCard(state.cpuCores)
        }

        if (state.message.isNotBlank()) {
            KleeCard(modifier = Modifier.fillMaxWidth()) {
                Text(state.message, color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }

        val sortedPolicies = state.cpuPolicies.sortedBy { it.hardwareMaxKHz }
        state.cpuPolicies.forEachIndexed { idx, policy ->
            val rank = sortedPolicies.indexOfFirst { it.id == policy.id }
            val clusterTitle = when {
                state.cpuPolicies.size == 3 && rank == 0 -> "Cluster Eficiência (Little · C0–C3)"
                state.cpuPolicies.size == 3 && rank == 1 -> "Cluster Desempenho (Medium · C4–C6)"
                state.cpuPolicies.size == 3 && rank == 2 -> "Cluster Prime (Big · C7)"
                state.cpuPolicies.size == 2 && rank == 0 -> "Cluster Eficiência"
                state.cpuPolicies.size == 2 && rank == 1 -> "Cluster Desempenho"
                else -> "Cluster ${idx + 1}"
            }
            CpuPolicyControlCard(
                policy = policy,
                clusterTitle = clusterTitle,
                busy = busy,
                onApply = onApply,
                onReset = onReset
            )
        }

        state.uclamp?.let { uclamp ->
            UclampControlCard(uclamp, busy, onApplyUclamp)
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun UclampControlCard(
    uclamp: UclampInfo,
    busy: Boolean,
    onApply: (Int, Int, Boolean) -> Unit
) {
    var minVal by remember(uclamp.minPercent) { mutableFloatStateOf(uclamp.minPercent) }
    var maxVal by remember(uclamp.maxPercent) { mutableFloatStateOf(uclamp.maxPercent) }
    var latSens by remember(uclamp.latencySensitive) { mutableStateOf(uclamp.latencySensitive) }

    KleeCard(modifier = Modifier.fillMaxWidth(), accentBorder = KleeColors.Primary.copy(alpha = 0.3f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = KleeColors.Primary, modifier = Modifier.size(18.dp))
                Column {
                    Text("UCLAMP (top-app)", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text("Controle de utilização mínima/máxima do app ativo", color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            StatusPill("${minVal.roundToInt()}% – ${maxVal.roundToInt()}%", KleeColors.Primary)
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("uclamp.min: ${minVal.roundToInt()}% (piso de responsividade)", color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            Slider(
                value = minVal,
                onValueChange = {
                    minVal = it
                    if (maxVal < it) maxVal = it
                },
                valueRange = 0f..100f,
                enabled = !busy && uclamp.canWriteMin
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("uclamp.max: ${maxVal.roundToInt()}% (teto de utilização)", color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            Slider(
                value = maxVal,
                onValueChange = {
                    maxVal = it
                    if (minVal > it) minVal = it
                },
                valueRange = 0f..100f,
                enabled = !busy && uclamp.canWriteMax
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Latency Sensitive", color = KleeColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                Text("Prioriza alocação imediata de núcleos rápidos para UI/jogos", color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = latSens,
                onCheckedChange = { latSens = it },
                enabled = !busy && uclamp.canWriteLatency
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onApply(0, 100, true) },
                enabled = !busy && uclamp.canWriteMin,
                modifier = Modifier.weight(0.42f),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Padrão") }
            Button(
                onClick = { onApply(minVal.roundToInt(), maxVal.roundToInt(), latSens) },
                enabled = !busy && (uclamp.canWriteMin || uclamp.canWriteMax),
                modifier = Modifier.weight(0.58f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Aplicar UCLAMP")
            }
        }
    }
}

@Composable
private fun CpuCoresOverviewCard(cores: List<CpuCoreStatus>) {
    KleeCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Núcleos em Tempo Real", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
            val onlineCount = cores.count { it.online }
            StatusPill("$onlineCount / ${cores.size} online", KleeColors.Cpu)
        }

        cores.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { core ->
                    val accent = if (core.online) KleeColors.Cpu else KleeColors.TextMuted
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(KleeColors.Surface)
                            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "CPU ${core.coreId}",
                            color = KleeColors.TextSecondary,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            if (core.online) "${core.currentKHz / 1000}" else "OFF",
                            color = accent,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                        if (core.online) {
                            Text("MHz", color = KleeColors.TextMuted, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CpuPolicyControlCard(
    policy: CpuPolicy,
    clusterTitle: String,
    busy: Boolean,
    onApply: (String, Long, Long, String?) -> Unit,
    onReset: (CpuPolicy) -> Unit
) {
    val freqTable = policy.frequenciesKHz
    var selectedMinKHz by remember(policy.id, policy.minKHz) { mutableLongStateOf(policy.minKHz) }
    var selectedMaxKHz by remember(policy.id, policy.maxKHz) { mutableLongStateOf(policy.maxKHz) }
    var manualMinMHz by remember(policy.id, policy.minKHz) { mutableStateOf((policy.minKHz / 1000L).toString()) }
    var manualMaxMHz by remember(policy.id, policy.maxKHz) { mutableStateOf((policy.maxKHz / 1000L).toString()) }
    var selectedGovernor by remember(policy.id, policy.governor) { mutableStateOf(policy.governor) }

    val hwRange = (policy.hardwareMaxKHz - policy.hardwareMinKHz).coerceAtLeast(1L)
    val curFraction = ((policy.currentKHz - policy.hardwareMinKHz).toFloat() / hwRange.toFloat()).coerceIn(0f, 1f)

    KleeCard(
        modifier = Modifier.fillMaxWidth(),
        accentBorder = KleeColors.Cpu.copy(alpha = 0.32f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(clusterTitle, color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Policy ${policy.id} · CPUs ${policy.cpus}" +
                        if (policy.driver.isNotBlank()) " · ${policy.driver}" else "",
                    color = KleeColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            StatusPill("${policy.currentKHz / 1000} MHz", KleeColors.Cpu)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ProgressMeterBar(fraction = curFraction, color = KleeColors.Cpu, heightDp = 7)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("HW Mín: ${policy.hardwareMinKHz / 1000} MHz", color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
                Text("Limites: ${policy.minKHz / 1000}–${policy.maxKHz / 1000} MHz", color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                Text("HW Máx: ${policy.hardwareMaxKHz / 1000} MHz", color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
            }
        }

        if (freqTable.isNotEmpty()) {
            val options = freqTable.map { "${it / 1000} MHz" }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SelectorBox(
                    label = "Frequência Mínima",
                    selectedText = "${selectedMinKHz / 1000} MHz",
                    options = options,
                    enabled = policy.canWriteMin,
                    accent = KleeColors.Cpu,
                    onSelected = { label ->
                        val khz = freqTable.firstOrNull { "${it / 1000} MHz" == label } ?: return@SelectorBox
                        selectedMinKHz = khz
                        if (selectedMaxKHz < khz) selectedMaxKHz = khz
                    },
                    modifier = Modifier.weight(1f)
                )
                SelectorBox(
                    label = "Frequência Máxima",
                    selectedText = "${selectedMaxKHz / 1000} MHz",
                    options = options,
                    enabled = policy.canWriteMax,
                    accent = KleeColors.Cpu,
                    onSelected = { label ->
                        val khz = freqTable.firstOrNull { "${it / 1000} MHz" == label } ?: return@SelectorBox
                        selectedMaxKHz = khz
                        if (selectedMinKHz > khz) selectedMinKHz = khz
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = manualMinMHz,
                    onValueChange = { manualMinMHz = it.filter(Char::isDigit) },
                    label = { Text("Mín. MHz") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = policy.canWriteMin
                )
                OutlinedTextField(
                    value = manualMaxMHz,
                    onValueChange = { manualMaxMHz = it.filter(Char::isDigit) },
                    label = { Text("Máx. MHz") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = policy.canWriteMax
                )
            }
        }

        SelectorBox(
            label = "Governor",
            selectedText = selectedGovernor,
            options = policy.governors,
            enabled = policy.canWriteGovernor && policy.governors.isNotEmpty(),
            accent = KleeColors.Primary,
            onSelected = { selectedGovernor = it },
            modifier = Modifier.fillMaxWidth()
        )
        if (selectedGovernor.isNotBlank()) {
            Text(
                governorDescription(selectedGovernor),
                color = KleeColors.TextMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { onReset(policy) },
                enabled = !busy && policy.canWriteMin && policy.canWriteMax,
                modifier = Modifier.weight(0.42f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reset")
            }
            Button(
                onClick = {
                    val minK = if (freqTable.isNotEmpty()) selectedMinKHz else (manualMinMHz.toLongOrNull()?.times(1000L) ?: 0L)
                    val maxK = if (freqTable.isNotEmpty()) selectedMaxKHz else (manualMaxMHz.toLongOrNull()?.times(1000L) ?: 0L)
                    if (minK > 0 && maxK >= minK) {
                        onApply(policy.id, minK, maxK, selectedGovernor.takeIf { policy.canWriteGovernor })
                    }
                },
                enabled = !busy && policy.canWriteMin && policy.canWriteMax &&
                    (freqTable.isNotEmpty() || (manualMinMHz.toLongOrNull() != null && manualMaxMHz.toLongOrNull() != null)),
                modifier = Modifier.weight(0.58f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KleeColors.Cpu, contentColor = Color.White)
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Aplicar Policy ${policy.id}")
            }
        }
    }
}

@Composable
fun GpuScreen(
    state: ControlSnapshot,
    busy: Boolean,
    onRefresh: () -> Unit,
    onApply: (String, Long, Long, String?) -> Unit,
    onReset: (GpuControl) -> Unit,
    onApplyGedToggle: (String, Boolean) -> Unit,
    onApplyGedInt: (String, Int) -> Unit,
    onApplyDram: (Long, Long) -> Unit,
    onResetDram: (DramDevfreqInfo) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "GPU Mali, GED & DRAM",
            subtitle = "49 OPPs (260–1508 MHz), motor MTK GED e barramento LPDDR5X",
            trailing = {
                FilledTonalIconButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = KleeColors.Gpu.copy(alpha = 0.18f),
                        contentColor = KleeColors.Gpu
                    )
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar GPU")
                }
            }
        )

        if (!state.root) {
            RootWarningCard()
        }

        state.gpu?.let { gpu ->
            GpuControlDetailCard(gpu, state.ged.gpuUtilization, busy, onApply, onReset)
        }

        if (state.ged.available) {
            GedControlCard(state.ged, busy, onApplyGedToggle, onApplyGedInt)
        }

        state.dram?.let { dram ->
            DramControlCard(dram, busy, onApplyDram, onResetDram)
        }

        if (state.message.isNotBlank()) {
            KleeCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    state.message,
                    color = KleeColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun GpuControlDetailCard(
    gpu: GpuControl,
    gpuUtil: Int?,
    busy: Boolean,
    onApply: (String, Long, Long, String?) -> Unit,
    onReset: (GpuControl) -> Unit
) {
    var selectedMinHz by remember(gpu.minHz) { mutableLongStateOf(gpu.minHz) }
    var selectedMaxHz by remember(gpu.maxHz) { mutableLongStateOf(gpu.maxHz) }
    var selectedGovernor by remember(gpu.governor) { mutableStateOf(gpu.governor) }
    var showDiag by remember { mutableStateOf(false) }

    val range = (gpu.hardwareMaxHz - gpu.hardwareMinHz).coerceAtLeast(1L)
    val curFrac = ((gpu.currentHz - gpu.hardwareMinHz).toFloat() / range.toFloat()).coerceIn(0f, 1f)

    KleeCard(
        modifier = Modifier.fillMaxWidth(),
        accentBorder = KleeColors.Gpu.copy(alpha = 0.35f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(gpu.name, color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                Text(gpu.path, color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                gpuUtil?.let { StatusPill("Uso $it%", KleeColors.Primary) }
                StatusPill("${gpu.currentHz / 1_000_000} MHz", KleeColors.Gpu)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ProgressMeterBar(fraction = curFrac, color = KleeColors.Gpu, heightDp = 8)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Mín: ${gpu.minHz / 1_000_000} MHz", color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                Text("${gpu.frequenciesHz.size} OPPs", color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
                Text("Máx: ${gpu.maxHz / 1_000_000} MHz", color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
        }

        if (gpu.frequenciesHz.isNotEmpty()) {
            val options = gpu.frequenciesHz.map { "${it / 1_000_000} MHz" }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SelectorBox(
                    label = "OPP Mínima",
                    selectedText = "${selectedMinHz / 1_000_000} MHz",
                    options = options,
                    enabled = gpu.canWriteMin,
                    accent = KleeColors.Gpu,
                    onSelected = { label ->
                        val hz = gpu.frequenciesHz.firstOrNull { "${it / 1_000_000} MHz" == label } ?: return@SelectorBox
                        selectedMinHz = hz
                        if (selectedMaxHz < hz) selectedMaxHz = hz
                    },
                    modifier = Modifier.weight(1f)
                )
                SelectorBox(
                    label = "OPP Máxima",
                    selectedText = "${selectedMaxHz / 1_000_000} MHz",
                    options = options,
                    enabled = gpu.canWriteMax,
                    accent = KleeColors.Gpu,
                    onSelected = { label ->
                        val hz = gpu.frequenciesHz.firstOrNull { "${it / 1_000_000} MHz" == label } ?: return@SelectorBox
                        selectedMaxHz = hz
                        if (selectedMinHz > hz) selectedMinHz = hz
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        SelectorBox(
            label = "Governor Devfreq",
            selectedText = selectedGovernor,
            options = gpu.governors,
            enabled = gpu.canWriteGovernor && gpu.governors.isNotEmpty(),
            accent = KleeColors.Gpu,
            onSelected = { selectedGovernor = it },
            modifier = Modifier.fillMaxWidth()
        )
        Text(governorDescription(selectedGovernor), color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { onReset(gpu) },
                enabled = !busy && gpu.canWriteMin && gpu.canWriteMax && gpu.frequenciesHz.isNotEmpty(),
                modifier = Modifier.weight(0.4f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reset")
            }
            Button(
                onClick = {
                    onApply(
                        gpu.path,
                        selectedMinHz,
                        selectedMaxHz,
                        selectedGovernor.takeIf { gpu.canWriteGovernor }
                    )
                },
                enabled = !busy && gpu.canWriteMin && gpu.canWriteMax && gpu.frequenciesHz.isNotEmpty(),
                modifier = Modifier.weight(0.6f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KleeColors.Gpu, contentColor = Color.White)
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Aplicar GPU")
            }
        }
    }

    if (gpu.gpufreqDiag.isNotBlank()) {
        KleeCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showDiag = !showDiag }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Diagnóstico MediaTek gpufreqv2", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                Icon(
                    if (showDiag) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = KleeColors.TextSecondary
                )
            }
            if (showDiag) {
                Text(
                    gpu.gpufreqDiag,
                    color = KleeColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun GedControlCard(
    ged: GedControlInfo,
    busy: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onApplyInt: (String, Int) -> Unit
) {
    KleeCard(modifier = Modifier.fillMaxWidth(), accentBorder = KleeColors.Gpu.copy(alpha = 0.28f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("MediaTek GED (DVFS GPU)", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
            ged.gpuUtilization?.let { StatusPill("Carga: $it%", KleeColors.Gpu) }
        }

        GedSwitchRow("Smart Boost (ged_smart_boost)", "Impulso inteligente de GPU sob carga 3D", ged.gedSmartBoost, !busy) {
            onToggle("ged_smart_boost", it)
        }
        HorizontalDivider(color = KleeColors.Border)
        GedSwitchRow("GPU Boost (boost_gpu_enable)", "Habilita aceleração de transição de OPP", ged.boostGpuEnable, !busy) {
            onToggle("boost_gpu_enable", it)
        }
        HorizontalDivider(color = KleeColors.Border)
        GedSwitchRow("GED KPI (is_GED_KPI_enabled)", "Escalonamento orientado ao frame-time real", ged.gedKpiEnabled, !busy) {
            onToggle("is_GED_KPI_enabled", it)
        }
        HorizontalDivider(color = KleeColors.Border)
        GedSwitchRow("Dynamic Core Scaling (dcs_mode)", "Desliga shader cores ociosos para economizar bateria", ged.dcsMode, !busy) {
            onToggle("dcs_mode", it)
        }

        ged.dvfsMarginValue?.let { curMargin ->
            var marginVal by remember(curMargin) { mutableFloatStateOf(curMargin.coerceIn(0, 100).toFloat()) }
            HorizontalDivider(color = KleeColors.Border)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("DVFS Margin (dvfs_margin_value)", color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Text("${marginVal.roundToInt()}", color = KleeColors.Gpu, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Slider(
                        value = marginVal,
                        onValueChange = { marginVal = it },
                        valueRange = 0f..100f,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = { onApplyInt("dvfs_margin_value", marginVal.roundToInt()) },
                        enabled = !busy && marginVal.roundToInt() != curMargin,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("Salvar") }
                }
            }
        }
    }
}

@Composable
private fun GedSwitchRow(
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
private fun DramControlCard(
    dram: DramDevfreqInfo,
    busy: Boolean,
    onApply: (Long, Long) -> Unit,
    onReset: (DramDevfreqInfo) -> Unit
) {
    var selectedMin by remember(dram.minHz) { mutableLongStateOf(dram.minHz) }
    var selectedMax by remember(dram.maxHz) { mutableLongStateOf(dram.maxHz) }
    val hwMin = dram.frequenciesHz.firstOrNull() ?: dram.minHz
    val hwMax = dram.frequenciesHz.lastOrNull() ?: dram.maxHz
    val frac = ((dram.currentHz - hwMin).toFloat() / (hwMax - hwMin).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)

    KleeCard(modifier = Modifier.fillMaxWidth(), accentBorder = KleeColors.Monitor.copy(alpha = 0.3f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = KleeColors.Monitor, modifier = Modifier.size(18.dp))
                Column {
                    Text("DRAM LPDDR5X (DVFSRC)", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text("${dram.frequenciesHz.size} degraus · ${hwMin / 1_000_000}–${hwMax / 1_000_000} MHz", color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            StatusPill("${dram.currentHz / 1_000_000} MHz", KleeColors.Monitor)
        }

        ProgressMeterBar(fraction = frac, color = KleeColors.Monitor, heightDp = 6)

        if (dram.frequenciesHz.isNotEmpty()) {
            val options = dram.frequenciesHz.map { "${it / 1_000_000} MHz" }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectorBox(
                    label = "Piso DRAM",
                    selectedText = "${selectedMin / 1_000_000} MHz",
                    options = options,
                    enabled = dram.canWriteMin,
                    accent = KleeColors.Monitor,
                    onSelected = { label ->
                        val hz = dram.frequenciesHz.firstOrNull { "${it / 1_000_000} MHz" == label } ?: return@SelectorBox
                        selectedMin = hz
                        if (selectedMax < hz) selectedMax = hz
                    },
                    modifier = Modifier.weight(1f)
                )
                SelectorBox(
                    label = "Teto DRAM",
                    selectedText = "${selectedMax / 1_000_000} MHz",
                    options = options,
                    enabled = dram.canWriteMax,
                    accent = KleeColors.Monitor,
                    onSelected = { label ->
                        val hz = dram.frequenciesHz.firstOrNull { "${it / 1_000_000} MHz" == label } ?: return@SelectorBox
                        selectedMax = hz
                        if (selectedMin > hz) selectedMin = hz
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onReset(dram) },
                    enabled = !busy && dram.canWriteMin && dram.canWriteMax,
                    modifier = Modifier.weight(0.4f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Reset") }
                Button(
                    onClick = { onApply(selectedMin, selectedMax) },
                    enabled = !busy && dram.canWriteMin && dram.canWriteMax,
                    modifier = Modifier.weight(0.6f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = KleeColors.Monitor, contentColor = Color.Black)
                ) { Text("Aplicar DRAM", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
