package dev.klee.kernelmanager

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.klee.kernelmanager.ui.components.ActionFeedbackBanner
import dev.klee.kernelmanager.ui.screens.CpuGpuTabScreen
import dev.klee.kernelmanager.ui.screens.CpuScreen
import dev.klee.kernelmanager.ui.screens.DeviceScreen
import dev.klee.kernelmanager.ui.screens.GpuScreen
import dev.klee.kernelmanager.ui.screens.HomeScreen
import dev.klee.kernelmanager.ui.screens.MonitorScreen
import dev.klee.kernelmanager.ui.screens.PowerScreen
import dev.klee.kernelmanager.ui.screens.ProfilesScreen
import dev.klee.kernelmanager.ui.screens.ThermalScreen
import dev.klee.kernelmanager.ui.screens.TuningScreen
import dev.klee.kernelmanager.ui.theme.KleeColors
import dev.klee.kernelmanager.ui.theme.KleeTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent {
            KleeTheme {
                KleeApp()
            }
        }
    }
}

private data class BottomNavDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KleeApp(vm: KernelManagerViewModel = viewModel()) {
    val dashboard by vm.dashboard.collectAsState()
    val cpu by vm.cpu.collectAsState()
    val gpu by vm.gpu.collectAsState()
    val thermal by vm.thermal.collectAsState()
    val power by vm.power.collectAsState()
    val monitor by vm.monitor.collectAsState()
    val monitorHistory by vm.monitorHistory.collectAsState()
    val monitorPaused by vm.monitorPaused.collectAsState()
    val monitorIntervalMs by vm.monitorIntervalMs.collectAsState()
    val tuning by vm.tuning.collectAsState()
    val deviceInfo by vm.deviceInfo.collectAsState()
    val savedProfiles by vm.savedProfiles.collectAsState()
    val activeProfile by vm.activeProfileName.collectAsState()
    val actionLogs by vm.actionLogs.collectAsState()
    val busy by vm.busy.collectAsState()
    val feedback by vm.feedback.collectAsState()

    var currentRoute by remember { mutableStateOf("HOME") }

    val bottomDestinations = remember {
        listOf(
            BottomNavDestination("HOME", "Início", Icons.Rounded.Home),
            BottomNavDestination("CONTROL", "CPU / GPU", Icons.Rounded.Memory),
            BottomNavDestination("MONITOR", "Monitor", Icons.AutoMirrored.Rounded.ShowChart),
            BottomNavDestination("PROFILES", "Perfis", Icons.Rounded.SportsEsports)
        )
    }

    val isSubModule = currentRoute in setOf("CPU", "GPU", "THERMAL", "POWER", "TUNING", "DEVICE")

    BackHandler(enabled = currentRoute != "HOME") {
        currentRoute = "HOME"
    }

    // Auto-dismiss success feedback after 5 seconds
    LaunchedEffect(feedback?.id) {
        val current = feedback
        if (current != null && !current.isError) {
            delay(5000)
            if (vm.feedback.value?.id == current.id) {
                vm.dismissFeedback()
            }
        }
    }

    // Load data when entering screens
    LaunchedEffect(currentRoute) {
        when (currentRoute) {
            "HOME" -> vm.loadDashboard()
            "CPU" -> vm.loadCpu()
            "GPU" -> vm.loadGpu()
            "CONTROL" -> {
                if (cpu.cpuPolicies.isEmpty()) vm.loadCpu()
                if (gpu.gpu == null) vm.loadGpu()
            }
            "THERMAL" -> vm.loadThermal()
            "POWER" -> vm.loadPower()
            "TUNING" -> vm.loadTuning()
            "DEVICE" -> vm.loadDeviceInfo()
        }
    }

    // Live monitor polling loop
    LaunchedEffect(currentRoute, monitorPaused, monitorIntervalMs) {
        if (currentRoute == "MONITOR" && !monitorPaused) {
            while (true) {
                vm.sampleMonitor()
                delay(monitorIntervalMs)
            }
        }
    }

    Scaffold(
        containerColor = KleeColors.Background,
        topBar = {
            if (isSubModule) {
                TopAppBar(
                    title = {
                        Text(
                            text = routeTitle(currentRoute),
                            color = KleeColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { currentRoute = "HOME" }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Voltar",
                                tint = KleeColors.Primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = KleeColors.Surface
                    )
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = KleeColors.Surface,
                tonalElevation = 0.dp
            ) {
                bottomDestinations.forEach { dest ->
                    val selected = when (dest.route) {
                        "CONTROL" -> currentRoute == "CONTROL" || currentRoute == "CPU" || currentRoute == "GPU"
                        else -> currentRoute == dest.route
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentRoute = dest.route },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = KleeColors.Primary,
                            selectedTextColor = KleeColors.Primary,
                            indicatorColor = KleeColors.Primary.copy(alpha = 0.16f),
                            unselectedIconColor = KleeColors.TextMuted,
                            unselectedTextColor = KleeColors.TextMuted
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = KleeColors.Primary,
                    trackColor = KleeColors.Surface
                )
            }

            AnimatedVisibility(
                visible = feedback != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                feedback?.let { fb ->
                    ActionFeedbackBanner(
                        feedback = fb,
                        onDismiss = vm::dismissFeedback,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (currentRoute) {
                    "HOME" -> HomeScreen(
                        summary = dashboard,
                        activeProfile = activeProfile,
                        busy = busy,
                        onRefresh = vm::loadDashboard,
                        onNavigate = { currentRoute = it },
                        onQuickProfile = { vm.applyBuiltInProfile(it, includeGpuBoost = false) }
                    )
                    "CONTROL" -> CpuGpuTabScreen(
                        cpuState = cpu,
                        gpuState = gpu,
                        busy = busy,
                        initialTab = 0,
                        onRefreshCpu = vm::loadCpu,
                        onApplyCpu = vm::applyCpu,
                        onResetCpu = vm::resetCpuPolicy,
                        onRefreshGpu = vm::loadGpu,
                        onApplyGpu = vm::applyGpu,
                        onResetGpu = vm::resetGpu
                    )
                    "CPU" -> CpuScreen(
                        state = cpu,
                        busy = busy,
                        onRefresh = vm::loadCpu,
                        onApply = vm::applyCpu,
                        onReset = vm::resetCpuPolicy
                    )
                    "GPU" -> GpuScreen(
                        state = gpu,
                        busy = busy,
                        onRefresh = vm::loadGpu,
                        onApply = vm::applyGpu,
                        onReset = vm::resetGpu
                    )
                    "THERMAL" -> ThermalScreen(
                        state = thermal,
                        busy = busy,
                        onRefresh = vm::loadThermal
                    )
                    "POWER" -> PowerScreen(
                        state = power,
                        busy = busy,
                        onRefresh = vm::loadPower
                    )
                    "MONITOR" -> MonitorScreen(
                        sample = monitor,
                        history = monitorHistory,
                        paused = monitorPaused,
                        intervalMs = monitorIntervalMs,
                        onTogglePause = { vm.setMonitorPaused(!monitorPaused) },
                        onSelectInterval = vm::setMonitorInterval,
                        onManualRefresh = vm::sampleMonitor
                    )
                    "PROFILES" -> ProfilesScreen(
                        activeProfile = activeProfile,
                        savedProfiles = savedProfiles,
                        busy = busy,
                        onApplyBuiltIn = vm::applyBuiltInProfile,
                        onRestoreBaseline = vm::restoreBaseline,
                        onSaveCurrent = vm::saveCurrentAsProfile,
                        onApplySaved = vm::applySavedProfile,
                        onDeleteSaved = vm::deleteSavedProfile
                    )
                    "TUNING" -> TuningScreen(
                        state = tuning,
                        busy = busy,
                        onRefresh = vm::loadTuning,
                        onApplyVm = vm::applyVmParam,
                        onApplyIo = vm::applyIoDevice,
                        onApplyTcp = vm::applyTcp,
                        onDropCaches = vm::dropCaches
                    )
                    "DEVICE" -> DeviceScreen(
                        info = deviceInfo,
                        logs = actionLogs,
                        busy = busy,
                        onRefresh = vm::loadDeviceInfo,
                        onExportReport = vm::generateDiagnosticReport,
                        onClearLogs = vm::clearLogs,
                        onNotify = vm::showInfo
                    )
                }
            }
        }
    }
}

private fun routeTitle(route: String): String = when (route) {
    "CPU" -> "CPU Control"
    "GPU" -> "GPU Control"
    "THERMAL" -> "Monitor Térmico"
    "POWER" -> "Bateria & Energia"
    "TUNING" -> "Memória, I/O & Rede"
    "DEVICE" -> "Dispositivo & Logs"
    else -> "KLEE Kernel Manager"
}
