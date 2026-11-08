package dev.leonardo.mt6899

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.leonardo.mt6899.core.Engine
import dev.leonardo.mt6899.core.Live
import dev.leonardo.mt6899.core.NodeSpec
import dev.leonardo.mt6899.core.Profile
import dev.leonardo.mt6899.core.SocInfo
import dev.leonardo.mt6899.core.WriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { Loading, NoRoot, Ready }

data class AppInfo(val label: String, val pkg: String)

enum class Tab(val title: String, val glyph: String) {
    Home("Painel", "◉"),
    Cpu("CPU", "▦"),
    Gpu("GPU", "▣"),
    Thermal("Térmica", "♨"),
    System("Sistema", "⚙"),
    Profiles("Perfis", "★")
}

data class UiState(
    val phase: Phase = Phase.Loading,
    val tab: Tab = Tab.Home,
    val info: SocInfo? = null,
    val live: Live? = null,
    val nodeValues: Map<String, String?> = emptyMap(),
    val extras: Map<String, String> = emptyMap(),
    val displayInfo: String = "",
    val profiles: List<Profile> = emptyList(),
    val active: String? = null,
    val prefs: Map<String, String> = emptyMap(),
    val appRules: Map<String, String> = emptyMap(),
    val apps: List<AppInfo> = emptyList(),
    val log: List<String> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PrefsStore(app)
    private val engine = Engine(RootShell, store) { LogBuffer.add(it) }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    @Volatile
    private var visible = false
    private var lastSafe = 0L

    init {
        viewModelScope.launch {
            LogBuffer.flow.collect { l -> _ui.update { it.copy(log = l) } }
        }
        viewModelScope.launch(Dispatchers.IO) { boot() }
        viewModelScope.launch(Dispatchers.IO) { pollLoop() }
    }

    // ---------------------------------------------------------------- ciclo de vida

    fun setVisible(v: Boolean) {
        visible = v
    }

    fun setTab(t: Tab) {
        _ui.update { it.copy(tab = t) }
    }

    fun consumeMessage() {
        _ui.update { it.copy(message = null) }
    }

    fun retry() {
        _ui.update { it.copy(phase = Phase.Loading) }
        viewModelScope.launch(Dispatchers.IO) { boot() }
    }

    private fun readPrefs(): Map<String, String> = mapOf(
        "lock" to (store.get("lock") ?: "0"),
        "boot" to (store.get("boot") ?: "1"),
        "watchdog" to (store.get("watchdog") ?: "0"),
        "appprof" to (store.get("appprof") ?: "0"),
        "guard" to (store.get("guard") ?: "1"),
        "g_soc" to (store.get("g_soc") ?: "80"),
        "g_skin" to (store.get("g_skin") ?: "44"),
        "g_batt" to (store.get("g_batt") ?: "42"),
        "interval" to (store.get("interval") ?: "10"),
        "thermal_paused" to (store.get("thermal_paused") ?: "0"),
        "overlay_monitor" to (store.get("overlay_monitor") ?: "0"),
        "snapshot" to (if (engine.hasSnapshot()) "1" else "0")
    )

    private fun boot() {
        val root = try {
            RootShell.hasRoot()
        } catch (e: Throwable) {
            false
        }
        if (!root) {
            _ui.update { it.copy(phase = Phase.NoRoot) }
            return
        }
        try {
            val info = engine.detect()
            if (!engine.hasSnapshot()) engine.captureSnapshot()
            _ui.update {
                it.copy(
                    phase = Phase.Ready, info = info, profiles = engine.allProfiles(),
                    active = store.get("active"), prefs = readPrefs(), appRules = engine.appRules()
                )
            }
            refreshLive()
            refreshNodes()
        } catch (e: Throwable) {
            LogBuffer.add("ERRO na detecção: ${e.message}")
            _ui.update { it.copy(phase = Phase.NoRoot) }
        }
    }

    // ---------------------------------------------------------------- leitura

    private fun refreshLive() {
        val live = try {
            engine.readLive()
        } catch (e: Throwable) {
            LogBuffer.add("ERRO leitura: ${e.message}")
            return
        }
        _ui.update { it.copy(live = live) }
        val trip = engine.guardTrip(live)
        if (trip != null && System.currentTimeMillis() - lastSafe > 60_000) {
            lastSafe = System.currentTimeMillis()
            engine.safeMode(trip)
            _ui.update {
                it.copy(message = "Modo seguro: $trip", active = store.get("active"), prefs = readPrefs())
            }
        }
    }

    private fun refreshNodes() {
        val i = _ui.value.info ?: return
        val v = try {
            engine.readNodes(i.nodes)
        } catch (e: Throwable) {
            return
        }
        _ui.update { it.copy(nodeValues = v) }
    }

    private fun refreshExtras() {
        val m = try {
            engine.readExtras()
        } catch (e: Throwable) {
            return
        }
        _ui.update { it.copy(extras = m) }
    }

    private suspend fun pollLoop() {
        while (true) {
            val s = _ui.value
            if (visible && s.phase == Phase.Ready) {
                refreshLive()
                when (s.tab) {
                    Tab.Cpu, Tab.Gpu, Tab.System -> refreshNodes()
                    Tab.Thermal -> refreshExtras()
                    else -> {}
                }
            }
            delay(1500)
        }
    }

    fun readDisplay() {
        viewModelScope.launch(Dispatchers.IO) {
            val d = engine.readDisplay()
            _ui.update { it.copy(displayInfo = d) }
        }
    }

    // ---------------------------------------------------------------- ações

    private fun act(label: String, block: () -> List<WriteResult>) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(busy = true) }
            val msg = try {
                val r = block()
                val ok = r.count { it.ok }
                when {
                    r.isEmpty() -> "$label: nada a fazer"
                    ok == r.size -> "$label: $ok/${r.size} ok"
                    else -> "$label: ${r.size - ok} falha(s) — veja o log"
                }
            } catch (e: Throwable) {
                LogBuffer.add("ERRO $label: ${e.message}")
                "$label: erro"
            }
            refreshLive()
            refreshNodes()
            _ui.update {
                it.copy(
                    busy = false, message = msg, profiles = engine.allProfiles(),
                    active = store.get("active"), prefs = readPrefs()
                )
            }
        }
    }

    fun setCpu(policy: Int, min: Long?, max: Long?, gov: String?) =
        act("CPU cluster $policy") { engine.setCpu(policy, min, max, gov) }

    fun resetCpu(policy: Int) {
        val pc = _ui.value.info?.policy(policy) ?: return
        val gov = listOf("sugov_ext", "schedutil").firstOrNull { it in pc.governors } ?: pc.governors.firstOrNull()
        setCpu(policy, pc.minHw, pc.maxHw, gov)
    }

    fun setGpuCeil(kHz: Long) = act("Teto GPU") { listOf(engine.setGpuCeilingKHz(kHz)) }
    fun setGpuFloor(kHz: Long) = act("Piso GPU") { listOf(engine.setGpuFloorKHz(kHz)) }
    fun fixGpu(idx: Int?) = act(if (idx == null) "Liberar OPP" else "Fixar OPP $idx") { listOf(engine.fixGpuOpp(idx)) }
    fun setLimit(id: Int, enabled: Boolean) = act("Limitador $id") { listOf(engine.setLimit(id, enabled)) }
    fun writeNode(spec: NodeSpec, v: String) = act(spec.label) { listOf(engine.writeNode(spec, v)) }
    fun setRefresh(hz: Int?) = act("Taxa de atualização") { listOf(engine.setRefresh(hz)) }
    fun pauseThermal(p: Boolean) = act(if (p) "Pausar thermald" else "Retomar thermald") { engine.pauseThermal(p) }
    fun applyProfile(name: String) = act("Perfil $name") { engine.applyByName(name) }
    fun safeNow() = act("Restaurar padrão seguro") {
        val r = engine.safeMode("pedido do usuário")
        engine.restoreModes()
        r
    }

    fun saveCurrentAs(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val saved = engine.saveProfile(engine.captureCurrent(n))
            _ui.update { it.copy(profiles = engine.allProfiles(), message = "Perfil '$saved' salvo") }
        }
    }

    fun deleteProfile(name: String) {
        engine.deleteProfile(name)
        _ui.update { it.copy(profiles = engine.allProfiles()) }
    }

    fun captureSnapshot() {
        viewModelScope.launch(Dispatchers.IO) {
            val n = engine.captureSnapshot()
            _ui.update { it.copy(message = "Snapshot salvo ($n nós)", prefs = readPrefs()) }
        }
    }

    fun restoreSnapshot() = act("Restaurar snapshot") { engine.restoreSnapshot() }

    fun prepareDiagnosticExport(onReady: (String) -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, message = "Coletando diagnóstico completo…") }
            try {
                val state = _ui.value
                val stateText = buildString {
                    appendLine("App: MT6899 Manager")
                    appendLine("Fase: ${state.phase}")
                    appendLine("Aba: ${state.tab.title}")
                    appendLine("Perfil ativo: ${state.active ?: "nenhum"}")
                    appendLine("Preferências: ${state.prefs}")
                    appendLine("SoC detectado: ${state.info?.socModel ?: "indisponível"}")
                    appendLine("Modelo: ${state.info?.marketName ?: "indisponível"}")
                    appendLine("Kernel: ${state.info?.kernel ?: "indisponível"}")
                    appendLine("Clusters CPU: ${state.info?.policies?.size ?: 0}")
                    appendLine("OPPs GPU: ${state.info?.gpuOpps?.size ?: 0}")
                    appendLine("Nós detectados: ${state.info?.nodes?.size ?: 0}")
                    appendLine("Estado ao vivo: ${state.live ?: "indisponível"}")
                    appendLine("Valores dos nós: ${state.nodeValues}")
                    appendLine("Extras: ${state.extras}")
                }
                val report = withContext(Dispatchers.IO) {
                    engine.diagnosticReport(LogBuffer.snapshot(), stateText)
                }
                _ui.update { it.copy(busy = false, message = "Diagnóstico pronto para exportar") }
                onReady(report)
            } catch (t: Throwable) {
                LogBuffer.add("ERRO ao coletar diagnóstico: ${t.javaClass.simpleName}: ${t.message}")
                _ui.update { it.copy(busy = false, message = "Falha ao coletar diagnóstico: ${t.message}") }
            }
        }
    }

    fun clearLog() = LogBuffer.clear()

    fun setPref(k: String, v: String) {
        store.put(k, v)
        if (k == "watchdog") {
            val ctx = getApplication<Application>()
            val i = Intent(ctx, GuardService::class.java)
            if (v == "1") ContextCompat.startForegroundService(ctx, i)
            else if (!engine.appProfilesOn()) ctx.stopService(i)
        }
        if (k == "appprof") {
            if (v == "1") startGuard() else engine.clearAppOverride() // o serviço encerra sozinho se nada mais o exige
        }
        _ui.update { it.copy(prefs = readPrefs()) }
    }

    private fun startGuard() {
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
    }

    /** Apps com ícone no launcher, para o seletor de "Perfil por app". */
    fun loadApps() {
        if (_ui.value.apps.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pm = getApplication<Application>().packageManager
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(launcher, 0)
                    .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                    .distinctBy { it.pkg }
                    .sortedBy { it.label.lowercase() }
                _ui.update { it.copy(apps = apps) }
            } catch (e: Throwable) {
                LogBuffer.add("Lista de apps: ${e.message}")
            }
        }
    }

    fun addAppRule(pkg: String, profile: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = engine.setAppRule(pkg, profile)
            if (ok && store.get("appprof") == "1") startGuard()
            _ui.update { it.copy(appRules = engine.appRules(), message = if (ok) "Regra salva" else "Regra recusada") }
        }
    }

    fun removeAppRule(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            engine.setAppRule(pkg, null)
            _ui.update { it.copy(appRules = engine.appRules(), message = "Regra removida") }
        }
    }

    fun startOverlayMonitor() {
        val app = getApplication<Application>()
        if (!Settings.canDrawOverlays(app)) {
            store.put("overlay_pending", "1")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${app.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            _ui.update { it.copy(message = "Permita 'Exibir sobre outros apps'; o monitor iniciará ao voltar.") }
            return
        }
        store.put("overlay_pending", "0")
        store.put("overlay_monitor", "1")
        ContextCompat.startForegroundService(app, Intent(app, OverlayMonitorService::class.java))
        _ui.update { it.copy(prefs = readPrefs(), message = "Monitor flutuante iniciado") }
    }

    fun stopOverlayMonitor() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, OverlayMonitorService::class.java))
        store.put("overlay_pending", "0")
        store.put("overlay_monitor", "0")
        _ui.update { it.copy(prefs = readPrefs(), message = "Monitor flutuante encerrado") }
    }

    fun handleOverlayPermissionReturn() {
        val app = getApplication<Application>()
        if (store.get("overlay_pending") == "1") {
            if (Settings.canDrawOverlays(app)) {
                startOverlayMonitor()
            } else {
                store.put("overlay_pending", "0")
            }
        }
        _ui.update { it.copy(prefs = readPrefs()) }
    }
}
