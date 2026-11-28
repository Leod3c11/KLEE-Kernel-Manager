package dev.klee.kernelmanager

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.klee.kernelmanager.service.FloatingMonitorService
import dev.klee.kernelmanager.ui.theme.KleeThemePreset
import kotlinx.coroutines.Dispatchers
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

    fun savePerAppRule(rule: PerAppRule) {
        val updated = (_perAppRules.value.filterNot { it.packageName == rule.packageName } + rule)
            .sortedBy { it.appLabel.lowercase() }
        settingsStore.savePerAppRules(updated)
        _perAppRules.value = updated
        recordLog("Regra Per-App (${rule.appLabel})", "perfil=${rule.profileId}, hz=${rule.refreshRateHz}, mtk=${rule.mtkPerfPreset}, bypass=${rule.enableBypassCharging}", false)
        showInfo("Regra salva para ${rule.appLabel}!")
    }

    fun deletePerAppRule(packageName: String) {
        val removed = _perAppRules.value.firstOrNull { it.packageName == packageName }
        val updated = _perAppRules.value.filterNot { it.packageName == packageName }
        settingsStore.savePerAppRules(updated)
        _perAppRules.value = updated
        showInfo("Regra removida${removed?.let { ": ${it.appLabel}" } ?: ""}.")
    }

    fun exportFullLogs(includeDmesg: Boolean, onReady: (ExportedLogFile, String) -> Unit) = runTask {
        val bundle = withContext(Dispatchers.IO) {
            repository.buildFullLogBundle(
                actionLogs = _actionLogs.value,
                perAppRules = _perAppRules.value,
                includeDmesg = includeDmesg
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
