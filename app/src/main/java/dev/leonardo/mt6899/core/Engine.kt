package dev.leonardo.mt6899.core

/**
 * Núcleo do gerenciador: lê/escreve os nós reais do MT6899 (POCO X8 Pro) via [Shell] root.
 * Não depende de Android; [prefix] existe apenas para testes contra uma árvore sysfs falsa.
 */
class Engine(
    private val sh: Shell,
    val store: Store,
    private val prefix: String = "",
    private val logger: (String) -> Unit = {}
) {
    @Volatile
    var info: SocInfo? = null
        private set

    private fun log(s: String) = logger(s)
    private fun p(path: String) = prefix + path
    private fun pol(id: Int) = "/sys/devices/system/cpu/cpufreq/policy$id"

    companion object {
        const val GPU_LIMIT_TABLE = "/proc/gpufreqv2/limit_table"
        const val GPU_STATUS = "/proc/gpufreqv2/gpufreq_status"
        const val GPU_OPP_TABLE = "/proc/gpufreqv2/gpu_working_opp_table"
        const val GPU_FIX = "/proc/gpufreqv2/fix_target_opp_index"
        const val GED = "/sys/kernel/ged/hal"
        const val GED_UP = "$GED/custom_upbound_gpu_freq"
        const val GED_BOOST = "$GED/custom_boost_gpu_freq"
        const val GED_CUR = "$GED/current_freqency"          // grafia do kernel
        const val GED_UTIL = "$GED/gpu_utilization"
        const val BATT = "/sys/class/power_supply/battery"
        const val KTHERMAL = "/sys/kernel/thermal"

        val TEMP_ZONES = listOf(
            "soc_max", "cpu-big-core7-0", "cpu-medium-core4-0", "cpu-little-core0",
            "gpu0", "quiet_therm", "display_therm", "battery", "wifi_therm", "flash_therm"
        )
    }

    // ------------------------------------------------------------------ leitura

    /** Lê vários arquivos num único shell. Valor null = arquivo inexistente. */
    fun readMany(paths: List<String>): Map<String, String?> {
        if (paths.isEmpty()) return emptyMap()
        val sb = StringBuilder("for p in ")
        paths.forEach { sb.append(q(p(it))).append(' ') }
        sb.append("; do if [ -e \"\$p\" ]; then echo \"@@E\$p\"; head -c 4000 \"\$p\" 2>/dev/null; echo; ")
        sb.append("else echo \"@@N\$p\"; fi; done")
        val out = sh.exec(sb.toString()).out
        val res = LinkedHashMap<String, String?>()
        var cur: String? = null
        var exists = false
        val buf = StringBuilder()
        fun flush() {
            val c = cur ?: return
            res[c] = if (exists) buf.toString().trimEnd() else null
        }
        for (line in out.lines()) {
            if (line.startsWith("@@E") || line.startsWith("@@N")) {
                flush()
                buf.setLength(0)
                exists = line.startsWith("@@E")
                cur = line.substring(3).removePrefix(prefix)
            } else {
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(line)
            }
        }
        flush()
        // garante todas as chaves pedidas
        return paths.associateWith { res[it] }
    }

    fun readOne(path: String): String? = readMany(listOf(path))[path]

    private fun prop(k: String): String = sh.exec("getprop $k").out.trim()

    // ------------------------------------------------------------------ detecção

    fun detect(): SocInfo {
        val dirs = sh.exec("ls -d " + q(p("/sys/devices/system/cpu/cpufreq")) + "/policy* 2>/dev/null").out.lines()
        val ids = dirs.mapNotNull { it.trim().substringAfterLast("policy").toIntOrNull() }.sorted()
        val files = listOf(
            "related_cpus", "scaling_available_frequencies", "scaling_available_governors",
            "cpuinfo_min_freq", "cpuinfo_max_freq"
        )
        val r = readMany(ids.flatMap { id -> files.map { "${pol(id)}/$it" } })
        val firstCpu = ids.associateWith { id -> Parse.words(r["${pol(id)}/related_cpus"]).firstOrNull() }
        val caps = readMany(firstCpu.values.filterNotNull().map { "/sys/devices/system/cpu/cpu$it/cpu_capacity" })

        val policies = ids.map { id ->
            val freqs = Parse.longs(r["${pol(id)}/scaling_available_frequencies"]).sorted()
            val minHw = Parse.intOf(r["${pol(id)}/cpuinfo_min_freq"]) ?: freqs.firstOrNull() ?: 0L
            val maxHw = Parse.intOf(r["${pol(id)}/cpuinfo_max_freq"]) ?: freqs.lastOrNull() ?: 0L
            CpuPolicy(
                id = id,
                cpus = (r["${pol(id)}/related_cpus"] ?: "").trim(),
                capacity = firstCpu[id]?.let { Parse.intOf(caps["/sys/devices/system/cpu/cpu$it/cpu_capacity"])?.toInt() },
                freqs = freqs,
                governors = Parse.words(r["${pol(id)}/scaling_available_governors"]),
                minHw = minHw,
                maxHw = maxHw
            )
        }

        // GPU
        val g = readMany(listOf(GPU_OPP_TABLE, "/sys/class/misc/mali0/device/gpuinfo"))
        var opps = Parse.oppTable(g[GPU_OPP_TABLE])

        // devfreq
        val dfNames = sh.exec("ls " + q(p("/sys/class/devfreq")) + " 2>/dev/null").out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        fun df(match: String): DevfreqInfo? {
            val name = dfNames.firstOrNull { it.contains(match, ignoreCase = true) } ?: return null
            val base = "/sys/class/devfreq/$name"
            val rr = readMany(listOf("$base/available_frequencies", "$base/available_governors"))
            val f = rr["$base/available_frequencies"] ?: return null
            return DevfreqInfo(base, Parse.longs(f).sorted(), Parse.words(rr["$base/available_governors"]))
        }
        val gpuDev = df("mali")
        val ddr = df("dvfsrc")
        val ufs = df("ufs")
        if (opps.isEmpty() && gpuDev != null) {
            opps = gpuDev.freqs.sortedDescending().mapIndexed { i, hz -> GpuOpp(i, hz / 1000, 0) }
        }

        // zonas térmicas
        val zoneOut = sh.exec(
            "for z in " + q(p("/sys/class/thermal")) + "/thermal_zone*; do echo \"\${z##*thermal_zone} \$(cat \"\$z/type\" 2>/dev/null)\"; done"
        ).out
        val zones = LinkedHashMap<String, Int>()
        zoneOut.lines().forEach { l ->
            val parts = l.trim().split(" ", limit = 2)
            val n = parts.getOrNull(0)?.toIntOrNull()
            val t = parts.getOrNull(1)?.trim()
            if (n != null && !t.isNullOrEmpty() && t !in zones) zones[t] = n
        }

        val blocks = sh.exec("ls " + q(p("/sys/block")) + " 2>/dev/null").out.lines()
            .map { it.trim() }.filter { Regex("^(sd[a-z]|mmcblk\\d+|nvme\\d+n\\d+)$").matches(it) }

        val cands = candidates(policies, ddr, ufs, gpuDev, blocks)
        val ex = readMany(cands.map { it.path })
        val nodes = cands.filter { ex[it.path] != null }.map { n ->
            // escolhas de scheduler vêm do próprio arquivo
            if (n.id.endsWith(".sched")) n.copy(choices = Parse.scheduler(ex[n.path]).second) else n
        }

        val market = prop("ro.product.marketname").ifEmpty { prop("ro.product.model") }
        val socModel = prop("ro.soc.model").ifEmpty { prop("ro.board.platform") }
        val result = SocInfo(
            marketName = market.ifEmpty { "?" },
            device = prop("ro.product.device").ifEmpty { "?" },
            socModel = socModel.ifEmpty { "?" },
            kernel = sh.exec("uname -r").out.trim(),
            gpuName = Parse.firstLine(g["/sys/class/misc/mali0/device/gpuinfo"]).ifEmpty { "GPU" },
            policies = policies,
            gpuOpps = opps,
            ddr = ddr,
            ufs = ufs,
            gpuDevfreq = gpuDev,
            zones = zones,
            blockDevs = blocks,
            nodes = nodes
        )
        info = result
        log("SoC ${result.socModel} · ${result.marketName} · ${policies.size} clusters · ${opps.size} OPPs GPU · ${nodes.size} nós ajustáveis")
        return result
    }

    private fun candidates(
        policies: List<CpuPolicy>, ddr: DevfreqInfo?, ufs: DevfreqInfo?, gpuDev: DevfreqInfo?, blocks: List<String>
    ): List<NodeSpec> {
        val l = ArrayList<NodeSpec>()
        for (pc in policies) {
            val first = Parse.words(pc.cpus).firstOrNull() ?: continue
            val b = "/sys/devices/system/cpu/cpu$first/core_ctl"
            val g = "CPU · core_ctl · cluster ${pc.id}"
            fun cc(n: String, lbl: String, hint: String = "", kind: Kind = Kind.INT, ch: List<String> = emptyList()) =
                l.add(NodeSpec("cpu${pc.id}.cc.$n", lbl, "$b/$n", g, kind, Risk.CAUTION, hint, ch))
            cc("enable", "core_ctl ativo", "0 = desliga o controle de núcleos deste cluster", Kind.CHOICE, listOf("0", "1"))
            cc("min_cpus", "Mínimo de CPUs online")
            cc("max_cpus", "Máximo de CPUs online")
            cc("up_thres", "Limiar para ligar núcleo (up_thres)")
            cc("cpu_busy_up_thres", "Busy up threshold")
            cc("cpu_busy_down_thres", "Busy down threshold")
        }
        l.add(NodeSpec("sched.pelt", "PELT multiplier", "/proc/sys/kernel/sched_pelt_multiplier", "Scheduler",
            Kind.CHOICE, Risk.CAUTION, "Maior = carga reage mais rápido (mais consumo). Visto: 4", listOf("1", "2", "4")))
        l.add(NodeSpec("sched.eas", "Energy Aware Scheduling", "/proc/sys/kernel/sched_energy_aware", "Scheduler",
            Kind.CHOICE, Risk.CAUTION, "", listOf("0", "1")))

        val gG = "GPU · GED"
        fun ged(id: String, lbl: String, f: String, hint: String, risk: Risk = Risk.CAUTION) =
            l.add(NodeSpec(id, lbl, "$GED/$f", gG, Kind.INT, risk, hint))
        ged("gpu.margin", "DVFS margin", "dvfs_margin_value", "Margem do DVFS da GPU. Visto: 530")
        ged("gpu.window", "Janela de carga", "loading_window_size", "Visto: 8")
        ged("gpu.stride", "Stride de carga", "loading_stride_size", "Visto: 2")
        ged("gpu.timer_margin", "Margem do timer", "timer_base_dvfs_margin", "Visto: 30")
        ged("gpu.loadmode", "Modo de loading", "dvfs_loading_mode", "Visto: 6")
        ged("gpu.boostlevel", "Boost level", "gpu_boost_level", "-1 = sem boost forçado")
        ged("gpu.fwidle", "Fallback window", "fallback_window_size", "Visto: 1")
        if (gpuDev != null) {
            val ch = gpuDev.freqs.map { it.toString() }
            l.add(NodeSpec("gpu.dev.max", "Limite máximo Devfreq (Hz)", gpuDev.path + "/max_freq", "GPU · devfreq", Kind.CHOICE, Risk.CAUTION,
                "Teto da interface Mali Devfreq. O driver ou a política térmica ainda podem impor um teto menor.", ch))
            l.add(NodeSpec("gpu.dev.min", "Limite mínimo Devfreq (Hz)", gpuDev.path + "/min_freq", "GPU · devfreq", Kind.CHOICE, Risk.CAUTION,
                "Piso da interface Mali Devfreq.", ch))
            l.add(NodeSpec("gpu.dev.gov", "Governor Devfreq", gpuDev.path + "/governor", "GPU · devfreq", Kind.CHOICE, Risk.CAUTION,
                "Disponibilidade e aceitação dependem do driver MediaTek.", gpuDev.governors))
        }
        if (ddr != null) {
            val ch = ddr.freqs.map { it.toString() }
            l.add(NodeSpec("ddr.gov", "DDR governor", ddr.path + "/governor", "DDR / UFS", Kind.CHOICE, Risk.CAUTION, "", ddr.governors))
            l.add(NodeSpec("ddr.min", "DDR min (B/s)", ddr.path + "/min_freq", "DDR / UFS", Kind.CHOICE, Risk.CAUTION,
                "Subir o piso mantém a DRAM mais rápida (mais consumo, menos latência)", ch))
            l.add(NodeSpec("ddr.max", "DDR max (B/s)", ddr.path + "/max_freq", "DDR / UFS", Kind.CHOICE, Risk.CAUTION, "", ch))
        }
        if (ufs != null) {
            val ch = ufs.freqs.map { it.toString() }
            l.add(NodeSpec("ufs.gov", "UFS governor", ufs.path + "/governor", "DDR / UFS", Kind.CHOICE, Risk.CAUTION, "", ufs.governors))
            l.add(NodeSpec("ufs.min", "UFS clock mín (Hz)", ufs.path + "/min_freq", "DDR / UFS", Kind.CHOICE, Risk.CAUTION,
                "Fixar no máximo evita queda de clock do armazenamento", ch))
            l.add(NodeSpec("ufs.max", "UFS clock máx (Hz)", ufs.path + "/max_freq", "DDR / UFS", Kind.CHOICE, Risk.CAUTION, "", ch))
        }
        fun vm(id: String, lbl: String, f: String, hint: String = "") =
            l.add(NodeSpec(id, lbl, "/proc/sys/vm/$f", "Memória", Kind.INT, Risk.SAFE, hint))
        vm("vm.swappiness", "swappiness", "swappiness", "0–200")
        vm("vm.vfs", "vfs_cache_pressure", "vfs_cache_pressure", "Padrão 100")
        vm("vm.dirty", "dirty_ratio", "dirty_ratio")
        vm("vm.dirty_bg", "dirty_background_ratio", "dirty_background_ratio")
        vm("vm.pagecluster", "page-cluster", "page-cluster", "0 costuma ser melhor com zRAM")
        vm("vm.wmark", "watermark_scale_factor", "watermark_scale_factor")
        for (b in blocks) {
            l.add(NodeSpec("io.$b.sched", "$b · scheduler", "/sys/block/$b/queue/scheduler", "I/O", Kind.CHOICE, Risk.CAUTION))
            l.add(NodeSpec("io.$b.ra", "$b · read_ahead_kb", "/sys/block/$b/queue/read_ahead_kb", "I/O", Kind.INT, Risk.SAFE))
        }
        l.add(NodeSpec("thermal.sports", "sports_mode", "$KTHERMAL/sports_mode", "Térmica", Kind.CHOICE, Risk.DANGER,
            "Modo esportivo do driver térmico MTK. Reduz a contenção térmica.", listOf("0", "1")))
        l.add(NodeSpec("fpsgo.enable", "FPSGO", "/sys/kernel/fpsgo/common/fpsgo_enable", "Térmica", Kind.CHOICE, Risk.CAUTION,
            "Controle de desempenho por frame do MediaTek", listOf("0", "1")))
        return l
    }

    // ------------------------------------------------------------------ estado ao vivo

    fun readLive(): Live {
        val i = info ?: detect()
        val paths = ArrayList<String>()
        for (pc in i.policies) {
            paths += "${pol(pc.id)}/scaling_cur_freq"
            paths += "${pol(pc.id)}/scaling_min_freq"
            paths += "${pol(pc.id)}/scaling_max_freq"
            paths += "${pol(pc.id)}/scaling_governor"
        }
        paths += listOf(GED_CUR, GED_UTIL, GED_UP, GED_BOOST, GPU_STATUS, GPU_LIMIT_TABLE, "$BATT/capacity", "$BATT/temp")
        val gpuDevfreqCurrentPath = i.gpuDevfreq?.path?.let { "$it/cur_freq" }
        if (gpuDevfreqCurrentPath != null) paths += gpuDevfreqCurrentPath
        val tz = TEMP_ZONES.mapNotNull { name -> i.zones[name]?.let { name to "/sys/class/thermal/thermal_zone$it/temp" } }
        paths += tz.map { it.second }
        val flagNames = listOf("is_cpu_limit", "is_gpu_limit", "is_apu_limit")
        paths += flagNames.map { "$KTHERMAL/$it" }
        val r = readMany(paths)

        val cpu = LinkedHashMap<Int, CpuLive>()
        for (pc in i.policies) {
            cpu[pc.id] = CpuLive(
                cur = Parse.intOf(r["${pol(pc.id)}/scaling_cur_freq"]) ?: 0L,
                min = Parse.intOf(r["${pol(pc.id)}/scaling_min_freq"]) ?: 0L,
                max = Parse.intOf(r["${pol(pc.id)}/scaling_max_freq"]) ?: 0L,
                gov = Parse.firstLine(r["${pol(pc.id)}/scaling_governor"])
            )
        }
        val cur = Parse.gedCurrent(r[GED_CUR])
        // O cur_freq desta ROM pode reportar 26 MHz fora da tabela real.
        // Só aceitamos o fallback Devfreq se corresponder exatamente a um OPP anunciado.
        val gpuDevfreqKHz = gpuDevfreqCurrentPath
            ?.let { Parse.intOf(r[it]) }
            ?.takeIf { rawHz -> i.gpuDevfreq?.freqs?.contains(rawHz) == true }
            ?.div(1000L)
        val status = r[GPU_STATUS]
        val ceil = Parse.ppmCeiling(status)
        val floor = Parse.ppmFloor(status)
        val temps = LinkedHashMap<String, Float>()
        for ((name, path) in tz) {
            val v = Parse.intOf(r[path]) ?: continue
            if (v > -100000) temps[name] = v / 1000f
        }
        val flags = LinkedHashMap<String, String>()
        for (f in flagNames) r["$KTHERMAL/$f"]?.let { flags[f] = Parse.firstLine(it) }
        val bt = Parse.intOf(r["$BATT/temp"])
        return Live(
            ts = System.currentTimeMillis(),
            cpu = cpu,
            gpuIdx = cur?.first,
            gpuKHz = cur?.second ?: gpuDevfreqKHz,
            gpuUtil = Parse.intOf(r[GED_UTIL])?.toInt(),
            gpuUpIdx = Parse.intOf(r[GED_UP])?.toInt(),
            gpuBoostIdx = Parse.intOf(r[GED_BOOST])?.toInt(),
            ppmCeilIdx = ceil?.first,
            ppmCeilLimiter = ceil?.second,
            ppmFloorIdx = floor?.first,
            ppmFloorLimiter = floor?.second,
            limits = Parse.limitTable(r[GPU_LIMIT_TABLE]),
            temps = temps,
            battPct = Parse.intOf(r["$BATT/capacity"])?.toInt(),
            battTempC = bt?.let { it / 10f },
            flags = flags
        )
    }

    fun readNodes(nodes: List<NodeSpec>): Map<String, String?> {
        val r = readMany(nodes.map { it.path })
        return nodes.associate { n ->
            val raw = r[n.path]
            n.id to when {
                raw == null -> null
                n.id.endsWith(".sched") -> Parse.scheduler(raw).first
                n.id.startsWith("gpu.") && n.path.startsWith(GED) -> Parse.intOf(raw)?.toString() ?: Parse.firstLine(raw)
                else -> Parse.firstLine(raw)
            }
        }
    }

    fun readExtras(): Map<String, String> {
        val keys = listOf("boost", "balance_mode", "temp_state", "foreground_app_name", "cloud_game", "video_mode", "screen_state")
        val r = readMany(keys.map { "/sys/class/thermal/thermal_message/$it" })
        val m = LinkedHashMap<String, String>()
        for (k in keys) r["/sys/class/thermal/thermal_message/$k"]?.let { m[k] = Parse.firstLine(it) }
        val ct = readCooling()
        ct.forEach { (k, v) -> m[k] = v }
        return m
    }

    private fun readCooling(): Map<String, String> {
        val out = sh.exec(
            "for c in " + q(p("/sys/class/thermal")) + "/cooling_device*; do echo \"\$(cat \"\$c/type\" 2>/dev/null)|\$(cat \"\$c/cur_state\" 2>/dev/null)|\$(cat \"\$c/max_state\" 2>/dev/null)\"; done"
        ).out
        val m = LinkedHashMap<String, String>()
        out.lines().forEach { l ->
            val a = l.split("|")
            if (a.size == 3 && a[0].isNotBlank() && !a[0].startsWith("cpu-pause")) m["cool:" + a[0]] = a[1] + "/" + a[2]
        }
        return m
    }

    fun readDisplay(): String {
        val r = sh.exec("dumpsys display 2>/dev/null | grep -m1 mActiveRenderFrameRate").out.trim()
        val v = Regex("=\\s*([0-9.]+)").find(r)?.groupValues?.get(1)
        val peak = sh.exec("settings get system peak_refresh_rate 2>/dev/null").out.trim()
        val min = sh.exec("settings get system min_refresh_rate 2>/dev/null").out.trim()
        return "ativo=${v ?: "?"} Hz · min=$min · peak=$peak"
    }

    // ------------------------------------------------------------------ escrita

    private fun execWrite(path: String, value: String, lock: Boolean): ShellResult {
        var cmd = "[ -e ${q(p(path))} ] || exit 3; printf '%s\\n' ${q(value)} > ${q(p(path))}"
        if (lock) cmd += "; rc=\$?; chmod 0444 ${q(p(path))} 2>/dev/null; exit \$rc"
        return sh.exec(cmd)
    }

    /** Escreve [value] em [path] e confere o valor lido de volta. */
    fun write(path: String, value: String, lock: Boolean = false, verify: Boolean = true): WriteResult {
        if (lock && store.get("mode:$path") == null) {
            val m = sh.exec("stat -c %a ${q(p(path))} 2>/dev/null").out.trim()
            if (m.isNotEmpty()) store.put("mode:$path", m)
        }
        val res = execWrite(path, value, lock)
        if (res.code == 3) return WriteResult(path, value, "", false, "nó inexistente")
        val got = if (verify) (readOne(path) ?: "") else ""
        val ok = res.ok && (!verify || Parse.same(value, got))
        val note = res.err.lineSequence().firstOrNull { it.isNotBlank() }?.take(90) ?: ""
        val shown = if (verify) Parse.firstLine(got) else if (res.ok) value else ""
        return WriteResult(path, value, shown, ok, note).also { log(it.line()) }
    }

    fun writeNode(spec: NodeSpec, value: String): WriteResult = when (spec.id) {
        "gpu.dev.max" -> value.toLongOrNull()?.let { setGpuDevfreqLimits(maxHz = it) }
            ?: WriteResult(spec.path, value, "", false, "frequência inválida")
        "gpu.dev.min" -> value.toLongOrNull()?.let { setGpuDevfreqLimits(minHz = it) }
            ?: WriteResult(spec.path, value, "", false, "frequência inválida")
        "gpu.dev.gov" -> setGpuDevfreqGovernor(value)
        else -> write(spec.path, value)
    }

    // --- CPU

    fun resolveMax(pc: CpuPolicy, v: Long): Long =
        if (v <= 0) pc.maxHw else (pc.freqs.lastOrNull { it <= v } ?: pc.freqs.firstOrNull() ?: pc.maxHw)

    fun resolveMin(pc: CpuPolicy, v: Long): Long =
        if (v <= 0) pc.minHw else (pc.freqs.firstOrNull { it >= v } ?: pc.freqs.lastOrNull() ?: pc.maxHw)

    fun lockEnabled(): Boolean = store.get("lock") == "1"

    fun setCpu(policy: Int, minK: Long?, maxK: Long?, gov: String?): List<WriteResult> {
        val base = pol(policy)
        val lock = lockEnabled()
        val res = ArrayList<WriteResult>()
        if (gov != null) res += write("$base/scaling_governor", gov)
        if (minK == null && maxK == null) return res
        val cur = readMany(listOf("$base/scaling_min_freq", "$base/scaling_max_freq"))
        val curMin = Parse.intOf(cur["$base/scaling_min_freq"]) ?: 0L
        val curMax = Parse.intOf(cur["$base/scaling_max_freq"]) ?: Long.MAX_VALUE
        var newMin = minK ?: curMin
        var newMax = maxK ?: curMax
        if (maxK != null && minK == null && newMin > newMax) newMin = newMax
        // piso acima do teto atual: o kernel rejeita (EINVAL); sobe o teto junto
        if (minK != null && maxK == null && newMin > newMax) newMax = newMin
        val writeMax = maxK != null || newMax != curMax
        val minP = "$base/scaling_min_freq"
        val maxP = "$base/scaling_max_freq"
        if (newMax < curMin) {
            if (minK != null || newMin != curMin) res += write(minP, newMin.toString(), lock)
            if (writeMax) res += write(maxP, newMax.toString(), lock)
        } else {
            if (writeMax) res += write(maxP, newMax.toString(), lock)
            if (minK != null || newMin != curMin) res += write(minP, newMin.toString(), lock)
        }
        return res
    }

    // --- GPU

    private fun gpuIdxForCeiling(kHz: Long): Int {
        val o = info?.gpuOpps ?: return 0
        if (kHz <= 0 || o.isEmpty() || kHz >= o.first().kHz) return 0
        return o.firstOrNull { it.kHz <= kHz }?.idx ?: o.last().idx
    }

    private fun gpuIdxForFloor(kHz: Long): Int {
        val o = info?.gpuOpps ?: return 0
        if (o.isEmpty()) return 0
        if (kHz <= o.last().kHz) return o.last().idx
        return o.lastOrNull { it.kHz >= kHz }?.idx ?: 0
    }

    /** kHz = 0 (ou ≥ máximo) remove o teto imposto por este gerenciador. */
    fun setGpuCeilingKHz(kHz: Long): WriteResult = write(GED_UP, gpuIdxForCeiling(kHz).toString())

    /** kHz = 0 (ou ≤ mínimo) remove o piso. */
    fun setGpuFloorKHz(kHz: Long): WriteResult = write(GED_BOOST, gpuIdxForFloor(kHz).toString())

    /**
     * Ajusta os limites Devfreq em Hz. Se um novo teto ficar abaixo do piso atual,
     * reduz o piso junto; se um novo piso ultrapassar o teto, eleva o teto junto.
     * A ordem das escritas evita a rejeição por min > max. Em falha parcial,
     * tenta restaurar o primeiro nó alterado.
     */
    fun setGpuDevfreqLimits(minHz: Long? = null, maxHz: Long? = null): WriteResult {
        val dev = info?.gpuDevfreq
            ?: return WriteResult("gpu-devfreq", "", "", false, "Devfreq da Mali não detectado")
        if (minHz == null && maxHz == null)
            return WriteResult(dev.path, "", "", false, "nenhum limite solicitado")

        val available = dev.freqs.toSet()
        if (minHz != null && minHz !in available)
            return WriteResult("${dev.path}/min_freq", minHz.toString(), "", false, "valor fora de available_frequencies")
        if (maxHz != null && maxHz !in available)
            return WriteResult("${dev.path}/max_freq", maxHz.toString(), "", false, "valor fora de available_frequencies")

        val minPath = "${dev.path}/min_freq"
        val maxPath = "${dev.path}/max_freq"
        val oldMin = readOne(minPath)?.trim()?.toLongOrNull()
            ?: return WriteResult(minPath, minHz?.toString() ?: "", "", false, "não foi possível ler o limite mínimo")
        val oldMax = readOne(maxPath)?.trim()?.toLongOrNull()
            ?: return WriteResult(maxPath, maxHz?.toString() ?: "", "", false, "não foi possível ler o limite máximo")

        var nextMin = minHz ?: oldMin
        var nextMax = maxHz ?: oldMax
        if (maxHz != null && nextMax < nextMin) nextMin = nextMax
        if (minHz != null && nextMin > nextMax) nextMax = nextMin
        if (nextMin > nextMax)
            return WriteResult(dev.path, "$nextMin..$nextMax", "$oldMin..$oldMax", false, "min_freq não pode superar max_freq")

        val changes = when {
            nextMax < oldMin -> listOf(minPath to nextMin, maxPath to nextMax)
            nextMin > oldMax -> listOf(maxPath to nextMax, minPath to nextMin)
            else -> buildList {
                if (nextMax != oldMax) add(maxPath to nextMax)
                if (nextMin != oldMin) add(minPath to nextMin)
            }
        }
        if (changes.isEmpty())
            return WriteResult(dev.path, "$nextMin..$nextMax", "$oldMin..$oldMax", true, "limites já estavam aplicados")

        var firstPath: String? = null
        for ((path, value) in changes) {
            val result = write(path, value.toString())
            if (!result.ok) {
                if (firstPath != null) {
                    val original = if (firstPath == minPath) oldMin else oldMax
                    write(firstPath!!, original.toString())
                }
                return WriteResult(dev.path, "$nextMin..$nextMax", "min=${readOne(minPath)} max=${readOne(maxPath)}", false,
                    "falha em ${path.substringAfterLast('/')}: ${result.note.ifBlank { "kernel não confirmou o valor" }}")
                    .also { log(it.line()) }
            }
            if (firstPath == null) firstPath = path
        }
        return WriteResult(dev.path, "$nextMin..$nextMax", "min=${readOne(minPath)} max=${readOne(maxPath)}", true)
            .also { log(it.line()) }
    }

    fun setGpuDevfreqGovernor(governor: String): WriteResult {
        val dev = info?.gpuDevfreq
            ?: return WriteResult("gpu-devfreq/governor", governor, "", false, "Devfreq da Mali não detectado")
        if (governor !in dev.governors)
            return WriteResult("${dev.path}/governor", governor, "", false, "governor não anunciado pelo driver")
        return write("${dev.path}/governor", governor)
    }

    fun fixGpuOpp(idx: Int?): WriteResult {
        val v = (idx ?: -1).toString()
        val w = write(GPU_FIX, v, verify = false)
        val cur = Parse.gpuOppIndex(readOne(GPU_STATUS))
        val ok = w.ok && (idx == null || cur == idx)
        return WriteResult(GPU_FIX, v, "OPP atual=${cur ?: "?"}", ok, if (!w.ok) w.note else if (idx != null && cur != idx) "GPU ociosa/limitada? confira o PPM" else "")
            .also { log(it.line()) }
    }

    fun setLimit(id: Int, enabled: Boolean): WriteResult {
        val v = if (enabled) "$id 1 1" else "$id 0 0"
        val w = write(GPU_LIMIT_TABLE, v, verify = false)
        val row = Parse.limitTable(readOne(GPU_LIMIT_TABLE)).firstOrNull { it.id == id }
        val ok = w.ok && row != null && row.cEnable == enabled
        if (ok) {
            val set = (store.get("disabled_limits") ?: "").split(",").filter { it.isNotBlank() }.toMutableSet()
            if (enabled) set.remove("$id") else set.add("$id")
            store.put("disabled_limits", set.joinToString(","))
        }
        return WriteResult(GPU_LIMIT_TABLE, v, row?.let { "${it.name}: c=${if (it.cEnable) 1 else 0} f=${if (it.fEnable) 1 else 0}" } ?: "?", ok,
            if (!w.ok) w.note else if (!ok) "o driver ignorou o comando (formato id c f)" else "").also { log(it.line()) }
    }

    // --- Display / serviços

    fun setRefresh(hz: Int?): WriteResult {
        if (hz == null) {
            sh.exec("settings delete system min_refresh_rate; settings delete system peak_refresh_rate; settings delete system user_refresh_rate")
            return WriteResult("display", "auto", "auto", true).also { log(it.line()) }
        }
        val f = "$hz.0"
        val r = sh.exec("settings put system min_refresh_rate $f; settings put system peak_refresh_rate $f; settings put system user_refresh_rate $hz")
        val got = sh.exec("settings get system peak_refresh_rate").out.trim()
        return WriteResult("display", f, got, r.ok && got.startsWith("$hz"), "HyperOS pode sobrescrever").also { log(it.line()) }
    }

    fun pauseThermal(pause: Boolean): List<WriteResult> {
        val out = listOf("mi_thermald", "thermald").map { svc ->
            val r = sh.exec((if (pause) "stop " else "start ") + svc)
            WriteResult("svc:$svc", if (pause) "stop" else "start", "code=${r.code}", r.ok, r.err.take(80))
                .also { log(it.line()) }
        }
        store.put("thermal_paused", if (pause) "1" else "0")
        return out
    }

    // ------------------------------------------------------------------ perfis

    fun builtins(): List<Profile> {
        val i = info ?: return emptyList()
        fun base(maxPct: List<Int>, minK: List<Long>, gpuMax: Long, gpuMin: Long, hz: Int): LinkedHashMap<String, String> {
            val m = LinkedHashMap<String, String>()
            i.policies.forEachIndexed { idx, pc ->
                val gov = if ("sugov_ext" in pc.governors) "sugov_ext" else if ("schedutil" in pc.governors) "schedutil" else pc.governors.firstOrNull() ?: ""
                if (gov.isNotEmpty()) m["cpu${pc.id}.gov"] = gov
                val pct = maxPct.getOrElse(idx) { maxPct.last() }
                m["cpu${pc.id}.max"] = if (pct >= 100) "0" else (pc.maxHw * pct / 100).toString()
                m["cpu${pc.id}.min"] = minK.getOrElse(idx) { 0L }.toString()
            }
            m["gpu.max_khz"] = gpuMax.toString()
            m["gpu.min_khz"] = gpuMin.toString()
            m["display.hz"] = hz.toString()
            return m
        }
        val eco = base(listOf(55, 45, 40), listOf(0, 0, 0), 650000, 0, 60)
        val bal = base(listOf(100), listOf(0, 0, 0), 0, 0, 120)
        val perf = base(listOf(100), listOf(1000000, 1200000, 1200000), 0, 520000, 120)
        val max = base(listOf(100), listOf(1300000, 1500000, 1500000), 0, 702000, 120)
        max["gpu.limit.THERMAL_AP"] = "off"
        return listOf(
            Profile("Economia", eco, true),
            Profile("Equilibrado", bal, true),
            Profile("Desempenho", perf, true),
            Profile("Máximo ⚠", max, true)
        )
    }

    fun allProfiles(): List<Profile> = builtins() + Profile.decode(store.get("profiles"))

    fun saveProfile(p: Profile) {
        val mine = Profile.decode(store.get("profiles")).filter { it.name != p.name } + p.copy(builtin = false)
        store.put("profiles", Profile.encode(mine))
    }

    fun deleteProfile(name: String) {
        val mine = Profile.decode(store.get("profiles")).filter { it.name != name }
        store.put("profiles", Profile.encode(mine))
    }

    /** Monta um perfil com o estado atual do aparelho. */
    fun captureCurrent(name: String): Profile {
        val i = info ?: detect()
        val live = readLive()
        val m = LinkedHashMap<String, String>()
        for (pc in i.policies) {
            val l = live.cpu[pc.id] ?: continue
            if (l.gov.isNotEmpty()) m["cpu${pc.id}.gov"] = l.gov
            m["cpu${pc.id}.max"] = if (l.max >= pc.maxHw) "0" else l.max.toString()
            m["cpu${pc.id}.min"] = if (l.min <= pc.minHw) "0" else l.min.toString()
        }
        val up = live.gpuUpIdx
        if (up != null) m["gpu.max_khz"] = if (up <= 0) "0" else (i.gpuOpps.firstOrNull { it.idx == up }?.kHz ?: 0L).toString()
        val bo = live.gpuBoostIdx
        if (bo != null) m["gpu.min_khz"] = if (bo >= i.gpuOpps.size - 1) "0" else (i.gpuOpps.firstOrNull { it.idx == bo }?.kHz ?: 0L).toString()
        return Profile(name, m, false)
    }

    fun applyProfile(pr: Profile): List<WriteResult> {
        val i = info ?: detect()
        val res = ArrayList<WriteResult>()
        log("── Perfil '${pr.name}' ──")
        for (pc in i.policies) {
            val g = pr.values["cpu${pc.id}.gov"]?.takeIf { it in pc.governors }
            val mx = pr.values["cpu${pc.id}.max"]?.toLongOrNull()?.let { resolveMax(pc, it) }
            val mn = pr.values["cpu${pc.id}.min"]?.toLongOrNull()?.let { resolveMin(pc, it) }
            if (g != null || mx != null || mn != null) res += setCpu(pc.id, mn, mx, g)
        }
        pr.values["gpu.max_khz"]?.toLongOrNull()?.let { res += setGpuCeilingKHz(it) }
        pr.values["gpu.min_khz"]?.toLongOrNull()?.let { res += setGpuFloorKHz(it) }
        for ((k, v) in pr.values) {
            when {
                k.startsWith("gpu.limit.") -> {
                    val name = k.removePrefix("gpu.limit.")
                    val lim = Parse.limitTable(readOne(GPU_LIMIT_TABLE)).firstOrNull { it.name == name }
                    if (lim != null) res += setLimit(lim.id, v != "off")
                }
                k.startsWith("node:") -> i.node(k.removePrefix("node:"))?.let { res += writeNode(it, v) }
                k == "display.hz" -> res += setRefresh(v.toIntOrNull())
                k == "thermal.pause" -> res += pauseThermal(v == "1")
            }
        }
        store.put("active", pr.name)
        return res
    }

    fun applyByName(name: String): List<WriteResult> {
        val pr = allProfiles().firstOrNull { it.name == name } ?: return emptyList()
        return applyProfile(pr)
    }

    /** Valores esperados (caminho -> valor) do perfil ativo, usados para detectar "deriva". */
    private fun expected(i: SocInfo, pr: Profile): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (pc in i.policies) {
            pr.values["cpu${pc.id}.gov"]?.takeIf { it in pc.governors }?.let { m["${pol(pc.id)}/scaling_governor"] = it }
            pr.values["cpu${pc.id}.max"]?.toLongOrNull()?.let { m["${pol(pc.id)}/scaling_max_freq"] = resolveMax(pc, it).toString() }
            pr.values["cpu${pc.id}.min"]?.toLongOrNull()?.let { m["${pol(pc.id)}/scaling_min_freq"] = resolveMin(pc, it).toString() }
        }
        pr.values["gpu.max_khz"]?.toLongOrNull()?.let { m[GED_UP] = gpuIdxForCeiling(it).toString() }
        pr.values["gpu.min_khz"]?.toLongOrNull()?.let { m[GED_BOOST] = gpuIdxForFloor(it).toString() }
        return m
    }

    /** Reaplica o perfil ativo se algo do sistema (thermald, perf HAL…) mudou os nós. */
    fun enforce(): Boolean {
        val i = info ?: return false
        val name = store.get("active") ?: return false
        val pr = allProfiles().firstOrNull { it.name == name } ?: return false
        val exp = expected(i, pr)
        val cur = readMany(exp.keys.toList())
        val bad = exp.filter { (k, v) -> cur[k]?.let { !Parse.same(v, it) } ?: false }
        if (bad.isEmpty()) return false
        log("Deriva em ${bad.size} nó(s): " + bad.keys.joinToString { it.substringAfterLast('/') } + " → reaplicando '${pr.name}'")
        applyProfile(pr)
        return true
    }

    // ------------------------------------------------------------------ snapshot

    private fun snapPaths(i: SocInfo): List<String> {
        val l = ArrayList<String>()
        for (pc in i.policies) l += listOf("scaling_governor", "scaling_min_freq", "scaling_max_freq").map { "${pol(pc.id)}/$it" }
        l += i.nodes.map { it.path }
        l += listOf(GED_UP, GED_BOOST)
        return l.distinct()
    }

    fun hasSnapshot(): Boolean = store.get("snap:done") == "1"

    fun captureSnapshot(): Int {
        val i = info ?: detect()
        store.keys().filter { it.startsWith("snap:") }.forEach { store.remove(it) }
        val r = readMany(snapPaths(i))
        var n = 0
        for ((path, raw) in r) {
            if (raw == null) continue
            val v = if (path.endsWith("/scheduler")) Parse.scheduler(raw).first else Parse.firstLine(raw)
            val vv = if (path.startsWith(GED)) (Parse.intOf(raw)?.toString() ?: v) else v
            store.put("snap:$path", vv)
            n++
        }
        store.put("snap:done", "1")
        log("Snapshot salvo ($n nós)")
        return n
    }

    fun restoreSnapshot(): List<WriteResult> {
        val i = info ?: detect()
        val res = ArrayList<WriteResult>()
        for (pc in i.policies) {
            val g = store.get("snap:${pol(pc.id)}/scaling_governor")
            val mn = store.get("snap:${pol(pc.id)}/scaling_min_freq")?.toLongOrNull()
            val mx = store.get("snap:${pol(pc.id)}/scaling_max_freq")?.toLongOrNull()
            res += setCpu(pc.id, mn, mx, g)
        }
        for (path in snapPaths(i)) {
            if (path.contains("/cpufreq/policy")) continue
            val v = store.get("snap:$path") ?: continue
            res += write(path, v)
        }
        restoreModes()
        store.remove("active")
        return res
    }

    fun restoreModes() {
        store.keys().filter { it.startsWith("mode:") }.forEach { k ->
            val path = k.removePrefix("mode:")
            sh.exec("chmod ${store.get(k)} ${q(p(path))} 2>/dev/null")
            store.remove(k)
        }
    }

    // ------------------------------------------------------------------ guarda térmica

    data class GuardCfg(val enabled: Boolean, val soc: Float, val skin: Float, val batt: Float)

    fun guardCfg() = GuardCfg(
        enabled = store.get("guard") != "0",
        soc = store.get("g_soc")?.toFloatOrNull() ?: 80f,
        skin = store.get("g_skin")?.toFloatOrNull() ?: 44f,
        batt = store.get("g_batt")?.toFloatOrNull() ?: 42f
    )

    fun guardReason(live: Live, g: GuardCfg = guardCfg()): String? {
        if (!g.enabled) return null
        live.temps["soc_max"]?.let { if (it >= g.soc) return "SoC ${"%.1f".format(it)}°C ≥ ${g.soc.toInt()}°C" }
        live.temps["quiet_therm"]?.let { if (it >= g.skin) return "Superfície ${"%.1f".format(it)}°C ≥ ${g.skin.toInt()}°C" }
        live.battTempC?.let { if (it >= g.batt) return "Bateria ${"%.1f".format(it)}°C ≥ ${g.batt.toInt()}°C" }
        return null
    }

    /** Há proteções térmicas desligadas por este app (serviços pausados / limitadores do driver)? */
    fun isRisky(): Boolean =
        store.get("thermal_paused") == "1" || !(store.get("disabled_limits") ?: "").isBlank()

    /**
     * Motivo para entrar em modo seguro, ou null. Com proteções enfraquecidas vale o limite configurado;
     * com tudo normal só dispara bem acima dele (o hardware já faz o seu throttling).
     */
    fun guardTrip(live: Live): String? {
        val g = guardCfg()
        val reason = guardReason(live, g) ?: return null
        if (isRisky()) return reason
        val hot = (live.temps["soc_max"] ?: 0f) >= g.soc + 10f || (live.battTempC ?: 0f) >= g.batt + 3f
        return if (hot) reason else null
    }

    /** Devolve o aparelho a um estado térmico normal e aplica o perfil Equilibrado. */
    fun safeMode(reason: String): List<WriteResult> {
        log("⚠ MODO SEGURO: $reason")
        val res = ArrayList<WriteResult>()
        if (store.get("thermal_paused") == "1") res += pauseThermal(false)
        (store.get("disabled_limits") ?: "").split(",").mapNotNull { it.toIntOrNull() }.forEach { res += setLimit(it, true) }
        res += applyByName("Equilibrado")
        return res
    }

    /**
     * Diagnóstico técnico somente de leitura. Coleta dados reais disponíveis no aparelho;
     * cada seção registra código de saída e erros para diferenciar ausência de permissão
     * de nó inexistente ou falha do comando.
     */

    /**
     * Diagnóstico técnico somente de leitura. Registra saída, stderr e exit code
     * para diferenciar nó ausente, falta de permissão e falha de comando.
     */
    fun diagnosticReport(appLog: List<String>, appState: String): String {
        val out = StringBuilder()
        fun line(s: String = "") { out.append(s).append('\n') }
        fun section(title: String, command: String) {
            line()
            line("========== " + title + " ==========")
            try {
                val r = sh.exec(command)
                line("[exit_code=" + r.code + "]")
                if (r.out.isNotBlank()) line(r.out.take(30000))
                if (r.err.isNotBlank()) line("[stderr] " + r.err.take(5000))
                if (r.out.isBlank() && r.err.isBlank()) line("(sem saída)")
            } catch (t: Throwable) {
                line("[EXCEPTION] " + t.javaClass.simpleName + ": " + t.message)
            }
        }

        line("KLEE KERNEL MANAGER - DIAGNÓSTICO COMPLETO")
        line("Gerado: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date()))
        line("Formato: texto simples UTF-8")
        line("Aviso: coleta somente leitura; não altera clocks, governors, tensão ou proteções.")
        line()
        line("========== ESTADO DO APLICATIVO ==========")
        line(appState)
        line()
        line("========== LOG INTERNO DO KLEE ==========")
        if (appLog.isEmpty()) line("(sem eventos registrados)") else appLog.forEach { line(it) }

        section("IDENTIFICAÇÃO DO SISTEMA", """
            echo '[getprop - propriedades não sensíveis]'
            getprop | grep -E '\[(ro\.product\.(model|device|marketname|brand)|ro\.soc\.model|ro\.board\.platform|ro\.build\.(version\.(release|sdk)|type|tags|fingerprint)|ro\.boot\.hardware)\]'
            echo '[kernel]'; uname -a
            echo '[uptime]'; cat /proc/uptime
            echo '[root identity]'; id
            echo '[SELinux]'; getenforce 2>/dev/null
            echo '[boot reason]'; getprop sys.boot.reason
        """.trimIndent())

        section("CPU - POLICIES, FREQUÊNCIAS E GOVERNORS", """
            for p in /sys/devices/system/cpu/cpufreq/policy*; do
              [ -d "${'$'}p" ] || continue
              echo "----- ${'$'}p -----"
              for f in related_cpus affected_cpus scaling_driver scaling_governor scaling_available_governors scaling_available_frequencies scaling_min_freq scaling_max_freq scaling_cur_freq cpuinfo_min_freq cpuinfo_max_freq cpuinfo_transition_latency; do
                [ -r "${'$'}p/${'$'}f" ] && { printf '%s=' "${'$'}f"; cat "${'$'}p/${'$'}f" 2>&1; }
              done
              stat -c 'permissions=%A (%a) owner=%U:%G' "${'$'}p/scaling_governor" "${'$'}p/scaling_min_freq" "${'$'}p/scaling_max_freq" 2>&1
            done
            echo '[cpu online]'; cat /sys/devices/system/cpu/online 2>&1
            echo '[cpu present]'; cat /sys/devices/system/cpu/present 2>&1
            echo '[cpuinfo summary]'; grep -E '^(processor|model name|CPU implementer|CPU part|BogoMIPS)' /proc/cpuinfo 2>&1
        """.replace("§","§").trimIndent())

        section("GPU MALI / DEVFREQ / GED / GPUFREQ", """
            for p in /sys/class/devfreq/*mali* /sys/devices/platform/soc/13000000.mali /sys/devices/platform/soc/13f50000.gpufreq /sys/devices/platform/soc/13c00000.gpueb /sys/kernel/ged /sys/kernel/ged/hal; do
              [ -e "${'$'}p" ] || continue
              echo "----- ${'$'}p -----"
              if [ -d "${'$'}p" ]; then
                for f in name governor available_governors available_frequencies min_freq max_freq cur_freq target_freq polling_interval trans_stat; do
                  [ -r "${'$'}p/${'$'}f" ] && { printf '%s=' "${'$'}f"; head -c 12000 "${'$'}p/${'$'}f" 2>&1; echo; }
                done
              else
                ls -ld "${'$'}p" 2>&1
              fi
            done
            for f in /proc/gpufreqv2/gpufreq_status /proc/gpufreqv2/gpu_working_opp_table /proc/gpufreqv2/limit_table /proc/gpufreqv2/fix_target_opp_index /sys/kernel/ged/hal/current_freqency /sys/kernel/ged/hal/gpu_utilization /sys/kernel/ged/hal/custom_upbound_gpu_freq /sys/kernel/ged/hal/custom_boost_gpu_freq /sys/kernel/ged/hal/gpu_boost_level; do
              [ -r "${'$'}f" ] && { echo "----- ${'$'}f -----"; cat "${'$'}f" 2>&1; }
            done
            echo '[gpuinfo]'; cat /sys/class/misc/mali0/device/gpuinfo 2>&1
            echo '[gpu modules]'; grep -iE 'mali|gpu|ged|gpufreq|gpueb' /proc/modules 2>&1
        """.trimIndent())

        section("DEVFREQ - TODOS OS DOMÍNIOS", """
            for p in /sys/class/devfreq/*; do
              [ -d "${'$'}p" ] || continue
              echo "----- ${'$'}p -----"
              for f in name governor available_governors available_frequencies min_freq max_freq cur_freq target_freq polling_interval; do
                [ -r "${'$'}p/${'$'}f" ] && { printf '%s=' "${'$'}f"; head -c 10000 "${'$'}p/${'$'}f" 2>&1; echo; }
              done
            done
        """.trimIndent())

        val paths = info?.nodes.orEmpty().map { it.path }.distinct()
        val nodeCommand = StringBuilder("for f in ")
        paths.forEach { nodeCommand.append(q(p(it))).append(' ') }
        nodeCommand.append("; do echo \"----- \\$f -----\"; if [ -e \"\\$f\" ]; then stat -c 'permissions=%A (%a) owner=%U:%G' \"\\$f\" 2>&1; head -c 4000 \"\\$f\" 2>&1; echo; else echo 'MISSING'; fi; done")
        section("NÓS DETECTADOS PELO KLEE - VALOR E PERMISSÕES",
            if (paths.isEmpty()) "echo 'Nenhum nó foi detectado pelo KLEE'" else nodeCommand.toString())

        section("TÉRMICA - ZONAS E COOLING DEVICES", """
            for z in /sys/class/thermal/thermal_zone*; do
              [ -d "${'$'}z" ] || continue
              echo "----- ${'$'}z -----"
              for f in type temp mode policy available_policies; do
                [ -r "${'$'}z/${'$'}f" ] && { printf '%s=' "${'$'}f"; cat "${'$'}z/${'$'}f" 2>&1; }
              done
            done
            for c in /sys/class/thermal/cooling_device*; do
              [ -d "${'$'}c" ] || continue
              echo "----- ${'$'}c -----"
              for f in type cur_state max_state; do
                [ -r "${'$'}c/${'$'}f" ] && { printf '%s=' "${'$'}f"; cat "${'$'}c/${'$'}f" 2>&1; }
              done
            done
            for f in /sys/kernel/thermal/* /sys/class/thermal/thermal_message/*; do
              [ -f "${'$'}f" ] && { echo "----- ${'$'}f -----"; head -c 3000 "${'$'}f" 2>&1; echo; }
            done
        """.trimIndent())

        section("BATERIA E ENERGIA", """
            for p in /sys/class/power_supply/*; do
              [ -d "${'$'}p" ] || continue
              echo "----- ${'$'}p -----"
              for f in type status health capacity voltage_now current_now power_now temp technology charge_counter; do
                [ -r "${'$'}p/${'$'}f" ] && { printf '%s=' "${'$'}f"; cat "${'$'}p/${'$'}f" 2>&1; }
              done
            done
        """.trimIndent())

        section("MEMÓRIA, ZRAM E ARMAZENAMENTO", """
            echo '[meminfo]'; cat /proc/meminfo
            echo '[swaps]'; cat /proc/swaps
            echo '[zram]'; for p in /sys/block/zram*; do [ -d "${'$'}p" ] || continue; echo "----- ${'$'}p -----"; for f in disksize mm_stat comp_algorithm max_comp_streams; do [ -r "${'$'}p/${'$'}f" ] && { printf '%s=' "${'$'}f"; cat "${'$'}p/${'$'}f" 2>&1; }; done; done
            echo '[block devices]'; lsblk 2>&1
            echo '[mounts relevantes]'; mount | grep -E 'sysfs|proc|debugfs|tracefs|cgroup'
        """.trimIndent())

        section("SCHEDULER, INTERRUPÇÕES E LIMITES DO KERNEL", """
            for f in /proc/sys/kernel/sched_pelt_multiplier /proc/sys/kernel/sched_energy_aware /proc/interrupts /proc/pressure/cpu /proc/pressure/memory /proc/pressure/io; do
              [ -r "${'$'}f" ] && { echo "----- ${'$'}f -----"; head -c 12000 "${'$'}f" 2>&1; echo; }
            done
            echo '[cmdline filtrada]'; tr ' ' '\\n' < /proc/cmdline | grep -viE 'serial|imei|androidboot\.serial'
        """.trimIndent())

        section("SERVIÇOS RELACIONADOS", """
            echo '[serviços térmicos/performance]'; getprop | grep -iE 'init\.svc\..*(thermal|perf|power|ged|gpu|apu)'
            echo '[processos relacionados]'; ps -A 2>/dev/null | grep -iE 'thermal|perf|power|ged|gpu|klee' | grep -v grep
        """.trimIndent())

        section("LOG DO KERNEL - EVENTOS RELACIONADOS", """
            dmesg 2>&1 | grep -iE 'mtk|mt6899|mali|gpu|ged|gpufreq|gpueb|devfreq|cpufreq|thermal|cooling|permission denied|avc: denied|klee' | tail -n 700
        """.trimIndent())

        section("LOGCAT - EVENTOS RELACIONADOS", """
            logcat -b main -b system -b crash -d -t 1800 -v threadtime 2>&1 | grep -iE 'mt6899|klee|mtk|mali|gpu|ged|gpufreq|gpueb|devfreq|cpufreq|thermal|avc: denied|fatal exception|permission denied|write failed' | tail -n 900
        """.trimIndent())

        line()
        line("========== FIM DO DIAGNÓSTICO ==========")
        line("Total de caracteres: " + out.length)
        return out.toString()
    }


}
