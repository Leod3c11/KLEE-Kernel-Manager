package dev.klee.kernelmanager

data class CpuCoreStatus(
    val coreId: Int,
    val online: Boolean,
    val currentKHz: Long
)

data class CpuPolicy(
    val id: String,
    val cpus: String,
    val currentKHz: Long,
    val minKHz: Long,
    val maxKHz: Long,
    val hardwareMinKHz: Long,
    val hardwareMaxKHz: Long,
    val governor: String,
    val governors: List<String>,
    val frequenciesKHz: List<Long>,
    val canWriteMin: Boolean,
    val canWriteMax: Boolean,
    val canWriteGovernor: Boolean,
    val driver: String = ""
)

data class GpuControl(
    val path: String,
    val name: String,
    val currentHz: Long,
    val minHz: Long,
    val maxHz: Long,
    val hardwareMinHz: Long,
    val hardwareMaxHz: Long,
    val governor: String,
    val governors: List<String>,
    val frequenciesHz: List<Long>,
    val canWriteMin: Boolean,
    val canWriteMax: Boolean,
    val canWriteGovernor: Boolean,
    val gpufreqDiag: String = ""
)

data class ControlSnapshot(
    val root: Boolean,
    val cpuPolicies: List<CpuPolicy> = emptyList(),
    val cpuCores: List<CpuCoreStatus> = emptyList(),
    val gpu: GpuControl? = null,
    val message: String = ""
)

data class ThermalZone(
    val name: String,
    val tempCelsius: Double,
    val rawValue: Long
)

data class CoolingDevice(
    val name: String,
    val curState: Int,
    val maxState: Int
)

data class MtkThermalExtra(
    val key: String,
    val value: String
)

data class ThermalSnapshot(
    val root: Boolean,
    val zones: List<ThermalZone> = emptyList(),
    val coolingDevices: List<CoolingDevice> = emptyList(),
    val mtkExtras: List<MtkThermalExtra> = emptyList(),
    val message: String = ""
)

data class PowerSupplyInfo(
    val name: String,
    val type: String,
    val online: Boolean?
)

data class PowerSnapshot(
    val root: Boolean,
    val status: String = "",
    val capacity: Int = 0,
    val voltageVolts: Double = 0.0,
    val currentMa: Double = 0.0,
    val powerWatts: Double = 0.0,
    val tempCelsius: Double = 0.0,
    val health: String = "",
    val technology: String = "",
    val cycleCount: Int? = null,
    val chargeFullMah: Int? = null,
    val chargeDesignMah: Int? = null,
    val chargeCounterMah: Int? = null,
    val supplies: List<PowerSupplyInfo> = emptyList(),
    val message: String = ""
)

data class CpuStatTicks(
    val total: Long,
    val idle: Long
)

data class ClusterLiveMetric(
    val policyId: String,
    val currentMHz: Long,
    val maxMHz: Long,
    val governor: String
)

data class MonitorSample(
    val root: Boolean,
    val timestampMs: Long = System.currentTimeMillis(),
    val cpuUsagePercent: Float = 0f,
    val peakCpuMHz: Long = 0L,
    val gpuMHz: Long = 0L,
    val gpuGovernor: String = "",
    val socTempC: Double? = null,
    val batteryTempC: Double? = null,
    val ramUsedMb: Long = 0L,
    val ramTotalMb: Long = 0L,
    val swapUsedMb: Long = 0L,
    val swapTotalMb: Long = 0L,
    val clusters: List<ClusterLiveMetric> = emptyList(),
    val loadAvg: String = "",
    val uptimeSeconds: Long = 0L,
    val statTicks: CpuStatTicks? = null,
    val message: String = ""
)

data class MemoryInfo(
    val totalMb: Long = 0L,
    val availableMb: Long = 0L,
    val usedMb: Long = 0L,
    val swapTotalMb: Long = 0L,
    val swapUsedMb: Long = 0L,
    val zramDiskSizeMb: Long = 0L,
    val zramAlgo: String = "",
    val swappiness: Int = 60,
    val vfsCachePressure: Int = 100,
    val dirtyRatio: Int = 20,
    val dirtyBackgroundRatio: Int = 10,
    val canWriteSwappiness: Boolean = false,
    val canWriteVfs: Boolean = false,
    val canWriteDirty: Boolean = false
)

data class IoDevice(
    val name: String,
    val currentScheduler: String,
    val availableSchedulers: List<String>,
    val readAheadKb: Int,
    val canWriteScheduler: Boolean,
    val canWriteReadAhead: Boolean
)

data class TcpCongestionInfo(
    val current: String = "",
    val available: List<String> = emptyList(),
    val canWrite: Boolean = false
)

data class TuningSnapshot(
    val root: Boolean,
    val memory: MemoryInfo? = null,
    val ioDevices: List<IoDevice> = emptyList(),
    val tcp: TcpCongestionInfo? = null,
    val message: String = ""
)

data class DeviceInfoSnapshot(
    val root: Boolean,
    val model: String = "",
    val device: String = "",
    val brand: String = "",
    val soc: String = "",
    val hardware: String = "",
    val kernelVersion: String = "",
    val androidVersion: String = "",
    val sdkInt: String = "",
    val securityPatch: String = "",
    val selinuxMode: String = "",
    val rootProvider: String = "",
    val abi: String = "",
    val uptimeSeconds: Long = 0L,
    val cpuCores: Int = 0,
    val totalRamMb: Long = 0L,
    val message: String = ""
)

data class DashboardSummary(
    val root: Boolean,
    val loaded: Boolean = false,
    val deviceModel: String = "POCO X8 Pro",
    val deviceCodename: String = "klee",
    val soc: String = "MT6899",
    val kernelShort: String = "",
    val cpuPeakMHz: Long = 0L,
    val cpuClusterCount: Int = 0,
    val gpuMHz: Long = 0L,
    val batteryPercent: Int = 0,
    val batteryStatus: String = "",
    val socTempC: Double? = null,
    val ramUsedMb: Long = 0L,
    val ramTotalMb: Long = 0L,
    val message: String = ""
)

data class CpuPolicyTarget(
    val policyId: String,
    val minKHz: Long,
    val maxKHz: Long,
    val governor: String
)

data class GpuTarget(
    val path: String,
    val minHz: Long,
    val maxHz: Long,
    val governor: String
)

data class SavedProfile(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    val cpuTargets: List<CpuPolicyTarget>,
    val gpuTarget: GpuTarget? = null
)

data class ActionLogEntry(
    val timestamp: String,
    val action: String,
    val detail: String,
    val isError: Boolean
)

enum class BuiltInProfile(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String
) {
    ECO(
        id = "ECO",
        title = "Economia",
        subtitle = "Menor consumo e menos calor",
        description = "Reduz o teto de frequência dos clusters para ~65% da faixa de hardware (ajustado à tabela real) e usa governor conservador/powersave ou schedutil."
    ),
    BALANCED(
        id = "BALANCED",
        title = "Balanceado",
        subtitle = "Uso diário fluido (padrão)",
        description = "Restaura os limites completos de hardware e os governors originais (ou schedutil/walt) para equilíbrio entre autonomia e fluidez."
    ),
    PERFORMANCE(
        id = "PERFORMANCE",
        title = "Desempenho",
        subtitle = "Resposta rápida sem travar no máximo",
        description = "Mantém o teto máximo liberado e eleva o piso mínimo para ~35% da tabela de frequências com governor dinâmico responsivo."
    ),
    GAMING(
        id = "GAMING",
        title = "Gaming",
        subtitle = "Clock alto constante para jogos",
        description = "Aplica governor performance nas policies compatíveis com limites máximos liberados e opção de boost seguro na GPU Mali."
    )
}
