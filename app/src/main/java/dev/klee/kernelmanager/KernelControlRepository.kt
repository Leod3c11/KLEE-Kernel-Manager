package dev.klee.kernelmanager

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
            "printf 'BATT|%s|%s\\n' \"\$(cat /sys/class/power_supply/battery/capacity 2>/dev/null)\" \"\$(cat /sys/class/power_supply/battery/status 2>/dev/null)\"; " +
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
        var battPct = 0
        var battStatus = ""
        var memTotalKb = 0L
        var memAvailKb = 0L
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
                "BATT" -> {
                    battPct = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    battStatus = p.getOrNull(2)?.trim() ?: ""
                }
                "TEMP" -> {
                    val name = p.getOrNull(1)?.lowercase() ?: ""
                    val raw = p.getOrNull(2)?.trim()?.toLongOrNull() ?: return@forEach
                    val c = normalizeTemp(raw)
                    if (c in 5.0..120.0) {
                        if (name.contains("soc") || name.contains("cpu") || name.contains("ap") || name.contains("mtktscpu")) {
                            socTemps.add(c)
                        } else {
                            fallbackTemps.add(c)
                        }
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
        val socTemp = socTemps.maxOrNull() ?: fallbackTemps.maxOrNull()

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
            batteryPercent = battPct,
            batteryStatus = battStatus,
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
            "printf 'CORE|%s|%s|%s\\n' \"\$cid\" \"\$on\" \"\$cf\"; done"

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

        return ControlSnapshot(
            root = true,
            cpuPolicies = list,
            cpuCores = cores,
            message = if (list.isEmpty()) "Nenhuma policy CPUFreq encontrada." else ""
        )
    }

    fun readGpu(): ControlSnapshot {
        if (!shell.rootCheck()) return ControlSnapshot(false, message = "Autorize o KLEE no KernelSU.")
        val s =
            "found=0; for d in /sys/class/devfreq/13000000.mali /sys/class/devfreq/*mali* /sys/class/devfreq/*gpu*; do " +
            "[ -d \"\$d\" ] || continue; [ \"\$found\" = 0 ] || continue; found=1; " +
            "printf 'GPU|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\\n' " +
            "\"\$d\" " +
            "\"\$(cat \"\$d/name\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/cur_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/min_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/max_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/min_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/max_freq\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/governor\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/available_governors\" 2>/dev/null)\" " +
            "\"\$(cat \"\$d/available_frequencies\" 2>/dev/null)\" " +
            "\"\$( [ -w \"\$d/min_freq\" ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w \"\$d/max_freq\" ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w \"\$d/governor\" ] && echo 1 || echo 0 )\"; done; " +
            "[ \"\$found\" = 1 ] || printf 'GPU_MISSING\\n'"

        val r = shell.runReadOnly(s)
        val diagCmd =
            "for f in /proc/gpufreqv2/gpufreq_status /proc/gpufreqv2/gpu_working_opp_table /proc/gpufreqv2/limit_table /proc/gpufreqv2/fix_target_opp_index; do " +
            "[ -r \"\$f\" ] && { echo \"=== \$(basename \"\$f\") ===\"; head -n 40 \"\$f\" 2>/dev/null; }; done"
        val diagText = shell.runReadOnly(diagCmd).stdout.joinToString("\n")

        if (r.stdout.any { it.contains("GPU_MISSING") }) {
            return ControlSnapshot(
                root = true,
                message = "Devfreq Mali ausente. Diagnóstico gpufreqv2:\n" +
                    diagText.ifBlank { "Os nós gpufreqv2 não estão legíveis neste kernel." }
            )
        }
        val p = r.stdout.firstOrNull { it.startsWith("GPU|") }?.split("|")
            ?: return ControlSnapshot(true, message = "Falha ao consultar Mali devfreq: " + result(r, "sem saída"))
        if (p.getOrNull(1) == "MISSING") {
            return ControlSnapshot(true, message = "Nó devfreq não encontrado; GED/gpufreqv2 ainda requer identificação específica.")
        }
        if (p.size < 14) return ControlSnapshot(true, message = "Resposta GPU incompleta.")
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
            message = if (g.frequenciesHz.isEmpty()) "OPPs indisponíveis; controle GPU bloqueado por segurança." else ""
        )
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
        require(governor == null || governor.matches(Regex("[A-Za-z0-9_]+"))) { "Governor da GPU inválido." }
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
            "for n in status capacity voltage_now current_now temp health technology cycle_count charge_full charge_full_design charge_counter; do " +
            "[ -r \"\$d/\$n\" ] && printf 'BAT|%s|%s\\n' \"\$n\" \"\$(cat \"\$d/\$n\" 2>/dev/null)\"; done; " +
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
            technology = bat["technology"] ?: "Li-ion",
            cycleCount = bat["cycle_count"]?.toIntOrNull(),
            chargeFullMah = toMah(bat["charge_full"]?.toLongOrNull()),
            chargeDesignMah = toMah(bat["charge_full_design"]?.toLongOrNull()),
            chargeCounterMah = toMah(bat["charge_counter"]?.toLongOrNull()),
            supplies = supplies.sortedByDescending { it.online == true },
            message = if (bat.isEmpty()) "Não foi possível ler /sys/class/power_supply/battery." else ""
        )
    }

    fun readMonitorSample(previousTicks: CpuStatTicks?): MonitorSample {
        if (!shell.rootCheck()) return MonitorSample(false, message = "Autorize o KLEE no KernelSU.")
        val script =
            "for d in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do [ -d \"\$d\" ] || continue; " +
            "id=\${d##*policy}; " +
            "printf 'CPU|%s|%s|%s|%s\\n' \"\$id\" \"\$(cat \"\$d/scaling_cur_freq\" 2>/dev/null)\" \"\$(cat \"\$d/cpuinfo_max_freq\" 2>/dev/null)\" \"\$(cat \"\$d/scaling_governor\" 2>/dev/null)\"; done; " +
            "for d in /sys/class/devfreq/13000000.mali /sys/class/devfreq/*mali* /sys/class/devfreq/*gpu*; do [ -d \"\$d\" ] || continue; " +
            "printf 'GPU|%s|%s\\n' \"\$(cat \"\$d/cur_freq\" 2>/dev/null)\" \"\$(cat \"\$d/governor\" 2>/dev/null)\"; break; done; " +
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
        var gpuGov = ""
        var loadAvg = ""
        var uptimeSec = 0L
        var statTicks: CpuStatTicks? = null
        var batTemp: Double? = null
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
                        if (name.contains("soc") || name.contains("cpu") || name.contains("mtktscpu")) {
                            socTemps.add(c)
                        }
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
            gpuGovernor = gpuGov,
            socTempC = socTemps.maxOrNull() ?: allTemps.maxOrNull(),
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
            "printf 'ZRAM|%s|%s\\n' \"\$(cat /sys/block/zram0/disksize 2>/dev/null)\" \"\$(cat /sys/block/zram0/comp_algorithm 2>/dev/null)\"; " +
            "printf 'VM|%s|%s|%s|%s|%s|%s|%s\\n' " +
            "\"\$(cat /proc/sys/vm/swappiness 2>/dev/null)\" " +
            "\"\$(cat /proc/sys/vm/vfs_cache_pressure 2>/dev/null)\" " +
            "\"\$(cat /proc/sys/vm/dirty_ratio 2>/dev/null)\" " +
            "\"\$(cat /proc/sys/vm/dirty_background_ratio 2>/dev/null)\" " +
            "\"\$( [ -w /proc/sys/vm/swappiness ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w /proc/sys/vm/vfs_cache_pressure ] && echo 1 || echo 0 )\" " +
            "\"\$( [ -w /proc/sys/vm/dirty_ratio ] && echo 1 || echo 0 )\"; " +
            "for b in sda sdb sdc mmcblk0 dm-0; do d=/sys/block/\$b/queue; [ -d \"\$d\" ] || continue; " +
            "printf 'IO|%s|%s|%s|%s|%s\\n' \"\$b\" \"\$(cat \"\$d/scheduler\" 2>/dev/null)\" \"\$(cat \"\$d/read_ahead_kb\" 2>/dev/null)\" " +
            "\"\$( [ -w \"\$d/scheduler\" ] && echo 1 || echo 0 )\" \"\$( [ -w \"\$d/read_ahead_kb\" ] && echo 1 || echo 0 )\"; done; " +
            "printf 'TCP|%s|%s|%s\\n' " +
            "\"\$(cat /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null)\" " +
            "\"\$(cat /proc/sys/net/ipv4/tcp_available_congestion_control 2>/dev/null)\" " +
            "\"\$( [ -w /proc/sys/net/ipv4/tcp_congestion_control ] && echo 1 || echo 0 )\""

        val r = shell.runReadOnly(script)
        var memTotalKb = 0L
        var memAvailKb = 0L
        var swapTotalKb = 0L
        var swapFreeKb = 0L
        var zramBytes = 0L
        var zramAlgo = ""
        var swappiness = 60
        var vfs = 100
        var dirty = 20
        var dirtyBg = 10
        var wSwap = false
        var wVfs = false
        var wDirty = false
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
                }
                "VM" -> {
                    swappiness = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 60
                    vfs = p.getOrNull(2)?.trim()?.toIntOrNull() ?: 100
                    dirty = p.getOrNull(3)?.trim()?.toIntOrNull() ?: 20
                    dirtyBg = p.getOrNull(4)?.trim()?.toIntOrNull() ?: 10
                    wSwap = p.getOrNull(5)?.trim() == "1"
                    wVfs = p.getOrNull(6)?.trim() == "1"
                    wDirty = p.getOrNull(7)?.trim() == "1"
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
                    ioDevices.add(
                        IoDevice(
                            name = name,
                            currentScheduler = active,
                            availableSchedulers = available,
                            readAheadKb = ra,
                            canWriteScheduler = p.getOrNull(4)?.trim() == "1",
                            canWriteReadAhead = p.getOrNull(5)?.trim() == "1"
                        )
                    )
                }
                "TCP" -> {
                    val cur = p.getOrNull(1)?.trim() ?: ""
                    val avail = p.getOrNull(2)?.trim()?.split(Regex("\\s+"))?.filter(String::isNotBlank) ?: emptyList()
                    tcp = TcpCongestionInfo(
                        current = cur,
                        available = avail,
                        canWrite = p.getOrNull(3)?.trim() == "1"
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
            swappiness = swappiness,
            vfsCachePressure = vfs,
            dirtyRatio = dirty,
            dirtyBackgroundRatio = dirtyBg,
            canWriteSwappiness = wSwap,
            canWriteVfs = wVfs,
            canWriteDirty = wDirty
        )

        return TuningSnapshot(
            root = true,
            memory = memInfo,
            ioDevices = ioDevices,
            tcp = tcp
        )
    }

    fun applyVmParam(param: String, value: Int): String {
        val allowed = mapOf(
            "swappiness" to (0..200),
            "vfs_cache_pressure" to (10..500),
            "dirty_ratio" to (5..80),
            "dirty_background_ratio" to (1..50)
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

    fun applyIoScheduler(device: String, scheduler: String?, readAheadKb: Int?): String {
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
        val script =
            "d='$d'; [ -d \"\$d\" ] || { echo 'IO_DEVICE_NOT_FOUND'; exit 2; }; " +
            schedPart + raPart +
            "echo \"OK I/O $device sched=\$(cat \"\$d/scheduler\" 2>/dev/null) ra=\$(cat \"\$d/read_ahead_kb\" 2>/dev/null)KB\""
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
            "printf 'KERNEL|%s\\n' \"\$(uname -a 2>/dev/null)\"; " +
            "printf 'ANDROID|%s\\n' \"\$(getprop ro.build.version.release 2>/dev/null)\"; " +
            "printf 'SDK|%s\\n' \"\$(getprop ro.build.version.sdk 2>/dev/null)\"; " +
            "printf 'PATCH|%s\\n' \"\$(getprop ro.build.version.security_patch 2>/dev/null)\"; " +
            "printf 'SELINUX|%s\\n' \"\$(getenforce 2>/dev/null)\"; " +
            "printf 'ROOT|%s\\n' \"\$(su -v 2>/dev/null | head -n 1)\"; " +
            "printf 'ABI|%s\\n' \"\$(getprop ro.product.cpu.abi 2>/dev/null)\"; " +
            "printf 'UPTIME|%s\\n' \"\$(cat /proc/uptime 2>/dev/null)\"; " +
            "printf 'CORES|%s\\n' \"\$(nproc 2>/dev/null)\"; " +
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
            kernelVersion = map["KERNEL"].orEmpty(),
            androidVersion = map["ANDROID"].orEmpty(),
            sdkInt = map["SDK"].orEmpty(),
            securityPatch = map["PATCH"].orEmpty(),
            selinuxMode = map["SELINUX"].orEmpty().ifBlank { "Desconhecido" },
            rootProvider = map["ROOT"].orEmpty().ifBlank { "Root (uid=0)" },
            abi = map["ABI"].orEmpty(),
            uptimeSeconds = map["UPTIME"]?.substringBefore(" ")?.toDoubleOrNull()?.toLong() ?: 0L,
            cpuCores = map["CORES"]?.toIntOrNull() ?: 0,
            totalRamMb = (map["MEM"]?.toLongOrNull() ?: 0L) / 1024L
        )
    }

    fun buildDiagnosticReport(): String {
        val dev = readDeviceInfo()
        val cpu = readCpu()
        val gpu = readGpu()
        val thermal = readThermalDetailed()
        val power = readPowerDetailed()
        val tuning = readTuning()

        return buildString {
            appendLine("=== KLEE Kernel Manager - Relatório de Diagnóstico ===")
            appendLine("Modelo: ${dev.brand} ${dev.model} (${dev.device})")
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
                appendLine("  Governors: ${p.governors.joinToString()}")
                appendLine("  Freqs (MHz): ${p.frequenciesKHz.joinToString { (it / 1000).toString() }}")
            }
            appendLine()
            appendLine("--- GPU ---")
            gpu.gpu?.let { g ->
                appendLine("${g.name} (${g.path})")
                appendLine("  cur=${g.currentHz / 1_000_000}MHz limits=${g.minHz / 1_000_000}-${g.maxHz / 1_000_000}MHz gov=${g.governor}")
                appendLine("  Governors: ${g.governors.joinToString()}")
                appendLine("  OPPs (MHz): ${g.frequenciesHz.joinToString { (it / 1_000_000).toString() }}")
            } ?: appendLine(gpu.message)
            appendLine()
            appendLine("--- Thermal (Top 10 sensores) ---")
            thermal.zones.take(10).forEach { z ->
                appendLine("  ${z.name}: ${"%.1f".format(z.tempCelsius)} °C")
            }
            val activeCool = thermal.coolingDevices.filter { it.curState > 0 }
            appendLine("  Cooling ativos: ${activeCool.size}/${thermal.coolingDevices.size}")
            activeCool.forEach { c -> appendLine("    ${c.name}: ${c.curState}/${c.maxState}") }
            appendLine()
            appendLine("--- Power ---")
            appendLine(
                "Bateria: ${power.capacity}% (${power.status}) · ${"%.2f".format(power.voltageVolts)}V · " +
                "${"%.0f".format(power.currentMa)}mA · ${"%.2f".format(power.powerWatts)}W · ${"%.1f".format(power.tempCelsius)}°C"
            )
            appendLine()
            appendLine("--- Memória, I/O & TCP ---")
            tuning.memory?.let { m ->
                appendLine("RAM: ${m.usedMb}/${m.totalMb} MB · Swap: ${m.swapUsedMb}/${m.swapTotalMb} MB · ZRAM: ${m.zramDiskSizeMb} MB (${m.zramAlgo})")
                appendLine("VM: swappiness=${m.swappiness}, vfs_cache_pressure=${m.vfsCachePressure}, dirty=${m.dirtyRatio}/${m.dirtyBackgroundRatio}")
            }
            tuning.ioDevices.forEach { io ->
                appendLine("I/O ${io.name}: sched=${io.currentScheduler} [${io.availableSchedulers.joinToString()}] ra=${io.readAheadKb}KB")
            }
            tuning.tcp?.let { t ->
                appendLine("TCP: ${t.current} [${t.available.joinToString()}]")
            }
        }
    }

    private fun normalizeTemp(raw: Long): Double = when {
        abs(raw) > 1000L -> raw / 1000.0
        abs(raw) > 150L -> raw / 10.0
        else -> raw.toDouble()
    }
}
