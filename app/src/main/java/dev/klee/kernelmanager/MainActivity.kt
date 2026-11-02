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

    fun loadCpu() = runTask {
        _cpu.value = withContext(Dispatchers.IO) { repository.readCpu() }
    }

    fun loadGpu() = runTask {
        _gpu.value = withContext(Dispatchers.IO) { repository.readGpu() }
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

@Composable
fun KleeApp(vm: KernelManagerViewModel = viewModel()) {
    val cpu by vm.cpu.collectAsState()
    val gpu by vm.gpu.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text("KLEE", style = MaterialTheme.typography.headlineLarge)
            Text("KERNEL MANAGER • POCO X8 PRO / MT6899", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(12.dp))
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("CPU") })
                Tab(selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("GPU") })
            }
            message?.let {
                Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            when (selectedTab) {
                0 -> CpuPanel(cpu, busy, vm::loadCpu, vm::applyCpu)
                else -> GpuPanel(gpu, busy, vm::loadGpu, vm::applyGpu)
            }
        }
    }
}

@Composable
private fun CpuPanel(
    state: ControlSnapshot,
    busy: Boolean,
    onRefresh: () -> Unit,
    onApply: (String, String, String, String) -> Unit
) {
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
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
            Text("Disponíveis: ${policy.governors.joinToString().ifBlank { "não informado" }}", style = MaterialTheme.typography.bodySmall)
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
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
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
            Text("Escalas detectadas: ${gpu.frequenciesHz.size} frequências", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { onApply(minText, maxText, if (gpu.canWriteGovernor) governor else "") },
                enabled = !busy && gpu.canWriteMin && gpu.canWriteMax &&
                    minText.toLongOrNull() != null && maxText.toLongOrNull() != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Aplicar GPU") }
            if (!gpu.canWriteMin || !gpu.canWriteMax) {
                Text("Os nós min_freq/max_freq estão somente leitura ou indisponíveis. Nenhuma escrita será tentada.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
