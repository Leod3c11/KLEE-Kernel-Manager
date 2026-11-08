@file:OptIn(ExperimentalMaterial3Api::class)

package dev.leonardo.mt6899

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.mt6899.ui.AppTheme
import dev.leonardo.mt6899.ui.CpuScreen
import dev.leonardo.mt6899.ui.DashboardScreen
import dev.leonardo.mt6899.ui.GpuScreen
import dev.leonardo.mt6899.ui.InventoryScreen
import dev.leonardo.mt6899.ui.ProfilesScreen
import dev.leonardo.mt6899.ui.SystemScreen
import dev.leonardo.mt6899.ui.ThermalScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { AppTheme { AppRoot(vm) } }
    }

    override fun onStart() {
        super.onStart()
        vm.setVisible(true)
    }

    override fun onStop() {
        vm.setVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        vm.handleOverlayPermissionReturn()
    }
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val snack = remember { SnackbarHostState() }
    var showLog by remember { mutableStateOf(false) }
    var pendingDiagnostic by remember { mutableStateOf("") }
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(pendingDiagnostic.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Não foi possível gravar o arquivo")
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Enviar diagnóstico KLEE"))
            } catch (t: Throwable) {
                Toast.makeText(context, "Falha ao exportar: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(ui.message) {
        val m = ui.message
        if (m != null) {
            snack.showSnackbar(m)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MT6899 Manager") },
                actions = { TextButton(onClick = { showLog = true }) { Text("Log") } }
            )
        },
        bottomBar = {
            if (ui.phase == Phase.Ready) {
                NavigationBar {
                    Tab.values().forEach { t ->
                        NavigationBarItem(
                            selected = ui.tab == t,
                            onClick = { vm.setTab(t) },
                            icon = { Text(t.glyph, fontSize = 18.sp) },
                            label = { Text(t.title, fontSize = 11.sp, maxLines = 1) }
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (ui.phase) {
                Phase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                Phase.NoRoot -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Sem acesso root", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Conceda root ao app no KernelSU Manager e tente de novo.",
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    Button(onClick = { vm.retry() }) { Text("Tentar novamente") }
                }

                Phase.Ready -> Column(Modifier.fillMaxSize()) {
                    if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    when (ui.tab) {
                        Tab.Home -> DashboardScreen(ui, vm)
                        Tab.Cpu -> CpuScreen(ui, vm)
                        Tab.Gpu -> GpuScreen(ui, vm)
                        Tab.Thermal -> ThermalScreen(ui, vm)
                        Tab.System -> SystemScreen(ui, vm)
                        Tab.Profiles -> ProfilesScreen(ui, vm)
                        Tab.Inventory -> InventoryScreen(ui, vm) {
                            vm.prepareDiagnosticExport { report ->
                                pendingDiagnostic = report
                                exportLauncher.launch("KLEE_Inventario_Kernel_" + System.currentTimeMillis() + ".txt")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("Log") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "O diagnóstico inclui hardware, nós sysfs, permissões, controles e logs filtrados do kernel/Android. Revise antes de compartilhar.",
                        fontSize = 12.sp
                    )
                    Text(
                        ui.log.asReversed().joinToString("\n").ifEmpty { "(vazio)" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("Fechar") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.clearLog() }) { Text("Limpar") }
                    TextButton(
                        onClick = {
                            vm.prepareDiagnosticExport { report ->
                                pendingDiagnostic = report
                                exportLauncher.launch("KLEE_Diagnostico_" + System.currentTimeMillis() + ".txt")
                            }
                        },
                        enabled = !ui.busy
                    ) { Text(if (ui.busy) "Coletando…" else "Exportar .txt") }
                }
            }
        )
    }
}
