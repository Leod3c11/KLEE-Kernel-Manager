package dev.klee.kernelmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.view.Display
import androidx.core.app.NotificationCompat
import dev.klee.kernelmanager.AppSettingsStore
import dev.klee.kernelmanager.BuiltInProfile
import dev.klee.kernelmanager.KernelControlRepository
import dev.klee.kernelmanager.MainActivity
import dev.klee.kernelmanager.PerAppRule
import dev.klee.kernelmanager.ProfileStore
import dev.klee.kernelmanager.RootShell
import dev.klee.kernelmanager.VulkanDriverStore
import dev.klee.kernelmanager.VulkanLoadStatus
import dev.klee.kernelmanager.VulkanMonitorNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class PerAppEngineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsStore: AppSettingsStore
    private lateinit var profileStore: ProfileStore
    private lateinit var vulkanDriverStore: VulkanDriverStore
    private val repository = KernelControlRepository(RootShell())

    private var watchJob: Job? = null
    private var verifyJob: Job? = null
    private var lastPackage: String? = null
    private var activeRule: PerAppRule? = null
    private var bypassWasEnabledByRule = false
    private var vulkanMountedByRule = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsStore = AppSettingsStore(applicationContext)
        profileStore = ProfileStore(applicationContext)
        vulkanDriverStore = VulkanDriverStore(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground("Monitorando apps configurados...")
        _running.value = true
        startWatching()
        return START_STICKY
    }

    private fun startAsForeground(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "KLEE Perfis por Aplicativo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Aplica automaticamente perfis de desempenho, driver Vulkan e taxa de atualização ao abrir apps."
            }
            nm.createNotificationChannel(ch)
        }
        val notif = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PerAppEngineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("KLEE Motor Per-App ativo")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stopIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        _status.value = statusText
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(statusText))
    }

    private fun startWatching() {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (isActive) {
                val fgPkg = repository.readForegroundPackage()
                if (!fgPkg.isNullOrBlank() && fgPkg != lastPackage) {
                    lastPackage = fgPkg
                    val rules = settingsStore.loadPerAppRules()
                    val matched = rules.firstOrNull { it.packageName == fgPkg }
                    if (matched != null) {
                        applyRule(matched)
                    } else if (activeRule != null) {
                        restoreDefaultAfterRule(activeRule!!)
                        activeRule = null
                    }
                }
                delay(1600L)
            }
        }
    }

    private suspend fun applyRule(rule: PerAppRule) = withContext(Dispatchers.IO) {
        activeRule = rule
        val appliedParts = mutableListOf<String>()
        var selectedDriver: dev.klee.kernelmanager.VulkanDriverPackage? = null

        if (rule.forceVulkanAngle) {
            repository.applyForceVulkanAngle(rule.packageName, true)
            appliedParts.add("ANGLE/VK")
        }
        if (rule.vulkanDriverId != "SYSTEM") {
            val driver = vulkanDriverStore.loadDrivers().firstOrNull { it.id == rule.vulkanDriverId }
            if (driver != null) {
                selectedDriver = driver
                val mountRes = repository.mountCustomVulkanDriver(driver, rule.packageName)
                if (mountRes.startsWith("OK")) {
                    vulkanMountedByRule = true
                    appliedParts.add("VK:${driver.name.take(14)}")
                }
            }
        }
        if (rule.profileId != "NONE") {
            applyProfileById(rule.profileId)
            appliedParts.add(rule.profileId)
        }
        if (rule.mtkPerfPreset != "NONE") {
            applyMtkPreset(rule.mtkPerfPreset)
            appliedParts.add("MTK:${rule.mtkPerfPreset}")
        }
        if (rule.refreshRateHz > 0) {
            repository.applyRefreshRate(rule.refreshRateHz, maxSupportedHz())
            appliedParts.add("${rule.refreshRateHz}Hz")
        }
        if (rule.enableBypassCharging) {
            repository.applyBypassCharging(true)
            bypassWasEnabledByRule = true
            appliedParts.add("Bypass")
        }

        val summary = "${rule.appLabel} -> ${appliedParts.joinToString(" · ").ifBlank { "Regra ativa" }}"
        updateNotification(summary)

        if (selectedDriver != null || rule.forceVulkanAngle) {
            val cur = VulkanMonitorNotifier.latestResult.value
            val alreadyCheckingThisPkg = cur != null &&
                cur.packageName == rule.packageName &&
                cur.status == VulkanLoadStatus.CHECKING
            if (!alreadyCheckingThisPkg) {
                verifyJob?.cancel()
                verifyJob = scope.launch {
                    delay(2500L)
                    var res = repository.verifyAppVulkanDriver(rule.packageName, rule.appLabel, selectedDriver)
                    if (res.status != VulkanLoadStatus.LOADED_CUSTOM && res.status != VulkanLoadStatus.ERROR_DETECTED) {
                        delay(3500L)
                        res = repository.verifyAppVulkanDriver(rule.packageName, rule.appLabel, selectedDriver)
                    }
                    VulkanMonitorNotifier.publish(applicationContext, res, notifyUser = true)
                    updateNotification("${rule.appLabel} · ${res.summary.take(70)}")
                }
            }
        }
    }

    private suspend fun restoreDefaultAfterRule(previousRule: PerAppRule) = withContext(Dispatchers.IO) {
        verifyJob?.cancel()
        if (vulkanMountedByRule) {
            repository.unmountCustomVulkanDriver()
            vulkanMountedByRule = false
        }
        if (previousRule.profileId != "NONE") {
            applyProfileById(BuiltInProfile.BALANCED.id)
        }
        if (previousRule.mtkPerfPreset != "NONE") {
            applyMtkPreset("STOCK")
        }
        if (previousRule.refreshRateHz > 0) {
            val defaultHz = settingsStore.loadDefaultRefreshRate()
            repository.applyRefreshRate(defaultHz, maxSupportedHz())
        }
        if (bypassWasEnabledByRule) {
            repository.applyBypassCharging(false)
            bypassWasEnabledByRule = false
        }
        updateNotification("Padrão restaurado (aguardando apps configurados)")
    }

    private fun applyProfileById(profileId: String) {
        val builtIn = BuiltInProfile.entries.firstOrNull { it.id == profileId }
        if (builtIn != null) {
            val cpuSnap = repository.readCpu()
            cpuSnap.cpuPolicies.forEach { policy ->
                val hwMin = policy.hardwareMinKHz.takeIf { it > 0 } ?: policy.minKHz
                val hwMax = policy.hardwareMaxKHz.takeIf { it > 0 } ?: policy.maxKHz
                val freqs = policy.frequenciesKHz
                val (minK, maxK, gov) = when (builtIn) {
                    BuiltInProfile.ECO -> {
                        val ecoMax = pickClosest(freqs, hwMin + ((hwMax - hwMin) * 0.65).toLong(), hwMin, hwMax)
                        val g = listOf("conservative", "sugov_ext", "powersave").firstOrNull { policy.governors.contains(it) }
                        Triple(hwMin, ecoMax.coerceAtLeast(hwMin), g)
                    }
                    BuiltInProfile.BALANCED -> {
                        val g = listOf("sugov_ext", "schedutil").firstOrNull { policy.governors.contains(it) }
                        Triple(hwMin, hwMax, g)
                    }
                    BuiltInProfile.PERFORMANCE -> {
                        val perfMin = pickClosest(freqs, hwMin + ((hwMax - hwMin) * 0.35).toLong(), hwMin, hwMax)
                        val g = listOf("sugov_ext", "schedutil", "performance").firstOrNull { policy.governors.contains(it) }
                        Triple(perfMin.coerceAtMost(hwMax), hwMax, g)
                    }
                    BuiltInProfile.GAMING -> {
                        val g = if (policy.governors.contains("performance")) "performance" else "sugov_ext"
                        Triple(hwMin, hwMax, g)
                    }
                }
                if (policy.canWriteMin && policy.canWriteMax) {
                    repository.applyCpu(policy.id, minK, maxK, gov?.takeIf { policy.canWriteGovernor })
                }
            }
        } else {
            val saved = profileStore.loadProfiles().firstOrNull { it.id == profileId } ?: return
            saved.cpuTargets.forEach { t ->
                repository.applyCpu(t.policyId, t.minKHz, t.maxKHz, t.governor.ifBlank { null })
            }
            saved.gpuTarget?.let { g ->
                repository.applyGpu(g.minHz, g.maxHz, null, g.path)
            }
        }
    }

    private fun applyMtkPreset(preset: String) {
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

    private fun pickClosest(table: List<Long>, target: Long, hwMin: Long, hwMax: Long): Long {
        if (table.isEmpty()) return target.coerceIn(hwMin, hwMax)
        return table.minByOrNull { abs(it - target) } ?: target.coerceIn(hwMin, hwMax)
    }

    private fun maxSupportedHz(): Int {
        return try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
            d?.supportedModes?.maxOfOrNull { it.refreshRate.roundToInt() } ?: 120
        } catch (_: Throwable) {
            120
        }
    }

    override fun onDestroy() {
        watchJob?.cancel()
        if (vulkanMountedByRule) {
            try {
                repository.unmountCustomVulkanDriver()
            } catch (_: Throwable) {
            }
            vulkanMountedByRule = false
        }
        scope.cancel()
        _running.value = false
        _status.value = "Motor Per-App parado"
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "klee_per_app_engine"
        private const val NOTIF_ID = 4002
        const val ACTION_STOP = "dev.klee.kernelmanager.PERAPP_STOP"

        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()

        private val _status = MutableStateFlow("Aguardando ativação")
        val status = _status.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, PerAppEngineService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PerAppEngineService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
