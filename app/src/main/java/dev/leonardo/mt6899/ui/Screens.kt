@file:OptIn(ExperimentalMaterial3Api::class)

package dev.leonardo.mt6899.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.leonardo.mt6899.MainViewModel
import dev.leonardo.mt6899.UiState
import dev.leonardo.mt6899.core.Profile
import dev.leonardo.mt6899.core.Risk

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NodeGroup(ui: UiState, vm: MainViewModel, group: String, title: String = group, subtitle: String? = null) {
    val nodes = ui.info?.nodes?.filter { it.group == group } ?: emptyList()
    if (nodes.isEmpty()) return
    Section(title, subtitle) {
        nodes.forEach { n -> NodeRow(n, ui.nodeValues[n.id]) { v -> vm.writeNode(n, v) } }
    }
}

// =====================================================================================
// PAINEL
// =====================================================================================

@Composable
fun DashboardScreen(ui: UiState, vm: MainViewModel) {
    val info = ui.info ?: return
    val live = ui.live
    var confirm by remember { mutableStateOf<String?>(null) }
    Page {
        Section("Aparelho") {
            KV("Modelo", "${info.marketName} (${info.device})")
            KV("SoC", info.socModel)
            KV("Kernel", info.kernel)
            KV("GPU", info.gpuName)
        }
        Section("Perfil", "Toque para aplicar. O perfil ativo é mantido pelo serviço, se ligado.") {
            ChipChoice(
                label = null,
                options = ui.profiles.map { it.name },
                selected = ui.active,
                enabled = !ui.busy,
                onSelect = { n -> if (n.endsWith("⚠")) confirm = n else vm.applyProfile(n) }
            )
        }
        if (live != null) {
            Section("CPU") {
                info.policies.forEach { pc ->
                    val l = live.cpu[pc.id]
                    if (l != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(pc.label)
                                Text(mhz(l.cur), fontWeight = FontWeight.Bold)
                            }
                            LinearProgressIndicator(
                                progress = { (l.cur.toFloat() / pc.maxHw.coerceAtLeast(1L)).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp)
                            )
                            Hint("${l.gov} · faixa ${mhz(l.min)} – ${mhz(l.max)} (hw ${mhz(pc.maxHw)})")
                        }
                    }
                }
            }
            Section("GPU") {
                KV("Frequência", live.gpuKHz?.let { mhz(it) } ?: "—")
                KV("Uso", live.gpuUtil?.let { "$it%" } ?: "—")
                val ceilName = live.limits.firstOrNull { it.id == live.ppmCeilLimiter }?.name ?: live.ppmCeilLimiter?.let { "id $it" } ?: "—"
                val ceilKHz = live.ppmCeilIdx?.let { i -> info.gpuOpps.firstOrNull { it.idx == i }?.kHz }
                KV("Teto efetivo", (ceilKHz?.let { mhz(it) } ?: "—") + " · $ceilName")
                val floorName = live.limits.firstOrNull { it.id == live.ppmFloorLimiter }?.name ?: live.ppmFloorLimiter?.let { "id $it" } ?: "—"
                KV("Piso efetivo", floorName)
            }
            Section("Temperaturas") {
                live.temps.forEach { (k, v) -> KV(k, deg(v), tempColor(v)) }
                live.battTempC?.let { KV("bateria (power_supply)", deg(it), tempColor(it)) }
                live.battPct?.let { KV("Carga", "$it%") }
            }
        }
        Section("Proteção") {
            val g = ui.prefs
            KV("Guarda térmica", if (g["guard"] == "0") "desligada" else "ligada (SoC ${g["g_soc"]}°C · sup. ${g["g_skin"]}°C · bat. ${g["g_batt"]}°C)")
            KV("Serviço em segundo plano", if (g["watchdog"] == "1") "ligado" else "desligado")
            if (g["thermal_paused"] == "1") Text("mi_thermald/thermald pausados", color = Red)
            OutlinedButton(onClick = { vm.safeNow() }, enabled = !ui.busy) { Text("Voltar ao padrão seguro") }
        }
    }
    val c = confirm
    if (c != null) {
        ConfirmDialog(
            "Aplicar '$c'?",
            "Este perfil desliga o limitador térmico AP da GPU e fixa pisos altos de frequência. Aquece e gasta bateria. Mantenha a guarda térmica ligada.",
            onConfirm = { vm.applyProfile(c) },
            onDismiss = { confirm = null }
        )
    }
}

// =====================================================================================
// CPU
// =====================================================================================

@Composable
fun CpuScreen(ui: UiState, vm: MainViewModel) {
    val info = ui.info ?: return
    val live = ui.live
    Page {
        info.policies.forEach { pc ->
            val l = live?.cpu?.get(pc.id)
            var adv by remember { mutableStateOf(false) }
            Section(pc.label, "capacidade ${pc.capacity ?: "?"} · atual ${l?.let { mhz(it.cur) } ?: "—"}") {
                ChipChoice("Governor", pc.governors, l?.gov, enabled = !ui.busy) { g -> vm.setCpu(pc.id, null, null, g) }
                FreqSlider("Frequência máxima", pc.freqs, l?.max, enabled = !ui.busy) { f -> vm.setCpu(pc.id, null, f, null) }
                FreqSlider("Frequência mínima", pc.freqs, l?.min, enabled = !ui.busy) { f -> vm.setCpu(pc.id, f, null, null) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.resetCpu(pc.id) }, enabled = !ui.busy) { Text("Padrão do cluster") }
                    TextButton(onClick = { adv = !adv }) { Text(if (adv) "Ocultar core_ctl" else "core_ctl") }
                }
                if (adv) {
                    info.nodes.filter { it.group.endsWith("cluster ${pc.id}") }.forEach { n ->
                        NodeRow(n, ui.nodeValues[n.id]) { v -> vm.writeNode(n, v) }
                    }
                }
            }
        }
        Section("Travar valores", "Depois de gravar, deixa o arquivo somente leitura (chmod 444) para que mi_thermald e o perf HAL não o sobrescrevam. \"Voltar ao padrão seguro\" restaura as permissões.") {
            SwitchRow("Travar escrita de min/max", ui.prefs["lock"] == "1") { vm.setPref("lock", if (it) "1" else "0") }
        }
        NodeGroup(ui, vm, "Scheduler")
    }
}

// =====================================================================================
// GPU
// =====================================================================================

@Composable
fun GpuScreen(ui: UiState, vm: MainViewModel) {
    val info = ui.info ?: return
    val live = ui.live
    val freqs = info.gpuOpps.map { it.kHz }.sorted()
    Page {
        Section("Estado", info.gpuName) {
            KV("Frequência", live?.gpuKHz?.let { mhz(it) } ?: "—")
            KV("Uso", live?.gpuUtil?.let { "$it%" } ?: "—")
            KV("OPP atual", live?.gpuIdx?.toString() ?: "—")
            if (live != null) {
                val cn = live.limits.firstOrNull { it.id == live.ppmCeilLimiter }?.name ?: "—"
                val ck = info.gpuOpps.firstOrNull { it.idx == live.ppmCeilIdx }?.kHz
                KV("Teto efetivo (PPM)", (ck?.let { mhz(it) } ?: "—") + " · $cn")
                val fn = live.limits.firstOrNull { it.id == live.ppmFloorLimiter }?.name ?: "—"
                KV("Piso efetivo (PPM)", fn)
            }
        }
        Section(
            "Limites deste app (GED)",
            "O GED recebe limites de vários clientes e vale o mais restritivo. Para ir acima de um teto térmico, use a seção de limitadores abaixo."
        ) {
            val upK = live?.gpuUpIdx?.let { i -> if (i <= 0) info.gpuMaxKHz else info.gpuOpps.firstOrNull { it.idx == i }?.kHz }
            val boK = live?.gpuBoostIdx?.let { i -> if (i >= info.gpuOpps.size - 1) info.gpuMinKHz else info.gpuOpps.firstOrNull { it.idx == i }?.kHz }
            FreqSlider("Teto (custom_upbound)", freqs, upK, enabled = !ui.busy) { vm.setGpuCeil(it) }
            FreqSlider("Piso (custom_boost)", freqs, boK, enabled = !ui.busy) { vm.setGpuFloor(it) }
            Hint("Teto no máximo e piso no mínimo = sem limite deste app.")
        }
        Section("Fixar frequência (OPP)", "Útil para benchmark. A GPU volta ao DVFS normal ao liberar.") {
            FreqSlider("OPP fixo", freqs, live?.gpuKHz, enabled = !ui.busy) { f ->
                val idx = info.gpuOpps.firstOrNull { it.kHz == f }?.idx
                if (idx != null) vm.fixGpu(idx)
            }
            OutlinedButton(onClick = { vm.fixGpu(null) }, enabled = !ui.busy) { Text("Liberar (DVFS normal)") }
        }
        Section(
            "Limitadores do driver (PPM - somente leitura)",
            "A tabela é exibida para identificar quem está impondo teto/piso. A escrita foi desativada porque o protocolo deste kernel ainda não foi validado; proteções térmicas e de potência não serão alteradas."
        ) {
            val limits = live?.limits ?: emptyList()
            if (limits.isEmpty()) Hint("limit_table indisponível")
            limits.forEach { lim ->
                val ck = if (lim.ceiling >= 0) info.gpuOpps.firstOrNull { it.idx == lim.ceiling }?.kHz else null
                val fk = if (lim.floor >= 0) info.gpuOpps.firstOrNull { it.idx == lim.floor }?.kHz else null
                KV(lim.name, "prioridade ${lim.priority} · teto " + (ck?.let { mhz(it) } ?: "sem teto") + " · piso " + (fk?.let { mhz(it) } ?: "sem piso"))
                Hint("ceiling idx=${lim.ceiling} (${if (lim.cEnable) "ativo" else "inativo"}) · floor idx=${lim.floor} (${if (lim.fEnable) "ativo" else "inativo"})")
            }
        }
        val devfreq = info.gpuDevfreq
        if (devfreq != null) {
            val minNode = info.node("gpu.dev.min")
            val maxNode = info.node("gpu.dev.max")
            val govNode = info.node("gpu.dev.gov")
            val minHz = ui.nodeValues["gpu.dev.min"]?.toLongOrNull()
            val maxHz = ui.nodeValues["gpu.dev.max"]?.toLongOrNull()
            Section(
                "Controle direto · Mali Devfreq",
                "${devfreq.freqs.size} OPPs detectados. A tabela do aparelho vai de ${devfreq.freqs.firstOrNull()?.div(1_000_000L) ?: 0} a ${devfreq.freqs.lastOrNull()?.div(1_000_000L) ?: 0} MHz. O driver ainda pode impor limites térmicos ou de potência."
            ) {
                KV("Mínimo aplicado", minHz?.let { "${it / 1_000_000L} MHz" } ?: "—")
                KV("Máximo aplicado", maxHz?.let { "${it / 1_000_000L} MHz" } ?: "—")
                FreqSlider(
                    "Limite mínimo",
                    devfreq.freqs,
                    minHz,
                    fmt = { "${it / 1_000_000L} MHz" },
                    enabled = !ui.busy
                ) { hz -> minNode?.let { vm.writeNode(it, hz.toString()) } }
                FreqSlider(
                    "Limite máximo",
                    devfreq.freqs,
                    maxHz,
                    fmt = { "${it / 1_000_000L} MHz" },
                    enabled = !ui.busy
                ) { hz -> maxNode?.let { vm.writeNode(it, hz.toString()) } }
                if (govNode != null && devfreq.governors.isNotEmpty()) {
                    ChipChoice(
                        "Governor Devfreq",
                        devfreq.governors,
                        ui.nodeValues["gpu.dev.gov"],
                        enabled = !ui.busy
                    ) { governor -> vm.writeNode(govNode, governor) }
                }
                Hint("Os limites são escritos em Hz nos nós reais min_freq/max_freq. Se o kernel rejeitar ou normalizar o valor, a interface informa a falha.")
            }
        }
        Section(
            "Desativar funções do GED",
            "Ajuste em tempo real dos parâmetros publicados pelo kernel. 0 desativa a função; 1 reativa. Isso não descarrega o módulo GED."
        ) {
            val gedControls = info.nodes.filter { it.group == "GED · controle" }
            if (gedControls.isEmpty()) {
                Hint("Este kernel não expõe gpu_dvfs_enable / ged_boost_enable em /sys/module/ged/parameters.")
            } else {
                gedControls.forEach { node ->
                    NodeRow(node, ui.nodeValues[node.id]) { value -> vm.writeNode(node, value) }
                }
            }
        }
                NodeGroup(ui, vm, "GPU · GED", "Ajuste fino do DVFS (GED)")
    }

}

// =====================================================================================
// TÉRMICA
// =====================================================================================

@Composable
fun ThermalScreen(ui: UiState, vm: MainViewModel) {
    val live = ui.live
    var confirmPause by remember { mutableStateOf(false) }
    Page {
        Section("Sensores") {
            if (live == null) Hint("sem leitura") else {
                live.temps.forEach { (k, v) -> KV(k, deg(v), tempColor(v)) }
                live.battTempC?.let { KV("bateria", deg(it), tempColor(it)) }
                live.flags.forEach { (k, v) -> KV(k, v) }
            }
        }
        Section("Estado do thermal_message (HyperOS)") {
            val ex = ui.extras
            if (ex.isEmpty()) Hint("lendo…")
            ex.filterKeys { !it.startsWith("cool:") }.forEach { (k, v) -> KV(k, v) }
        }
        Section("Cooling devices (atual/máx)", "Os de cpu-pause ficam ocultos.") {
            ui.extras.filterKeys { it.startsWith("cool:") }.forEach { (k, v) -> KV(k.removePrefix("cool:"), v) }
        }
        Section(
            "Serviços térmicos",
            "Pausar mi_thermald e thermald tira a camada de software que reduz clock e brilho. O driver do kernel e o hardware continuam protegendo o chip."
        ) {
            SwitchRow(
                "Pausar mi_thermald / thermald",
                ui.prefs["thermal_paused"] == "1",
                enabled = !ui.busy
            ) { on -> if (on) confirmPause = true else vm.pauseThermal(false) }
        }
        NodeGroup(ui, vm, "Térmica", "Driver térmico / FPSGO")
        Section("Guarda térmica", "Se um limite for passado com proteções enfraquecidas, volta ao padrão seguro sozinha (app aberto ou serviço ligado).") {
            SwitchRow("Guarda ligada", ui.prefs["guard"] != "0") { vm.setPref("guard", if (it) "1" else "0") }
            Stepper("SoC", ui.prefs["g_soc"]?.toIntOrNull() ?: 80, "°C", 60, 100) { vm.setPref("g_soc", it.toString()) }
            Stepper("Superfície", ui.prefs["g_skin"]?.toIntOrNull() ?: 44, "°C", 36, 52) { vm.setPref("g_skin", it.toString()) }
            Stepper("Bateria", ui.prefs["g_batt"]?.toIntOrNull() ?: 42, "°C", 36, 50) { vm.setPref("g_batt", it.toString()) }
        }
    }
    if (confirmPause) {
        ConfirmDialog(
            "Pausar serviços térmicos?",
            "Mantenha a guarda térmica ligada e o serviço em segundo plano ativo (aba Perfis).",
            onConfirm = { vm.pauseThermal(true) },
            onDismiss = { confirmPause = false }
        )
    }
}

// =====================================================================================
// SISTEMA
// =====================================================================================

@Composable
fun SystemScreen(ui: UiState, vm: MainViewModel) {
    Page {
        Section("Taxa de atualização", "Melhor esforço via settings; o HyperOS pode sobrescrever.") {
            ChipChoice(
                label = null,
                options = listOf("Auto", "30", "60", "90", "120"),
                selected = null,
                enabled = !ui.busy,
                display = { if (it == "Auto") it else "$it Hz" },
                onSelect = { vm.setRefresh(it.toIntOrNull()) }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.readDisplay() }) { Text("Ler estado") }
                Spacer(Modifier.width(8.dp))
                Hint(ui.displayInfo)
            }
        }
        NodeGroup(ui, vm, "DDR / UFS", "DDR (DVFSRC) e UFS")
        NodeGroup(ui, vm, "Memória", "Memória virtual")
        NodeGroup(ui, vm, "I/O", "Armazenamento (I/O)")
    }
}

// =====================================================================================
// PERFIS
// =====================================================================================

private fun summary(ui: UiState, p: Profile): String {
    val info = ui.info
    val cpu = info?.policies?.joinToString("/") { pc ->
        val v = p.values["cpu${pc.id}.max"]?.toLongOrNull()
        if (v == null) "·" else if (v <= 0) "máx" else mhz(v)
    } ?: ""
    val gpu = p.values["gpu.max_khz"]?.toLongOrNull()?.let { if (it <= 0) "sem teto" else "≤ ${mhz(it)}" }
    val floor = p.values["gpu.min_khz"]?.toLongOrNull()?.takeIf { it > 0 }?.let { "piso ${mhz(it)}" }
    val hz = p.values["display.hz"]?.let { "$it Hz" }
    val lim = p.values.keys.filter { it.startsWith("gpu.limit.") }.joinToString { it.removePrefix("gpu.limit.") + " off" }
    return listOfNotNull(
        if (cpu.isNotEmpty()) "CPU máx $cpu" else null,
        gpu?.let { "GPU $it" }, floor, hz, lim.ifEmpty { null }
    ).joinToString(" · ")
}

@Composable
fun ProfilesScreen(ui: UiState, vm: MainViewModel) {
    var newName by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    Page {
        ui.profiles.forEach { p ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.name + if (ui.active == p.name) "  ●" else "",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (ui.active == p.name) Green else Color.Unspecified
                        )
                        if (p.builtin) Hint("padrão") else TextButton(onClick = { vm.deleteProfile(p.name) }) { Text("Excluir") }
                    }
                    Hint(summary(ui, p))
                    Button(
                        onClick = { if (p.name.endsWith("⚠")) confirm = p.name else vm.applyProfile(p.name) },
                        enabled = !ui.busy
                    ) { Text("Aplicar") }
                }
            }
        }
        Section("Salvar estado atual como perfil", "Captura governors, min/max de cada cluster e teto/piso da GPU como estão agora.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                    label = { Text("Nome") }, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.saveCurrentAs(newName); newName = "" }, enabled = newName.isNotBlank()) { Text("Salvar") }
            }
        }
        Section("Automação") {
            SwitchRow("Aplicar perfil no boot", ui.prefs["boot"] != "0", "Aguarda ~20 s depois do boot para o sistema assentar. Em HyperOS, libere \"Início automático\" para o app.") {
                vm.setPref("boot", if (it) "1" else "0")
            }
            SwitchRow(
                "Serviço em segundo plano",
                ui.prefs["watchdog"] == "1",
                "Reaplica o perfil quando mi_thermald/perf HAL mudarem os nós e vigia a temperatura."
            ) { vm.setPref("watchdog", if (it) "1" else "0") }
            Stepper("Intervalo do serviço", ui.prefs["interval"]?.toIntOrNull() ?: 10, " s", 3, 120, 1) {
                vm.setPref("interval", it.toString())
            }
        }
        AppProfilesSection(ui, vm)
        Section(
            "Monitor flutuante de CPU/GPU",
            "Mostra as frequências atuais por cima de outros apps. Arraste pela barra superior; o serviço continua ativo ao fechar o MT6899 Manager."
        ) {
            val overlayRunning = ui.prefs["overlay_monitor"] == "1"
            Button(
                onClick = { if (overlayRunning) vm.stopOverlayMonitor() else vm.startOverlayMonitor() },
                enabled = ui.phase == dev.leonardo.mt6899.Phase.Ready
            ) {
                Text(if (overlayRunning) "Parar monitor" else "Iniciar monitor flutuante")
            }
            Hint("Na primeira vez, permita 'Exibir sobre outros apps'. Um aviso permanente permite parar o monitor.")
        }
        Section("Snapshot e padrão", "O snapshot é o estado de quando o app abriu pela primeira vez (não necessariamente o de fábrica).") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.captureSnapshot() }, enabled = !ui.busy) { Text("Capturar agora") }
                OutlinedButton(onClick = { vm.restoreSnapshot() }, enabled = !ui.busy) { Text("Restaurar") }
            }
            Button(onClick = { vm.safeNow() }, enabled = !ui.busy) { Text("Padrão seguro (Equilibrado)") }
        }
    }
    val c = confirm
    if (c != null) {
        ConfirmDialog(
            "Aplicar '$c'?",
            "Desliga o limitador térmico AP da GPU e fixa pisos altos. Aquece e gasta bateria. Mantenha a guarda térmica ligada.",
            onConfirm = { vm.applyProfile(c) },
            onDismiss = { confirm = null }
        )
    }
}
