package dev.klee.kernelmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { KleeApp() } }
    }
}

class KernelManagerViewModel : ViewModel() {
    private val repository = KernelControlRepository(RootShell())
    private val _cpu = MutableStateFlow(ControlSnapshot(false, message = "Toque em Atualizar para consultar as policies CPUFreq."))
    val cpu = _cpu.asStateFlow()
    private val _gpu = MutableStateFlow(ControlSnapshot(false, message = "Toque em Atualizar para consultar o controlador Mali devfreq."))
    val gpu = _gpu.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _thermal = MutableStateFlow(ControlSnapshot(false, message="Toque em Atualizar para ler sensores térmicos."))
    val thermal = _thermal.asStateFlow()
    private val _power = MutableStateFlow(ControlSnapshot(false, message="Toque em Atualizar para ler a bateria."))
    val power = _power.asStateFlow()
    private val _monitor = MutableStateFlow(ControlSnapshot(false, message="Abra Monitor para iniciar a coleta."))
    val monitor = _monitor.asStateFlow()
    private val originalGovernors = mutableMapOf<String, String>()

    fun loadCpu() = runTask {
        _cpu.value = withContext(Dispatchers.IO) { repository.readCpu() }
    }

    fun loadGpu() = runTask {
        _gpu.value = withContext(Dispatchers.IO) { repository.readGpu() }
    }

    fun loadThermal() = runTask { _thermal.value = withContext(Dispatchers.IO) { repository.readThermal() } }
    fun loadPower() = runTask { _power.value = withContext(Dispatchers.IO) { repository.readPower() } }
    fun loadMonitor() = runTask { _monitor.value = withContext(Dispatchers.IO) { repository.readMonitor() } }

    fun applyGamingProfile(performance: Boolean) = runTask {
        val snapshot = withContext(Dispatchers.IO) { repository.readCpu() }
        if (!snapshot.root || snapshot.cpuPolicies.isEmpty()) error("Não foi possível identificar as policies CPU.")
        snapshot.cpuPolicies.forEach { policy ->
            if (policy.governor.isNotBlank()) originalGovernors.putIfAbsent(policy.id, policy.governor)
            val target = if (performance) {
                if (policy.governors.contains("performance")) "performance" else error("Governor performance indisponível na policy " + policy.id)
            } else originalGovernors[policy.id] ?: policy.governor
            val result = withContext(Dispatchers.IO) { repository.applyCpu(policy.id, policy.minKHz, policy.maxKHz, target.takeIf { policy.canWriteGovernor }) }
            if (!result.contains("OK")) error("Policy " + policy.id + ": " + result)
        }
        _cpu.value = withContext(Dispatchers.IO) { repository.readCpu() }
        _message.value = if (performance) "Perfil Gaming aplicado; limites preservados." else "Governors originais restaurados; limites preservados."
    }

    fun applyCpu(id: String, minMHz: String, maxMHz: String, governor: String) = runTask {
        val min = minMHz.toLongOrNull()?.times(1000) ?: error("Frequência mínima inválida.")
        val max = maxMHz.toLongOrNull()?.times(1000) ?: error("Frequência máxima inválida.")
        val result = withContext(Dispatchers.IO) {
            repository.applyCpu(id, min, max, governor.ifBlank { null })
        }
        _message.value = result
        _cpu.value = withContext(Dispatchers.IO) { repository.readCpu() }
    }

    fun applyGpu(minMHz: String, maxMHz: String, governor: String) = runTask {
        val min = minMHz.toLongOrNull()?.times(1_000_000) ?: error("Frequência mínima da GPU inválida.")
        val max = maxMHz.toLongOrNull()?.times(1_000_000) ?: error("Frequência máxima da GPU inválida.")
        val result = withContext(Dispatchers.IO) {
            repository.applyGpu(min, max, governor.ifBlank { null })
        }
        _message.value = result
        _gpu.value = withContext(Dispatchers.IO) { repository.readGpu() }
    }

    private fun runTask(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try { block() } catch (t: Throwable) {
                _message.value = t.message ?: t.javaClass.simpleName
            } finally { _busy.value = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KleeApp(vm: KernelManagerViewModel = viewModel()) {
    val cpu by vm.cpu.collectAsState()
    val gpu by vm.gpu.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val thermal by vm.thermal.collectAsState()
    val power by vm.power.collectAsState()
    val monitor by vm.monitor.collectAsState()
    var section by remember { mutableStateOf("HOME") }

    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Black) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (section == "HOME") {
                Text("KLEE Kernel Manager", style = MaterialTheme.typography.headlineLarge, color = androidx.compose.ui.graphics.Color.White)
                Text("POCO X8 Pro · MT6899", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.LightGray)
                Spacer(Modifier.height(24.dp))
                val items = listOf(
                    Triple("CPU Control", "3 clusters, frequências e governors", "CPU"),
                    Triple("GPU Control", "Mali-G720 e DVFS", "GPU"),
                    Triple("Thermal", "Sensores e throttling", "THERMAL"),
                    Triple("Power", "Energia e desempenho", "POWER"),
                    Triple("Gaming", "Perfis de jogos", "GAMING"),
                    Triple("Monitor", "Temperatura, carga e clocks", "MONITOR")
                )
                for (row in 0..2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (col in 0..1) {
                            val item = items[row * 2 + col]
                            Card(
                                onClick = { section = item.third },
                                modifier = Modifier.weight(1f).height(166.dp),
                                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF242424)),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
                            ) {
                                Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val icon = when(item.third) {
                                        "CPU" -> "▣"
                                        "GPU" -> "◉"
                                        "THERMAL" -> "♨"
                                        "POWER" -> "ϟ"
                                        "GAMING" -> "🎮"
                                        else -> "▤"
                                    }
                                    Text(icon, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineMedium)
                                    Text(item.first, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleLarge)
                                    Text(item.second, color = androidx.compose.ui.graphics.Color.LightGray, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            } else {
                LaunchedEffect(section) {
                    when (section) {
                        "THERMAL" -> vm.loadThermal()
                        "POWER" -> vm.loadPower()
                        "MONITOR" -> while (true) { vm.loadMonitor(); delay(2500) }
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = { section = "HOME" }) { Text("‹ Início") }
                    Text(sectionTitle(section), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleLarge)
                }
                message?.let {
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                when (section) {
                    "CPU" -> CpuPanel(cpu, busy, vm::loadCpu, vm::applyCpu)
                    "GPU" -> GpuPanel(gpu, busy, vm::loadGpu, vm::applyGpu)
                    "THERMAL" -> DiagnosticPanel("Sensores térmicos e cooling", thermal, busy, vm::loadThermal)
                    "POWER" -> DiagnosticPanel("Bateria e alimentação", power, busy, vm::loadPower)
                    "MONITOR" -> DiagnosticPanel("Monitoramento ao vivo · 2,5 s", monitor, busy, vm::loadMonitor)
                    "GAMING" -> GamingPanel(busy, vm::applyGamingProfile)
                    else -> Text("Seção indisponível.")
                }
            }
        }
    }
}

private fun sectionTitle(section: String): String = when(section) {
    "CPU" -> "CPU Control"
    "GPU" -> "GPU Control"
    "THERMAL" -> "Thermal"
    "POWER" -> "Power"
    "GAMING" -> "Gaming"
    "MONITOR" -> "Monitor"
    else -> "KLEE"
}

@Composable
private fun CpuPanel(
    state: ControlSnapshot,
    busy: Boolean,
    onRefresh: () -> Unit,
    onApply: (String, String, String, String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Controle dos clusters", style = MaterialTheme.typography.titleLarge)
        Text("As policies são detectadas no próprio kernel. Frequências em MHz; o app converte para kHz ao aplicar.")
        Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Processando..." else "Atualizar CPU")
        }
        if (state.message.isNotBlank()) Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.root) Text("Root não disponível. Conceda acesso ao KLEE pelo KernelSU.")
        state.cpuPolicies.forEach { policy ->
            CpuPolicyCard(policy, busy, onApply)
        }
    }
}

@Composable
private fun CpuPolicyCard(
    policy: CpuPolicy,
    busy: Boolean,
    onApply: (String, String, String, String) -> Unit
) {
    var minText by remember(policy.id, policy.minKHz) { mutableStateOf((policy.minKHz / 1000).toString()) }
    var maxText by remember(policy.id, policy.maxKHz) { mutableStateOf((policy.maxKHz / 1000).toString()) }
    var governor by remember(policy.id, policy.governor) { mutableStateOf(policy.governor) }
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Policy ${policy.id} • CPUs ${policy.cpus}", style = MaterialTheme.typography.titleMedium)
            Text("Atual: ${policy.currentKHz / 1000} MHz  |  Hardware: ${policy.hardwareMinKHz / 1000}–${policy.hardwareMaxKHz / 1000} MHz")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minText, onValueChange = { minText = it.filter(Char::isDigit) },
                    label = { Text("Mín. MHz") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
                OutlinedTextField(
                    value = maxText, onValueChange = { maxText = it.filter(Char::isDigit) },
                    label = { Text("Máx. MHz") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Governor: ", style = MaterialTheme.typography.bodyMedium)
                Box {
                    TextButton(onClick = { expanded = true }, enabled = policy.canWriteGovernor && policy.governors.isNotEmpty()) {
                        Text(governor.ifBlank { "Indisponível" })
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        policy.governors.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = {
                                governor = option
                                expanded = false
                            })
                        }
                    }
                }
            }
            Text("Governors: ${policy.governors.joinToString().ifBlank { "não informado" }}", style = MaterialTheme.typography.bodySmall)
            Text("Frequências: ${policy.frequenciesKHz.joinToString { (it / 1000).toString() + " MHz" }.ifBlank { "não informadas pelo driver" }}", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { onApply(policy.id, minText, maxText, if (policy.canWriteGovernor) governor else "") },
                enabled = !busy && policy.canWriteMin && policy.canWriteMax &&
                    minText.toLongOrNull() != null && maxText.toLongOrNull() != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Aplicar policy ${policy.id}") }
            if (!policy.canWriteMin || !policy.canWriteMax) {
                Text("Este kernel não permite escrita nos limites desta policy.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GpuPanel(
    state: ControlSnapshot,
    busy: Boolean,
    onRefresh: () -> Unit,
    onApply: (String, String, String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Mali-G720 MC8", style = MaterialTheme.typography.titleLarge)
        Text("Controlador consultado: /sys/class/devfreq/13000000.mali")
        Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Processando..." else "Atualizar GPU")
        }
        if (state.message.isNotBlank()) Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.root) Text("Root não disponível. Conceda acesso ao KLEE pelo KernelSU.")
        state.gpu?.let { gpu ->
            GpuControlCard(gpu, busy, onApply)
        }
    }
}

@Composable
private fun GpuControlCard(
    gpu: GpuControl,
    busy: Boolean,
    onApply: (String, String, String) -> Unit
) {
    var minText by remember(gpu.minHz) { mutableStateOf((gpu.minHz / 1_000_000).toString()) }
    var maxText by remember(gpu.maxHz) { mutableStateOf((gpu.maxHz / 1_000_000).toString()) }
    var governor by remember(gpu.governor) { mutableStateOf(gpu.governor) }
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(gpu.name, style = MaterialTheme.typography.titleMedium)
            Text("Atual: ${gpu.currentHz / 1_000_000} MHz")
            Text("Limites atuais: ${gpu.minHz / 1_000_000}–${gpu.maxHz / 1_000_000} MHz")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minText, onValueChange = { minText = it.filter(Char::isDigit) },
                    label = { Text("Mín. MHz") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
                OutlinedTextField(
                    value = maxText, onValueChange = { maxText = it.filter(Char::isDigit) },
                    label = { Text("Máx. MHz") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Governor: ")
                Box {
                    TextButton(onClick = { expanded = true }, enabled = gpu.canWriteGovernor && gpu.governors.isNotEmpty()) {
                        Text(governor.ifBlank { "Indisponível" })
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        gpu.governors.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = {
                                governor = option
                                expanded = false
                            })
                        }
                    }
                }
            }
            Text("OPPs/frequências detectadas: ${gpu.frequenciesHz.size}", style = MaterialTheme.typography.bodySmall)
            Text("Frequências: ${gpu.frequenciesHz.joinToString { (it / 1_000_000).toString() + " MHz" }.ifBlank { "não informadas pelo driver" }}", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { onApply(minText, maxText, if (gpu.canWriteGovernor) governor else "") },
                enabled = !busy && gpu.canWriteMin && gpu.canWriteMax && gpu.frequenciesHz.isNotEmpty() &&
                    minText.toLongOrNull() != null && maxText.toLongOrNull() != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Aplicar GPU") }
            if (!gpu.canWriteMin || !gpu.canWriteMax || gpu.frequenciesHz.isEmpty()) {
                Text("Nós de controle ou tabela de frequências indisponíveis. A aplicação fica bloqueada para evitar escrita inválida.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DiagnosticPanel(title: String, state: ControlSnapshot, busy: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleLarge)
        Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Lendo..." else "Atualizar") }
        if (!state.root) Text("Acesso root indisponível. Autorize o KLEE no KernelSU.", color = MaterialTheme.colorScheme.error)
        val lines = state.message.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) Text("Aguardando leitura...")
        lines.forEach { line ->
            val p = line.split("|")
            val kind = p.firstOrNull() ?: ""
            val label = when (kind) {
                "TEMP" -> p.getOrNull(1) ?: "Sensor"
                "COOL" -> "Cooling · " + (p.getOrNull(1) ?: "dispositivo")
                "EXTRA" -> p.getOrNull(1) ?: "MediaTek"
                "SUPPLY" -> "Fonte · " + (p.getOrNull(1) ?: "")
                "CPU" -> "CPU " + (p.getOrNull(1) ?: "")
                "GPU" -> "GPU Mali"
                "LOAD" -> "Carga do sistema"
                "STAT" -> "CPU /proc/stat"
                else -> kind.replace('_', ' ')
            }
            val raw = p.getOrNull(2)?.toLongOrNull()
            val value = when (kind) {
                "TEMP" -> if (raw != null && raw > 1000) "%.1f °C".format(raw / 1000.0) else (p.getOrNull(2) ?: "")
                "COOL" -> "Estado " + (p.getOrNull(2) ?: "?") + " / " + (p.getOrNull(3) ?: "?")
                "EXTRA", "SUPPLY" -> p.getOrNull(2) ?: ""
                "CPU" -> ((p.getOrNull(2)?.toLongOrNull() ?: 0L) / 1000).toString() + " MHz · " + (p.getOrNull(3) ?: "")
                "GPU" -> ((p.getOrNull(1)?.toLongOrNull() ?: 0L) / 1000000).toString() + " MHz · " + (p.getOrNull(2) ?: "")
                "LOAD", "STAT" -> p.getOrNull(1) ?: ""
                else -> p.drop(1).joinToString(" · ").ifBlank { line }
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF242424))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, color = androidx.compose.ui.graphics.Color.LightGray, style = MaterialTheme.typography.labelLarge)
                    Text(value, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun GamingPanel(busy: Boolean, onApply: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Perfis de desempenho", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleLarge)
        Text("Gaming seleciona o governor performance nas policies que o disponibilizam. Os limites atuais são preservados. Restaurar recupera os governors lidos antes da primeira aplicação.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { onApply(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Ativar Gaming") }
        OutlinedButton(onClick = { onApply(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Restaurar governors") }
        Text("Não altera OPPs, limites térmicos ou nós proprietários da GPU.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}
