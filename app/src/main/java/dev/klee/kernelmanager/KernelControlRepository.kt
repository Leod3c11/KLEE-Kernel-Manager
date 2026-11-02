package dev.klee.kernelmanager

data class CpuPolicy(
    val id: String, val cpus: String, val currentKHz: Long,
    val minKHz: Long, val maxKHz: Long, val hardwareMinKHz: Long, val hardwareMaxKHz: Long,
    val governor: String, val governors: List<String>, val frequenciesKHz: List<Long>,
    val canWriteMin: Boolean, val canWriteMax: Boolean, val canWriteGovernor: Boolean
)

data class GpuControl(
    val path: String, val name: String, val currentHz: Long, val minHz: Long, val maxHz: Long,
    val hardwareMinHz: Long, val hardwareMaxHz: Long, val governor: String,
    val governors: List<String>, val frequenciesHz: List<Long>,
    val canWriteMin: Boolean, val canWriteMax: Boolean, val canWriteGovernor: Boolean
)

data class ControlSnapshot(
    val root: Boolean, val cpuPolicies: List<CpuPolicy> = emptyList(),
    val gpu: GpuControl? = null, val message: String = ""
)

class KernelControlRepository(private val shell: RootShell) {
    fun readCpu(): ControlSnapshot {
        if (!shell.rootCheck()) return ControlSnapshot(false, message = "Autorize o KLEE no KernelSU para acessar os controles.")
        val script = """
            for d in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do
              [ -d "$d" ] || continue
              id=${'$'}{d##*policy}
              val() { [ -r "$d/$1" ] && tr '\n' ' ' < "$d/$1" 2>/dev/null || printf ''; }
              printf 'CPU|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' \
                "$id" "$(val related_cpus)" "$(val scaling_cur_freq)" \
                "$(val scaling_min_freq)" "$(val scaling_max_freq)" \
                "$(val cpuinfo_min_freq)" "$(val cpuinfo_max_freq)" \
                "$(val scaling_governor)" "$(val scaling_available_governors)" \
                "$(val scaling_available_frequencies)" \
                "$( [ -w "$d/scaling_min_freq" ] && echo 1 || echo 0 )" \
                "$( [ -w "$d/scaling_max_freq" ] && echo 1 || echo 0 )" \
                "$( [ -w "$d/scaling_governor" ] && echo 1 || echo 0 )"
            done
        """.trimIndent()
        val result = shell.runReadOnly(script)
        if (!result.success) return ControlSnapshot(true, message = result.stderr.joinToString("\n"))
        val policies = result.stdout.mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 14 || p[0] != "CPU") return@mapNotNull null
            fun n(i: Int) = p.getOrNull(i)?.trim()?.toLongOrNull() ?: 0L
            CpuPolicy(
                id = p[1], cpus = p[2].trim(), currentKHz = n(3), minKHz = n(4), maxKHz = n(5),
                hardwareMinKHz = n(6), hardwareMaxKHz = n(7), governor = p[8].trim(),
                governors = p[9].trim().split(Regex("\\s+")).filter(String::isNotBlank),
                frequenciesKHz = p[10].trim().split(Regex("\\s+")).mapNotNull(String::toLongOrNull).distinct().sorted(),
                canWriteMin = p[11] == "1", canWriteMax = p[12] == "1", canWriteGovernor = p[13] == "1"
            )
        }
        return ControlSnapshot(true, cpuPolicies = policies,
            message = if (policies.isEmpty()) "Nenhuma policy CPUFreq foi encontrada." else "")
    }

    fun readGpu(): ControlSnapshot {
        if (!shell.rootCheck()) return ControlSnapshot(false, message = "Autorize o KLEE no KernelSU para acessar os controles.")
        val path = "/sys/class/devfreq/13000000.mali"
        val script = """
            d='$path'
            if [ ! -d "$d" ]; then echo 'GPU|MISSING'; exit 0; fi
            val() { [ -r "$d/$1" ] && tr '\n' ' ' < "$d/$1" 2>/dev/null || printf ''; }
            printf 'GPU|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n' \
              "$d" "$(val name)" "$(val cur_freq)" "$(val min_freq)" "$(val max_freq)" \
              "$(val min_freq)" "$(val max_freq)" "$(val governor)" \
              "$(val available_governors)" "$(val available_frequencies)" \
              "$( [ -w "$d/min_freq" ] && echo 1 || echo 0 )" \
              "$( [ -w "$d/max_freq" ] && echo 1 || echo 0 )" \
              "$( [ -w "$d/governor" ] && echo 1 || echo 0 )"
        """.trimIndent()
        val result = shell.runReadOnly(script)
        val line = result.stdout.firstOrNull { it.startsWith("GPU|") }
            ?: return ControlSnapshot(true, message = "Não foi possível consultar o controlador devfreq da Mali.")
        val p = line.split("|")
        if (p.getOrNull(1) == "MISSING") return ControlSnapshot(true,
            message = "O nó $path não existe. A interface GED/gpufreqv2 ainda precisa de identificação específica antes de habilitar escrita.")
        if (p.size < 14) return ControlSnapshot(true, message = "Resposta incompleta do controlador GPU.")
        fun n(i: Int) = p.getOrNull(i)?.trim()?.toLongOrNull() ?: 0L
        val gpu = GpuControl(
            path = p[1], name = p[2].trim().ifBlank { "Mali-G720" }, currentHz = n(3),
            minHz = n(4), maxHz = n(5), hardwareMinHz = 0, hardwareMaxHz = 0,
            governor = p[8].trim(),
            governors = p[9].trim().split(Regex("\\s+")).filter(String::isNotBlank),
            frequenciesHz = p[10].trim().split(Regex("\\s+")).mapNotNull(String::toLongOrNull).distinct().sorted(),
            canWriteMin = p[11] == "1", canWriteMax = p[12] == "1", canWriteGovernor = p[13] == "1"
        )
        return ControlSnapshot(true, gpu = gpu,
            message = if (gpu.frequenciesHz.isEmpty()) "O kernel não expôs available_frequencies; por segurança, os controles de frequência da GPU ficam bloqueados." else "")
    }

    fun applyCpu(id: String, minKHz: Long, maxKHz: Long, governor: String?): String {
        require(id.matches(Regex("[0-9]{1,3}")))
        require(minKHz > 0 && maxKHz >= minKHz)
        require(governor == null || governor.matches(Regex("[A-Za-z0-9_]+")))
        val d = "/sys/devices/system/cpu/cpufreq/policy$id"
        val govLine = if (governor == null) "" else
            "case \" \$(cat \"\$d/scaling_available_governors\" 2>/dev/null) \" in *\" $governor \"*) ;; *) rollback; echo 'Governor não disponível'; exit 8;; esac; echo '$governor' > \"\$d/scaling_governor\" || { rollback; echo 'Falha ao alterar governor'; exit 9; }; "
        val script = """
            d='$d'
            [ -d "${'$'}d" ] || { echo 'Policy não encontrada'; exit 2; }
            oldmin=$(cat "${'$'}d/scaling_min_freq") || exit 3
            oldmax=$(cat "${'$'}d/scaling_max_freq") || exit 3
            hwmin=$(cat "${'$'}d/cpuinfo_min_freq" 2>/dev/null || echo 1)
            hwmax=$(cat "${'$'}d/cpuinfo_max_freq" 2>/dev/null || echo 999999999)
            [ $minKHz -ge "${'$'}hwmin" ] && [ $maxKHz -le "${'$'}hwmax" ] || { echo 'Frequência fora dos limites de hardware'; exit 4; }
            [ $minKHz -le $maxKHz ] || exit 5
            rollback() { echo "${'$'}oldmin" > "${'$'}d/scaling_min_freq"; echo "${'$'}oldmax" > "${'$'}d/scaling_max_freq"; }
            if [ $maxKHz -lt "${'$'}oldmin" ]; then
              echo $minKHz > "${'$'}d/scaling_min_freq" && echo $maxKHz > "${'$'}d/scaling_max_freq" || { rollback; echo 'Falha ao aplicar limites'; exit 6; }
            elif [ $minKHz -gt "${'$'}oldmax" ]; then
              echo $maxKHz > "${'$'}d/scaling_max_freq" && echo $minKHz > "${'$'}d/scaling_min_freq" || { rollback; echo 'Falha ao aplicar limites'; exit 6; }
            else
              echo $minKHz > "${'$'}d/scaling_min_freq" && echo $maxKHz > "${'$'}d/scaling_max_freq" || { rollback; echo 'Falha ao aplicar limites'; exit 6; }
            fi
            $govLine
            actualmin=$(cat "${'$'}d/scaling_min_freq"); actualmax=$(cat "${'$'}d/scaling_max_freq")
            [ "${'$'}actualmin" = '$minKHz' ] && [ "${'$'}actualmax" = '$maxKHz' ] || { rollback; echo 'Read-back divergiu; limites restaurados'; exit 7; }
            echo "OK|MIN=${'$'}actualmin|MAX=${'$'}actualmax|GOV=$(cat "${'$'}d/scaling_governor")"
        """.trimIndent()
        val r = shell.runRoot(script)
        return if (r.success) r.stdout.joinToString("\n") else r.stderr.plus(r.stdout).joinToString("\n").ifBlank { "Falha ao aplicar configuração (código ${r.code})." }
    }

    fun applyGpu(minHz: Long, maxHz: Long, governor: String?): String {
        require(minHz > 0 && maxHz >= minHz)
        require(governor == null || governor.matches(Regex("[A-Za-z0-9_]+")))
        val d = "/sys/class/devfreq/13000000.mali"
        val govLine = if (governor == null) "" else
            "case \" \$(cat \"\$d/available_governors\" 2>/dev/null) \" in *\" $governor \"*) ;; *) rollback; echo 'Governor GPU não disponível'; exit 8;; esac; echo '$governor' > \"\$d/governor\" || { rollback; echo 'Falha ao alterar governor GPU'; exit 9; }; "
        val script = """
            d='$d'
            [ -d "${'$'}d" ] || exit 2
            [ -w "${'$'}d/min_freq" ] && [ -w "${'$'}d/max_freq" ] || { echo 'Os limites devfreq da GPU não são graváveis'; exit 3; }
            oldmin=$(cat "${'$'}d/min_freq"); oldmax=$(cat "${'$'}d/max_freq")
            [ $minHz -le $maxHz ] || exit 4
            freqs=$(cat "${'$'}d/available_frequencies" 2>/dev/null)
            [ -n "${'$'}freqs" ] || { echo 'Tabela OPP da GPU indisponível; escrita bloqueada'; exit 4; }
            case " ${'$'}freqs " in *" $minHz "*) ;; *) echo 'Mínimo não pertence aos OPPs disponíveis'; exit 4;; esac
            case " ${'$'}freqs " in *" $maxHz "*) ;; *) echo 'Máximo não pertence aos OPPs disponíveis'; exit 4;; esac
            rollback() { echo "${'$'}oldmin" > "${'$'}d/min_freq"; echo "${'$'}oldmax" > "${'$'}d/max_freq"; }
            if [ $maxHz -lt "${'$'}oldmin" ]; then
              echo $minHz > "${'$'}d/min_freq" && echo $maxHz > "${'$'}d/max_freq" || { rollback; exit 5; }
            elif [ $minHz -gt "${'$'}oldmax" ]; then
              echo $maxHz > "${'$'}d/max_freq" && echo $minHz > "${'$'}d/min_freq" || { rollback; exit 5; }
            else
              echo $minHz > "${'$'}d/min_freq" && echo $maxHz > "${'$'}d/max_freq" || { rollback; exit 5; }
            fi
            $govLine
            actualmin=$(cat "${'$'}d/min_freq"); actualmax=$(cat "${'$'}d/max_freq")
            [ "${'$'}actualmin" = '$minHz' ] && [ "${'$'}actualmax" = '$maxHz' ] || { rollback; echo 'Read-back divergiu; limites restaurados'; exit 6; }
            echo "OK|MIN=${'$'}actualmin|MAX=${'$'}actualmax|GOV=$(cat "${'$'}d/governor" 2>/dev/null)"
        """.trimIndent()
        val r = shell.runRoot(script)
        return if (r.success) r.stdout.joinToString("\n") else r.stderr.plus(r.stdout).joinToString("\n").ifBlank { "Falha ao aplicar GPU (código ${r.code})." }
    }
}
