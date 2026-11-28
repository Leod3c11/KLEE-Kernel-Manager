package dev.klee.kernelmanager

import java.util.Locale
import kotlin.math.abs

class KernelControlRepository(private val shell: RootShell) {

    private fun result(r: CommandResult, fallback: String): String =
        if (r.success) r.stdout.joinToString("\n").ifBlank { "OK" }
        else "ERRO (exit=${r.code}): " + r.stderr.plus(r.stdout).joinToString("\n").ifBlank { fallback }

    fun readDashboardSummary(): DashboardSummary {
        if (!shell.rootCheck()) {
            return DashboardSummary(
                root = false,
                loaded = true,
                message = "Root indisponível. Conceda acesso ao KLEE pelo KernelSU."
            )
        }
        val script =
            "printf 'MODEL|%s\\n' \"\$(getprop ro.product.model 2>/dev/null)\"; " +
            "printf 'DEVICE|%s\\n' \"\$(getprop ro.product.device 2>/dev/null)\"; " +
            "printf 'SOC|%s\\n' \"\$(getprop ro.soc.model 2>/dev/null | head -n 1)\"; " +
            "printf 'BOARD|%s\\n' \"\$(getprop ro.board.platform 2>/dev/null)\"; " +
            "printf 'KERNEL|%s\\n' \"\$(uname -r 2>/dev/null)\"; " +
            "for d in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do [ -d \"\$d\" ] || continue; " +
            "printf 'CPU|%s\\n' \"\$(cat \"\$d/scaling_cur_freq\" 2>/dev/null)\"; done; " +
            "for d in /sys/class/devfreq/13000000.mali /sys/class/devfreq/*mali* /sys/class/devfreq/*gpu*; do [ -d \"\$d\" ] || continue; " +
            "printf 'GPU|%s\\n' \"\$(cat \"\$d/cur_freq\" 2>/dev/null)\"; break; done; " +
            "[ -r /sys/kernel/ged/hal/gpu_utilization ] && printf 'GPU_UTIL|%s\\n' \"\$(cat /sys/kernel/ged/hal/gpu_utilization 2>/dev/null)\"; " +
            "[ -r /sys/class/devfreq/mtk-dvfsrc-devfreq/cur_freq ] && printf 'DRAM|%s\\n' \"\$(cat /sys/class/devfreq/mtk-dvfsrc-devfreq/cur_freq 2>/dev/null)\"; " +
            "printf 'BATT|%s|%s|%s\\n' \"\$(cat /sys/class/power_supply/battery/capacity 2>/dev/null)\" \"\$(cat /sys/class/power_supply/battery/status 2>/dev/null)\" \"\$(cat /sys/class/power_supply/battery/input_suspend 2>/dev/null)\"; " +
            "for d in /sys/class/thermal/thermal_zone[0-9]*; do [ -d \"\$d\" ] || continue; " +
            "t=\$(cat \"\$d/type\" 2>/dev/null); v=\$(cat \"\$d/temp\" 2>/dev/null); " +
            "[ -n \"\$t\" ] && [ -n \"\$v\" ] && printf 'TEMP|%s|%s\\n' \"\$t\" \"\$v\"; done; " +
            "awk '/^(MemTotal|MemAvailable):/{printf \"MEM|%s|%s\\n\", \$1, \$2}' /proc/meminfo 2>/dev/null"

        val r = shell.runReadOnly(script)
        var model = "POCO X8 Pro"
        var device = "klee"
        var soc = ""
        var board = ""
        var kernel = ""
        var cpuPeakKHz = 0L
        var clusterCount = 0
        var gpuHz = 0L
        var gpuUtil: Int? = null
        var dramHz = 0L
        var battPct = 0
        var battStatus = ""
        var bypassActive = false
        var memTotalKb = 0L
        var memAvailKb = 0L
        var exactSocMax: Double? = null
        val socTemps = mutableListOf<Double>()
        val fallbackTemps = mutableListOf<Double>()

        r.stdout.forEach { line ->
            val p = line.split("|")
            when (p.firstOrNull()) {
                "MODEL" -> p.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { model = it }
                "DEVICE" -> p.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { device = it }
                "SOC" -> p.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { soc = it }
                "BOARD" -> p.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { board = it }
                "KERNEL" -> p.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { kernel = it }
                "CPU" -> {
                    clusterCount++
                    val cur = p.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
                    if (cur > cpuPeakKHz) cpuPeakKHz = cur
                }
                "GPU" -> gpuHz = p.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
                "GPU_UTIL" -> gpuUtil = parseFirstInt(p.getOrNull(1))
                "DRAM" -> dramHz = p.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
                "BATT" -> {
                    battPct = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    battStatus = p.getOrNull(2)?.trim() ?: ""
                    bypassActive = p.getOrNull(3)?.trim() == "1"
                }
                "TEMP" -> {
                    val name = p.getOrNull(1)?.lowercase() ?: ""
                    val raw = p.getOrNull(2)?.trim()?.toLongOrNull() ?: return@forEach
                    val c = normalizeTemp(raw)
                    if (c in 5.0..120.0) {
                        if (name == "soc_max") exactSocMax = c
                        else if (name.contains("soc") || name.contains("cpu") || name.contains("mtktsap")) socTemps.add(c)
                        else fallbackTemps.add(c)
                    }
                }
                "MEM" -> {
                    val key = p.getOrNull(1)?.trim() ?: ""
                    val kb = p.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
                    if (key.startsWith("MemTotal")) memTotalKb = kb
                    if (key.startsWith("MemAvailable")) memAvailKb = kb
                }
            }
        }

        val ramTotalMb = memTotalKb / 1024L
        val ramUsedMb = ((memTotalKb - memAvailKb).coerceAtLeast(0L)) / 1024L
        val displaySoc = soc.ifBlank { board.uppercase().ifBlank { "MT6899" } }
        val socTemp = exactSocMax ?: socTemps.maxOrNull() ?: fallbackTemps.maxOrNull()

        return DashboardSummary(
            root = true,
            loaded = true,
            deviceModel = model,
            deviceCodename = device,
            soc = displaySoc,
            kernelShort = kernel.substringBefore("-g").take(32),
            cpuPeakMHz = cpuPeakKHz / 1000L,
            cpuClusterCount = clusterCount,
            gpuMHz = gpuHz / 1_000_000L,
            gpuUtilization = gpuUtil,
            dramMHz = dramHz / 1_000_000L,
            batteryPercent = battPct,
            batteryStatus = battStatus,
            bypassActive = bypassActive,
            socTempC = socTemp,
            ramUsedMb = ramUsedMb,
            ramTotalMb = ramTotalMb
        )
    }

    fun readCpu(): ControlSnapshot {
        if (!shell.rootCheck()) return ControlSnapshot(false, message = "Autorize o KLEE no KernelSU.")
        val s =
            "for d in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do [ -d \"\$d\" ] || continue; " +
            "id=\${d##*policy}; " +
            "printf 'CPU|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\\n' " +
            "\"\$id\" " +
            "\"\$(cat \"\$d/related_cpus\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/scaling_cur_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/scaling_min_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/scaling_max_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/cpuinfo_min_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/cpuinfo_max_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/scaling_governor\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/scaling_available_governors\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/scaling_available_frequencies\" 2>/dev/null)\" " +
            "\"\$( [ -w \"\$d/scaling_min_freq\" ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w \"\$d/scaling_max_freq\" ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w \"\$d/scaling_governor\" ] && echo 1 || echo 0 )\" " +
            "\"\$(cat \"\$d/scaling_driver\" 2>/dev/null)\"; done; " +
            "for c in /sys/devices/system/cpu/cpu[0-9]*; do [ -d \"\$c\" ] || continue; " +
            "cid=\${c##*cpu}; case \"\$cid\" in *[!0-9]*) continue;; esac; " +
            "on=1; [ -r \"\$c/online\" ] && on=\$(cat \"\$c/online\" 2>/dev/null); " +
            "cf=\$(cat \"\$c/cpufreq/scaling_cur_freq\" 2>/dev/null); " +
            "printf 'CORE|%s|%s|%s\\n' \"\$cid\" \"\$on\" \"\$cf\"; done; " +
            "u=/dev/cpuctl/top-app; if [ -d \"\$u\" ]; then " +
            "printf 'UCLAMP|%s|%s|%s|%s|%s|%s\\n' " +
            "\"\$(cat \"\$u/cpu.uclamp.min\" 2>/dev/null)\" " +
            "\"\$(cat \"\$u/cpu.uclamp.max\" 2>/dev/null)\" " +
            "\"\$(cat \"\$u/cpu.uclamp.latency_sensitive\" 2>/dev/null)\" " +
            "\"\$( [ -w \"\$u/cpu.uclamp.min\" ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w \"\$u/cpu.uclamp.max\" ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w \"\$u/cpu.uclamp.latency_sensitive\" ] && echo 1 || echo 0 )\"; fi"

        val r = shell.runReadOnly(s)
        if (!r.success) return ControlSnapshot(true, message = r.stderr.joinToString("\n"))

        val list = r.stdout.mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 14 || p[0] != "CPU") return@mapNotNull null
            fun number(index: Int): Long = p.getOrNull(index)?.trim()?.toLongOrNull() ?: 0L
            CpuPolicy(
                id = p[1],
                cpus = p[2].trim(),
                currentKHz = number(3),
                minKHz = number(4),
                maxKHz = number(5),
                hardwareMinKHz = number(6),
                hardwareMaxKHz = number(7),
                governor = p[8].trim(),
                governors = p[9].split(Regex("\\s+")).filter(String::isNotBlank),
                frequenciesKHz = p[10].split(Regex("\\s+")).mapNotNull(String::toLongOrNull).distinct().sorted(),
                canWriteMin = p[11].trim() == "1",
                canWriteMax = p[12].trim() == "1",
                canWriteGovernor = p[13].trim() == "1",
                driver = p.getOrNull(14)?.trim() ?: ""
            )
        }.sortedBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }

        val cores = r.stdout.mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 4 || p[0] != "CORE") return@mapNotNull null
            val cid = p[1].trim().toIntOrNull() ?: return@mapNotNull null
            CpuCoreStatus(
                coreId = cid,
                online = p[2].trim() != "0",
                currentKHz = p[3].trim().toLongOrNull() ?: 0L
            )
        }.sortedBy { it.coreId }

        val uclamp = r.stdout.firstOrNull { it.startsWith("UCLAMP|") }?.split("|")?.let { p ->
            if (p.size < 7) null else {
                val minVal = p[1].trim().toFloatOrNull() ?: 0f
                val maxRaw = p[2].trim()
                val maxVal = if (maxRaw.equals("max", ignoreCase = true)) 100f else (maxRaw.toFloatOrNull() ?: 100f)
                UclampInfo(
                    group = "top-app",
                    minPercent = minVal.coerceIn(0f, 100f),
                    maxPercent = maxVal.coerceIn(0f, 100f),
                    latencySensitive = p[3].trim() == "1",
                    canWriteMin = p[4].trim() == "1",
                    canWriteMax = p[5].trim() == "1",
                    canWriteLatency = p[6].trim() == "1"
                )
            }
        }

        return ControlSnapshot(
            root = true,
            cpuPolicies = list,
            cpuCores = cores,
            uclamp = uclamp,
            message = if (list.isEmpty()) "Nenhuma policy CPUFreq encontrada." else ""
        )
    }

    fun applyUclamp(minPct: Int, maxPct: Int, latencySensitive: Boolean): String {
        require(minPct in 0..100 && maxPct in 0..100 && minPct <= maxPct) { "Faixa UCLAMP inválida (0–100%)." }
        val minStr = String.format(Locale.US, "%.2f", minPct.toDouble())
        val maxStr = if (maxPct >= 100) "max" else String.format(Locale.US, "%.2f", maxPct.toDouble())
        val latStr = if (latencySensitive) "1" else "0"
        val script =
            "u=/dev/cpuctl/top-app; [ -d \"\$u\" ] || { echo 'UCLAMP_NOT_FOUND'; exit 2; }; " +
            "if [ -w \"\$u/cpu.uclamp.max\" ]; then printf '%s' '$maxStr' > \"\$u/cpu.uclamp.max\" || { echo 'UCLAMP_MAX_FAILED'; exit 3; }; fi; " +
            "if [ -w \"\$u/cpu.uclamp.min\" ]; then printf '%s' '$minStr' > \"\$u/cpu.uclamp.min\" || { echo 'UCLAMP_MIN_FAILED'; exit 4; }; fi; " +
            "if [ -w \"\$u/cpu.uclamp.latency_sensitive\" ]; then printf '%s' '$latStr' > \"\$u/cpu.uclamp.latency_sensitive\" || { echo 'UCLAMP_LAT_FAILED'; exit 5; }; fi; " +
            "echo \"OK UCLAMP top-app min=\$(cat \"\$u/cpu.uclamp.min\" 2>/dev/null) max=\$(cat \"\$u/cpu.uclamp.max\" 2>/dev/null) latency=\$(cat \"\$u/cpu.uclamp.latency_sensitive\" 2>/dev/null)\""
        return result(shell.runRoot(script), "Falha ao aplicar UCLAMP.")
    }

    fun readGpu(): ControlSnapshot {
        if (!shell.rootCheck()) return ControlSnapshot(false, message = "Autorize o KLEE no KernelSU.")
        val s =
            "found=0; for d in /sys/class/devfreq/13000000.mali /sys/class/devfreq/*mali* /sys/class/devfreq/*gpu*; do " +
            "[ -d \"\$d\" ] || continue; [ \"\$found\" = 0 ] || continue; found=1; " +
            "printf 'GPU|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\\n' " +
            "\"\$d\" \"\$(cat \"\$d/name\" 2>/dev/null)\" \"\$(cat \"\$d/cur_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/min_freq\" 2>/dev/null)\" \"\$(cat \"\$d/max_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/min_freq\" 2>/dev/null)\" \"\$(cat \"\$d/max_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/governor\" 2>/dev/null)\" \"\$(cat \"\$d/available_governors\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/available_frequencies\" 2>/dev/null)\" " +
            "\"\$( [ -w \"\$d/min_freq\" ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w \"\$d/max_freq\" ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w \"\$d/governor\" ] && echo 1 || echo 0 )\"; done; " +
            "[ \"\$found\" = 1 ] || printf 'GPU_MISSING\\n'; " +
            "g=/sys/kernel/ged/hal; gp=/sys/module/ged/parameters; " +
            "if [ -d \"\$g\" ] || [ -d \"\$gp\" ]; then " +
            "printf 'GED|%s|%s|%s|%s|%s|%s|%s\\n' " +
            "\"\$(cat \"\$g/gpu_utilization\" 2>/dev/null)\" " +
            "\"\$(cat \"\$g/gpu_boost_level\" 2>/dev/null)\" " +
            "\"\$(cat \"\$g/dvfs_margin_value\" 2>/dev/null)\" " +
            "\"\$(cat \"\$g/dcs_mode\" 2>/dev/null)\" " +
            "\"\$(cat \"\$gp/ged_smart_boost\" 2>/dev/null)\" " +
            "\"\$(cat \"\$gp/boost_gpu_enable\" 2>/dev/null)\" " +
            "\"\$(cat \"\$gp/is_GED_KPI_enabled\" 2>/dev/null)\"; fi; " +
            "dr=/sys/class/devfreq/mtk-dvfsrc-devfreq; if [ -d \"\$dr\" ]; then " +
            "printf 'DRAM|%s|%s|%s|%s|%s|%s|%s\\n' " +
            "\"\$(cat \"\$dr/cur_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$dr/min_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$dr/max_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$dr/governor\" 2>/dev/null)\" " +
            "\"\$(cat \"\$dr/available_frequencies\" 2>/dev/null)\" " +
            "\"\$( [ -w \"\$dr/min_freq\" ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w \"\$dr/max_freq\" ] && echo 1 || echo 0 )\"; fi"

        val r = shell.runReadOnly(s)
        // Safe gpufreqv2 read-only diagnostics (never touches whitebox_test or power control)
        val diagCmd =
            "for f in /proc/gpufreqv2/gpufreq_status /proc/gpufreqv2/limit_table; do " +
            "[ -r \"\$f\" ] && { echo \"=== \$(basename \"\$f\") ===\"; head -n 35 \"\$f\" 2>/dev/null; }; done"
        val diagText = shell.runReadOnly(diagCmd).stdout.joinToString("\n")

        val ged = r.stdout.firstOrNull { it.startsWith("GED|") }?.split("|")?.let { p ->
            if (p.size < 8) GedControlInfo() else GedControlInfo(
                available = true,
                gpuUtilization = parseFirstInt(p[1]),
                gpuBoostLevel = parseFirstInt(p[2]),
                dvfsMarginValue = parseFirstInt(p[3]),
                dcsMode = parseBool01(p[4]),
                gedSmartBoost = parseBool01(p[5]),
                boostGpuEnable = parseBool01(p[6]),
                gedKpiEnabled = parseBool01(p[7])
            )
        } ?: GedControlInfo()

        val dram = r.stdout.firstOrNull { it.startsWith("DRAM|") }?.split("|")?.let { p ->
            if (p.size < 8) null else DramDevfreqInfo(
                path = "/sys/class/devfreq/mtk-dvfsrc-devfreq",
                currentHz = p[1].trim().toLongOrNull() ?: 0L,
                minHz = p[2].trim().toLongOrNull() ?: 0L,
                maxHz = p[3].trim().toLongOrNull() ?: 0L,
                governor = p[4].trim(),
                frequenciesHz = p[5].split(Regex("\\s+")).mapNotNull(String::toLongOrNull).distinct().sorted(),
                canWriteMin = p[6].trim() == "1",
                canWriteMax = p[7].trim() == "1"
            )
        }

        if (r.stdout.any { it.contains("GPU_MISSING") }) {
            return ControlSnapshot(
                root = true,
                ged = ged,
                dram = dram,
                message = "Devfreq Mali ausente. Diagnóstico gpufreqv2:\n" +
                    diagText.ifBlank { "Os nós gpufreqv2 não estão legíveis neste kernel." }
            )
        }
        val p = r.stdout.firstOrNull { it.startsWith("GPU|") }?.split("|")
            ?: return ControlSnapshot(true, ged = ged, dram = dram, message = "Falha ao consultar Mali devfreq: " + result(r, "sem saída"))
        if (p.size < 14) return ControlSnapshot(true, ged = ged, dram = dram, message = "Resposta GPU incompleta.")
        fun n(i: Int) = p[i].trim().toLongOrNull() ?: 0L
        val freqs = p[10].split(Regex("\\s+")).mapNotNull(String::toLongOrNull).distinct().sorted()
        val hwMin = freqs.firstOrNull() ?: n(4)
        val hwMax = freqs.lastOrNull() ?: n(5)
        val g = GpuControl(
            path = p[1].trim(),
            name = p[2].trim().ifBlank { "Mali-G720" },
            currentHz = n(3),
            minHz = n(4),
            maxHz = n(5),
            hardwareMinHz = hwMin,
            hardwareMaxHz = hwMax,
            governor = p[8].trim(),
            governors = p[9].split(Regex("\\s+")).filter(String::isNotBlank),
            frequenciesHz = freqs,
            canWriteMin = p[11].trim() == "1",
            canWriteMax = p[12].trim() == "1",
            canWriteGovernor = p[13].trim() == "1",
            gpufreqDiag = diagText
        )
        return ControlSnapshot(
            root = true,
            gpu = g,
            ged = ged,
            dram = dram,
            message = if (g.frequenciesHz.isEmpty()) "OPPs indisponíveis; controle GPU bloqueado por segurança." else ""
        )
    }

    fun applyGedToggle(key: String, enabled: Boolean): String {
        val allowed = mapOf(
            "dcs_mode" to "/sys/kernel/ged/hal/dcs_mode",
            "ged_smart_boost" to "/sys/module/ged/parameters/ged_smart_boost",
            "boost_gpu_enable" to "/sys/module/ged/parameters/boost_gpu_enable",
            "is_GED_KPI_enabled" to "/sys/module/ged/parameters/is_GED_KPI_enabled"
        )
        val path = allowed[key] ?: error("Parâmetro GED não suportado: $key")
        val v = if (enabled) "1" else "0"
        val script =
            "n='$path'; [ -w \"\$n\" ] || { echo 'GED_NODE_NOT_WRITABLE'; exit 2; }; " +
            "if ! printf '%s' '$v' > \"\$n\"; then echo 'GED_WRITE_FAILED'; exit 3; fi; " +
            "echo \"OK GED $key=\$(cat \"\$n\" 2>/dev/null)\""
        return result(shell.runRoot(script), "Falha ao aplicar GED $key.")
    }

    fun applyGedInt(key: String, value: Int): String {
        val allowed = mapOf(
            "gpu_boost_level" to ("/sys/kernel/ged/hal/gpu_boost_level" to (-1..100)),
            "dvfs_margin_value" to ("/sys/kernel/ged/hal/dvfs_margin_value" to (0..100))
        )
        val (path, range) = allowed[key] ?: error("Parâmetro GED não suportado: $key")
        require(value in range) { "Valor fora da faixa segura (${range.first}..${range.last})." }
        val script =
            "n='$path'; [ -w \"\$n\" ] || { echo 'GED_NODE_NOT_WRITABLE'; exit 2; }; " +
            "if ! printf '%s' '$value' > \"\$n\"; then echo 'GED_WRITE_FAILED'; exit 3; fi; " +
            "echo \"OK GED $key=\$(cat \"\$n\" 2>/dev/null)\""
        return result(shell.runRoot(script), "Falha ao aplicar GED $key.")
    }

    fun applyDram(minHz: Long, maxHz: Long): String {
        require(minHz > 0 && maxHz >= minHz) { "Faixa de frequência DRAM inválida." }
        val d = "/sys/class/devfreq/mtk-dvfsrc-devfreq"
        val s =
            "d='$d'; [ -r \"\$d/available_frequencies\" ] && [ -w \"\$d/min_freq\" ] && [ -w \"\$d/max_freq\" ] || { echo 'DRAM_NODES_UNAVAILABLE'; exit 2; }; " +
            "oldmin=\$(cat \"\$d/min_freq\") || exit 2; oldmax=\$(cat \"\$d/max_freq\") || exit 2; " +
            "freqs=\$(cat \"\$d/available_frequencies\" 2>/dev/null); hasmin=0; hasmax=0; " +
            "for f in \$freqs; do [ \"\$f\" = '$minHz' ] && hasmin=1; [ \"\$f\" = '$maxHz' ] && hasmax=1; done; " +
            "[ \"\$hasmin\" = 1 ] && [ \"\$hasmax\" = 1 ] || { echo 'DRAM_FREQ_NOT_IN_TABLE'; exit 3; }; " +
            "rollback(){ printf '%s' \"\$oldmin\" > \"\$d/min_freq\" 2>/dev/null; printf '%s' \"\$oldmax\" > \"\$d/max_freq\" 2>/dev/null; }; " +
            "if [ '$minHz' -gt \"\$oldmax\" ]; then " +
            "printf '%s' '$maxHz' > \"\$d/max_freq\" || { rollback; echo 'DRAM_MAX_FAILED'; exit 4; }; " +
            "printf '%s' '$minHz' > \"\$d/min_freq\" || { rollback; echo 'DRAM_MIN_FAILED'; exit 5; }; " +
            "else " +
            "printf '%s' '$minHz' > \"\$d/min_freq\" || { rollback; echo 'DRAM_MIN_FAILED'; exit 5; }; " +
            "printf '%s' '$maxHz' > \"\$d/max_freq\" || { rollback; echo 'DRAM_MAX_FAILED'; exit 4; }; fi; " +
            "echo \"OK DRAM min=\$(cat \"\$d/min_freq\") max=\$(cat \"\$d/max_freq\")\""
        return result(shell.runRoot(s), "Falha ao ajustar DRAM.")
    }

    fun readMtkPerf(): MtkPerfSnapshot {
        if (!shell.rootCheck()) return MtkPerfSnapshot(false, message = "Autorize o KLEE no KernelSU.")
        val script =
            "if [ -d /sys/kernel/fpsgo ]; then echo 'HAS_FPSGO|1'; " +
            "printf 'FP|fpsgo_enable|%s\\n' \"\$(cat /sys/kernel/fpsgo/common/fpsgo_enable 2>/dev/null)\"; " +
            "printf 'FP|boost_ta|%s\\n' \"\$(cat /sys/kernel/fpsgo/fbt/boost_ta 2>/dev/null)\"; " +
            "printf 'FP|boost_VIP|%s\\n' \"\$(cat /sys/kernel/fpsgo/fbt/boost_VIP 2>/dev/null)\"; " +
            "printf 'FP|rescue_enable|%s\\n' \"\$(cat /sys/kernel/fpsgo/fbt/rescue_enable 2>/dev/null)\"; " +
            "printf 'FP|ultra_rescue|%s\\n' \"\$(cat /sys/kernel/fpsgo/fbt/ultra_rescue 2>/dev/null)\"; " +
            "printf 'FP|powerRL_enable|%s\\n' \"\$(cat /sys/kernel/fpsgo/fbt/powerRL_enable 2>/dev/null)\"; " +
            "printf 'FP|status|%s\\n' \"\$(head -n 3 /sys/kernel/fpsgo/fstb/fpsgo_status 2>/dev/null | tr '\\n' ' ')\"; fi; " +
            "if [ -d /sys/module/metis/parameters ]; then echo 'HAS_METIS|1'; " +
            "for k in mi_fboost_enable speed_touch_enable vip_link_enable metis_io_enabled in_perf_mod thermal_break_enable; do " +
            "printf 'MT|%s|%s\\n' \"\$k\" \"\$(cat \"/sys/module/metis/parameters/\$k\" 2>/dev/null)\"; done; fi"

        val r = shell.runReadOnly(script)
        var hasFpsgo = false
        var hasMetis = false
        val fp = mutableMapOf<String, String>()
        val mt = mutableMapOf<String, String>()

        r.stdout.forEach { line ->
            val p = line.split("|")
            when (p.firstOrNull()) {
                "HAS_FPSGO" -> hasFpsgo = true
                "HAS_METIS" -> hasMetis = true
                "FP" -> if (p.size >= 3) fp[p[1]] = p[2].trim()
                "MT" -> if (p.size >= 3) mt[p[1]] = p[2].trim()
            }
        }

        return MtkPerfSnapshot(
            root = true,
            fpsgoAvailable = hasFpsgo,
            fpsgoEnable = parseBool01(fp["fpsgo_enable"]),
            boostTa = parseBool01(fp["boost_ta"]),
            boostVip = parseBool01(fp["boost_VIP"]),
            rescueEnable = parseBool01(fp["rescue_enable"]),
            ultraRescue = parseBool01(fp["ultra_rescue"]),
            powerRlEnable = parseBool01(fp["powerRL_enable"]),
            fpsgoStatus = fp["status"].orEmpty(),
            metisAvailable = hasMetis,
            miFboostEnable = parseBool01(mt["mi_fboost_enable"]),
            speedTouchEnable = parseBool01(mt["speed_touch_enable"]),
            vipLinkEnable = parseBool01(mt["vip_link_enable"]),
            metisIoEnabled = parseBool01(mt["metis_io_enabled"]),
            inPerfMod = parseBool01(mt["in_perf_mod"]),
            thermalBreakEnable = parseBool01(mt["thermal_break_enable"]),
            message = if (!hasFpsgo && !hasMetis) "FPSGO e Metis não detectados neste kernel." else ""
        )
    }

    fun applyMtkPerfBool(key: String, enabled: Boolean): String {
        val allowed = mapOf(
            "fpsgo_enable" to "/sys/kernel/fpsgo/common/fpsgo_enable",
            "boost_ta" to "/sys/kernel/fpsgo/fbt/boost_ta",
            "boost_VIP" to "/sys/kernel/fpsgo/fbt/boost_VIP",
            "rescue_enable" to "/sys/kernel/fpsgo/fbt/rescue_enable",
            "ultra_rescue" to "/sys/kernel/fpsgo/fbt/ultra_rescue",
            "powerRL_enable" to "/sys/kernel/fpsgo/fbt/powerRL_enable",
            "mi_fboost_enable" to "/sys/module/metis/parameters/mi_fboost_enable",
            "speed_touch_enable" to "/sys/module/metis/parameters/speed_touch_enable",
            "vip_link_enable" to "/sys/module/metis/parameters/vip_link_enable",
            "metis_io_enabled" to "/sys/module/metis/parameters/metis_io_enabled",
            "in_perf_mod" to "/sys/module/metis/parameters/in_perf_mod",
            "thermal_break_enable" to "/sys/module/metis/parameters/thermal_break_enable"
        )
        val path = allowed[key] ?: error("Parâmetro MTK/Metis não permitido: $key")
        val v = if (enabled) "1" else "0"
        val script =
            "n='$path'; [ -w \"\$n\" ] || { echo 'NODE_NOT_WRITABLE'; exit 2; }; " +
            "if ! printf '%s' '$v' > \"\$n\"; then echo 'WRITE_FAILED'; exit 3; fi; " +
            "echo \"OK $key=\$(cat \"\$n\" 2>/dev/null)\""
        return result(shell.runRoot(script), "Falha ao ajustar $key.")
    }

    fun applyCpu(id: String, minKHz: Long, maxKHz: Long, governor: String?): String {
        require(id.matches(Regex("[0-9]{1,3}"))) { "Policy inválida." }
        require(minKHz > 0 && maxKHz >= minKHz) { "Faixa de frequência inválida." }
        require(governor == null || governor.matches(Regex("[A-Za-z0-9_]+"))) { "Governor inválido." }
        val d = "/sys/devices/system/cpu/cpufreq/policy$id"
        val govCheck = if (governor == null) "" else
            "available=\$(cat \"\$d/scaling_available_governors\" 2>/dev/null); case \" \$available \" in *' $governor '*) ;; *) echo 'GOVERNOR_UNAVAILABLE'; exit 7;; esac; "
        val govWrite = if (governor == null) "" else
            "if [ \"\$oldgov\" != '$governor' ]; then if ! printf '%s' '$governor' > \"\$d/scaling_governor\"; then echo 'GOVERNOR_WRITE_FAILED'; exit 8; fi; actualgov=\$(cat \"\$d/scaling_governor\" 2>/dev/null); if [ \"\$actualgov\" != '$governor' ]; then echo 'GOVERNOR_READBACK_MISMATCH expected=$governor actual=\$actualgov'; [ -z \"\$oldgov\" ] || printf '%s' \"\$oldgov\" > \"\$d/scaling_governor\" 2>/dev/null; exit 9; fi; fi; "
        val s =
            "d='$d'; [ -d \"\$d\" ] || { echo 'POLICY_NOT_FOUND'; exit 2; }; " +
            "oldmin=\$(cat \"\$d/scaling_min_freq\") || exit 2; " +
            "oldmax=\$(cat \"\$d/scaling_max_freq\") || exit 2; " +
            "oldgov=\$(cat \"\$d/scaling_governor\" 2>/dev/null); " +
            "hwmin=\$(cat \"\$d/cpuinfo_min_freq\"); hwmax=\$(cat \"\$d/cpuinfo_max_freq\"); " +
            "[ $minKHz -ge \$hwmin ] && [ $maxKHz -le \$hwmax ] || { echo 'FREQUENCY_OUT_OF_HARDWARE_RANGE'; exit 3; }; " +
            govCheck + govWrite +
            "rollback(){ cm=\$(cat \"\$d/scaling_min_freq\" 2>/dev/null); cx=\$(cat \"\$d/scaling_max_freq\" 2>/dev/null); " +
            "if [ \"\$oldmin\" -gt \"\$cx\" ]; then printf '%s' \"\$oldmax\" > \"\$d/scaling_max_freq\" 2>/dev/null; printf '%s' \"\$oldmin\" > \"\$d/scaling_min_freq\" 2>/dev/null; " +
            "elif [ \"\$oldmax\" -lt \"\$cm\" ]; then printf '%s' \"\$oldmin\" > \"\$d/scaling_min_freq\" 2>/dev/null; printf '%s' \"\$oldmax\" > \"\$d/scaling_max_freq\" 2>/dev/null; " +
            "else printf '%s' \"\$oldmin\" > \"\$d/scaling_min_freq\" 2>/dev/null; printf '%s' \"\$oldmax\" > \"\$d/scaling_max_freq\" 2>/dev/null; fi; " +
            "[ -z \"\$oldgov\" ] || printf '%s' \"\$oldgov\" > \"\$d/scaling_governor\" 2>/dev/null; }; " +
            "if [ $maxKHz -lt \"\$oldmin\" ]; then " +
            "if [ $minKHz -gt $maxKHz ] || ! printf '%s' '$minKHz' > \"\$d/scaling_min_freq\"; then echo 'CPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; " +
            "if ! printf '%s' '$maxKHz' > \"\$d/scaling_max_freq\"; then echo 'CPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; " +
            "elif [ $minKHz -gt \"\$oldmax\" ]; then " +
            "if ! printf '%s' '$maxKHz' > \"\$d/scaling_max_freq\"; then echo 'CPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; " +
            "if ! printf '%s' '$minKHz' > \"\$d/scaling_min_freq\"; then echo 'CPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; " +
            "elif [ $maxKHz -lt \"\$oldmax\" ]; then " +
            "if [ \"\$oldmax\" != '$maxKHz' ] && ! printf '%s' '$maxKHz' > \"\$d/scaling_max_freq\"; then echo 'CPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; " +
            "if [ \"\$oldmin\" != '$minKHz' ] && ! printf '%s' '$minKHz' > \"\$d/scaling_min_freq\"; then echo 'CPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; " +
            "else " +
            "if [ \"\$oldmin\" != '$minKHz' ] && ! printf '%s' '$minKHz' > \"\$d/scaling_min_freq\"; then echo 'CPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; " +
            "if [ \"\$oldmax\" != '$maxKHz' ] && ! printf '%s' '$maxKHz' > \"\$d/scaling_max_freq\"; then echo 'CPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; fi; " +
            "actualmin=\$(cat \"\$d/scaling_min_freq\"); actualmax=\$(cat \"\$d/scaling_max_freq\"); " +
            "[ \"\$actualmin\" = '$minKHz' ] && [ \"\$actualmax\" = '$maxKHz' ] || { echo \"CPU_LIMIT_READBACK_MISMATCH min=\$actualmin max=\$actualmax\"; rollback; exit 12; }; " +
            "echo \"OK CPU policy$id min=\$actualmin max=\$actualmax governor=\$(cat \"\$d/scaling_governor\" 2>/dev/null)\""
        return result(shell.runRoot(s), "Falha ao aplicar CPU.")
    }

    fun applyGpu(
        minHz: Long,
        maxHz: Long,
        governor: String?,
        devicePath: String = "/sys/class/devfreq/13000000.mali"
    ): String {
        require(minHz > 0 && maxHz >= minHz) { "Faixa de frequência da GPU inválida." }
        require(governor == null || governor.matches(Regex("[A-Za-z0-9_-]+"))) { "Governor da GPU inválido." }
        require(devicePath.matches(Regex("^/sys/class/devfreq/[A-Za-z0-9._-]+$"))) { "Caminho devfreq inválido." }
        val govCheck = if (governor == null) "" else
            "available=\$(cat \"\$d/available_governors\" 2>/dev/null); case \" \$available \" in *' $governor '*) ;; *) echo 'GPU_GOVERNOR_UNAVAILABLE'; exit 7;; esac; "
        val govWrite = if (governor == null) "" else
            "if [ \"\$oldgov\" != '$governor' ]; then if ! printf '%s' '$governor' > \"\$d/governor\"; then echo 'GPU_GOVERNOR_WRITE_FAILED'; exit 8; fi; actualgov=\$(cat \"\$d/governor\" 2>/dev/null); [ \"\$actualgov\" = '$governor' ] || { echo 'GPU_GOVERNOR_READBACK_MISMATCH'; [ -z \"\$oldgov\" ] || printf '%s' \"\$oldgov\" > \"\$d/governor\" 2>/dev/null; exit 9; }; fi; "
        val s =
            "d='$devicePath'; [ -r \"\$d/available_frequencies\" ] && [ -w \"\$d/min_freq\" ] && [ -w \"\$d/max_freq\" ] || { echo 'GPU_DEVFREQ_NODES_UNAVAILABLE'; exit 2; }; " +
            "oldmin=\$(cat \"\$d/min_freq\") || exit 2; oldmax=\$(cat \"\$d/max_freq\") || exit 2; oldgov=\$(cat \"\$d/governor\" 2>/dev/null); " +
            "freqs=\$(cat \"\$d/available_frequencies\" 2>/dev/null); hasmin=0; hasmax=0; " +
            "for f in \$freqs; do [ \"\$f\" = '$minHz' ] && hasmin=1; [ \"\$f\" = '$maxHz' ] && hasmax=1; done; " +
            "[ \"\$hasmin\" = 1 ] && [ \"\$hasmax\" = 1 ] || { echo 'GPU_FREQUENCY_NOT_IN_OPP_TABLE'; exit 3; }; " +
            govCheck + govWrite +
            "rollback(){ cm=\$(cat \"\$d/min_freq\" 2>/dev/null); cx=\$(cat \"\$d/max_freq\" 2>/dev/null); " +
            "if [ \"\$oldmin\" -gt \"\$cx\" ]; then printf '%s' \"\$oldmax\" > \"\$d/max_freq\" 2>/dev/null; printf '%s' \"\$oldmin\" > \"\$d/min_freq\" 2>/dev/null; " +
            "elif [ \"\$oldmax\" -lt \"\$cm\" ]; then printf '%s' \"\$oldmin\" > \"\$d/min_freq\" 2>/dev/null; printf '%s' \"\$oldmax\" > \"\$d/max_freq\" 2>/dev/null; " +
            "else printf '%s' \"\$oldmin\" > \"\$d/min_freq\" 2>/dev/null; printf '%s' \"\$oldmax\" > \"\$d/max_freq\" 2>/dev/null; fi; " +
            "[ -z \"\$oldgov\" ] || printf '%s' \"\$oldgov\" > \"\$d/governor\" 2>/dev/null; }; " +
            "if [ '$maxHz' -lt \"\$oldmin\" ]; then " +
            "if ! printf '%s' '$minHz' > \"\$d/min_freq\"; then echo 'GPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; " +
            "if ! printf '%s' '$maxHz' > \"\$d/max_freq\"; then echo 'GPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; " +
            "elif [ '$minHz' -gt \"\$oldmax\" ]; then " +
            "if ! printf '%s' '$maxHz' > \"\$d/max_freq\"; then echo 'GPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; " +
            "if ! printf '%s' '$minHz' > \"\$d/min_freq\"; then echo 'GPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; " +
            "elif [ '$maxHz' -lt \"\$oldmax\" ]; then " +
            "if [ \"\$oldmax\" != '$maxHz' ] && ! printf '%s' '$maxHz' > \"\$d/max_freq\"; then echo 'GPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; " +
            "if [ \"\$oldmin\" != '$minHz' ] && ! printf '%s' '$minHz' > \"\$d/min_freq\"; then echo 'GPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; " +
            "else " +
            "if [ \"\$oldmin\" != '$minHz' ] && ! printf '%s' '$minHz' > \"\$d/min_freq\"; then echo 'GPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; " +
            "if [ \"\$oldmax\" != '$maxHz' ] && ! printf '%s' '$maxHz' > \"\$d/max_freq\"; then echo 'GPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; fi; " +
            "actualmin=\$(cat \"\$d/min_freq\"); actualmax=\$(cat \"\$d/max_freq\"); " +
            "[ \"\$actualmin\" = '$minHz' ] && [ \"\$actualmax\" = '$maxHz' ] || { echo \"GPU_LIMIT_READBACK_MISMATCH min=\$actualmin max=\$actualmax\"; rollback; exit 12; }; " +
            "echo \"OK GPU min=\$actualmin max=\$actualmax governor=\$(cat \"\$d/governor\" 2>/dev/null)\""
        return result(shell.runRoot(s), "Falha ao aplicar GPU.")
    }

    fun readThermalDetailed(): ThermalSnapshot {
        if (!shell.rootCheck()) return ThermalSnapshot(false, message = "Autorize o KLEE no KernelSU.")
        val script =
            "for d in /sys/class/thermal/thermal_zone[0-9]*; do [ -d \"\$d\" ] || continue; " +
            "t=\$(cat \"\$d/type\" 2>/dev/null); v=\$(cat \"\$d/temp\" 2>/dev/null); " +
            "[ -n \"\$t\" ] && [ -n \"\$v\" ] && printf 'TEMP|%s|%s\\n' \"\$t\" \"\$v\"; done; " +
            "for d in /sys/class/thermal/cooling_device[0-9]*; do [ -d \"\$d\" ] || continue; " +
            "t=\$(cat \"\$d/type\" 2>/dev/null); v=\$(cat \"\$d/cur_state\" 2>/dev/null); m=\$(cat \"\$d/max_state\" 2>/dev/null); " +
            "[ -n \"\$t\" ] && printf 'COOL|%s|%s|%s\\n' \"\$t\" \"\$v\" \"\$m\"; done; " +
            "for f in /sys/class/thermal/thermal_message/board_sensor_temp /sys/class/thermal/thermal_message/display_therm_temp /sys/class/thermal/thermal_message/temp_state /sys/class/thermal/thermal_message/balance_mode /sys/class/thermal/thermal_message/sconfig; do " +
            "[ -r \"\$f\" ] && printf 'EXTRA|%s|%s\\n' \"\$(basename \"\$f\")\" \"\$(cat \"\$f\" 2>/dev/null)\"; done"

        val r = shell.runReadOnly(script)
        val zones = mutableListOf<ThermalZone>()
        val cooling = mutableListOf<CoolingDevice>()
        val extras = mutableListOf<MtkThermalExtra>()

        r.stdout.forEach { line ->
            val p = line.split("|")
            when (p.firstOrNull()) {
                "TEMP" -> {
                    val name = p.getOrNull(1)?.trim() ?: return@forEach
                    val raw = p.getOrNull(2)?.trim()?.toLongOrNull() ?: return@forEach
                    val celsius = normalizeTemp(raw)
                    if (celsius > -40.0 && celsius < 180.0) {
                        zones.add(ThermalZone(name, celsius, raw))
                    }
                }
                "COOL" -> {
                    val name = p.getOrNull(1)?.trim() ?: return@forEach
                    val cur = p.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
                    val max = p.getOrNull(3)?.trim()?.toIntOrNull() ?: 0
                    cooling.add(CoolingDevice(name, cur, max))
                }
                "EXTRA" -> {
                    val k = p.getOrNull(1)?.trim() ?: return@forEach
                    val v = p.getOrNull(2)?.trim() ?: ""
                    extras.add(MtkThermalExtra(k, v))
                }
            }
        }

        return ThermalSnapshot(
            root = true,
            zones = zones.sortedByDescending { it.tempCelsius },
            coolingDevices = cooling.sortedByDescending { it.curState },
            mtkExtras = extras,
            message = if (zones.isEmpty() && cooling.isEmpty()) "Nenhuma zona térmica legível foi encontrada." else ""
        )
    }

    fun readPowerDetailed(): PowerSnapshot {
        if (!shell.rootCheck()) return PowerSnapshot(false, message = "Autorize o KLEE no KernelSU.")
        val script =
            "d=/sys/class/power_supply/battery; " +
            "for n in status capacity voltage_now current_now temp health technology cycle_count charge_full charge_full_design charge_counter input_suspend; do " +
            "[ -r \"\$d/\$n\" ] && printf 'BAT|%s|%s\\n' \"\$n\" \"\$(cat \"\$d/\$n\" 2>/dev/null)\"; done; " +
            "printf 'BAT|can_write_suspend|%s\\n' \"\$( [ -w \"\$d/input_suspend\" ] && echo 1 || echo 0 )\"; " +
            "for s in /sys/class/power_supply/*; do [ -d \"\$s\" ] || continue; " +
            "on=''; [ -r \"\$s/online\" ] && on=\$(cat \"\$s/online\" 2>/dev/null); " +
            "printf 'SUPPLY|%s|%s|%s\\n' \"\$(basename \"\$s\")\" \"\$(cat \"\$s/type\" 2>/dev/null)\" \"\$on\"; done"

        val r = shell.runReadOnly(script)
        val bat = mutableMapOf<String, String>()
        val supplies = mutableListOf<PowerSupplyInfo>()

        r.stdout.forEach { line ->
            val p = line.split("|")
            when (p.firstOrNull()) {
                "BAT" -> {
                    val key = p.getOrNull(1)?.trim() ?: return@forEach
                    val value = p.getOrNull(2)?.trim() ?: ""
                    bat[key] = value
                }
                "SUPPLY" -> {
                    val name = p.getOrNull(1)?.trim() ?: return@forEach
                    val type = p.getOrNull(2)?.trim()?.ifBlank { "Desconhecido" } ?: "Desconhecido"
                    val on = p.getOrNull(3)?.trim()?.let { if (it == "1") true else if (it == "0") false else null }
                    supplies.add(PowerSupplyInfo(name, type, on))
                }
            }
        }

        val rawVoltage = bat["voltage_now"]?.toLongOrNull() ?: 0L
        val voltageVolts = when {
            rawVoltage > 100_000 -> rawVoltage / 1_000_000.0
            rawVoltage > 100 -> rawVoltage / 1_000.0
            else -> rawVoltage.toDouble()
        }

        val rawCurrent = bat["current_now"]?.toLongOrNull() ?: 0L
        val currentMa = if (abs(rawCurrent) > 25_000) rawCurrent / 1_000.0 else rawCurrent.toDouble()
        val powerWatts = abs(voltageVolts * (currentMa / 1_000.0))

        val rawTemp = bat["temp"]?.toLongOrNull() ?: 0L
        val tempC = when {
            rawTemp > 1000 -> rawTemp / 1000.0
            rawTemp > 100 -> rawTemp / 10.0
            else -> rawTemp.toDouble()
        }

        fun toMah(raw: Long?): Int? {
            if (raw == null || raw <= 0) return null
            return if (raw > 100_000) (raw / 1000L).toInt() else raw.toInt()
        }

        return PowerSnapshot(
            root = true,
            status = bat["status"] ?: "Desconhecido",
            capacity = bat["capacity"]?.toIntOrNull() ?: 0,
            voltageVolts = voltageVolts,
            currentMa = currentMa,
            powerWatts = powerWatts,
            tempCelsius = tempC,
            health = bat["health"] ?: "Desconhecida",
            technology = bat["technology"] ?: "Li-poly",
            cycleCount = bat["cycle_count"]?.toIntOrNull(),
            chargeFullMah = toMah(bat["charge_full"]?.toLongOrNull()),
            chargeDesignMah = toMah(bat["charge_full_design"]?.toLongOrNull()),
            chargeCounterMah = toMah(bat["charge_counter"]?.toLongOrNull()),
            inputSuspend = parseBool01(bat["input_suspend"]),
            canWriteInputSuspend = bat["can_write_suspend"] == "1",
            supplies = supplies.sortedByDescending { it.online == true },
            message = if (bat.isEmpty()) "Não foi possível ler /sys/class/power_supply/battery." else ""
        )
    }

    fun applyBypassCharging(suspend: Boolean): String {
        val v = if (suspend) "1" else "0"
        val script =
            "n=/sys/class/power_supply/battery/input_suspend; [ -w \"\$n\" ] || { echo 'BYPASS_NODE_NOT_WRITABLE'; exit 2; }; " +
            "if ! printf '%s' '$v' > \"\$n\"; then echo 'BYPASS_WRITE_FAILED'; exit 3; fi; " +
            "actual=\$(cat \"\$n\" 2>/dev/null); " +
            "[ \"\$actual\" = '$v' ] || { echo \"BYPASS_READBACK_MISMATCH actual=\$actual\"; exit 4; }; " +
            "echo \"OK Bypass Charging (input_suspend=\$actual)\""
        return result(shell.runRoot(script), "Falha ao alterar Bypass Charging.")
    }

    fun readMonitorSample(previousTicks: CpuStatTicks?): MonitorSample {
        if (!shell.rootCheck()) return MonitorSample(false, message = "Autorize o KLEE no KernelSU.")
        val script =
            "for d in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do [ -d \"\$d\" ] || continue; " +
            "id=\${d##*policy}; " +
            "printf 'CPU|%s|%s|%s|%s\\n' \"\$id\" \"\$(cat \"\$d/scaling_cur_freq\" 2>/dev/null)\" \"\$(cat \"\$d/cpuinfo_max_freq\" 2>/dev/null)\" \"\$(cat \"\$d/scaling_governor\" 2>/dev/null)\"; done; " +
            "for d in /sys/class/devfreq/13000000.mali /sys/class/devfreq/*mali* /sys/class/devfreq/*gpu*; do [ -d \"\$d\" ] || continue; " +
            "printf 'GPU|%s|%s\\n' \"\$(cat \"\$d/cur_freq\" 2>/dev/null)\" \"\$(cat \"\$d/governor\" 2>/dev/null)\"; break; done; " +
            "[ -r /sys/kernel/ged/hal/gpu_utilization ] && printf 'GPU_UTIL|%s\\n' \"\$(cat /sys/kernel/ged/hal/gpu_utilization 2>/dev/null)\"; " +
            "[ -r /sys/class/devfreq/mtk-dvfsrc-devfreq/cur_freq ] && printf 'DRAM|%s\\n' \"\$(cat /sys/class/devfreq/mtk-dvfsrc-devfreq/cur_freq 2>/dev/null)\"; " +
            "[ -r /sys/class/devfreq/112b0000.ufshci/cur_freq ] && printf 'UFS|%s\\n' \"\$(cat /sys/class/devfreq/112b0000.ufshci/cur_freq 2>/dev/null)\"; " +
            "printf 'LOAD|%s\\n' \"\$(cat /proc/loadavg 2>/dev/null)\"; " +
            "printf 'STAT|%s\\n' \"\$(head -n 1 /proc/stat 2>/dev/null)\"; " +
            "printf 'UPTIME|%s\\n' \"\$(cat /proc/uptime 2>/dev/null)\"; " +
            "printf 'BATTEMP|%s\\n' \"\$(cat /sys/class/power_supply/battery/temp 2>/dev/null)\"; " +
            "for d in /sys/class/thermal/thermal_zone[0-9]*; do [ -d \"\$d\" ] || continue; " +
            "t=\$(cat \"\$d/type\" 2>/dev/null); v=\$(cat \"\$d/temp\" 2>/dev/null); " +
            "[ -n \"\$t\" ] && [ -n \"\$v\" ] && printf 'TEMP|%s|%s\\n' \"\$t\" \"\$v\"; done; " +
            "awk '/^(MemTotal|MemAvailable|SwapTotal|SwapFree):/{printf \"MEM|%s|%s\\n\", \$1, \$2}' /proc/meminfo 2>/dev/null"

        val r = shell.runReadOnly(script)
        val clusters = mutableListOf<ClusterLiveMetric>()
        var gpuMHz = 0L
        var gpuUtil: Int? = null
        var gpuGov = ""
        var dramMHz = 0L
        var ufsMHz = 0L
        var loadAvg = ""
        var uptimeSec = 0L
        var statTicks: CpuStatTicks? = null
        var batTemp: Double? = null
        var exactSocMax: Double? = null
        val socTemps = mutableListOf<Double>()
        val allTemps = mutableListOf<Double>()
        var memTotalKb = 0L
        var memAvailKb = 0L
        var swapTotalKb = 0L
        var swapFreeKb = 0L

        r.stdout.forEach { line ->
            val p = line.split("|")
            when (p.firstOrNull()) {
                "CPU" -> {
                    val id = p.getOrNull(1)?.trim() ?: return@forEach
                    val cur = (p.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L) / 1000L
                    val max = (p.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L) / 1000L
                    val gov = p.getOrNull(4)?.trim() ?: ""
                    clusters.add(ClusterLiveMetric(id, cur, max, gov))
                }
                "GPU" -> {
                    gpuMHz = (p.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L) / 1_000_000L
                    gpuGov = p.getOrNull(2)?.trim() ?: ""
                }
                "GPU_UTIL" -> gpuUtil = parseFirstInt(p.getOrNull(1))
                "DRAM" -> dramMHz = (p.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L) / 1_000_000L
                "UFS" -> ufsMHz = (p.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L) / 1_000_000L
                "LOAD" -> loadAvg = p.getOrNull(1)?.trim()?.split(Regex("\\s+"))?.take(3)?.joinToString(" · ") ?: ""
                "UPTIME" -> uptimeSec = p.getOrNull(1)?.trim()?.substringBefore(" ")?.toDoubleOrNull()?.toLong() ?: 0L
                "STAT" -> {
                    val nums = p.getOrNull(1)?.trim()?.split(Regex("\\s+"))?.drop(1)?.mapNotNull(String::toLongOrNull)
                    if (nums != null && nums.size >= 4) {
                        val idle = nums[3] + (nums.getOrNull(4) ?: 0L)
                        val total = nums.sum()
                        statTicks = CpuStatTicks(total = total, idle = idle)
                    }
                }
                "BATTEMP" -> {
                    val raw = p.getOrNull(1)?.trim()?.toLongOrNull()
                    if (raw != null) {
                        batTemp = if (raw > 1000) raw / 1000.0 else if (raw > 100) raw / 10.0 else raw.toDouble()
                    }
                }
                "TEMP" -> {
                    val name = p.getOrNull(1)?.lowercase() ?: ""
                    val raw = p.getOrNull(2)?.trim()?.toLongOrNull() ?: return@forEach
                    val c = normalizeTemp(raw)
                    if (c in 5.0..120.0) {
                        allTemps.add(c)
                        if (name == "soc_max") exactSocMax = c
                        else if (name.contains("soc") || name.contains("cpu") || name.contains("mtktsap")) socTemps.add(c)
                    }
                }
                "MEM" -> {
                    val key = p.getOrNull(1)?.trim() ?: ""
                    val kb = p.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
                    when {
                        key.startsWith("MemTotal") -> memTotalKb = kb
                        key.startsWith("MemAvailable") -> memAvailKb = kb
                        key.startsWith("SwapTotal") -> swapTotalKb = kb
                        key.startsWith("SwapFree") -> swapFreeKb = kb
                    }
                }
            }
        }

        val cpuPct = if (previousTicks != null && statTicks != null) {
            val dTotal = statTicks!!.total - previousTicks.total
            val dIdle = statTicks!!.idle - previousTicks.idle
            if (dTotal > 0L) (((dTotal - dIdle).toFloat() / dTotal.toFloat()) * 100f).coerceIn(0f, 100f) else 0f
        } else 0f

        val sortedClusters = clusters.sortedBy { it.policyId.toIntOrNull() ?: Int.MAX_VALUE }
        return MonitorSample(
            root = true,
            cpuUsagePercent = cpuPct,
            peakCpuMHz = sortedClusters.maxOfOrNull { it.currentMHz } ?: 0L,
            gpuMHz = gpuMHz,
            gpuUtilization = gpuUtil,
            gpuGovernor = gpuGov,
            dramMHz = dramMHz,
            ufsMHz = ufsMHz,
            socTempC = exactSocMax ?: socTemps.maxOrNull() ?: allTemps.maxOrNull(),
            batteryTempC = batTemp,
            ramUsedMb = ((memTotalKb - memAvailKb).coerceAtLeast(0L)) / 1024L,
            ramTotalMb = memTotalKb / 1024L,
            swapUsedMb = ((swapTotalKb - swapFreeKb).coerceAtLeast(0L)) / 1024L,
            swapTotalMb = swapTotalKb / 1024L,
            clusters = sortedClusters,
            loadAvg = loadAvg,
            uptimeSeconds = uptimeSec,
            statTicks = statTicks,
            message = if (sortedClusters.isEmpty() && r.stdout.isEmpty()) "Não foi possível coletar métricas." else ""
        )
    }

    fun readTuning(): TuningSnapshot {
        if (!shell.rootCheck()) return TuningSnapshot(false, message = "Autorize o KLEE no KernelSU.")
        val script =
            "awk '/^(MemTotal|MemAvailable|SwapTotal|SwapFree):/{printf \"MEM|%s|%s\\n\", \$1, \$2}' /proc/meminfo 2>/dev/null; " +
            "printf 'ZRAM|%s|%s|%s\\n' \"\$(cat /sys/block/zram0/disksize 2>/dev/null)\" \"\$(cat /sys/block/zram0/comp_algorithm 2>/dev/null)\" \"\$(cat /sys/block/zram0/mm_stat 2>/dev/null)\"; " +
            "printf 'VM|%s|%s|%s|%s|%s|%s|%s|%s|%s\\n' " +
            "\"\$(cat /proc/sys/vm/swappiness 2>/dev/null)\" " +
            "\"\$(cat /proc/sys/vm/vfs_cache_pressure 2>/dev/null)\" " +
            "\"\$(cat /proc/sys/vm/dirty_ratio 2>/dev/null)\" " +
            "\"\$(cat /proc/sys/vm/dirty_background_ratio 2>/dev/null)\" " +
            "\"\$(cat /proc/sys/vm/watermark_scale_factor 2>/dev/null)\" " +
            "\"\$( [ -w /proc/sys/vm/swappiness ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w /proc/sys/vm/vfs_cache_pressure ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w /proc/sys/vm/dirty_ratio ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w /proc/sys/vm/watermark_scale_factor ] && echo 1 || echo 0 )\"; " +
            "if [ -r /sys/kernel/mm/lru_gen/enabled ]; then " +
            "printf 'MGLRU|%s|%s\\n' \"\$(cat /sys/kernel/mm/lru_gen/enabled 2>/dev/null)\" \"\$(cat /sys/kernel/mm/lru_gen/min_ttl_ms 2>/dev/null)\"; fi; " +
            "u=/sys/devices/platform/soc/112b0000.ufshci; if [ -d \"\$u\" ]; then " +
            "printf 'UFS|%s|%s|%s|%s|%s|%s\\n' " +
            "\"\$(cat /sys/class/devfreq/112b0000.ufshci/cur_freq 2>/dev/null)\" " +
            "\"\$(cat /sys/class/devfreq/112b0000.ufshci/max_freq 2>/dev/null)\" " +
            "\"\$(cat \"\$u/wb_on\" 2>/dev/null)\" " +
            "\"\$(cat \"\$u/clkscale_enable\" 2>/dev/null)\" " +
            "\"\$(cat \"\$u/clkgate_enable\" 2>/dev/null)\" " +
            "\"\$(cat \"\$u/auto_hibern8\" 2>/dev/null)\"; fi; " +
            "for b in sda sdb sdc mmcblk0; do d=/sys/block/\$b/queue; [ -d \"\$d\" ] || continue; " +
            "printf 'IO|%s|%s|%s|%s|%s|%s|%s\\n' \"\$b\" \"\$(cat \"\$d/scheduler\" 2>/dev/null)\" \"\$(cat \"\$d/read_ahead_kb\" 2>/dev/null)\" \"\$(cat \"\$d/iostats\" 2>/dev/null)\" " +
            "\"\$( [ -w \"\$d/scheduler\" ] && echo 1 || echo 0 )\" \"\$( [ -w \"\$d/read_ahead_kb\" ] && echo 1 || echo 0 )\" \"\$( [ -w \"\$d/iostats\" ] && echo 1 || echo 0 )\"; done; " +
            "printf 'TCP|%s|%s|%s|%s|%s|%s\\n' " +
            "\"\$(cat /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null)\" " +
            "\"\$(cat /proc/sys/net/ipv4/tcp_available_congestion_control 2>/dev/null)\" " +
            "\"\$(cat /proc/sys/net/ipv4/tcp_fastopen 2>/dev/null)\" " +
            "\"\$(cat /proc/sys/net/core/default_qdisc 2>/dev/null)\" " +
            "\"\$( [ -w /proc/sys/net/ipv4/tcp_congestion_control ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w /proc/sys/net/ipv4/tcp_fastopen ] && echo 1 || echo 0 )\""

        val r = shell.runReadOnly(script)
        var memTotalKb = 0L
        var memAvailKb = 0L
        var swapTotalKb = 0L
        var swapFreeKb = 0L
        var zramBytes = 0L
        var zramAlgo = ""
        var zramOrigMb = 0L
        var zramComprMb = 0L
        var zramRatio = 0.0
        var swappiness = 100
        var vfs = 100
        var dirty = 20
        var dirtyBg = 10
        var watermark = 80
        var wSwap = false
        var wVfs = false
        var wDirty = false
        var wWatermark = false
        var mglruAvail = false
        var mglruHex = ""
        var mglruActive = false
        var mglruTtl = 0
        var ufsInfo = UfsInfo()
        val ioDevices = mutableListOf<IoDevice>()
        var tcp: TcpCongestionInfo? = null

        r.stdout.forEach { line ->
            val p = line.split("|")
            when (p.firstOrNull()) {
                "MEM" -> {
                    val key = p.getOrNull(1)?.trim() ?: ""
                    val kb = p.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
                    when {
                        key.startsWith("MemTotal") -> memTotalKb = kb
                        key.startsWith("MemAvailable") -> memAvailKb = kb
                        key.startsWith("SwapTotal") -> swapTotalKb = kb
                        key.startsWith("SwapFree") -> swapFreeKb = kb
                    }
                }
                "ZRAM" -> {
                    zramBytes = p.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
                    val rawAlgo = p.getOrNull(2)?.trim() ?: ""
                    val activeMatch = Regex("\\[([^\\]]+)]").find(rawAlgo)
                    zramAlgo = activeMatch?.groupValues?.getOrNull(1) ?: rawAlgo.substringBefore(" ")
                    val stats = p.getOrNull(3)?.trim()?.split(Regex("\\s+"))?.mapNotNull(String::toLongOrNull) ?: emptyList()
                    if (stats.size >= 2 && stats[1] > 0) {
                        zramOrigMb = stats[0] / (1024L * 1024L)
                        zramComprMb = stats[1] / (1024L * 1024L)
                        zramRatio = stats[0].toDouble() / stats[1].toDouble()
                    }
                }
                "VM" -> {
                    swappiness = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 100
                    vfs = p.getOrNull(2)?.trim()?.toIntOrNull() ?: 100
                    dirty = p.getOrNull(3)?.trim()?.toIntOrNull() ?: 20
                    dirtyBg = p.getOrNull(4)?.trim()?.toIntOrNull() ?: 10
                    watermark = p.getOrNull(5)?.trim()?.toIntOrNull() ?: 80
                    wSwap = p.getOrNull(6)?.trim() == "1"
                    wVfs = p.getOrNull(7)?.trim() == "1"
                    wDirty = p.getOrNull(8)?.trim() == "1"
                    wWatermark = p.getOrNull(9)?.trim() == "1"
                }
                "MGLRU" -> {
                    mglruAvail = true
                    mglruHex = p.getOrNull(1)?.trim() ?: "0x0000"
                    val parsed = mglruHex.removePrefix("0x").toIntOrNull(16) ?: 0
                    mglruActive = parsed != 0
                    mglruTtl = p.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
                }
                "UFS" -> {
                    ufsInfo = UfsInfo(
                        available = true,
                        currentMHz = (p.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L) / 1_000_000L,
                        maxMHz = (p.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L) / 1_000_000L,
                        wbOn = parseBool01(p.getOrNull(3)),
                        clkscaleEnable = parseBool01(p.getOrNull(4)),
                        clkgateEnable = parseBool01(p.getOrNull(5)),
                        autoHibern8Us = p.getOrNull(6)?.trim()?.toLongOrNull()
                    )
                }
                "IO" -> {
                    val name = p.getOrNull(1)?.trim() ?: return@forEach
                    val rawSched = p.getOrNull(2)?.trim() ?: ""
                    val tokens = rawSched.split(Regex("\\s+")).filter(String::isNotBlank)
                    val active = tokens.firstOrNull { it.startsWith("[") && it.endsWith("]") }
                        ?.removeSurrounding("[", "]")
                        ?: tokens.firstOrNull() ?: "none"
                    val available = tokens.map { it.removeSurrounding("[", "]") }.distinct()
                    val ra = p.getOrNull(3)?.trim()?.toIntOrNull() ?: 128
                    val iostats = p.getOrNull(4)?.trim() != "0"
                    ioDevices.add(
                        IoDevice(
                            name = name,
                            currentScheduler = active,
                            availableSchedulers = available,
                            readAheadKb = ra,
                            ioStatsEnabled = iostats,
                            canWriteScheduler = p.getOrNull(5)?.trim() == "1",
                            canWriteReadAhead = p.getOrNull(6)?.trim() == "1",
                            canWriteIoStats = p.getOrNull(7)?.trim() == "1"
                        )
                    )
                }
                "TCP" -> {
                    val cur = p.getOrNull(1)?.trim() ?: ""
                    val avail = p.getOrNull(2)?.trim()?.split(Regex("\\s+"))?.filter(String::isNotBlank) ?: emptyList()
                    tcp = TcpCongestionInfo(
                        current = cur,
                        available = avail,
                        tcpFastOpen = p.getOrNull(3)?.trim()?.toIntOrNull() ?: 1,
                        defaultQdisc = p.getOrNull(4)?.trim().orEmpty(),
                        canWrite = p.getOrNull(5)?.trim() == "1",
                        canWriteFastOpen = p.getOrNull(6)?.trim() == "1"
                    )
                }
            }
        }

        val memInfo = MemoryInfo(
            totalMb = memTotalKb / 1024L,
            availableMb = memAvailKb / 1024L,
            usedMb = ((memTotalKb - memAvailKb).coerceAtLeast(0L)) / 1024L,
            swapTotalMb = swapTotalKb / 1024L,
            swapUsedMb = ((swapTotalKb - swapFreeKb).coerceAtLeast(0L)) / 1024L,
            zramDiskSizeMb = zramBytes / (1024L * 1024L),
            zramAlgo = zramAlgo.ifBlank { "N/D" },
            zramOrigMb = zramOrigMb,
            zramComprMb = zramComprMb,
            zramCompressionRatio = zramRatio,
            swappiness = swappiness,
            vfsCachePressure = vfs,
            dirtyRatio = dirty,
            dirtyBackgroundRatio = dirtyBg,
            watermarkScaleFactor = watermark,
            mglruEnabledHex = mglruHex,
            mglruActive = mglruActive,
            mglruAvailable = mglruAvail,
            mglruMinTtlMs = mglruTtl,
            canWriteSwappiness = wSwap,
            canWriteVfs = wVfs,
            canWriteDirty = wDirty,
            canWriteWatermark = wWatermark
        )

        return TuningSnapshot(
            root = true,
            memory = memInfo,
            ufs = ufsInfo,
            ioDevices = ioDevices,
            tcp = tcp
        )
    }

    fun applyMglru(enabled: Boolean): String {
        val target = if (enabled) "y" else "n"
        val script =
            "n=/sys/kernel/mm/lru_gen/enabled; [ -w \"\$n\" ] || { echo 'MGLRU_NOT_WRITABLE'; exit 2; }; " +
            "if ! printf '%s' '$target' > \"\$n\"; then echo 'MGLRU_WRITE_FAILED'; exit 3; fi; " +
            "echo \"OK MGLRU enabled=\$(cat \"\$n\" 2>/dev/null)\""
        return result(shell.runRoot(script), "Falha ao alterar MGLRU.")
    }

    fun applyUfsBool(key: String, enabled: Boolean): String {
        val allowed = mapOf(
            "wb_on" to "/sys/devices/platform/soc/112b0000.ufshci/wb_on",
            "clkscale_enable" to "/sys/devices/platform/soc/112b0000.ufshci/clkscale_enable",
            "clkgate_enable" to "/sys/devices/platform/soc/112b0000.ufshci/clkgate_enable"
        )
        val path = allowed[key] ?: error("Parâmetro UFS não permitido: $key")
        val v = if (enabled) "1" else "0"
        val script =
            "n='$path'; [ -w \"\$n\" ] || { echo 'UFS_NODE_NOT_WRITABLE'; exit 2; }; " +
            "if ! printf '%s' '$v' > \"\$n\"; then echo 'UFS_WRITE_FAILED'; exit 3; fi; " +
            "echo \"OK UFS $key=\$(cat \"\$n\" 2>/dev/null)\""
        return result(shell.runRoot(script), "Falha ao alterar UFS $key.")
    }

    fun applyVmParam(param: String, value: Int): String {
        val allowed = mapOf(
            "swappiness" to (0..200),
            "vfs_cache_pressure" to (10..500),
            "dirty_ratio" to (5..80),
            "dirty_background_ratio" to (1..50),
            "watermark_scale_factor" to (10..500)
        )
        val range = allowed[param] ?: error("Parâmetro VM não permitido: $param")
        require(value in range) { "Valor fora da faixa segura (${range.first}..${range.last})." }
        val node = "/proc/sys/vm/$param"
        val script =
            "n='$node'; [ -w \"\$n\" ] || { echo 'VM_NODE_NOT_WRITABLE'; exit 2; }; " +
            "old=\$(cat \"\$n\" 2>/dev/null); " +
            "if ! printf '%s' '$value' > \"\$n\"; then echo 'VM_WRITE_FAILED'; exit 3; fi; " +
            "actual=\$(cat \"\$n\" 2>/dev/null); " +
            "[ \"\$actual\" = '$value' ] || { [ -z \"\$old\" ] || printf '%s' \"\$old\" > \"\$n\" 2>/dev/null; echo \"VM_READBACK_MISMATCH actual=\$actual\"; exit 4; }; " +
            "echo \"OK vm.$param=\$actual\""
        return result(shell.runRoot(script), "Falha ao aplicar vm.$param.")
    }

    fun applyIoScheduler(device: String, scheduler: String?, readAheadKb: Int?, ioStats: Boolean? = null): String {
        require(device.matches(Regex("^(sd[a-z]|mmcblk[0-9]+|dm-[0-9]+)$"))) { "Dispositivo de bloco inválido." }
        require(scheduler == null || scheduler.matches(Regex("^[A-Za-z0-9_-]+$"))) { "Scheduler inválido." }
        require(readAheadKb == null || readAheadKb in 64..4096) { "Read-ahead fora da faixa segura (64..4096 KB)." }
        val d = "/sys/block/$device/queue"
        val schedPart = if (scheduler == null) "" else
            "if [ -w \"\$d/scheduler\" ]; then " +
            "if ! printf '%s' '$scheduler' > \"\$d/scheduler\"; then echo 'IO_SCHED_WRITE_FAILED'; exit 3; fi; " +
            "cur=\$(cat \"\$d/scheduler\" 2>/dev/null); " +
            "case \"\$cur\" in *'[$scheduler]'*) ;; *) echo \"IO_SCHED_READBACK_MISMATCH \$cur\"; exit 4;; esac; fi; "
        val raPart = if (readAheadKb == null) "" else
            "if [ -w \"\$d/read_ahead_kb\" ]; then " +
            "if ! printf '%s' '$readAheadKb' > \"\$d/read_ahead_kb\"; then echo 'IO_RA_WRITE_FAILED'; exit 5; fi; " +
            "actualra=\$(cat \"\$d/read_ahead_kb\" 2>/dev/null); " +
            "[ \"\$actualra\" = '$readAheadKb' ] || { echo \"IO_RA_READBACK_MISMATCH \$actualra\"; exit 6; }; fi; "
        val statsPart = if (ioStats == null) "" else {
            val sv = if (ioStats) "1" else "0"
            "if [ -w \"\$d/iostats\" ]; then printf '%s' '$sv' > \"\$d/iostats\" || { echo 'IO_STATS_WRITE_FAILED'; exit 7; }; fi; "
        }
        val script =
            "d='$d'; [ -d \"\$d\" ] || { echo 'IO_DEVICE_NOT_FOUND'; exit 2; }; " +
            schedPart + raPart + statsPart +
            "echo \"OK I/O $device sched=\$(cat \"\$d/scheduler\" 2>/dev/null) ra=\$(cat \"\$d/read_ahead_kb\" 2>/dev/null)KB iostats=\$(cat \"\$d/iostats\" 2>/dev/null)\""
        return result(shell.runRoot(script), "Falha ao aplicar I/O em $device.")
    }

    fun applyTcpCongestion(algorithm: String): String {
        require(algorithm.matches(Regex("^[A-Za-z0-9_-]+$"))) { "Algoritmo TCP inválido." }
        val script =
            "n=/proc/sys/net/ipv4/tcp_congestion_control; [ -w \"\$n\" ] || { echo 'TCP_NODE_NOT_WRITABLE'; exit 2; }; " +
            "avail=\$(cat /proc/sys/net/ipv4/tcp_available_congestion_control 2>/dev/null); " +
            "case \" \$avail \" in *' $algorithm '*) ;; *) echo 'TCP_ALGO_UNAVAILABLE'; exit 3;; esac; " +
            "old=\$(cat \"\$n\" 2>/dev/null); " +
            "if ! printf '%s' '$algorithm' > \"\$n\"; then echo 'TCP_WRITE_FAILED'; exit 4; fi; " +
            "actual=\$(cat \"\$n\" 2>/dev/null); " +
            "[ \"\$actual\" = '$algorithm' ] || { [ -z \"\$old\" ] || printf '%s' \"\$old\" > \"\$n\" 2>/dev/null; echo \"TCP_READBACK_MISMATCH \$actual\"; exit 5; }; " +
            "echo \"OK TCP=\$actual\""
        return result(shell.runRoot(script), "Falha ao alterar controle TCP.")
    }

    fun applyTcpFastOpen(value: Int): String {
        require(value in setOf(0, 1, 3)) { "Valor tcp_fastopen inválido (use 0, 1 ou 3)." }
        val script =
            "n=/proc/sys/net/ipv4/tcp_fastopen; [ -w \"\$n\" ] || { echo 'TCP_FO_NOT_WRITABLE'; exit 2; }; " +
            "if ! printf '%s' '$value' > \"\$n\"; then echo 'TCP_FO_WRITE_FAILED'; exit 3; fi; " +
            "echo \"OK tcp_fastopen=\$(cat \"\$n\" 2>/dev/null)\""
        return result(shell.runRoot(script), "Falha ao alterar TCP Fast Open.")
    }

    fun dropCaches(): String {
        val script = "sync && printf '3' > /proc/sys/vm/drop_caches && echo 'OK Caches liberados com sucesso'"
        return result(shell.runRoot(script), "Falha ao liberar caches.")
    }

    fun readDeviceInfo(): DeviceInfoSnapshot {
        if (!shell.rootCheck()) return DeviceInfoSnapshot(false, message = "Autorize o KLEE no KernelSU.")
        val script =
            "printf 'MODEL|%s\\n' \"\$(getprop ro.product.model 2>/dev/null)\"; " +
            "printf 'DEVICE|%s\\n' \"\$(getprop ro.product.device 2>/dev/null)\"; " +
            "printf 'BRAND|%s\\n' \"\$(getprop ro.product.brand 2>/dev/null)\"; " +
            "printf 'SOC|%s\\n' \"\$(getprop ro.soc.model 2>/dev/null)\"; " +
            "printf 'HW|%s\\n' \"\$(getprop ro.board.platform 2>/dev/null)\"; " +
            "printf 'HYPEROS|%s\\n' \"\$(getprop ro.mi.os.version.name 2>/dev/null)\"; " +
            "printf 'KERNEL|%s\\n' \"\$(uname -a 2>/dev/null)\"; " +
            "printf 'ANDROID|%s\\n' \"\$(getprop ro.build.version.release 2>/dev/null)\"; " +
            "printf 'SDK|%s\\n' \"\$(getprop ro.build.version.sdk 2>/dev/null)\"; " +
            "printf 'PATCH|%s\\n' \"\$(getprop ro.build.version.security_patch 2>/dev/null)\"; " +
            "printf 'SELINUX|%s\\n' \"\$(getenforce 2>/dev/null)\"; " +
            "printf 'ROOT|%s\\n' \"\$(su -v 2>/dev/null | head -n 1)\"; " +
            "printf 'ABI|%s\\n' \"\$(getprop ro.product.cpu.abi 2>/dev/null)\"; " +
            "printf 'UPTIME|%s\\n' \"\$(cat /proc/uptime 2>/dev/null)\"; " +
            "cores=0; for c in /sys/devices/system/cpu/cpu[0-9]*; do [ -d \"\$c\" ] && cores=\$((cores+1)); done; printf 'CORES|%s\\n' \"\$cores\"; " +
            "awk '/^MemTotal:/{printf \"MEM|%s\\n\", \$2}' /proc/meminfo 2>/dev/null"

        val r = shell.runReadOnly(script)
        val map = mutableMapOf<String, String>()
        r.stdout.forEach { line ->
            val idx = line.indexOf('|')
            if (idx > 0) map[line.substring(0, idx)] = line.substring(idx + 1).trim()
        }

        return DeviceInfoSnapshot(
            root = true,
            model = map["MODEL"].orEmpty().ifBlank { "Desconhecido" },
            device = map["DEVICE"].orEmpty().ifBlank { "klee" },
            brand = map["BRAND"].orEmpty(),
            soc = map["SOC"].orEmpty().ifBlank { map["HW"].orEmpty().uppercase().ifBlank { "MT6899" } },
            hardware = map["HW"].orEmpty(),
            hyperOsVersion = map["HYPEROS"].orEmpty(),
            kernelVersion = map["KERNEL"].orEmpty(),
            androidVersion = map["ANDROID"].orEmpty(),
            sdkInt = map["SDK"].orEmpty(),
            securityPatch = map["PATCH"].orEmpty(),
            selinuxMode = map["SELINUX"].orEmpty().ifBlank { "Desconhecido" },
            rootProvider = map["ROOT"].orEmpty().ifBlank { "Root (uid=0)" },
            abi = map["ABI"].orEmpty(),
            uptimeSeconds = map["UPTIME"]?.substringBefore(" ")?.toDoubleOrNull()?.toLong() ?: 0L,
            cpuCores = map["CORES"]?.toIntOrNull() ?: 8,
            totalRamMb = (map["MEM"]?.toLongOrNull() ?: 0L) / 1024L
        )
    }

    fun buildDiagnosticReport(): String {
        val dev = readDeviceInfo()
        val cpu = readCpu()
        val gpu = readGpu()
        val mtkPerf = readMtkPerf()
        val thermal = readThermalDetailed()
        val power = readPowerDetailed()
        val tuning = readTuning()

        return buildString {
            appendLine("=== KLEE Kernel Manager v0.5.1 - Relatório de Diagnóstico ===")
            appendLine("Modelo: ${dev.brand} ${dev.model} (${dev.device}) · HyperOS: ${dev.hyperOsVersion}")
            appendLine("SoC / Plataforma: ${dev.soc} / ${dev.hardware}")
            appendLine("Kernel: ${dev.kernelVersion}")
            appendLine("Android: ${dev.androidVersion} (SDK ${dev.sdkInt}) · Patch: ${dev.securityPatch}")
            appendLine("SELinux: ${dev.selinuxMode} · Root: ${dev.rootProvider}")
            appendLine("CPU Cores: ${dev.cpuCores} · RAM Total: ${dev.totalRamMb} MB")
            appendLine()
            appendLine("--- CPU Policies (${cpu.cpuPolicies.size}) ---")
            cpu.cpuPolicies.forEach { p ->
                appendLine(
                    "Policy ${p.id} [CPUs ${p.cpus}] cur=${p.currentKHz / 1000}MHz " +
                    "limits=${p.minKHz / 1000}-${p.maxKHz / 1000}MHz hw=${p.hardwareMinKHz / 1000}-${p.hardwareMaxKHz / 1000}MHz " +
                    "gov=${p.governor} driver=${p.driver}"
                )
            }
            cpu.uclamp?.let { u ->
                appendLine("UCLAMP top-app: min=${u.minPercent}% max=${u.maxPercent}% latency_sensitive=${u.latencySensitive}")
            }
            appendLine()
            appendLine("--- GPU, GED & DRAM ---")
            gpu.gpu?.let { g ->
                appendLine("${g.name} (${g.path}): cur=${g.currentHz / 1_000_000}MHz limits=${g.minHz / 1_000_000}-${g.maxHz / 1_000_000}MHz gov=${g.governor}")
            }
            if (gpu.ged.available) {
                appendLine("GED: util=${gpu.ged.gpuUtilization}% boost=${gpu.ged.gpuBoostLevel} margin=${gpu.ged.dvfsMarginValue} dcs=${gpu.ged.dcsMode} smart_boost=${gpu.ged.gedSmartBoost}")
            }
            gpu.dram?.let { dr ->
                appendLine("DRAM (dvfsrc): cur=${dr.currentHz / 1_000_000}MHz limits=${dr.minHz / 1_000_000}-${dr.maxHz / 1_000_000}MHz")
            }
            appendLine()
            appendLine("--- FPSGO & Xiaomi Metis ---")
            appendLine("FPSGO: enable=${mtkPerf.fpsgoEnable} boost_ta=${mtkPerf.boostTa} rescue=${mtkPerf.rescueEnable} ultra=${mtkPerf.ultraRescue}")
            appendLine("Metis: fboost=${mtkPerf.miFboostEnable} speed_touch=${mtkPerf.speedTouchEnable} vip_link=${mtkPerf.vipLinkEnable} perf=${mtkPerf.inPerfMod}")
            appendLine()
            appendLine("--- Thermal (Top 10 ativos) ---")
            thermal.zones.filter { it.tempCelsius > 0.5 }.take(10).forEach { z ->
                appendLine("  ${z.name}: ${"%.1f".format(z.tempCelsius)} °C")
            }
            appendLine()
            appendLine("--- Power & Bypass ---")
            appendLine(
                "Bateria: ${power.capacity}% (${power.status}) · ${"%.3f".format(power.voltageVolts)}V · " +
                "${"%.0f".format(power.currentMa)}mA · ${"%.2f".format(power.powerWatts)}W · ${"%.1f".format(power.tempCelsius)}°C · " +
                "Bypass=${power.inputSuspend}"
            )
            appendLine()
            appendLine("--- Memória, UFS & Rede ---")
            tuning.memory?.let { m ->
                appendLine("RAM: ${m.usedMb}/${m.totalMb} MB · ZRAM: ${m.zramDiskSizeMb} MB (${m.zramAlgo}, ${"%.2f".format(m.zramCompressionRatio)}x) · MGLRU=${m.mglruEnabledHex}")
                appendLine("VM: swappiness=${m.swappiness} vfs=${m.vfsCachePressure} dirty=${m.dirtyRatio} watermark=${m.watermarkScaleFactor}")
            }
            if (tuning.ufs.available) {
                appendLine("UFS 4.0: ${tuning.ufs.currentMHz}/${tuning.ufs.maxMHz}MHz wb_on=${tuning.ufs.wbOn} clkscale=${tuning.ufs.clkscaleEnable} clkgate=${tuning.ufs.clkgateEnable}")
            }
            tuning.ioDevices.forEach { io ->
                appendLine("I/O ${io.name}: sched=${io.currentScheduler} ra=${io.readAheadKb}KB iostats=${io.ioStatsEnabled}")
            }
            tuning.tcp?.let { t ->
                appendLine("TCP: ${t.current} [${t.available.joinToString()}] fastopen=${t.tcpFastOpen} qdisc=${t.defaultQdisc}")
            }
        }
    }

    fun readRefreshRateSettings(currentDisplayHz: Int, supportedHz: List<Int>): DisplayRefreshInfo {
        if (!shell.rootCheck()) {
            return DisplayRefreshInfo(currentHz = currentDisplayHz, supportedHz = supportedHz)
        }
        val script =
            "printf 'MIN|%s\\n' \"\$(settings get system min_refresh_rate 2>/dev/null)\"; " +
            "printf 'PEAK|%s\\n' \"\$(settings get system peak_refresh_rate 2>/dev/null)\"; " +
            "printf 'USER|%s\\n' \"\$(settings get system user_refresh_rate 2>/dev/null)\"; " +
            "printf 'MIUI|%s\\n' \"\$(settings get secure miui_refresh_rate 2>/dev/null)\""
        val r = shell.runReadOnly(script)
        val map = mutableMapOf<String, String>()
        r.stdout.forEach { line ->
            val p = line.split("|")
            if (p.size >= 2) {
                val v = p[1].trim()
                if (v.isNotBlank() && v != "null") map[p[0]] = v
            }
        }
        return DisplayRefreshInfo(
            currentHz = currentDisplayHz,
            supportedHz = supportedHz.distinct().sorted().ifEmpty { listOf(60, 90, 120) },
            minSetting = map["MIN"].orEmpty(),
            peakSetting = map["PEAK"].orEmpty(),
            userSetting = map["USER"].orEmpty(),
            miuiSetting = map["MIUI"].orEmpty()
        )
    }

    fun applyRefreshRate(targetHz: Int, maxSupportedHz: Int = 120): String {
        require(targetHz == 0 || targetHz in 30..165) { "Taxa de atualização inválida: $targetHz Hz" }
        val script = if (targetHz == 0) {
            "settings delete system min_refresh_rate 2>/dev/null; " +
            "settings delete system peak_refresh_rate 2>/dev/null; " +
            "settings put system user_refresh_rate $maxSupportedHz 2>/dev/null; " +
            "settings put secure miui_refresh_rate $maxSupportedHz 2>/dev/null; " +
            "echo 'OK Taxa de atualização: Automática (até ${maxSupportedHz} Hz)'"
        } else {
            val hzFloat = "$targetHz.0"
            "settings put system min_refresh_rate $hzFloat && " +
            "settings put system peak_refresh_rate $hzFloat && " +
            "settings put system user_refresh_rate $targetHz; " +
            "settings put secure miui_refresh_rate $targetHz 2>/dev/null; " +
            "echo \"OK Taxa de atualização fixada em $targetHz Hz (min=\$(settings get system min_refresh_rate 2>/dev/null) peak=\$(settings get system peak_refresh_rate 2>/dev/null))\""
        }
        return result(shell.runRoot(script), "Falha ao alterar taxa de atualização.")
    }

    fun readForegroundPackage(): String? {
        if (!shell.rootCheck()) return null
        val script =
            "out=\$(dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity'); " +
            "if [ -z \"\$out\" ]; then out=\$(dumpsys window displays 2>/dev/null | grep -m1 'mCurrentFocus'); fi; " +
            "printf '%s\\n' \"\$out\""
        val r = shell.runReadOnly(script)
        val line = r.stdout.firstOrNull { it.isNotBlank() } ?: return null
        // Extract package name before '/' in component string like "com.pkg.name/.MainActivity"
        val match = Regex("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)/").find(line)
        return match?.groupValues?.getOrNull(1)
    }

    fun readPowerWattsQuick(): Pair<Double, Double> {
        val script =
            "d=/sys/class/power_supply/battery; " +
            "printf '%s|%s\\n' \"\$(cat \"\$d/voltage_now\" 2>/dev/null)\" \"\$(cat \"\$d/current_now\" 2>/dev/null)\""
        val r = shell.runReadOnly(script)
        val p = r.stdout.firstOrNull()?.split("|") ?: return 0.0 to 0.0
        val rawV = p.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
        val rawI = p.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
        val v = when {
            rawV > 100_000 -> rawV / 1_000_000.0
            rawV > 100 -> rawV / 1_000.0
            else -> rawV.toDouble()
        }
        val ma = if (abs(rawI) > 25_000) rawI / 1_000.0 else rawI.toDouble()
        return abs(v * (ma / 1_000.0)) to ma
    }

    fun buildFullLogBundle(
        actionLogs: List<ActionLogEntry>,
        perAppRules: List<PerAppRule>,
        includeDmesg: Boolean
    ): String {
        val baseReport = buildDiagnosticReport()
        val pid = android.os.Process.myPid()
        val logcat = shell.runReadOnly("logcat -d -t 200 --pid=$pid 2>/dev/null").stdout
        val dmesg = if (includeDmesg) {
            shell.runReadOnly("dmesg 2>/dev/null | tail -n 120").stdout
        } else emptyList()
        val refreshSettings = shell.runReadOnly(
            "printf 'min=%s peak=%s user=%s miui=%s\\n' " +
            "\"\$(settings get system min_refresh_rate 2>/dev/null)\" " +
            "\"\$(settings get system peak_refresh_rate 2>/dev/null)\" " +
            "\"\$(settings get system user_refresh_rate 2>/dev/null)\" " +
            "\"\$(settings get secure miui_refresh_rate 2>/dev/null)\""
        ).stdout.joinToString(" ")

        return buildString {
            appendLine(baseReport)
            appendLine()
            appendLine("--- Display / Refresh Rate Settings ---")
            appendLine(refreshSettings.ifBlank { "Indisponível" })
            appendLine()
            appendLine("--- Regras Per-App configuradas (${perAppRules.size}) ---")
            if (perAppRules.isEmpty()) {
                appendLine("Nenhuma regra configurada.")
            } else {
                perAppRules.forEach { rule ->
                    appendLine("  ${rule.appLabel} (${rule.packageName}): perfil=${rule.profileId}, mtk=${rule.mtkPerfPreset}, hz=${rule.refreshRateHz}, bypass=${rule.enableBypassCharging}, vulkan=${rule.vulkanDriverId}, forceAngle=${rule.forceVulkanAngle}")
                }
            }
            val vkInfo = inspectSystemVulkan()
            appendLine()
            appendLine("--- Sistema Vulkan & ANGLE ---")
            appendLine("Vendor HW Dir: ${vkInfo.vendorHwDir} · Vulkan SO: ${vkInfo.vulkanFullPath} (symlink=${vkInfo.isSymlink} -> ${vkInfo.symlinkTarget})")
            appendLine("ANGLE disponível: ${vkInfo.angleAvailable} · Overlay montado: ${vkInfo.overlayMounted} (driver=${vkInfo.activeDriverId}, app=${vkInfo.activePackage})")
            val anglePkgs = shell.runReadOnly("printf 'pkgs=%s | vals=%s\\n' \"\$(settings get global angle_gl_driver_selection_pkgs 2>/dev/null)\" \"\$(settings get global angle_gl_driver_selection_values 2>/dev/null)\"").stdout.joinToString(" ")
            appendLine("ANGLE settings: $anglePkgs")
            appendLine()
            appendLine("--- Histórico de Ações Root KLEE (${actionLogs.size}) ---")
            if (actionLogs.isEmpty()) {
                appendLine("Nenhuma ação registrada nesta sessão.")
            } else {
                actionLogs.forEach { log ->
                    val tag = if (log.isError) "ERR" else "OK "
                    appendLine("[$tag ${log.timestamp}] ${log.action} => ${log.detail}")
                }
            }
            appendLine()
            appendLine("--- App Logcat (PID $pid, últimas ${logcat.size} linhas) ---")
            if (logcat.isEmpty()) appendLine("(vazio)") else logcat.forEach { appendLine(it) }
            if (includeDmesg) {
                appendLine()
                appendLine("--- Kernel dmesg (últimas ${dmesg.size} linhas) ---")
                if (dmesg.isEmpty()) appendLine("(indisponível)") else dmesg.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("=== FIM DO LOG KLEE v0.5.1 ===")
        }
    }

    fun inspectSystemVulkan(): SystemVulkanInfo {
        if (!shell.rootCheck()) {
            return SystemVulkanInfo(root = false, message = "Autorize o KLEE no KernelSU.")
        }
        val script =
            "hwdir=''; vkfile=''; vkpath=''; " +
            "for dir in /vendor/lib64/hw /odm/lib64/hw /system/vendor/lib64/hw; do " +
            "  [ -d \"\$dir\" ] || continue; " +
            "  for f in \"\$dir\"/vulkan.*.so; do " +
            "    [ -e \"\$f\" ] || continue; " +
            "    hwdir=\"\$dir\"; vkfile=\$(basename \"\$f\"); vkpath=\"\$f\"; break 2; " +
            "  done; " +
            "done; " +
            "issym=0; symtgt=''; realvk=\"\$vkpath\"; " +
            "if [ -L \"\$vkpath\" ]; then issym=1; symtgt=\$(readlink -f \"\$vkpath\" 2>/dev/null); [ -n \"\$symtgt\" ] && realvk=\"\$symtgt\"; fi; " +
            "hasangle=0; " +
            "if pm list packages 2>/dev/null | grep -qi angle || [ -e /system/lib64/libEGL_angle.so ] || [ -e /vendor/lib64/egl/libEGL_angle.so ] || [ -e /apex/com.android.angle/lib64/libEGL_angle.so ]; then hasangle=1; fi; " +
            "mounted=0; " +
            "if [ -n \"\$realvk\" ] && grep -F \" \$realvk \" /proc/1/mountinfo 2>/dev/null | grep -q . || grep -q 'klee_hw_overlay' /proc/1/mountinfo 2>/dev/null || [ -f /data/adb/klee_drivers/active_state.txt ]; then mounted=1; fi; " +
            "state=\$(cat /data/adb/klee_drivers/active_state.txt 2>/dev/null); " +
            "printf 'VK|%s|%s|%s|%s|%s|%s|%s|%s\\n' \"\$hwdir\" \"\$vkfile\" \"\$vkpath\" \"\$issym\" \"\$symtgt\" \"\$hasangle\" \"\$mounted\" \"\$state\""

        val r = shell.runReadOnly(script)
        val p = r.stdout.firstOrNull { it.startsWith("VK|") }?.split("|")
            ?: return SystemVulkanInfo(root = true, message = "Não foi possível inspecionar Vulkan do sistema.")
        val stateParts = p.getOrNull(8).orEmpty().split(":")
        return SystemVulkanInfo(
            root = true,
            vendorHwDir = p.getOrNull(1).orEmpty().ifBlank { "/vendor/lib64/hw" },
            vulkanFileName = p.getOrNull(2).orEmpty(),
            vulkanFullPath = p.getOrNull(3).orEmpty(),
            isSymlink = p.getOrNull(4) == "1",
            symlinkTarget = p.getOrNull(5).orEmpty(),
            angleAvailable = p.getOrNull(6) == "1",
            overlayMounted = p.getOrNull(7) == "1",
            activeDriverId = stateParts.getOrNull(0).orEmpty(),
            activePackage = stateParts.getOrNull(1).orEmpty(),
            message = if (p.getOrNull(2).isNullOrBlank()) "Nenhum vulkan.*.so encontrado em /vendor/lib64/hw." else ""
        )
    }

    fun mountCustomVulkanDriver(driver: VulkanDriverPackage, targetPkg: String): String {
        require(driver.installDir.matches(Regex("^/data/adb/klee_drivers/drv_[a-f0-9-]+$"))) { "Diretório de driver inválido." }
        require(driver.primaryLibName.matches(Regex("^[A-Za-z0-9._-]+\\.so$"))) { "Biblioteca principal inválida." }
        require(targetPkg.matches(Regex("^[A-Za-z0-9._-]+$"))) { "Pacote alvo inválido." }

        val srcLib = "${driver.installDir}/${driver.primaryLibName}"
        val script =
            "( " +
            "NS=''; if command -v nsenter >/dev/null 2>&1 && nsenter --mount=/proc/1/ns/mnt true 2>/dev/null; then NS='nsenter --mount=/proc/1/ns/mnt --'; fi; " +
            "[ -f '$srcLib' ] || { echo 'DRIVER_SO_MISSING: $srcLib'; exit 2; }; " +
            "vkpath=''; " +
            "for dir in /vendor/lib64/hw /odm/lib64/hw /system/vendor/lib64/hw; do " +
            "  [ -d \"\$dir\" ] || continue; " +
            "  for f in \"\$dir\"/vulkan.*.so; do " +
            "    [ -e \"\$f\" ] || continue; " +
            "    vkpath=\"\$f\"; break 2; " +
            "  done; " +
            "done; " +
            "[ -n \"\$vkpath\" ] || { echo 'SYSTEM_VULKAN_HAL_NOT_FOUND'; exit 3; }; " +
            "realvk=\$(readlink -f \"\$vkpath\" 2>/dev/null); [ -n \"\$realvk\" ] || realvk=\"\$vkpath\"; " +
            "case \"\$realvk\" in *libGLES_mali*) echo 'ABORT_GLES_SYMLINK: alvo aponta para libGLES_mali.so'; exit 4;; esac; " +
            "\$NS umount -l \"\$realvk\" 2>/dev/null || true; " +
            "chmod 755 /data/adb /data/adb/klee_drivers '${driver.installDir}' 2>/dev/null || true; " +
            "chmod 644 '$srcLib' || { echo 'CHMOD_FAILED'; exit 5; }; " +
            "chcon --reference=\"\$realvk\" '$srcLib' 2>/dev/null || chcon u:object_r:same_process_hal_file:s0 '$srcLib' 2>/dev/null || chcon u:object_r:vendor_file:s0 '$srcLib' 2>/dev/null || true; " +
            "merr=\$(\$NS mount --bind '$srcLib' \"\$realvk\" 2>&1); rc=\$?; " +
            "if [ \"\$rc\" != 0 ]; then merr=\$(mount --bind '$srcLib' \"\$realvk\" 2>&1); rc=\$?; fi; " +
            "if [ \"\$rc\" != 0 ]; then echo \"MOUNT_BIND_FAILED target=\$realvk err=\$merr\"; exit 6; fi; " +
            "mkdir -p /data/adb/klee_drivers; " +
            "printf '%s:%s:%s' '${driver.id}' '$targetPkg' \"\$realvk\" > /data/adb/klee_drivers/active_state.txt; " +
            "ctx=\$(ls -Z \"\$realvk\" 2>/dev/null | head -n 1); " +
            "echo \"OK Vulkan montado (global=\$([ -n \"\$NS\" ] && echo sim || echo master)): \$realvk -> ${driver.primaryLibName} [\$ctx]\" " +
            ")"
        return result(shell.runRoot(script), "Falha ao montar driver Vulkan customizado.")
    }

    fun unmountCustomVulkanDriver(): String {
        val script =
            "( " +
            "NS=''; if command -v nsenter >/dev/null 2>&1 && nsenter --mount=/proc/1/ns/mnt true 2>/dev/null; then NS='nsenter --mount=/proc/1/ns/mnt --'; fi; " +
            "saved=\$(cat /data/adb/klee_drivers/active_state.txt 2>/dev/null | cut -d: -f3); " +
            "if [ -n \"\$saved\" ]; then \$NS umount -l \"\$saved\" 2>/dev/null || umount -l \"\$saved\" 2>/dev/null || true; fi; " +
            "for dir in /vendor/lib64/hw /odm/lib64/hw /system/vendor/lib64/hw; do " +
            "  for f in \"\$dir\"/vulkan.*.so; do " +
            "    [ -e \"\$f\" ] || continue; " +
            "    rv=\$(readlink -f \"\$f\" 2>/dev/null); " +
            "    [ -n \"\$rv\" ] && { \$NS umount -l \"\$rv\" 2>/dev/null || umount -l \"\$rv\" 2>/dev/null || true; }; " +
            "  done; " +
            "  if grep -q 'klee_hw_overlay' /proc/1/mountinfo 2>/dev/null; then \$NS umount -l \"\$dir\" 2>/dev/null || true; fi; " +
            "done; " +
            "rm -rf /dev/klee_hw_overlay /dev/klee_orig_hw /data/adb/klee_drivers/active_state.txt; " +
            "echo 'OK Driver Vulkan padrão do sistema restaurado' " +
            ")"
        return result(shell.runRoot(script), "Falha ao desmontar driver Vulkan.")
    }

    fun applyForceVulkanAngle(packageName: String, enable: Boolean): String {
        require(packageName.matches(Regex("^[A-Za-z0-9._-]+$"))) { "Pacote inválido." }
        val curPkgsRaw = shell.runReadOnly("settings get global angle_gl_driver_selection_pkgs 2>/dev/null").stdout.firstOrNull().orEmpty().trim()
        val curValsRaw = shell.runReadOnly("settings get global angle_gl_driver_selection_values 2>/dev/null").stdout.firstOrNull().orEmpty().trim()

        val pkgs = if (curPkgsRaw.isBlank() || curPkgsRaw == "null") mutableListOf() else curPkgsRaw.split(",").filter { it.isNotBlank() }.toMutableList()
        val vals = if (curValsRaw.isBlank() || curValsRaw == "null") mutableListOf() else curValsRaw.split(",").filter { it.isNotBlank() }.toMutableList()

        val map = linkedMapOf<String, String>()
        for (i in pkgs.indices) {
            map[pkgs[i]] = vals.getOrElse(i) { "default" }
        }

        if (enable) {
            map[packageName] = "angle"
        } else {
            map.remove(packageName)
        }

        val cmd = if (map.isEmpty()) {
            "settings delete global angle_gl_driver_selection_pkgs 2>/dev/null; " +
            "settings delete global angle_gl_driver_selection_values 2>/dev/null; " +
            "echo 'OK ANGLE removido para $packageName'"
        } else {
            val newPkgs = map.keys.joinToString(",")
            val newVals = map.values.joinToString(",")
            val gameModeCmd = if (enable) "cmd game mode performance '$packageName' >/dev/null 2>&1 || true; " else ""
            "settings put global angle_gl_driver_selection_pkgs '$newPkgs' && " +
            "settings put global angle_gl_driver_selection_values '$newVals' && " +
            gameModeCmd +
            "echo 'OK Forçar Vulkan (ANGLE) ${if (enable) "ATIVADO" else "DESATIVADO"} para $packageName'"
        }
        return result(shell.runRoot(cmd), "Falha ao configurar ANGLE para $packageName.")
    }

    fun launchAppClean(packageName: String): String {
        require(packageName.matches(Regex("^[A-Za-z0-9._-]+$"))) { "Pacote inválido." }
        val script =
            "am force-stop '$packageName' 2>/dev/null || true; " +
            "sleep 0.35; " +
            "monkey -p '$packageName' -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || { echo 'APP_LAUNCH_FAILED'; exit 2; }; " +
            "echo 'OK App $packageName iniciado com driver customizado'"
        return result(shell.runRoot(script), "Falha ao iniciar $packageName.")
    }

    private fun normalizeTemp(raw: Long): Double = when {
        abs(raw) > 1000L -> raw / 1000.0
        abs(raw) > 150L -> raw / 10.0
        else -> raw.toDouble()
    }

    private fun parseFirstInt(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return Regex("-?[0-9]+").find(raw)?.value?.toIntOrNull()
    }

    private fun parseBool01(raw: String?): Boolean? {
        val t = raw?.trim()?.lowercase() ?: return null
        return when {
            t == "1" || t == "y" || t == "true" || t.startsWith("1 ") -> true
            t == "0" || t == "n" || t == "false" || t.startsWith("0 ") -> false
            else -> null
        }
    }
}
