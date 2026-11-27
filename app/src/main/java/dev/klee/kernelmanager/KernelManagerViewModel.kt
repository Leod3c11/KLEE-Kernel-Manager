package dev.klee.kernelmanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class UiFeedback(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val isError: Boolean = false
)

class KernelManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = KernelControlRepository(RootShell())
    private val profileStore = ProfileStore(application.applicationContext)

    private val _dashboard = MutableStateFlow(DashboardSummary(root = false, loaded = false))
    val dashboard = _dashboard.asStateFlow()

    private val _cpu = MutableStateFlow(ControlSnapshot(false, message = "Toque em Atualizar para consultar as policies CPUFreq."))
    val cpu = _cpu.asStateFlow()

    private val _gpu = MutableStateFlow(ControlSnapshot(false, message = "Toque em Atualizar para consultar o controlador Mali devfreq."))
    val gpu = _gpu.asStateFlow()

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

    fun setMonitorPaused(paused: Boolean) {
        _monitorPaused.value = paused
    }

    fun setMonitorInterval(intervalMs: Long) {
        _monitorIntervalMs.value = intervalMs
    }

    fun loadDashboard() = runQuietTask {
        _dashboard.value = withContext(Dispatchers.IO) { repository.readDashboardSummary() }
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
        val targetGov = (base?.governor ?: policy.governor).takeIf { policy.canWriteGovernor && it.isNotBlank() }
        val result = withContext(Dispatchers.IO) {
            repository.applyCpu(policy.id, targetMin, targetMax, targetGov)
        }
        val isErr = !result.startsWith("OK")
        recordLog("Reset CPU Policy ${policy.id}", result, isErr)
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
                    val gov = listOf("powersave", "conservative", "schedutil", "walt")
                        .firstOrNull { policy.governors.contains(it) }
                    Triple(hwMin, ecoMax.coerceAtLeast(hwMin), gov)
                }
                BuiltInProfile.BALANCED -> {
                    val base = baselineCpu[policy.id]
                    val gov = base?.governor?.takeIf { policy.governors.contains(it) }
                        ?: listOf("schedutil", "walt", "interactive").firstOrNull { policy.governors.contains(it) }
                    Triple(hwMin, hwMax, gov)
                }
                BuiltInProfile.PERFORMANCE -> {
                    val perfMin = pickClosestFrequency(freqs, hwMin + ((hwMax - hwMin) * 0.35).toLong(), hwMin, hwMax)
                    val gov = listOf("walt", "schedutil", "performance")
                        .firstOrNull { policy.governors.contains(it) }
                    Triple(perfMin.coerceAtMost(hwMax), hwMax, gov)
                }
                BuiltInProfile.GAMING -> {
                    val gov = if (policy.governors.contains("performance")) "performance"
                    else listOf("walt", "schedutil").firstOrNull { policy.governors.contains(it) }
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
            val (gMin, gMax, gGov) = when (profile) {
                BuiltInProfile.ECO -> Triple(freqs.first(), freqs[(freqs.size * 2) / 3], null)
                BuiltInProfile.BALANCED -> Triple(freqs.first(), freqs.last(), baselineGpu?.governor)
                BuiltInProfile.PERFORMANCE -> Triple(freqs[freqs.size / 3], freqs.last(), null)
                BuiltInProfile.GAMING -> {
                    val highFloor = freqs[(freqs.size / 2).coerceAtMost(freqs.lastIndex)]
                    val gov = gpu.governors.firstOrNull { it == "performance" }
                    Triple(highFloor, freqs.last(), gov)
                }
            }
            val gRes = withContext(Dispatchers.IO) {
                repository.applyGpu(gMin, gMax, gGov?.takeIf { gpu.canWriteGovernor }, gpu.path)
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
                repository.applyGpu(g.minHz, g.maxHz, g.governor.ifBlank { null }, g.path)
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

    fun applyIoDevice(device: String, scheduler: String?, readAheadKb: Int?) = runTask {
        val res = withContext(Dispatchers.IO) { repository.applyIoScheduler(device, scheduler, readAheadKb) }
        val isErr = !res.startsWith("OK")
        recordLog("I/O $device ($scheduler / ${readAheadKb}KB)", res, isErr)
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

    fun dropCaches() = runTask {
        val res = withContext(Dispatchers.IO) { repository.dropCaches() }
        val isErr = !res.startsWith("OK")
        recordLog("Liberar Caches VM", res, isErr)
        _feedback.value = UiFeedback(text = res, isError = isErr)
        _tuning.value = withContext(Dispatchers.IO) { repository.readTuning() }
    }

    fun generateDiagnosticReport(onReady: (String) -> Unit) = runTask {
        val report = withContext(Dispatchers.IO) { repository.buildDiagnosticReport() }
        recordLog("Relatório de Diagnóstico", "Gerado (${report.length} caracteres)", false)
        onReady(report)
    }

    fun clearLogs() {
        _actionLogs.value = emptyList()
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
        _actionLogs.value = (listOf(entry) + _actionLogs.value).take(50)
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
                // Silent dashboard background read failure
            }
        }
    }
}
