package dev.klee.kernelmanager

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.net.Uri
import android.view.Display
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.klee.kernelmanager.service.FloatingMonitorService
import dev.klee.kernelmanager.ui.theme.KleeThemePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

data class UiFeedback(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val isError: Boolean = false
)

class KernelManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val shell = RootShell()
    private val repository = KernelControlRepository(shell)
    private val profileStore = ProfileStore(application.applicationContext)
    private val settingsStore = AppSettingsStore(application.applicationContext)
    private val vulkanDriverStore = VulkanDriverStore(application.applicationContext)

    private var autoUnmountJob: Job? = null
    private var verifyJob: Job? = null
    private var resolvedCarrier: VulkanCarrierInfo? = null

    private val _themePreset = MutableStateFlow(settingsStore.loadTheme())
    val themePreset = _themePreset.asStateFlow()

    private val _hudConfig = MutableStateFlow(settingsStore.loadHudConfig())
    val hudConfig = _hudConfig.asStateFlow()

    private val _defaultRefreshHz = MutableStateFlow(settingsStore.loadDefaultRefreshRate())
    val defaultRefreshHz = _defaultRefreshHz.asStateFlow()

    private val _refreshInfo = MutableStateFlow(DisplayRefreshInfo())
    val refreshInfo = _refreshInfo.asStateFlow()

    private val _perAppRules = MutableStateFlow<List<PerAppRule>>(settingsStore.loadPerAppRules())
    val perAppRules = _perAppRules.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppEntry>>(emptyList())
    val installedApps = _installedApps.asStateFlow()

    private val _vulkanDrivers = MutableStateFlow<List<VulkanDriverPackage>>(vulkanDriverStore.loadDrivers())
    val vulkanDrivers = _vulkanDrivers.asStateFlow()

    private val _systemVulkan = MutableStateFlow(SystemVulkanInfo())
    val systemVulkan = _systemVulkan.asStateFlow()

    private val _vulkanAutoUnmountSec = MutableStateFlow(settingsStore.loadVulkanAutoUnmountSec())
    val vulkanAutoUnmountSec = _vulkanAutoUnmountSec.asStateFlow()

    val vulkanVerification = VulkanMonitorNotifier.latestResult

    private val _dashboard = MutableStateFlow(DashboardSummary(root = false, loaded = false))
    val dashboard = _dashboard.asStateFlow()

    private val _cpu = MutableStateFlow(ControlSnapshot(false, message = "Toque em Atualizar para consultar as policies CPUFreq."))
    val cpu = _cpu.asStateFlow()

    private val _gpu = MutableStateFlow(ControlSnapshot(false, message = "Toque em Atualizar para consultar Mali, GED e DRAM."))
    val gpu = _gpu.asStateFlow()

    private val _mtkPerf = MutableStateFlow(MtkPerfSnapshot(false, message = "Toque em Atualizar para consultar FPSGO e Metis."))
    val mtkPerf = _mtkPerf.asStateFlow()

    private val _thermal = MutableStateFlow(ThermalSnapshot(false, message = "Toque em Atualizar para ler sensores térmicos."))
    val thermal = _thermal.asStateFlow()

    private val _power = MutableStateFlow(PowerSnapshot(false, message = "Toque em Atualizar para ler a bateria."))
    val power = _power.asStateFlow()

    private val _monitor = MutableStateFlow(MonitorSample(false, message = "Aguardando coleta ao vivo..."))
    val monitor = _monitor.asStateFlow()

    private val _monitorHistory = MutableStateFlow<List<MonitorSample>>(emptyList())
    val monitorHistory = _monitorHistory.asStateFlow()

    private val _monitorPaused = MutableStateFlow(false)
    val monitorPaused = _monitorPaused.asStateFlow()

    private val _monitorIntervalMs = MutableStateFlow(2000L)
    val monitorIntervalMs = _monitorIntervalMs.asStateFlow()

    private val _tuning = MutableStateFlow(TuningSnapshot(false, message = "Toque em Atualizar para ler parâmetros do kernel."))
    val tuning = _tuning.asStateFlow()

    private val _deviceInfo = MutableStateFlow(DeviceInfoSnapshot(false, message = "Toque em Atualizar para inspecionar o dispositivo."))
    val deviceInfo = _deviceInfo.asStateFlow()

    private val _savedProfiles = MutableStateFlow<List<SavedProfile>>(profileStore.loadProfiles())
    val savedProfiles = _savedProfiles.asStateFlow()

    private val _activeProfileName = MutableStateFlow("Padrão")
    val activeProfileName = _activeProfileName.asStateFlow()

    private val _actionLogs = MutableStateFlow<List<ActionLogEntry>>(emptyList())
    val actionLogs = _actionLogs.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _feedback = MutableStateFlow<UiFeedback?>(null)
    val feedback = _feedback.asStateFlow()

    private val baselineCpu = mutableMapOf<String, CpuPolicyTarget>()
    private var baselineGpu: GpuTarget? = null
    private var lastCpuTicks: CpuStatTicks? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun dismissFeedback() {
        _feedback.value = null
    }

    fun showInfo(text: String) {
        _feedback.value = UiFeedback(text = text, isError = false)
    }

    fun selectTheme(preset: KleeThemePreset) {
        settingsStore.saveTheme(preset)
        _themePreset.value = preset
        FloatingMonitorService.reloadConfig(getApplication())
        showInfo("Tema alterado para ${preset.title}.")
    }

    fun updateHudConfig(cfg: FloatingHudConfig) {
        settingsStore.saveHudConfig(cfg)
        _hudConfig.value = cfg
        FloatingMonitorService.reloadConfig(getApplication())
    }

    fun loadRefreshRate() = runQuietTask {
        val (curHz, supported) = queryDisplayModes()
        _refreshInfo.value = withContext(Dispatchers.IO) {
            repository.readRefreshRateSettings(curHz, supported)
        }
    }

    fun applyRefreshRate(targetHz: Int, saveAsDefault: Boolean = true) = runTask {
        val (_, supported) = queryDisplayModes()
        val maxHz = supported.maxOrNull() ?: 120
        val res = withContext(Dispatchers.IO) { repository.applyRefreshRate(targetHz, maxHz) }
        val isErr = !res.startsWith("OK")
        if (!isErr && saveAsDefault) {
            settingsStore.saveDefaultRefreshRate(targetHz)
            _defaultRefreshHz.value = targetHz
        }
        val label = if (targetHz == 0) "Automática" else "${targetHz} Hz"
        recordLog("Taxa de Atualização -> $label", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        loadRefreshRate()
    }

    fun loadInstalledApps() = runTask {
        val pm = getApplication<Application>().packageManager
        val list = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { appInfo ->
                    val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val label = try {
                        pm.getApplicationLabel(appInfo).toString().ifBlank { appInfo.packageName }
                    } catch (_: Throwable) {
                        appInfo.packageName
                    }
                    InstalledAppEntry(
                        packageName = appInfo.packageName,
                        label = label,
                        isSystem = isSys
                    )
                }
                .sortedWith(compareBy<InstalledAppEntry> { it.isSystem }.thenBy { it.label.lowercase() })
        }
        _installedApps.value = list
    }

    fun savePerAppRule(rule: PerAppRule) = runTask {
        val updated = (_perAppRules.value.filterNot { it.packageName == rule.packageName } + rule)
            .sortedBy { it.appLabel.lowercase() }
        settingsStore.savePerAppRules(updated)
        _perAppRules.value = updated
        val angleRes = withContext(Dispatchers.IO) {
            repository.applyForceVulkanAngle(rule.packageName, rule.forceVulkanAngle)
        }
        recordLog(
            "Regra Per-App (${rule.appLabel})",
            "perfil=${rule.profileId}, hz=${rule.refreshRateHz}, mtk=${rule.mtkPerfPreset}, vk=${rule.vulkanDriverId}, angle=${rule.forceVulkanAngle} ($angleRes)",
            false
        )
        showInfo("Regra salva para ${rule.appLabel}!")
    }

    fun deletePerAppRule(packageName: String) = runTask {
        val removed = _perAppRules.value.firstOrNull { it.packageName == packageName }
        val updated = _perAppRules.value.filterNot { it.packageName == packageName }
        settingsStore.savePerAppRules(updated)
        _perAppRules.value = updated
        if (removed?.forceVulkanAngle == true) {
            withContext(Dispatchers.IO) { repository.applyForceVulkanAngle(packageName, false) }
        }
        showInfo("Regra removida${removed?.let { ": ${it.appLabel}" } ?: ""}.")
    }

    private suspend fun ensureCarrier(forceRefresh: Boolean = false): VulkanCarrierInfo? {
        if (!forceRefresh && resolvedCarrier != null) return resolvedCarrier
        val found = withContext(Dispatchers.IO) {
            VulkanCarrierResolver.resolveCarrier(getApplication<Application>().applicationContext, shell)
        }
        resolvedCarrier = found
        return found
    }

    fun loadSystemVulkan() = runQuietTask {
        val carrier = ensureCarrier(forceRefresh = true)
        _systemVulkan.value = withContext(Dispatchers.IO) { repository.inspectSystemVulkan(carrier) }
        _vulkanDrivers.value = vulkanDriverStore.loadDrivers()
    }

    fun setVulkanAutoUnmountSec(sec: Int) {
        settingsStore.saveVulkanAutoUnmountSec(sec)
        _vulkanAutoUnmountSec.value = sec
    }

    fun importVulkanDriverZip(uri: Uri) = runTask {
        val pkg = withContext(Dispatchers.IO) { vulkanDriverStore.importFromZip(uri, shell) }
        _vulkanDrivers.value = vulkanDriverStore.loadDrivers()
        val carrier = ensureCarrier()
        _systemVulkan.value = withContext(Dispatchers.IO) { repository.inspectSystemVulkan(carrier) }
        val msg = "Driver '${pkg.name}' importado (${pkg.primaryLibName})!"
        recordLog("Importar Driver Vulkan", msg, false)
        _feedback.value = UiFeedback(text = msg, isError = false)
    }

    fun deleteVulkanDriver(driverId: String) = runTask {
        val carrier = ensureCarrier()
        val curSys = withContext(Dispatchers.IO) { repository.inspectSystemVulkan(carrier) }
        if (curSys.overlayMounted && curSys.activeDriverId == driverId) {
            withContext(Dispatchers.IO) { repository.unmountCustomVulkanDriver() }
        }
        val updated = withContext(Dispatchers.IO) { vulkanDriverStore.deleteDriver(driverId, shell) }
        _vulkanDrivers.value = updated
        // Reset any per-app rules referencing the deleted driver
        val fixedRules = _perAppRules.value.map {
            if (it.vulkanDriverId == driverId) it.copy(vulkanDriverId = "SYSTEM") else it
        }
        settingsStore.savePerAppRules(fixedRules)
        _perAppRules.value = fixedRules
        _systemVulkan.value = withContext(Dispatchers.IO) { repository.inspectSystemVulkan(carrier) }
        recordLog("Remover Driver Vulkan", "Driver $driverId removido.", false)
        showInfo("Driver Vulkan removido.")
    }

    fun restoreDefaultVulkanDriver() = runTask {
        autoUnmountJob?.cancel()
        verifyJob?.cancel()
        val res = withContext(Dispatchers.IO) { repository.unmountCustomVulkanDriver() }
        val isErr = !res.startsWith("OK")
        recordLog("Restaurar Driver Vulkan Padrão", res, isErr)
        val carrier = ensureCarrier()
        _systemVulkan.value = withContext(Dispatchers.IO) { repository.inspectSystemVulkan(carrier) }
        _feedback.value = UiFeedback(text = res, isError = isErr)
    }

    fun launchAppWithRule(rule: PerAppRule) = runTask {
        val logSteps = mutableListOf<String>()
        var stagedCustom = false
        var selectedDriver: VulkanDriverPackage? = null

        // 1. Configure ANGLE (enable or disable according to rule)
        val angleRes = withContext(Dispatchers.IO) {
            repository.applyForceVulkanAngle(rule.packageName, rule.forceVulkanAngle)
        }
        if (rule.forceVulkanAngle) {
            logSteps.add("ANGLE: ON")
        }

        // 2. Stage Custom Vulkan Driver into GraphicsEnvironment Carrier Namespace (ro.gfx.driver.0)
        if (rule.vulkanDriverId != "SYSTEM") {
            val driver = _vulkanDrivers.value.firstOrNull { it.id == rule.vulkanDriverId }
                ?: error("Driver Vulkan selecionado não encontrado.")
            val carrier = ensureCarrier(forceRefresh = true)
                ?: error("Nenhum pacote carrier de sistema encontrado para ro.gfx.driver.0 (${VulkanCarrierResolver.lastProbeReport}).")
            val sameDriverPkgs = _perAppRules.value
                .filter { it.vulkanDriverId == driver.id }
                .map { it.packageName }
            val stageRes = withContext(Dispatchers.IO) {
                repository.stageCustomVulkanDriver(driver, rule.packageName, carrier, sameDriverPkgs)
            }
            recordLog("Stage Vulkan (${driver.name})", stageRes, !stageRes.startsWith("OK"))
            if (!stageRes.startsWith("OK")) {
                error("Falha ao ativar driver ${driver.name}: $stageRes")
            }
            stagedCustom = true
            selectedDriver = driver
            logSteps.add("Vulkan: ${driver.name} (${carrier.mode})")
        } else {
            // Ensure this app is not opted-in if its rule explicitly requests System Mali
            val curSys = withContext(Dispatchers.IO) { repository.inspectSystemVulkan(ensureCarrier()) }
            if (curSys.optInApps.split(",").contains(rule.packageName)) {
                withContext(Dispatchers.IO) { repository.unmountCustomVulkanDriver() }
            }
        }

        // 3. Performance profile
        if (rule.profileId != "NONE") {
            val builtIn = BuiltInProfile.entries.firstOrNull { it.id == rule.profileId }
            if (builtIn != null) {
                applyBuiltInProfileSync(builtIn)
                logSteps.add("Perfil: ${builtIn.title}")
            } else {
                val custom = _savedProfiles.value.firstOrNull { it.id == rule.profileId }
                if (custom != null) {
                    custom.cpuTargets.forEach { t ->
                        withContext(Dispatchers.IO) { repository.applyCpu(t.policyId, t.minKHz, t.maxKHz, t.governor.ifBlank { null }) }
                    }
                    logSteps.add("Perfil: ${custom.name}")
                }
            }
        }

        // 4. MTK FPSGO / Metis preset
        if (rule.mtkPerfPreset != "NONE") {
            applyMtkPresetSync(rule.mtkPerfPreset)
            logSteps.add("MTK: ${rule.mtkPerfPreset}")
        }

        // 5. Refresh Rate
        if (rule.refreshRateHz > 0) {
            val (_, supported) = queryDisplayModes()
            withContext(Dispatchers.IO) { repository.applyRefreshRate(rule.refreshRateHz, supported.maxOrNull() ?: 120) }
            logSteps.add("${rule.refreshRateHz}Hz")
        }

        // 6. Bypass Charging
        if (rule.enableBypassCharging) {
            withContext(Dispatchers.IO) { repository.applyBypassCharging(true) }
            logSteps.add("Bypass ON")
        }

        // 7. Clean restart & launch game so GraphicsEnvironment triggers UnloadBuiltinDriver() from scratch
        val launchRes = withContext(Dispatchers.IO) { repository.launchAppClean(rule.packageName) }
        val isErr = !launchRes.startsWith("OK")
        val summary = "${rule.appLabel} iniciado (${logSteps.joinToString(" · ").ifBlank { "Regra aplicada" }})"
        recordLog("Lançar ${rule.appLabel}", "$summary | $angleRes | $launchRes", isErr)
        _systemVulkan.value = withContext(Dispatchers.IO) { repository.inspectSystemVulkan(ensureCarrier()) }
        _feedback.value = UiFeedback(text = if (isErr) launchRes else summary, isError = isErr)

        // 8. Automatically monitor whether GraphicsEnvironment unloaded Mali & loaded the custom driver
        if (!isErr && (stagedCustom || rule.forceVulkanAngle)) {
            startPostLaunchVerification(
                rule = rule,
                driver = selectedDriver
            )
        }
    }

    private fun startPostLaunchVerification(
        rule: PerAppRule,
        driver: VulkanDriverPackage?
    ) {
        verifyJob?.cancel()
        autoUnmountJob?.cancel()
        val appCtx = getApplication<Application>().applicationContext
        val drvName = driver?.name ?: "Padrão do Sistema (Mali)"

        VulkanMonitorNotifier.publish(
            context = appCtx,
            result = VulkanVerificationResult(
                packageName = rule.packageName,
                appLabel = rule.appLabel,
                driverName = drvName,
                status = VulkanLoadStatus.CHECKING,
                summary = "Aguardando ${rule.appLabel} inicializar e GraphicsEnvironment carregar o driver...",
                timestamp = timeFormat.format(Date())
            ),
            notifyUser = true
        )

        verifyJob = viewModelScope.launch {
            val delaysMs = listOf(1800L, 2200L, 2500L, 3000L)
            var finalResult: VulkanVerificationResult? = null

            for ((idx, waitMs) in delaysMs.withIndex()) {
                delay(waitMs)
                val check = withContext(Dispatchers.IO) {
                    repository.verifyAppVulkanDriver(rule.packageName, rule.appLabel, driver)
                }
                finalResult = check

                val doneEarly = check.status == VulkanLoadStatus.LOADED_CUSTOM ||
                    (check.status == VulkanLoadStatus.ERROR_DETECTED && idx >= 1)

                if (doneEarly || idx == delaysMs.lastIndex) {
                    break
                } else {
                    VulkanMonitorNotifier.publish(
                        context = appCtx,
                        result = check.copy(
                            status = VulkanLoadStatus.CHECKING,
                            summary = "Verificando ${rule.appLabel} (tentativa ${idx + 1}/${delaysMs.size})... ${check.summary}"
                        ),
                        notifyUser = false
                    )
                }
            }

            finalResult?.let { res ->
                VulkanMonitorNotifier.publish(appCtx, res, notifyUser = true)
                val isProblem = res.status == VulkanLoadStatus.ERROR_DETECTED ||
                    (driver != null && res.status == VulkanLoadStatus.LOADED_STOCK)
                recordLog(
                    "Monitor Vulkan (${rule.appLabel})",
                    "[${res.status}] ${res.summary} | unload=${res.unloadedBuiltin} | carrier=${res.carrierPathMatched} | maps=${res.matchedMaps.size} | logs=${res.relevantLogcat.size}",
                    isProblem
                )
            }
        }
    }

    fun verifyDriverNow(rule: PerAppRule) = runTask {
        val driver = if (rule.vulkanDriverId == "SYSTEM") null
        else _vulkanDrivers.value.firstOrNull { it.id == rule.vulkanDriverId }
        val res = withContext(Dispatchers.IO) {
            repository.verifyAppVulkanDriver(rule.packageName, rule.appLabel, driver)
        }
        val appCtx = getApplication<Application>().applicationContext
        VulkanMonitorNotifier.publish(appCtx, res, notifyUser = true)
        val isProblem = res.status == VulkanLoadStatus.ERROR_DETECTED ||
            (driver != null && res.status == VulkanLoadStatus.LOADED_STOCK)
        recordLog("Verificar Vulkan (${rule.appLabel})", "[${res.status}] ${res.summary}", isProblem)
        _feedback.value = UiFeedback(text = res.summary, isError = isProblem)
    }

    fun recheckLastVulkanVerification() {
        val last = vulkanVerification.value ?: return
        val matchedRule = _perAppRules.value.firstOrNull { it.packageName == last.packageName }
        if (matchedRule != null) {
            verifyDriverNow(matchedRule)
            return
        }
        runTask {
            val driver = _vulkanDrivers.value.firstOrNull { it.name == last.driverName }
            val res = withContext(Dispatchers.IO) {
                repository.verifyAppVulkanDriver(last.packageName, last.appLabel, driver)
            }
            VulkanMonitorNotifier.publish(getApplication<Application>().applicationContext, res, notifyUser = true)
            val isProblem = res.status == VulkanLoadStatus.ERROR_DETECTED ||
                (driver != null && res.status == VulkanLoadStatus.LOADED_STOCK)
            recordLog("Verificar Vulkan (${last.appLabel})", "[${res.status}] ${res.summary}", isProblem)
            _feedback.value = UiFeedback(text = res.summary, isError = isProblem)
        }
    }

    private suspend fun applyBuiltInProfileSync(profile: BuiltInProfile) = withContext(Dispatchers.IO) {
        val cpuSnap = repository.readCpu()
        captureCpuBaseline(cpuSnap.cpuPolicies)
        cpuSnap.cpuPolicies.forEach { policy ->
            val hwMin = policy.hardwareMinKHz.takeIf { it > 0 } ?: policy.minKHz
            val hwMax = policy.hardwareMaxKHz.takeIf { it > 0 } ?: policy.maxKHz
            val freqs = policy.frequenciesKHz
            val (tMin, tMax, tGov) = when (profile) {
                BuiltInProfile.ECO -> {
                    val ecoMax = pickClosestFrequency(freqs, hwMin + ((hwMax - hwMin) * 0.65).toLong(), hwMin, hwMax)
                    val g = listOf("conservative", "sugov_ext", "powersave").firstOrNull { policy.governors.contains(it) }
                    Triple(hwMin, ecoMax.coerceAtLeast(hwMin), g)
                }
                BuiltInProfile.BALANCED -> {
                    val g = listOf("sugov_ext", "schedutil").firstOrNull { policy.governors.contains(it) }
                    Triple(hwMin, hwMax, g)
                }
                BuiltInProfile.PERFORMANCE -> {
                    val perfMin = pickClosestFrequency(freqs, hwMin + ((hwMax - hwMin) * 0.35).toLong(), hwMin, hwMax)
                    val g = listOf("sugov_ext", "schedutil", "performance").firstOrNull { policy.governors.contains(it) }
                    Triple(perfMin.coerceAtMost(hwMax), hwMax, g)
                }
                BuiltInProfile.GAMING -> {
                    val g = if (policy.governors.contains("performance")) "performance" else "sugov_ext"
                    Triple(hwMin, hwMax, g)
                }
            }
            if (policy.canWriteMin && policy.canWriteMax) {
                repository.applyCpu(policy.id, tMin, tMax, tGov?.takeIf { policy.canWriteGovernor })
            }
        }
        _activeProfileName.value = profile.title
    }

    private suspend fun applyMtkPresetSync(preset: String) = withContext(Dispatchers.IO) {
        val pairs = when (preset) {
            "GAMING" -> listOf(
                "fpsgo_enable" to true, "boost_ta" to true, "boost_VIP" to true,
                "rescue_enable" to true, "ultra_rescue" to true,
                "mi_fboost_enable" to true, "speed_touch_enable" to true, "in_perf_mod" to true
            )
            "COOL" -> listOf(
                "fpsgo_enable" to true, "boost_ta" to false, "ultra_rescue" to false,
                "powerRL_enable" to true, "in_perf_mod" to false, "thermal_break_enable" to true
            )
            else -> listOf(
                "fpsgo_enable" to true, "boost_ta" to true, "rescue_enable" to true,
                "ultra_rescue" to false, "in_perf_mod" to false, "thermal_break_enable" to true
            )
        }
        pairs.forEach { (k, v) -> repository.applyMtkPerfBool(k, v) }
    }

    fun exportFullLogs(includeDmesg: Boolean, onReady: (ExportedLogFile, String) -> Unit) = runTask {
        val carrier = ensureCarrier()
        val bundle = withContext(Dispatchers.IO) {
            repository.buildFullLogBundle(
                actionLogs = _actionLogs.value,
                perAppRules = _perAppRules.value,
                includeDmesg = includeDmesg,
                lastVulkanCheck = vulkanVerification.value,
                carrierHint = carrier
            )
        }
        val exported = withContext(Dispatchers.IO) {
            LogExporter.exportToFiles(
                context = getApplication(),
                shell = shell,
                content = bundle,
                saveToDownloads = true
            )
        }
        val locMsg = exported.downloadsPath ?: exported.cacheFile.absolutePath
        recordLog("Exportar Logs", "Salvo em $locMsg (${exported.contentLength} bytes)", false)
        showInfo(
            if (exported.downloadsPath != null) "Log salvo em Download/${exported.fileName}"
            else "Log pronto (${exported.fileName})"
        )
        onReady(exported, bundle)
    }

    fun setMonitorPaused(paused: Boolean) {
        _monitorPaused.value = paused
    }

    fun setMonitorInterval(intervalMs: Long) {
        _monitorIntervalMs.value = intervalMs
    }

    fun loadDashboard() = runQuietTask {
        _dashboard.value = withContext(Dispatchers.IO) { repository.readDashboardSummary() }
        val (curHz, supported) = queryDisplayModes()
        _refreshInfo.value = withContext(Dispatchers.IO) {
            repository.readRefreshRateSettings(curHz, supported)
        }
    }

    fun loadCpu() = runTask {
        val snap = withContext(Dispatchers.IO) { repository.readCpu() }
        captureCpuBaseline(snap.cpuPolicies)
        _cpu.value = snap
    }

    fun loadGpu() = runTask {
        val snap = withContext(Dispatchers.IO) { repository.readGpu() }
        captureGpuBaseline(snap.gpu)
        _gpu.value = snap
    }

    fun loadMtkPerf() = runTask {
        _mtkPerf.value = withContext(Dispatchers.IO) { repository.readMtkPerf() }
    }

    fun loadThermal() = runTask {
        _thermal.value = withContext(Dispatchers.IO) { repository.readThermalDetailed() }
    }

    fun loadPower() = runTask {
        _power.value = withContext(Dispatchers.IO) { repository.readPowerDetailed() }
    }

    fun loadTuning() = runTask {
        _tuning.value = withContext(Dispatchers.IO) { repository.readTuning() }
    }

    fun loadDeviceInfo() = runTask {
        _deviceInfo.value = withContext(Dispatchers.IO) { repository.readDeviceInfo() }
        loadRefreshRate()
    }

    fun sampleMonitor() {
        viewModelScope.launch {
            val sample = withContext(Dispatchers.IO) { repository.readMonitorSample(lastCpuTicks) }
            if (sample.statTicks != null) {
                lastCpuTicks = sample.statTicks
            }
            _monitor.value = sample
            if (sample.root) {
                _monitorHistory.value = (_monitorHistory.value + sample).takeLast(30)
            }
        }
    }

    fun applyCpu(id: String, minKHz: Long, maxKHz: Long, governor: String?) = runTask {
        val result = withContext(Dispatchers.IO) {
            repository.applyCpu(id, minKHz, maxKHz, governor?.ifBlank { null })
        }
        val isErr = !result.startsWith("OK")
        recordLog("CPU Policy $id (${minKHz / 1000}–${maxKHz / 1000} MHz · ${governor ?: "mantido"})", result, isErr)
        _feedback.value = UiFeedback(text = result, isError = isErr)
        _cpu.value = withContext(Dispatchers.IO) { repository.readCpu() }
        _activeProfileName.value = "Personalizado"
    }

    fun resetCpuPolicy(policy: CpuPolicy) = runTask {
        val base = baselineCpu[policy.id]
        val targetMin = policy.hardwareMinKHz.takeIf { it > 0 } ?: policy.minKHz
        val targetMax = policy.hardwareMaxKHz.takeIf { it > 0 } ?: policy.maxKHz
        val targetGov = (base?.governor ?: policy.governors.firstOrNull { it == "sugov_ext" } ?: policy.governor)
            .takeIf { policy.canWriteGovernor && it.isNotBlank() }
        val result = withContext(Dispatchers.IO) {
            repository.applyCpu(policy.id, targetMin, targetMax, targetGov)
        }
        val isErr = !result.startsWith("OK")
        recordLog("Reset CPU Policy ${policy.id}", result, isErr)
        _feedback.value = UiFeedback(text = result, isError = isErr)
        _cpu.value = withContext(Dispatchers.IO) { repository.readCpu() }
    }

    fun applyUclamp(minPct: Int, maxPct: Int, latencySensitive: Boolean) = runTask {
        val result = withContext(Dispatchers.IO) {
            repository.applyUclamp(minPct, maxPct, latencySensitive)
        }
        val isErr = !result.startsWith("OK")
        recordLog("UCLAMP top-app ($minPct%–$maxPct%, lat=$latencySensitive)", result, isErr)
        _feedback.value = UiFeedback(text = result, isError = isErr)
        _cpu.value = withContext(Dispatchers.IO) { repository.readCpu() }
    }

    fun applyGpu(path: String, minHz: Long, maxHz: Long, governor: String?) = runTask {
        val result = withContext(Dispatchers.IO) {
            repository.applyGpu(minHz, maxHz, governor?.ifBlank { null }, path)
        }
        val isErr = !result.startsWith("OK")
        recordLog("GPU (${minHz / 1_000_000}–${maxHz / 1_000_000} MHz · ${governor ?: "mantido"})", result, isErr)
        _feedback.value = UiFeedback(text = result, isError = isErr)
        _gpu.value = withContext(Dispatchers.IO) { repository.readGpu() }
        _activeProfileName.value = "Personalizado"
    }

    fun resetGpu(gpu: GpuControl) = runTask {
        val min = gpu.frequenciesHz.firstOrNull() ?: gpu.minHz
        val max = gpu.frequenciesHz.lastOrNull() ?: gpu.maxHz
        val gov = (baselineGpu?.governor ?: gpu.governor).takeIf { gpu.canWriteGovernor && it.isNotBlank() }
        val result = withContext(Dispatchers.IO) {
            repository.applyGpu(min, max, gov, gpu.path)
        }
        val isErr = !result.startsWith("OK")
        recordLog("Reset GPU", result, isErr)
        _feedback.value = UiFeedback(text = result, isError = isErr)
        _gpu.value = withContext(Dispatchers.IO) { repository.readGpu() }
    }

    fun applyGedToggle(key: String, enabled: Boolean) = runTask {
        val res = withContext(Dispatchers.IO) { repository.applyGedToggle(key, enabled) }
        val isErr = !res.startsWith("OK")
        recordLog("GED $key=${if (enabled) 1 else 0}", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        _gpu.value = withContext(Dispatchers.IO) { repository.readGpu() }
    }

    fun applyGedInt(key: String, value: Int) = runTask {
        val res = withContext(Dispatchers.IO) { repository.applyGedInt(key, value) }
        val isErr = !res.startsWith("OK")
        recordLog("GED $key=$value", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        _gpu.value = withContext(Dispatchers.IO) { repository.readGpu() }
    }

    fun applyDram(minHz: Long, maxHz: Long) = runTask {
        val res = withContext(Dispatchers.IO) { repository.applyDram(minHz, maxHz) }
        val isErr = !res.startsWith("OK")
        recordLog("DRAM (${minHz / 1_000_000}–${maxHz / 1_000_000} MHz)", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        _gpu.value = withContext(Dispatchers.IO) { repository.readGpu() }
    }

    fun resetDram(dram: DramDevfreqInfo) = runTask {
        val min = dram.frequenciesHz.firstOrNull() ?: dram.minHz
        val max = dram.frequenciesHz.lastOrNull() ?: dram.maxHz
        val res = withContext(Dispatchers.IO) { repository.applyDram(min, max) }
        val isErr = !res.startsWith("OK")
        recordLog("Reset DRAM", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        _gpu.value = withContext(Dispatchers.IO) { repository.readGpu() }
    }

    fun applyMtkPerfBool(key: String, enabled: Boolean) = runTask {
        val res = withContext(Dispatchers.IO) { repository.applyMtkPerfBool(key, enabled) }
        val isErr = !res.startsWith("OK")
        recordLog("MTK/Metis $key=${if (enabled) 1 else 0}", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        _mtkPerf.value = withContext(Dispatchers.IO) { repository.readMtkPerf() }
    }

    fun applyMtkPerfPreset(preset: String) = runTask {
        val changes: List<Pair<String, Boolean>> = when (preset) {
            "GAMING" -> listOf(
                "fpsgo_enable" to true,
                "boost_ta" to true,
                "boost_VIP" to true,
                "rescue_enable" to true,
                "ultra_rescue" to true,
                "mi_fboost_enable" to true,
                "speed_touch_enable" to true,
                "vip_link_enable" to true,
                "metis_io_enabled" to true,
                "in_perf_mod" to true
            )
            "COOL" -> listOf(
                "fpsgo_enable" to true,
                "boost_ta" to false,
                "ultra_rescue" to false,
                "powerRL_enable" to true,
                "in_perf_mod" to false,
                "thermal_break_enable" to true
            )
            else -> listOf(
                "fpsgo_enable" to true,
                "boost_ta" to true,
                "rescue_enable" to true,
                "ultra_rescue" to false,
                "mi_fboost_enable" to true,
                "in_perf_mod" to false,
                "thermal_break_enable" to true
            )
        }
        val errors = mutableListOf<String>()
        changes.forEach { (k, v) ->
            val r = withContext(Dispatchers.IO) { repository.applyMtkPerfBool(k, v) }
            if (!r.startsWith("OK")) errors.add("$k: $r")
        }
        _mtkPerf.value = withContext(Dispatchers.IO) { repository.readMtkPerf() }
        val label = when (preset) {
            "GAMING" -> "Gaming Anti-Stutter"
            "COOL" -> "Frio / Estável"
            else -> "Stock HyperOS"
        }
        val msg = if (errors.isEmpty()) "Preset $label aplicado em FPSGO & Metis." else "Aplicado parcialmente: ${errors.take(2).joinToString("; ")}"
        recordLog("Preset FPSGO/Metis ($label)", msg, errors.isNotEmpty())
        _feedback.value = UiFeedback(text = msg, isError = errors.isNotEmpty())
    }

    fun applyBypassCharging(suspend: Boolean) = runTask {
        val res = withContext(Dispatchers.IO) { repository.applyBypassCharging(suspend) }
        val isErr = !res.startsWith("OK")
        recordLog("Bypass Charging (${if (suspend) "ATIVO" else "DESATIVADO"})", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        _power.value = withContext(Dispatchers.IO) { repository.readPowerDetailed() }
        loadDashboard()
    }

    fun applyBuiltInProfile(profile: BuiltInProfile, includeGpuBoost: Boolean = false) = runTask {
        val cpuSnap = withContext(Dispatchers.IO) { repository.readCpu() }
        if (!cpuSnap.root || cpuSnap.cpuPolicies.isEmpty()) {
            error("Não foi possível identificar as policies CPU. Verifique o acesso root.")
        }
        captureCpuBaseline(cpuSnap.cpuPolicies)

        val gpuSnap = withContext(Dispatchers.IO) { repository.readGpu() }
        captureGpuBaseline(gpuSnap.gpu)

        val errors = mutableListOf<String>()
        cpuSnap.cpuPolicies.forEach { policy ->
            val hwMin = policy.hardwareMinKHz.takeIf { it > 0 } ?: policy.minKHz
            val hwMax = policy.hardwareMaxKHz.takeIf { it > 0 } ?: policy.maxKHz
            val freqs = policy.frequenciesKHz

            val (targetMin, targetMax, targetGov) = when (profile) {
                BuiltInProfile.ECO -> {
                    val ecoMax = pickClosestFrequency(freqs, hwMin + ((hwMax - hwMin) * 0.65).toLong(), hwMin, hwMax)
                    val gov = listOf("conservative", "sugov_ext", "powersave", "schedutil")
                        .firstOrNull { policy.governors.contains(it) }
                    Triple(hwMin, ecoMax.coerceAtLeast(hwMin), gov)
                }
                BuiltInProfile.BALANCED -> {
                    val base = baselineCpu[policy.id]
                    val gov = base?.governor?.takeIf { policy.governors.contains(it) }
                        ?: listOf("sugov_ext", "schedutil", "walt").firstOrNull { policy.governors.contains(it) }
                    Triple(hwMin, hwMax, gov)
                }
                BuiltInProfile.PERFORMANCE -> {
                    val perfMin = pickClosestFrequency(freqs, hwMin + ((hwMax - hwMin) * 0.35).toLong(), hwMin, hwMax)
                    val gov = listOf("sugov_ext", "schedutil", "performance")
                        .firstOrNull { policy.governors.contains(it) }
                    Triple(perfMin.coerceAtMost(hwMax), hwMax, gov)
                }
                BuiltInProfile.GAMING -> {
                    val gov = if (policy.governors.contains("performance")) "performance"
                    else listOf("sugov_ext", "schedutil").firstOrNull { policy.governors.contains(it) }
                    Triple(hwMin, hwMax, gov)
                }
            }

            if (policy.canWriteMin && policy.canWriteMax) {
                val res = withContext(Dispatchers.IO) {
                    repository.applyCpu(
                        policy.id,
                        targetMin,
                        targetMax,
                        targetGov?.takeIf { policy.canWriteGovernor }
                    )
                }
                if (!res.startsWith("OK")) errors.add("Policy ${policy.id}: $res")
            }
        }

        val gpu = gpuSnap.gpu
        if (includeGpuBoost && gpu != null && gpu.canWriteMin && gpu.canWriteMax && gpu.frequenciesHz.size >= 2) {
            val freqs = gpu.frequenciesHz
            val (gMin, gMax) = when (profile) {
                BuiltInProfile.ECO -> freqs.first() to freqs[(freqs.size * 2) / 3]
                BuiltInProfile.BALANCED -> freqs.first() to freqs.last()
                BuiltInProfile.PERFORMANCE -> freqs[freqs.size / 3] to freqs.last()
                BuiltInProfile.GAMING -> freqs[(freqs.size / 2).coerceAtMost(freqs.lastIndex)] to freqs.last()
            }
            val gRes = withContext(Dispatchers.IO) {
                repository.applyGpu(gMin, gMax, null, gpu.path)
            }
            if (!gRes.startsWith("OK")) errors.add("GPU: $gRes")
        }

        _cpu.value = withContext(Dispatchers.IO) { repository.readCpu() }
        _gpu.value = withContext(Dispatchers.IO) { repository.readGpu() }

        if (errors.isNotEmpty()) {
            val msg = "Perfil ${profile.title} aplicado parcialmente:\n" + errors.joinToString("\n")
            recordLog("Perfil ${profile.title}", msg, true)
            _feedback.value = UiFeedback(text = msg, isError = true)
        } else {
            val msg = "Perfil ${profile.title} aplicado com sucesso."
            _activeProfileName.value = profile.title
            recordLog("Perfil ${profile.title}", msg, false)
            _feedback.value = UiFeedback(text = msg, isError = false)
        }
    }

    fun restoreBaseline() = runTask {
        if (baselineCpu.isEmpty()) {
            val snap = withContext(Dispatchers.IO) { repository.readCpu() }
            captureCpuBaseline(snap.cpuPolicies)
            _cpu.value = snap
            val msg = "Nenhuma alteração anterior registrada; estado atual salvo como base."
            _feedback.value = UiFeedback(text = msg, isError = false)
            return@runTask
        }
        val errors = mutableListOf<String>()
        baselineCpu.values.forEach { target ->
            val res = withContext(Dispatchers.IO) {
                repository.applyCpu(target.policyId, target.minKHz, target.maxKHz, target.governor.ifBlank { null })
            }
            if (!res.startsWith("OK")) errors.add("Policy ${target.policyId}: $res")
        }
        baselineGpu?.let { g ->
            val res = withContext(Dispatchers.IO) {
                repository.applyGpu(g.minHz, g.maxHz, null, g.path)
            }
            if (!res.startsWith("OK")) errors.add("GPU: $res")
        }
        _cpu.value = withContext(Dispatchers.IO) { repository.readCpu() }
        _gpu.value = withContext(Dispatchers.IO) { repository.readGpu() }
        _activeProfileName.value = "Original"
        val msg = if (errors.isEmpty()) "Governors e limites originais restaurados." else errors.joinToString("\n")
        recordLog("Restaurar Padrões", msg, errors.isNotEmpty())
        _feedback.value = UiFeedback(text = msg, isError = errors.isNotEmpty())
    }

    fun saveCurrentAsProfile(name: String, description: String) = runTask {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Informe um nome para o perfil." }
        val cpuSnap = withContext(Dispatchers.IO) { repository.readCpu() }
        if (!cpuSnap.root || cpuSnap.cpuPolicies.isEmpty()) error("Sem leitura de CPU para salvar perfil.")
        val gpuSnap = withContext(Dispatchers.IO) { repository.readGpu() }

        val profile = SavedProfile(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            description = description.trim().ifBlank {
                "${cpuSnap.cpuPolicies.size} clusters · " +
                    cpuSnap.cpuPolicies.joinToString(" / ") { "${it.maxKHz / 1000}MHz" }
            },
            createdAt = System.currentTimeMillis(),
            cpuTargets = cpuSnap.cpuPolicies.map {
                CpuPolicyTarget(it.id, it.minKHz, it.maxKHz, it.governor)
            },
            gpuTarget = gpuSnap.gpu?.takeIf { it.frequenciesHz.isNotEmpty() }?.let {
                GpuTarget(it.path, it.minHz, it.maxHz, it.governor)
            }
        )

        val updated = listOf(profile) + _savedProfiles.value
        profileStore.saveProfiles(updated)
        _savedProfiles.value = updated
        recordLog("Salvar Perfil", "Perfil '$cleanName' salvo.", false)
        _feedback.value = UiFeedback(text = "Perfil '$cleanName' salvo com sucesso!", isError = false)
    }

    fun applySavedProfile(profile: SavedProfile) = runTask {
        val cpuSnap = withContext(Dispatchers.IO) { repository.readCpu() }
        captureCpuBaseline(cpuSnap.cpuPolicies)
        val errors = mutableListOf<String>()

        profile.cpuTargets.forEach { target ->
            val res = withContext(Dispatchers.IO) {
                repository.applyCpu(target.policyId, target.minKHz, target.maxKHz, target.governor.ifBlank { null })
            }
            if (!res.startsWith("OK")) errors.add("Policy ${target.policyId}: $res")
        }
        profile.gpuTarget?.let { g ->
            val res = withContext(Dispatchers.IO) {
                repository.applyGpu(g.minHz, g.maxHz, g.governor.ifBlank { null }, g.path)
            }
            if (!res.startsWith("OK")) errors.add("GPU: $res")
        }

        _cpu.value = withContext(Dispatchers.IO) { repository.readCpu() }
        _gpu.value = withContext(Dispatchers.IO) { repository.readGpu() }
        _activeProfileName.value = profile.name
        val msg = if (errors.isEmpty()) "Perfil '${profile.name}' aplicado." else errors.joinToString("\n")
        recordLog("Aplicar '${profile.name}'", msg, errors.isNotEmpty())
        _feedback.value = UiFeedback(text = msg, isError = errors.isNotEmpty())
    }

    fun deleteSavedProfile(profileId: String) {
        val updated = _savedProfiles.value.filterNot { it.id == profileId }
        profileStore.saveProfiles(updated)
        _savedProfiles.value = updated
        _feedback.value = UiFeedback(text = "Perfil removido.", isError = false)
    }

    fun applyVmParam(param: String, value: Int) = runTask {
        val res = withContext(Dispatchers.IO) { repository.applyVmParam(param, value) }
        val isErr = !res.startsWith("OK")
        recordLog("VM vm.$param=$value", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        _tuning.value = withContext(Dispatchers.IO) { repository.readTuning() }
    }

    fun applyMglru(enabled: Boolean) = runTask {
        val res = withContext(Dispatchers.IO) { repository.applyMglru(enabled) }
        val isErr = !res.startsWith("OK")
        recordLog("MGLRU ${if (enabled) "ATIVAR" else "DESATIVAR"}", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        _tuning.value = withContext(Dispatchers.IO) { repository.readTuning() }
    }

    fun applyUfsBool(key: String, enabled: Boolean) = runTask {
        val res = withContext(Dispatchers.IO) { repository.applyUfsBool(key, enabled) }
        val isErr = !res.startsWith("OK")
        recordLog("UFS $key=${if (enabled) 1 else 0}", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        _tuning.value = withContext(Dispatchers.IO) { repository.readTuning() }
    }

    fun applyIoDevice(device: String, scheduler: String?, readAheadKb: Int?, ioStats: Boolean? = null) = runTask {
        val res = withContext(Dispatchers.IO) { repository.applyIoScheduler(device, scheduler, readAheadKb, ioStats) }
        val isErr = !res.startsWith("OK")
        recordLog("I/O $device ($scheduler / ${readAheadKb}KB / stats=$ioStats)", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        _tuning.value = withContext(Dispatchers.IO) { repository.readTuning() }
    }

    fun applyTcp(algorithm: String) = runTask {
        val res = withContext(Dispatchers.IO) { repository.applyTcpCongestion(algorithm) }
        val isErr = !res.startsWith("OK")
        recordLog("TCP Congestion -> $algorithm", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        _tuning.value = withContext(Dispatchers.IO) { repository.readTuning() }
    }

    fun applyTcpFastOpen(value: Int) = runTask {
        val res = withContext(Dispatchers.IO) { repository.applyTcpFastOpen(value) }
        val isErr = !res.startsWith("OK")
        recordLog("TCP Fast Open -> $value", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        _tuning.value = withContext(Dispatchers.IO) { repository.readTuning() }
    }

    fun dropCaches() = runTask {
        val res = withContext(Dispatchers.IO) { repository.dropCaches() }
        val isErr = !res.startsWith("OK")
        recordLog("Liberar Caches VM", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        _tuning.value = withContext(Dispatchers.IO) { repository.readTuning() }
    }

    fun clearLogs() {
        _actionLogs.value = emptyList()
    }

    private fun queryDisplayModes(): Pair<Int, List<Int>> {
        return try {
            val dm = getApplication<Application>().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
            val cur = d?.refreshRate?.roundToInt() ?: 120
            val modes = d?.supportedModes?.map { it.refreshRate.roundToInt() }?.distinct()?.sorted()
                ?: listOf(60, 90, 120)
            cur to modes
        } catch (_: Throwable) {
            120 to listOf(60, 90, 120)
        }
    }

    private fun captureCpuBaseline(policies: List<CpuPolicy>) {
        policies.forEach { p ->
            baselineCpu.putIfAbsent(p.id, CpuPolicyTarget(p.id, p.minKHz, p.maxKHz, p.governor))
        }
    }

    private fun captureGpuBaseline(gpu: GpuControl?) {
        if (baselineGpu == null && gpu != null && gpu.frequenciesHz.isNotEmpty()) {
            baselineGpu = GpuTarget(gpu.path, gpu.minHz, gpu.maxHz, gpu.governor)
        }
    }

    private fun pickClosestFrequency(table: List<Long>, target: Long, hwMin: Long, hwMax: Long): Long {
        if (table.isEmpty()) return target.coerceIn(hwMin, hwMax)
        return table.minByOrNull { kotlin.math.abs(it - target) } ?: target.coerceIn(hwMin, hwMax)
    }

    private fun recordLog(action: String, detail: String, isError: Boolean) {
        val entry = ActionLogEntry(
            timestamp = timeFormat.format(Date()),
            action = action,
            detail = detail,
            isError = isError
        )
        _actionLogs.value = (listOf(entry) + _actionLogs.value).take(80)
    }

    private fun runTask(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                recordLog("Erro", msg, true)
                _feedback.value = UiFeedback(text = msg, isError = true)
            } finally {
                _busy.value = false
            }
        }
    }

    private fun runQuietTask(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (_: Throwable) {
            }
        }
    }
}
