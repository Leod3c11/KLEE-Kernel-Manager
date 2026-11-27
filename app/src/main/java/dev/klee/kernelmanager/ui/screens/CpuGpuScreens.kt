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
import dev.klee.kernelmanager.GpuControl
import dev.klee.kernelmanager.ui.components.KleeCard
import dev.klee.kernelmanager.ui.components.ProgressMeterBar
import dev.klee.kernelmanager.ui.components.RootWarningCard
import dev.klee.kernelmanager.ui.components.SectionHeader
import dev.klee.kernelmanager.ui.components.SelectorBox
import dev.klee.kernelmanager.ui.components.StatusPill
import dev.klee.kernelmanager.ui.components.governorDescription
import dev.klee.kernelmanager.ui.theme.KleeColors

@Composable
fun CpuGpuTabScreen(
    cpuState: ControlSnapshot,
    gpuState: ControlSnapshot,
    busy: Boolean,
    initialTab: Int = 0,
    onRefreshCpu: () -> Unit,
    onApplyCpu: (String, Long, Long, String?) -> Unit,
    onResetCpu: (CpuPolicy) -> Unit,
    onRefreshGpu: () -> Unit,
    onApplyGpu: (String, Long, Long, String?) -> Unit,
    onResetGpu: (GpuControl) -> Unit
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
                text = { Text("CPU Clusters", fontWeight = FontWeight.SemiBold) },
                icon = { Icon(Icons.Rounded.Memory, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("GPU Mali", fontWeight = FontWeight.SemiBold) },
                icon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }

        if (selectedTab == 0) {
            CpuScreen(cpuState, busy, onRefreshCpu, onApplyCpu, onResetCpu)
        } else {
            GpuScreen(gpuState, busy, onRefreshGpu, onApplyGpu, onResetGpu)
        }
    }
}

@Composable
fun CpuScreen(
    state: ControlSnapshot,
    busy: Boolean,
    onRefresh: () -> Unit,
    onApply: (String, Long, Long, String?) -> Unit,
    onReset: (CpuPolicy) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "Controle de CPU",
            subtitle = "Escalonamento seguro com verificação de leitura e rollback",
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
                state.cpuPolicies.size == 3 && rank == 0 -> "Cluster Eficiência (Little)"
                state.cpuPolicies.size == 3 && rank == 1 -> "Cluster Desempenho (Big)"
                state.cpuPolicies.size == 3 && rank == 2 -> "Cluster Prime (Ultra)"
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

        Spacer(Modifier.height(12.dp))
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
                Text("Atual: ${policy.minKHz / 1000}–${policy.maxKHz / 1000} MHz", color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
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

        if (!policy.canWriteMin || !policy.canWriteMax) {
            Text(
                "Este kernel não permite escrita nos limites desta policy.",
                color = KleeColors.Danger,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun GpuScreen(
    state: ControlSnapshot,
    busy: Boolean,
    onRefresh: () -> Unit,
    onApply: (String, Long, Long, String?) -> Unit,
    onReset: (GpuControl) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "Controle de GPU",
            subtitle = "Mali-G720 Devfreq com validação estrita de tabela OPP",
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
            GpuControlDetailCard(gpu, busy, onApply, onReset)
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
            StatusPill("${gpu.currentHz / 1_000_000} MHz", KleeColors.Gpu)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ProgressMeterBar(fraction = curFrac, color = KleeColors.Gpu, heightDp = 8)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Mín: ${gpu.minHz / 1_000_000} MHz", color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                Text("${gpu.frequenciesHz.size} OPPs detectadas", color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
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

        if (!gpu.canWriteMin || !gpu.canWriteMax || gpu.frequenciesHz.isEmpty()) {
            Text(
                "Nós de controle ou tabela OPP indisponíveis. A escrita fica bloqueada para segurança.",
                color = KleeColors.Danger,
                style = MaterialTheme.typography.bodySmall
            )
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
