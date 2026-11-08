package dev.leonardo.mt6899.core

/**
 * Núcleo do gerenciador: lê/escreve os nós reais do MT6899 (POCO X8 Pro) via [Shell] root.
 * Não depende de Android; [prefix] existe apenas para testes contra uma árvore sysfs falsa.
 */
class Engine(
    private val sh: Shell,
    val store: Store,
    private val prefix: String = "",
    // antes do logger: quem chama com lambda final (Engine(sh, store) { ... }) continua ligando ao logger
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val logger: (String) -> Unit = {}
) {
    @Volatile
    var info: SocInfo? = null
        private set

    private fun log(s: String) = logger(s)
    private fun p(path: String) = prefix + path
    private fun pol(id: Int) = "/sys/devices/system/cpu/cpufreq/policy$id"

    // Controle de "briga" com o sistema em enforce(): no máximo N reaplicações por janela.
    private val driftTimes = ArrayDeque<Long>()
    private var enforceMutedUntil = 0L

    companion object {
        const val P_ECO = "Economia"
        const val P_BAL = "Equilibrado"
        const val P_PERF = "Desempenho"
        const val P_MAX = "Máximo ⚠"
        private val BUILTIN_NAMES = listOf(P_ECO, P_BAL, P_PERF, P_MAX)

        private const val ENFORCE_WINDOW_MS = 60_000L
        private const val ENFORCE_MAX_PER_WINDOW = 4
        private val MODE_RE = Regex("^[0-7]{3,4}$")
        private val THERMAL_SERVICES = listOf("mi_thermald", "thermald")
        private const val BIG_READ = 65_536

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

    /**
     * Lê vários arquivos num único shell. Valor null = arquivo inexistente.
     * [maxBytes] limita cada arquivo; tabelas grandes (OPPs da GPU) precisam de mais que o padrão,
     * senão a leitura é cortada no meio sem aviso.
     */
    fun readMany(paths: List<String>, maxBytes: Int = 4000): Map<String, String?> {
        if (paths.isEmpty()) return emptyMap()
        val sb = StringBuilder("for p in ")
        paths.forEach { sb.append(q(p(it))).append(' ') }
        sb.append("; do if [ -e \"\$p\" ]; then echo \"@@E\$p\"; head -c $maxBytes \"\$p\" 2>/dev/null; echo; ")
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

    /** Várias propriedades numa única chamada de shell (antes eram cinco chamadas em sequência). */
    private fun props(vararg keys: String): Map<String, String> {
        val sb = StringBuilder("for k in ")
        keys.forEach { sb.append(it).append(' ') }
        sb.append("; do echo \"\$k=\$(getprop \$k)\"; done")
        val m = LinkedHashMap<String, String>()
        sh.exec(sb.toString()).out.lines().forEach { l ->
            val i = l.indexOf('=')
            if (i > 0) m[l.substring(0, i)] = l.substring(i + 1).trim()
        }
        return m
    }

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
        val g = readMany(listOf(GPU_OPP_TABLE, "/sys/class/misc/mali0/device/gpuinfo"), BIG_READ)
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

        val pr = props("ro.product.marketname", "ro.product.model", "ro.soc.model", "ro.board.platform", "ro.product.device")
        val market = (pr["ro.product.marketname"] ?: "").ifEmpty { pr["ro.product.model"] ?: "" }
        val socModel = (pr["ro.soc.model"] ?: "").ifEmpty { pr["ro.board.platform"] ?: "" }
        val result = SocInfo(
            marketName = market.ifEmpty { "?" },
            device = (pr["ro.product.device"] ?: "").ifEmpty { "?" },
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

        // Parâmetros de módulo do GED: só serão expostos se o kernel realmente os publicar.
        // 0 desativa a função correspondente; não descarrega o módulo GED.
        l.add(NodeSpec("ged.dvfs", "GED · GPU DVFS", "/sys/module/ged/parameters/gpu_dvfs_enable", "GED · controle",
            Kind.CHOICE, Risk.CAUTION, "0 = desativado · 1 = ativado. Depende de o kernel expor o parâmetro como gravável.", listOf("0", "1")))
        l.add(NodeSpec("ged.boost", "GED · GPU Boost", "/sys/module/ged/parameters/ged_boost_enable", "GED · controle",
            Kind.CHOICE, Risk.CAUTION, "0 = desativado · 1 = ativado. Não desativa o driver Mali nem as proteções térmicas.", listOf("0", "1")))

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

        // Parâmetros GED confirmados no inventário deste aparelho.
        val gedParams = "/sys/module/ged/parameters"
        val oppKHz = gpuDev?.freqs?.map { it / 1000L }?.distinct()?.sorted().orEmpty()
        fun gedToggle(id: String, label: String, file: String, hint: String) {
            l.add(NodeSpec(id, label, "$gedParams/$file", gG, Kind.CHOICE, Risk.CAUTION, hint, listOf("0", "1")))
        }
        gedToggle("ged.boost_gpu", "GED · Boost GPU", "boost_gpu_enable", "Parâmetro boost_gpu_enable publicado pelo módulo GED.")
        gedToggle("ged.smart_boost", "GED · Smart Boost", "ged_smart_boost", "Ativa/desativa a política GED Smart Boost.")
        gedToggle("ged.opp_logs", "GED · Logs de OPP", "gpu_opp_logs_enable", "Ativa/desativa somente os logs de OPP do GED.")
        if (oppKHz.isNotEmpty()) {
            val choices = oppKHz.map { it.toString() }
            l.add(NodeSpec("gpu.cust_upbound", "GED · Teto personalizado (kHz)", "$gedParams/gpu_cust_upbound_freq", gG, Kind.CHOICE, Risk.CAUTION,
                "Teto personalizado em kHz. Deve permanecer acima do piso GED.", choices))
            l.add(NodeSpec("gpu.cust_boost", "GED · Frequência de boost (kHz)", "$gedParams/gpu_cust_boost_freq", gG, Kind.CHOICE, Risk.CAUTION,
                "Frequência solicitada para boost. Aceita somente OPPs presentes na tabela do aparelho.", choices))
            l.add(NodeSpec("gpu.bottom", "GED · Frequência inferior (kHz)", "$gedParams/gpu_bottom_freq", gG, Kind.CHOICE, Risk.CAUTION,
                "Piso inferior do GED. Não ultrapasse o teto personalizado.", choices))
        }
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
        val r = readMany(paths, 16_000)

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

    /** Telemetria APU/GED somente leitura; ausência de nó é omitida, nunca simulada. */
    fun readApuTelemetry(): Map<String, String> {
        val paths = linkedMapOf(
            "Limite APU" to "$KTHERMAL/is_apu_limit",
            "Informações APU" to "$KTHERMAL/apu_info",
            "APU ATC" to "$KTHERMAL/apu_atc",
            "Limite CPU" to "$KTHERMAL/is_cpu_limit",
            "Limite GPU" to "$KTHERMAL/is_gpu_limit",
            "Frequência GED (índice / kHz)" to "$GED/current_freqency",
            "Utilização GED" to "$GED/gpu_utilization",
            "GPU loading" to "/sys/module/ged/parameters/gpu_loading",
            "GPU idle" to "/sys/module/ged/parameters/gpu_idle",
            "GED DVFS habilitado" to "/sys/module/ged/parameters/gpu_dvfs_enable",
            "GED Boost habilitado" to "/sys/module/ged/parameters/ged_boost_enable",
            "GED Smart Boost" to "/sys/module/ged/parameters/ged_smart_boost",
            "Boost GPU" to "/sys/module/ged/parameters/boost_gpu_enable",
            "Teto GED personalizado (kHz)" to "/sys/module/ged/parameters/gpu_cust_upbound_freq",
            "Boost GED personalizado (kHz)" to "/sys/module/ged/parameters/gpu_cust_boost_freq",
            "Piso GED (kHz)" to "/sys/module/ged/parameters/gpu_bottom_freq",
            "Função APU TOP" to "/sys/module/apu_top/parameters/aputop_func_sel",
            "Função PBM APU" to "/sys/module/apusys/parameters/apu_pbm_func_sel"
        )
        val raw = readMany(paths.values.toList(), 8192)
        return paths.mapNotNull { (label, path) ->
            raw[path]?.trim()?.takeIf { it.isNotEmpty() }?.let { label to it }
        }.toMap(LinkedHashMap())
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
            if (MODE_RE.matches(m)) store.put("mode:$path", m)
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
        "gpu.cust_upbound" -> writeGedGpuOpp(spec.path, value, "/sys/module/ged/parameters/gpu_bottom_freq", true)
        "gpu.bottom" -> writeGedGpuOpp(spec.path, value, "/sys/module/ged/parameters/gpu_cust_upbound_freq", false)
        "gpu.cust_boost" -> writeGedGpuOpp(spec.path, value, null, null)
        "gpu.dev.max" -> value.toLongOrNull()?.let { setGpuDevfreqLimits(maxHz = it) }
            ?: WriteResult(spec.path, value, "", false, "frequência inválida")
        "gpu.dev.min" -> value.toLongOrNull()?.let { setGpuDevfreqLimits(minHz = it) }
            ?: WriteResult(spec.path, value, "", false, "frequência inválida")
        "gpu.dev.gov" -> setGpuDevfreqGovernor(value)
        else -> write(spec.path, value)
    }

    private fun writeGedGpuOpp(path: String, value: String, pairedPath: String?, isUpper: Boolean?): WriteResult {
        val requested = value.toLongOrNull()
            ?: return WriteResult(path, value, "", false, "frequência inválida")
        val valid = info?.gpuOpps?.map { it.kHz }?.toSet().orEmpty()
        if (requested !in valid)
            return WriteResult(path, value, "", false, "valor não corresponde a uma OPP publicada pelo driver")
        if (pairedPath != null) {
            val paired = readOne(pairedPath)?.trim()?.toLongOrNull()
                ?: return WriteResult(path, value, "", false, "não foi possível validar o limite GED relacionado")
            val conflict = if (isUpper == true) requested < paired else requested > paired
            if (conflict)
                return WriteResult(path, value, paired.toString(), false,
                    if (isUpper == true) "teto não pode ficar abaixo do piso GED" else "piso não pode superar o teto GED")
        }
        return write(path, requested.toString())
    }

    // --- CPU

    /** Sem tabela de frequências (scaling_available_frequencies ausente) usa o pedido, limitado ao hardware. */
    fun resolveMax(pc: CpuPolicy, v: Long): Long = when {
        v <= 0 -> pc.maxHw
        pc.freqs.isEmpty() -> clampHw(pc, v)
        else -> pc.freqs.lastOrNull { it <= v } ?: pc.freqs.first()
    }

    fun resolveMin(pc: CpuPolicy, v: Long): Long = when {
        v <= 0 -> pc.minHw
        pc.freqs.isEmpty() -> clampHw(pc, v)
        else -> pc.freqs.firstOrNull { it >= v } ?: pc.freqs.last()
    }

    private fun clampHw(pc: CpuPolicy, v: Long): Long =
        if (pc.maxHw > 0 && pc.maxHw >= pc.minHw) v.coerceIn(pc.minHw, pc.maxHw) else v

    fun lockEnabled(): Boolean = store.get("lock") == "1"

    fun setCpu(policy: Int, minK: Long?, maxK: Long?, gov: String?): List<WriteResult> {
        val base = pol(policy)
        val lock = lockEnabled()
        val res = ArrayList<WriteResult>()
        if (gov != null) res += write("$base/scaling_governor", gov)
        if (minK == null && maxK == null) return res
        val minP = "$base/scaling_min_freq"
        val maxP = "$base/scaling_max_freq"
        val cur = readMany(listOf(minP, maxP))
        val curMin = Parse.intOf(cur[minP]) ?: 0L
        val curMax = Parse.intOf(cur[maxP]) ?: Long.MAX_VALUE
        var newMin = minK ?: curMin
        var newMax = maxK ?: curMax
        if (newMin > newMax) {
            when {
                // pedido incoerente (piso > teto): o teto vence, o kernel rejeitaria o contrário
                minK != null && maxK != null -> {
                    log("CPU policy$policy: piso $minK > teto $maxK; piso reduzido ao teto")
                    newMin = newMax
                }
                // novo teto abaixo do piso atual: leva o piso junto
                maxK != null -> newMin = newMax
                // novo piso acima do teto atual: o kernel rejeita (EINVAL); sobe o teto junto
                else -> newMax = newMin
            }
        }
        res += writeRange(minP, maxP, newMin, newMax, curMin, curMax, minK != null, maxK != null, lock)
        return res
    }

    /**
     * Escreve um par min/max (cpufreq ou devfreq) na ordem que o kernel aceita e desfaz o que já foi
     * escrito se uma das escritas falhar, para não deixar o par pela metade.
     * [forceMin]/[forceMax] escrevem mesmo sem mudança (reafirma o valor e aplica o lock).
     */
    private fun writeRange(
        minPath: String, maxPath: String,
        newMin: Long, newMax: Long, oldMin: Long, oldMax: Long,
        forceMin: Boolean, forceMax: Boolean, lock: Boolean
    ): List<WriteResult> {
        if (newMin > newMax)
            return listOf(WriteResult(minPath, newMin.toString(), "", false, "min_freq não pode superar max_freq"))
        val minStep = if (forceMin || newMin != oldMin) Triple(minPath, newMin, oldMin) else null
        val maxStep = if (forceMax || newMax != oldMax) Triple(maxPath, newMax, oldMax) else null
        // Teto novo abaixo do piso atual: baixa o piso primeiro. Nos demais casos, teto primeiro.
        val ordered = if (newMax < oldMin) listOfNotNull(minStep, maxStep) else listOfNotNull(maxStep, minStep)
        val results = ArrayList<WriteResult>()
        val undo = ArrayList<Pair<String, Long>>()
        for ((path, value, old) in ordered) {
            val r = write(path, value.toString(), lock)
            results += r
            if (!r.ok) {
                // a escrita que falhou pode ter sido aceita com outro valor (kernel ajustou): desfaz também
                val now = readOne(path)?.trim()?.toLongOrNull()
                if (now != null && now != old) undo += path to old
                // valores 0 / MAX_VALUE são os "não consegui ler" de setCpu: não faz sentido regravá-los
                for ((p2, o2) in undo.asReversed()) if (o2 in 1 until Long.MAX_VALUE) write(p2, o2.toString())
                return results
            }
            undo += path to old
        }
        return results
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

        if (nextMin == oldMin && nextMax == oldMax)
            return WriteResult(dev.path, "$nextMin..$nextMax", "$oldMin..$oldMax", true, "limites já estavam aplicados")

        val steps = writeRange(minPath, maxPath, nextMin, nextMax, oldMin, oldMax, forceMin = false, forceMax = false, lock = false)
        val failed = steps.firstOrNull { !it.ok }
        val state = "min=${readOne(minPath)?.trim()} max=${readOne(maxPath)?.trim()}"
        val result =
            if (failed != null) {
                WriteResult(dev.path, "$nextMin..$nextMax", state, false,
                    "falha em ${failed.path.substringAfterLast('/')}: ${failed.note.ifBlank { "kernel não confirmou o valor" }}")
            } else {
                WriteResult(dev.path, "$nextMin..$nextMax", state, true)
            }
        log(result.line())
        return result
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
        // um índice fora da tabela não deve chegar ao driver (fixar OPP é a escrita mais sensível da GPU)
        val valid = info?.gpuOpps?.map { it.idx }?.toSet().orEmpty()
        if (idx != null && valid.isNotEmpty() && idx !in valid)
            return WriteResult(GPU_FIX, v, "", false, "índice fora da tabela de OPPs (0..${valid.max()})").also { log(it.line()) }
        if (idx != null && idx < 0)
            return WriteResult(GPU_FIX, v, "", false, "índice inválido").also { log(it.line()) }
        val w = write(GPU_FIX, v, verify = false)
        val cur = Parse.gpuOppIndex(readOne(GPU_STATUS))
        val ok = w.ok && (idx == null || cur == idx)
        return WriteResult(GPU_FIX, v, "OPP atual=${cur ?: "?"}", ok, if (!w.ok) w.note else if (idx != null && cur != idx) "GPU ociosa/limitada? confira o PPM" else "")
            .also { log(it.line()) }
    }

    /**
     * PPM limit_table é telemetria somente leitura até validar o protocolo
     * de escrita específico deste kernel. Evita comandos especulativos ao driver.
     */
    fun setLimit(id: Int, enabled: Boolean): WriteResult {
        val row = Parse.limitTable(readOne(GPU_LIMIT_TABLE)).firstOrNull { it.id == id }
        val current = row?.let { it.name + ": c=" + (if (it.cEnable) 1 else 0) + " f=" + (if (it.fEnable) 1 else 0) } ?: "linha ausente"
        return WriteResult(GPU_LIMIT_TABLE, if (enabled) "enable $id" else "disable $id", current, false,
            "limit_table está em modo somente leitura até validar o protocolo do driver").also { log(it.line()) }
    }

    // --- Display / serviços

    fun setRefresh(hz: Int?): WriteResult {
        if (hz == null) {
            val d = sh.exec("settings delete system min_refresh_rate; settings delete system peak_refresh_rate; settings delete system user_refresh_rate")
            return WriteResult("display", "auto", if (d.ok) "auto" else "", d.ok, d.err.take(80)).also { log(it.line()) }
        }
        val f = "$hz.0"
        val r = sh.exec("settings put system min_refresh_rate $f; settings put system peak_refresh_rate $f; settings put system user_refresh_rate $hz")
        val got = sh.exec("settings get system peak_refresh_rate").out.trim()
        return WriteResult("display", f, got, r.ok && got.startsWith("$hz"), "HyperOS pode sobrescrever").also { log(it.line()) }
    }

    /**
     * Pausa/retoma os serviços térmicos. Guarda quais serviços foram de fato parados
     * ("thermal_stopped"): o aparelho pode não ter `thermald`, e marcar como pausado (ou retomado)
     * sem olhar o resultado deixava a proteção térmica registrada com o estado errado.
     */
    fun pauseThermal(pause: Boolean): List<WriteResult> {
        val prev = store.get("thermal_stopped")?.split(",")?.filter { it.isNotEmpty() }
        // sem registro do que foi parado (preferência antiga, ou serviço parado fora do app):
        // tenta iniciar os dois, que é inofensivo se já estiverem rodando
        val legacy = !pause && prev == null
        val targets = if (pause || legacy) THERMAL_SERVICES else prev.orEmpty()
        val results = targets.map { svc ->
            val r = sh.exec((if (pause) "stop " else "start ") + svc)
            WriteResult("svc:$svc", if (pause) "stop" else "start", "code=${r.code}", r.ok, r.err.take(80))
                .also { log(it.line()) }
        }
        val stopped: List<String> = if (pause) {
            ((prev ?: emptyList()) + targets.filterIndexed { i, _ -> results[i].ok }).distinct()
        } else if (legacy) {
            emptyList()
        } else {
            targets.filterIndexed { i, _ -> !results[i].ok } // não conseguiu retomar: continua registrado
        }
        if (stopped.isEmpty()) store.remove("thermal_stopped") else store.put("thermal_stopped", stopped.joinToString(","))
        store.put("thermal_paused", if (stopped.isEmpty()) "0" else "1")
        return results
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
            Profile(P_ECO, eco, true),
            Profile(P_BAL, bal, true),
            Profile(P_PERF, perf, true),
            Profile(P_MAX, max, true)
        )
    }

    fun allProfiles(): List<Profile> = builtins() + Profile.decode(store.get("profiles"))

    /**
     * Salva um perfil do usuário e devolve o nome com que ele ficou. O nome passa pela mesma limpeza
     * do encode (senão "a|b" nunca era substituído, pois voltava como "a/b") e não pode repetir o de
     * um perfil embutido (o embutido sempre vencia em applyByName, escondendo o salvo).
     */
    fun saveProfile(p: Profile): String {
        var name = Profile.cleanName(p.name).ifEmpty { "Perfil" }
        if (BUILTIN_NAMES.any { it.equals(name, ignoreCase = true) }) name += " (meu)"
        val mine = Profile.decode(store.get("profiles")).filter { !it.name.equals(name, ignoreCase = true) } +
            p.copy(name = name, builtin = false)
        store.put("profiles", Profile.encode(mine))
        return name
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
        val lastIdx = i.gpuOpps.lastOrNull()?.idx ?: 0
        if (bo != null) m["gpu.min_khz"] = if (bo >= lastIdx) "0" else (i.gpuOpps.firstOrNull { it.idx == bo }?.kHz ?: 0L).toString()
        return Profile(name, m, false)
    }

    fun applyProfile(pr: Profile): List<WriteResult> = applyProfileInternal(pr, quiet = false)

    /**
     * [quiet] = reaplicação automática (enforce / modo seguro): mexe só em CPU, GPU e nós, sem refazer
     * a taxa de atualização nem parar/iniciar serviços a cada deriva.
     */
    private fun applyProfileInternal(pr: Profile, quiet: Boolean): List<WriteResult> {
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
                k.startsWith("gpu.limit.") -> log("Perfil ignorado: limit_table é somente leitura até validar o protocolo do driver")
                k.startsWith("node:") -> i.node(k.removePrefix("node:"))?.let { res += writeNode(it, v) }
                k == "display.hz" -> if (!quiet) res += setRefresh(v.toIntOrNull())
                k == "thermal.pause" -> if (!quiet) res += pauseThermal(v == "1")
            }
        }
        store.put("active", pr.name)
        return res
    }

    fun applyByName(name: String): List<WriteResult> {
        val pr = allProfiles().firstOrNull { it.name == name } ?: return emptyList()
        return applyProfile(pr)
    }

    // ------------------------------------------------------------------ perfil por app

    /** Janelas do sistema que não contam como "sair do app" (gaveta de notificações, barras). */
    private val fgIgnored = setOf("android", "com.android.systemui", "com.miui.systemui")
    private var appMiss = 0

    fun appRules(): Map<String, String> = AppRules.decode(store.get("app_rules"))

    fun appProfilesOn(): Boolean = store.get("appprof") == "1" && appRules().isNotEmpty()

    /** Perfis marcados com ⚠ desligam proteções térmicas: nunca são aplicados sozinhos por app. */
    fun appRuleAllowed(profile: String): Boolean = !profile.endsWith("⚠")

    /** Cria/atualiza a regra ([profile] = nome) ou remove ([profile] = null). false = recusada. */
    fun setAppRule(pkg: String, profile: String?): Boolean {
        if (!AppRules.isPackage(pkg)) return false
        val m = LinkedHashMap(appRules())
        if (profile == null) {
            m.remove(pkg)
        } else {
            if (!appRuleAllowed(profile) || allProfiles().none { it.name == profile }) return false
            m[pkg] = profile
        }
        if (m.isEmpty()) store.remove("app_rules") else store.put("app_rules", AppRules.encode(m))
        return true
    }

    /** Esquece que há um perfil de app em vigor (sem desfazê-lo). */
    fun clearAppOverride() {
        appMiss = 0
        store.remove("app_base")
        store.remove("app_cur")
        store.remove("app_want")
    }

    /** Pacote do app em primeiro plano (precisa de root), ou null se não deu para descobrir. */
    fun foregroundPackage(): String? {
        val a = sh.exec("dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity'")
        Parse.foregroundPackage(a.out)?.let { return it }
        return Parse.foregroundPackage(sh.exec("dumpsys window 2>/dev/null | grep -m1 mCurrentFocus").out)
    }

    /**
     * Chamado a cada ciclo do serviço com a tela ligada. Ao entrar num app com regra aplica o perfil dele
     * (guardando o que estava ativo); ao sair, depois de 2 leituras seguidas fora do app, volta ao anterior.
     * [ignore] = pacotes que não contam (o próprio gerenciador). Devolve o perfil aplicado, ou null.
     */
    fun autoSwitchByApp(ignore: Set<String> = emptySet()): String? {
        val rules = appRules()
        if (rules.isEmpty()) return null
        val fg = foregroundPackage() ?: return null
        if (fg in fgIgnored || fg in ignore) return null
        val want = rules[fg]
        val cur = store.get("app_cur")
        if (want != null) {
            appMiss = 0
            if (cur == fg) return null // já aplicado; não briga com uma troca manual feita depois
            if (!appRuleAllowed(want) || allProfiles().none { it.name == want }) {
                log("Perfil por app: '$want' indisponível para $fg")
                return null
            }
            if (store.get("app_base") == null) store.put("app_base", store.get("active") ?: "")
            log("Perfil por app: $fg → '$want'")
            applyByName(want)
            store.put("app_cur", fg)
            store.put("app_want", want)
            return want
        }
        if (cur == null) return null
        if (++appMiss < 2) return null // debounce: uma leitura fora do app pode ser só uma janela do sistema
        val base = store.get("app_base").orEmpty()
        // se o perfil ativo não é mais o do app (modo seguro ou troca manual), não desfaz por cima
        val untouched = store.get("active") == store.get("app_want")
        clearAppOverride()
        if (!untouched) {
            log("Perfil por app: saiu de $cur; o perfil mudou nesse meio tempo, mantido")
            return null
        }
        log("Perfil por app: saiu de $cur → " + base.ifEmpty { "estado salvo" })
        return when {
            base.isNotEmpty() && allProfiles().any { it.name == base } -> {
                applyByName(base)
                base
            }
            hasSnapshot() -> {
                restoreSnapshot()
                "snapshot"
            }
            else -> null
        }
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
        val now = clock()
        if (now < enforceMutedUntil) return false
        val exp = expected(i, pr)
        val cur = readMany(exp.keys.toList())
        val bad = exp.filter { (k, v) -> cur[k]?.let { !Parse.same(v, it) } ?: false }
        if (bad.isEmpty()) return false
        // Se o sistema desfaz o perfil toda vez (throttling térmico, perf HAL), reaplicar em loop só
        // gera escrita e briga: depois de algumas tentativas na janela, pausa a reaplicação por um tempo.
        while (driftTimes.isNotEmpty() && now - driftTimes.first() > ENFORCE_WINDOW_MS) driftTimes.removeFirst()
        if (driftTimes.size >= ENFORCE_MAX_PER_WINDOW) {
            enforceMutedUntil = now + ENFORCE_WINDOW_MS
            driftTimes.clear()
            log("Deriva persistente em ${bad.size} nó(s): o sistema continua alterando os valores; reaplicação pausada por ${ENFORCE_WINDOW_MS / 1000} s")
            return false
        }
        driftTimes.addLast(now)
        log("Deriva em ${bad.size} nó(s): " + bad.keys.joinToString { it.substringAfterLast('/') } + " → reaplicando '${pr.name}'")
        applyProfileInternal(pr, quiet = true)
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
        // Pares min/max do devfreq (GPU, DDR, UFS) precisam de ordem válida: gravar o teto antigo antes
        // de baixar o piso atual é rejeitado pelo kernel e deixava o restore pela metade.
        val rangePaths = HashSet<String>()
        for (df in listOfNotNull(i.gpuDevfreq, i.ddr, i.ufs)) {
            val minP = "${df.path}/min_freq"
            val maxP = "${df.path}/max_freq"
            rangePaths += minP
            rangePaths += maxP
            val sMin = store.get("snap:$minP")?.toLongOrNull() ?: continue
            val sMax = store.get("snap:$maxP")?.toLongOrNull() ?: continue
            res += restoreRange(minP, maxP, sMin, sMax)
        }
        for (path in snapPaths(i)) {
            if (path.contains("/cpufreq/policy") || path in rangePaths) continue
            val v = store.get("snap:$path") ?: continue
            res += write(path, v)
        }
        // o snapshot é o estado "de fábrica": serviços térmicos parados pelo app voltam junto
        if (store.get("thermal_paused") == "1") res += pauseThermal(false)
        restoreModes()
        clearAppOverride()
        store.remove("active")
        return res
    }

    private fun restoreRange(minPath: String, maxPath: String, wantMin: Long, wantMax: Long): List<WriteResult> {
        val cur = readMany(listOf(minPath, maxPath))
        val oldMin = Parse.intOf(cur[minPath])
        val oldMax = Parse.intOf(cur[maxPath])
        if (oldMin == null || oldMax == null)
            return listOf(WriteResult(minPath, wantMin.toString(), "", false, "não foi possível ler os limites atuais"))
        return writeRange(minPath, maxPath, wantMin, wantMax, oldMin, oldMax, forceMin = false, forceMax = false, lock = false)
    }

    fun restoreModes() {
        store.keys().filter { it.startsWith("mode:") }.forEach { k ->
            val path = k.removePrefix("mode:")
            val mode = store.get(k)?.trim()
            // o valor vem das preferências e vai para um shell root: só aceita octal puro
            if (mode != null && MODE_RE.matches(mode)) sh.exec("chmod $mode ${q(p(path))} 2>/dev/null")
            else log("Modo de arquivo inválido ignorado: $path")
            store.remove(k)
        }
    }

    // ------------------------------------------------------------------ guarda térmica

    data class GuardCfg(val enabled: Boolean, val soc: Float, val skin: Float, val batt: Float)

    /** Limites configuráveis, mas dentro de faixas sãs: "0" travava o app em modo seguro, "500" desligava a guarda. */
    private fun guardLimit(key: String, def: Float, lo: Float, hi: Float): Float =
        (store.get(key)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: def).coerceIn(lo, hi)

    fun guardCfg() = GuardCfg(
        enabled = store.get("guard") != "0",
        soc = guardLimit("g_soc", 80f, 50f, 110f),
        skin = guardLimit("g_skin", 44f, 35f, 60f),
        batt = guardLimit("g_batt", 42f, 35f, 55f)
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
        if (!(store.get("disabled_limits") ?: "").isBlank()) log("AVISO: preferência antiga registra limitadores desativados; tabela PPM não será alterada por segurança")
        // quiet: o modo seguro só alivia CPU/GPU; não deve trocar a taxa de atualização da tela
        allProfiles().firstOrNull { it.name == P_BAL }?.let { res += applyProfileInternal(it, quiet = true) }
        return res
    }

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
        """.trimIndent())

        section("CPU - PPM, LIMITADORES E VÍNCULOS TÉRMICOS", """
            echo CPU cooling devices
            for p in /sys/class/thermal/cooling_device*; do
              [ -d "${'$'}p" ] || continue
              t=$(cat "${'$'}p/type" 2>/dev/null)
              case "${'$'}t" in *cpu*|*cpufreq*|*cluster*|*ppm*|*mtk*) echo "----- ${'$'}p type=${'$'}t -----"; for f in cur_state max_state stats/time_in_state; do [ -r "${'$'}p/${'$'}f" ] && { printf '%s=' "${'$'}f"; cat "${'$'}p/${'$'}f" 2>&1; }; done;; esac
            done
            echo PROC PPM performance nodes
            for f in /proc/ppm/* /proc/ppm/*/* /proc/cpufreq/* /proc/cpufreq/*/* /proc/mtk_perf/* /proc/mtk_perf/*/* /proc/thermal/* /proc/thermal/*/*; do
              [ -f "${'$'}f" ] || continue
              case "${'$'}f" in *limit*|*opp*|*policy*|*status*|*state*|*freq*|*dump*|*thermal*) echo "----- ${'$'}f -----"; head -c 12000 "${'$'}f" 2>&1; echo;; esac
            done
            echo SYSFS PPM performance nodes
            for f in /sys/devices/system/cpu/cpufreq/*/*limit* /sys/devices/system/cpu/cpufreq/*/*thermal* /sys/devices/platform/*/*ppm* /sys/devices/platform/*/*perf* /sys/kernel/*/*cpu*limit*; do
              [ -f "${'$'}f" ] || continue
              echo "----- ${'$'}f -----"; head -c 6000 "${'$'}f" 2>&1; echo
            done
            echo Thermal trip points and bindings
            for z in /sys/class/thermal/thermal_zone*; do
              [ -d "${'$'}z" ] || continue
              echo "----- ${'$'}z -----"
              for f in type temp; do [ -r "${'$'}z/${'$'}f" ] && { printf '%s=' "${'$'}f"; cat "${'$'}z/${'$'}f" 2>&1; }; done
              for f in "${'$'}z"/trip_point_*_type "${'$'}z"/trip_point_*_temp "${'$'}z"/cdev*; do [ -r "${'$'}f" ] && { echo "----- ${'$'}f -----"; cat "${'$'}f" 2>&1; }; done
            done
            echo Android power performance services
            getprop | grep -iE 'init\.svc\..*(thermal|perf|power|ppm|cpu)'
            ps -A 2>/dev/null | grep -iE 'thermal|perf|power|ppm|cpufreq' | grep -v grep
        """.trimIndent())

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
        nodeCommand.append("; do echo \"----- \$f -----\"; if [ -e \"\$f\" ]; then stat -c 'permissions=%A (%a) owner=%U:%G' \"\$f\" 2>&1; head -c 4000 \"\$f\" 2>&1; echo; else echo 'MISSING'; fi; done")
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
            echo '[cmdline filtrada]'; tr ' ' '\n' < /proc/cmdline | grep -viE 'serial|imei|androidboot\.serial'
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


        section("INVENTÁRIO DE INTERFACES DO KERNEL - SOMENTE LEITURA", """
            echo '[módulos e parâmetros publicados]'
            for f in /sys/module/*/parameters/*; do
              [ -e "${'$'}f" ] || continue
              if [ -r "${'$'}f" ]; then value=$(head -c 800 "${'$'}f" 2>/dev/null | tr '\n' ' '); else value='<sem leitura>'; fi
              if [ -w "${'$'}f" ]; then writable=yes; else writable=no; fi
              stat -c '%n | mode=%a | owner=%U:%G' "${'$'}f" 2>/dev/null
              echo "value=${'$'}value | root_writable=${'$'}writable"
            done | head -n 300
            echo '[APU / NPU / DSP / VPU / MDLA / IOMMU]'
            for root in /sys/class/devfreq /sys/class/remoteproc /sys/class/misc /sys/bus/platform/devices /sys/kernel /sys/module; do
              [ -d "${'$'}root" ] || continue
              find "${'$'}root" -mindepth 1 -maxdepth 3 2>/dev/null | grep -iE 'apu|npu|neural|mdla|vpu|dsp|iommu|scp|edma|apusys|mali|ged' | head -n 250
            done
            echo '[devfreq: valores e permissões]'
            for p in /sys/class/devfreq/*; do
              [ -d "${'$'}p" ] || continue
              echo "----- ${'$'}p -----"
              for f in name governor available_governors available_frequencies min_freq max_freq cur_freq target_freq polling_interval; do
                [ -r "${'$'}p/${'$'}f" ] && { printf '%s=' "${'$'}f"; head -c 4000 "${'$'}p/${'$'}f" 2>&1; echo; }
              done
              stat -c 'directory=%n mode=%a owner=%U:%G' "${'$'}p" 2>/dev/null
            done
            echo '[árvore de memória e zRAM]'
            find /sys/block/zram* /sys/kernel/mm /sys/devices/system/node -mindepth 1 -maxdepth 2 -type f 2>/dev/null | head -n 250
            echo '[drivers vinculados a dispositivos platform]'
            for d in /sys/bus/platform/devices/*; do
              [ -e "${'$'}d" ] || continue
              drv=$(readlink -f "${'$'}d/driver" 2>/dev/null)
              [ -n "${'$'}drv" ] && echo "${'$'}d -> ${'$'}drv"
            done | head -n 300
        """.trimIndent())
        line()
        line("========== FIM DO DIAGNÓSTICO ==========")
        line("Total de caracteres: " + out.length)
        return out.toString()
    }


}
