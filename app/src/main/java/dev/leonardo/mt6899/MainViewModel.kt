package dev.leonardo.mt6899

import android.app.Application
import android.content.Intent
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

enum class Phase { Loading, NoRoot, Ready }

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
        "guard" to (store.get("guard") ?: "1"),
        "g_soc" to (store.get("g_soc") ?: "80"),
        "g_skin" to (store.get("g_skin") ?: "44"),
        "g_batt" to (store.get("g_batt") ?: "42"),
        "interval" to (store.get("interval") ?: "10"),
        "thermal_paused" to (store.get("thermal_paused") ?: "0"),
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
                    active = store.get("active"), prefs = readPrefs()
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
            engine.saveProfile(engine.captureCurrent(n))
            _ui.update { it.copy(profiles = engine.allProfiles(), message = "Perfil '$n' salvo") }
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

    fun clearLog() = LogBuffer.clear()

    fun setPref(k: String, v: String) {
        store.put(k, v)
        if (k == "watchdog") {
            val ctx = getApplication<Application>()
            val i = Intent(ctx, GuardService::class.java)
            if (v == "1") ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
        }
        _ui.update { it.copy(prefs = readPrefs()) }
    }
}
