package dev.klee.kernelmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

data class DashboardState(
    val checking: Boolean = false,
    val rootAvailable: Boolean? = null,
    val summary: String = "Toque em “Analisar aparelho” para iniciar a inspeção somente leitura.",
    val policies: List<String> = emptyList(),
    val error: String? = null
)

class DashboardViewModel : ViewModel() {
    private val repository = DeviceRepository(RootShell())
    private val _state = MutableStateFlow(DashboardState())
    val state = _state.asStateFlow()

    fun inspect() {
        viewModelScope.launch {
            _state.value = _state.value.copy(checking = true, error = null)
            try {
                val result = withContext(Dispatchers.IO) { repository.inspect() }
                _state.value = DashboardState(
                    checking = false,
                    rootAvailable = result.rootAvailable,
                    summary = result.summary,
                    policies = result.policies
                )
            } catch (t: Throwable) {
                _state.value = DashboardState(
                    checking = false,
                    rootAvailable = false,
                    summary = "A inspeção falhou.",
                    error = t.message ?: t.javaClass.simpleName
                )
            }
        }
    }
}

@Composable
fun KleeApp(vm: DashboardViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("KLEE", style = MaterialTheme.typography.displayMedium)
            Text("KERNEL MANAGER  •  MT6899", style = MaterialTheme.typography.labelLarge)
            HorizontalDivider()
            Text("Painel de diagnóstico", style = MaterialTheme.typography.headlineSmall)
            Text(
                when (state.rootAvailable) {
                    true -> "ROOT: DISPONÍVEL"
                    false -> "ROOT: NÃO CONFIRMADO"
                    null -> "ROOT: AINDA NÃO VERIFICADO"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Button(onClick = vm::inspect, enabled = !state.checking, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.checking) "Analisando..." else "Analisar aparelho")
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Identificação", style = MaterialTheme.typography.titleMedium)
                    Text(state.summary)
                }
            }
            if (state.policies.isNotEmpty()) {
                Text("CPUFreq policies", style = MaterialTheme.typography.titleMedium)
                state.policies.forEach { policy ->
                    Card(Modifier.fillMaxWidth()) {
                        Text(policy, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            state.error?.let {
                Text("Erro: $it", color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Build inicial: somente leitura. Nenhuma frequência, tensão, governor ou parâmetro térmico é alterado.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
