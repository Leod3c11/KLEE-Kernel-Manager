package dev.klee.kernelmanager

data class CpuPolicy(val id:String,val cpus:String,val currentKHz:Long,val minKHz:Long,val maxKHz:Long,val hardwareMinKHz:Long,val hardwareMaxKHz:Long,val governor:String,val governors:List<String>,val frequenciesKHz:List<Long>,val canWriteMin:Boolean,val canWriteMax:Boolean,val canWriteGovernor:Boolean)
data class GpuControl(val path:String,val name:String,val currentHz:Long,val minHz:Long,val maxHz:Long,val hardwareMinHz:Long,val hardwareMaxHz:Long,val governor:String,val governors:List<String>,val frequenciesHz:List<Long>,val canWriteMin:Boolean,val canWriteMax:Boolean,val canWriteGovernor:Boolean)
data class ControlSnapshot(val root:Boolean,val cpuPolicies:List<CpuPolicy> = emptyList(),val gpu:GpuControl? = null,val message:String = "")

class KernelControlRepository(private val shell:RootShell) {
  private fun result(r:CommandResult, fallback:String):String =
    if (r.success) r.stdout.joinToString("\n").ifBlank { "OK" }
    else "ERRO (exit=${r.code}): " + r.stderr.plus(r.stdout).joinToString("\n").ifBlank { fallback }
  fun readCpu():ControlSnapshot {
    if(!shell.rootCheck()) return ControlSnapshot(false,message="Autorize o KLEE no KernelSU.")
    val s = "for d in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do [ -d \"\$d\" ] || continue; id=\${d##*policy}; printf 'CPU|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\\n' \"\$id\" \"\$(cat \"\$d/related_cpus\" 2>/dev/null)\" \"\$(cat \"\$d/scaling_cur_freq\" 2>/dev/null)\" \"\$(cat \"\$d/scaling_min_freq\" 2>/dev/null)\" \"\$(cat \"\$d/scaling_max_freq\" 2>/dev/null)\" \"\$(cat \"\$d/cpuinfo_min_freq\" 2>/dev/null)\" \"\$(cat \"\$d/cpuinfo_max_freq\" 2>/dev/null)\" \"\$(cat \"\$d/scaling_governor\" 2>/dev/null)\" \"\$(cat \"\$d/scaling_available_governors\" 2>/dev/null)\" \"\$(cat \"\$d/scaling_available_frequencies\" 2>/dev/null)\" \"\$( [ -w \"\$d/scaling_min_freq\" ] && echo 1 || echo 0 )\" \"\$( [ -w \"\$d/scaling_max_freq\" ] && echo 1 || echo 0 )\" \"\$( [ -w \"\$d/scaling_governor\" ] && echo 1 || echo 0 )\"; done"
    val r=shell.runReadOnly(s); if(!r.success) return ControlSnapshot(true,message=r.stderr.joinToString("\n"))
    val list = r.stdout.mapNotNull { line ->
      val p = line.split("|")
      if (p.size < 14 || p[0] != "CPU") return@mapNotNull null
      fun number(index: Int): Long = p[index].trim().toLongOrNull() ?: 0L
      CpuPolicy(
        id = p[1],
        cpus = p[2],
        currentKHz = number(3),
        minKHz = number(4),
        maxKHz = number(5),
        hardwareMinKHz = number(6),
        hardwareMaxKHz = number(7),
        governor = p[8],
        governors = p[9].split(Regex("\\s+")).filter(String::isNotBlank),
        frequenciesKHz = p[10].split(Regex("\\s+")).mapNotNull(String::toLongOrNull).sorted(),
        canWriteMin = p[11] == "1",
        canWriteMax = p[12] == "1",
        canWriteGovernor = p[13] == "1"
      )
    }
    return ControlSnapshot(true,cpuPolicies=list,message=if(list.isEmpty()) "Nenhuma policy CPUFreq encontrada." else "")
  }
  fun readGpu():ControlSnapshot {
    if(!shell.rootCheck()) return ControlSnapshot(false,message="Autorize o KLEE no KernelSU.")
    val s="found=0; for d in /sys/class/devfreq/13000000.mali /sys/class/devfreq/*mali* /sys/class/devfreq/*gpu*; do [ -d \"\$d\" ] || continue; [ \"\$found\" = 0 ] || continue; found=1; printf 'GPU|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\\n' \"\$d\" \"\$(cat \"\$d/name\" 2>/dev/null)\" \"\$(cat \"\$d/cur_freq\" 2>/dev/null)\" \"\$(cat \"\$d/min_freq\" 2>/dev/null)\" \"\$(cat \"\$d/max_freq\" 2>/dev/null)\" \"\$(cat \"\$d/min_freq\" 2>/dev/null)\" \"\$(cat \"\$d/max_freq\" 2>/dev/null)\" \"\$(cat \"\$d/governor\" 2>/dev/null)\" \"\$(cat \"\$d/available_governors\" 2>/dev/null)\" \"\$(cat \"\$d/available_frequencies\" 2>/dev/null)\" \"\$( [ -w \"\$d/min_freq\" ] && echo 1 || echo 0 )\" \"\$( [ -w \"\$d/max_freq\" ] && echo 1 || echo 0 )\" \"\$( [ -w \"\$d/governor\" ] && echo 1 || echo 0 )\"; done; [ \"\$found\" = 1 ] || printf 'GPU_MISSING\\n'"; 
    val r=shell.runReadOnly(s); if (r.stdout.any { it.contains("GPU_MISSING") }) {
      val diag = shell.runReadOnly("for f in /proc/gpufreqv2/gpufreq_status /proc/gpufreqv2/gpu_working_opp_table /proc/gpufreqv2/limit_table /proc/gpufreqv2/fix_target_opp_index; do [ -r \"\$f\" ] && { echo \"NODE|$(basename \"\$f\")\"; cat \"\$f\" 2>/dev/null; }; done")
      return ControlSnapshot(true,message="Devfreq Mali ausente. Diagnóstico gpufreqv2:\n" + diag.stdout.joinToString("\n").ifBlank { "Os nós gpufreqv2 listados no relatório não estão legíveis." })
    }
    val p=r.stdout.firstOrNull { it.startsWith("GPU|") }?.split("|") ?: return ControlSnapshot(true, message="Falha ao consultar Mali devfreq: " + result(r, "sem saída"))
    if(p.getOrNull(1)=="MISSING") return ControlSnapshot(true,message="Nó devfreq não encontrado; GED/gpufreqv2 ainda requer identificação específica.")
    if(p.size<14) return ControlSnapshot(true,message="Resposta GPU incompleta.")
    fun n(i:Int)=p[i].trim().toLongOrNull()?:0L
    val g=GpuControl(p[1],p[2].ifBlank{"Mali-G720"},n(3),n(4),n(5),0,0,p[8],p[9].split(Regex("\\s+")).filter(String::isNotBlank),p[10].split(Regex("\\s+")).mapNotNull(String::toLongOrNull).sorted(),p[11]=="1",p[12]=="1",p[13]=="1")
    return ControlSnapshot(true,gpu=g,message=if(g.frequenciesHz.isEmpty()) "OPPs indisponíveis; controle GPU bloqueado." else "")
  }
  fun applyCpu(id:String,minKHz:Long,maxKHz:Long,governor:String?):String {
    require(id.matches(Regex("[0-9]{1,3}"))); require(minKHz>0 && maxKHz>=minKHz); require(governor==null || governor.matches(Regex("[A-Za-z0-9_]+")))
    val d="/sys/devices/system/cpu/cpufreq/policy$id"
    val govCheck=if(governor==null) "" else "available=\$(cat \"\$d/scaling_available_governors\" 2>/dev/null); case \" \$available \" in *' "+governor+" '*) ;; *) echo 'GOVERNOR_UNAVAILABLE'; exit 7;; esac; "
    val govWrite=if(governor==null) "" else "if [ \"\$oldgov\" != '"+governor+"' ]; then if ! printf '%s' '"+governor+"' > \"\$d/scaling_governor\"; then echo 'GOVERNOR_WRITE_FAILED'; exit 8; fi; actualgov=\$(cat \"\$d/scaling_governor\" 2>/dev/null); if [ \"\$actualgov\" != '"+governor+"' ]; then echo 'GOVERNOR_READBACK_MISMATCH expected="+governor+" actual=\$actualgov'; [ -z \"\$oldgov\" ] || printf '%s' \"\$oldgov\" > \"\$d/scaling_governor\" 2>/dev/null; exit 9; fi; fi; "
    val s="d='"+d+"'; [ -d \"\$d\" ] || { echo 'POLICY_NOT_FOUND'; exit 2; }; oldmin=\$(cat \"\$d/scaling_min_freq\") || exit 2; oldmax=\$(cat \"\$d/scaling_max_freq\") || exit 2; oldgov=\$(cat \"\$d/scaling_governor\" 2>/dev/null); hwmin=\$(cat \"\$d/cpuinfo_min_freq\"); hwmax=\$(cat \"\$d/cpuinfo_max_freq\"); [ "+minKHz+" -ge \$hwmin ] && [ "+maxKHz+" -le \$hwmax ] || { echo 'FREQUENCY_OUT_OF_HARDWARE_RANGE'; exit 3; }; "+govCheck+govWrite+"rollback(){ cm=\$(cat \"\$d/scaling_min_freq\" 2>/dev/null); cx=\$(cat \"\$d/scaling_max_freq\" 2>/dev/null); if [ \"\$oldmin\" -gt \"\$cx\" ]; then printf '%s' \"\$oldmax\" > \"\$d/scaling_max_freq\" 2>/dev/null; printf '%s' \"\$oldmin\" > \"\$d/scaling_min_freq\" 2>/dev/null; elif [ \"\$oldmax\" -lt \"\$cm\" ]; then printf '%s' \"\$oldmin\" > \"\$d/scaling_min_freq\" 2>/dev/null; printf '%s' \"\$oldmax\" > \"\$d/scaling_max_freq\" 2>/dev/null; else printf '%s' \"\$oldmin\" > \"\$d/scaling_min_freq\" 2>/dev/null; printf '%s' \"\$oldmax\" > \"\$d/scaling_max_freq\" 2>/dev/null; fi; [ -z \"\$oldgov\" ] || printf '%s' \"\$oldgov\" > \"\$d/scaling_governor\" 2>/dev/null; }; if [ "+maxKHz+" -lt \"\$oldmin\" ]; then if [ "+minKHz+" -gt "+maxKHz+" ] || ! printf '%s' '"+minKHz+"' > \"\$d/scaling_min_freq\"; then echo 'CPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; if ! printf '%s' '"+maxKHz+"' > \"\$d/scaling_max_freq\"; then echo 'CPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; elif [ "+minKHz+" -gt \"\$oldmax\" ]; then if ! printf '%s' '"+maxKHz+"' > \"\$d/scaling_max_freq\"; then echo 'CPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; if ! printf '%s' '"+minKHz+"' > \"\$d/scaling_min_freq\"; then echo 'CPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; elif [ "+maxKHz+" -lt \"\$oldmax\" ]; then if [ \"\$oldmax\" != '"+maxKHz+"' ] && ! printf '%s' '"+maxKHz+"' > \"\$d/scaling_max_freq\"; then echo 'CPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; if [ \"\$oldmin\" != '"+minKHz+"' ] && ! printf '%s' '"+minKHz+"' > \"\$d/scaling_min_freq\"; then echo 'CPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; else if [ \"\$oldmin\" != '"+minKHz+"' ] && ! printf '%s' '"+minKHz+"' > \"\$d/scaling_min_freq\"; then echo 'CPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; if [ \"\$oldmax\" != '"+maxKHz+"' ] && ! printf '%s' '"+maxKHz+"' > \"\$d/scaling_max_freq\"; then echo 'CPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; fi; actualmin=\$(cat \"\$d/scaling_min_freq\"); actualmax=\$(cat \"\$d/scaling_max_freq\"); [ \"\$actualmin\" = '"+minKHz+"' ] && [ \"\$actualmax\" = '"+maxKHz+"' ] || { echo 'CPU_LIMIT_READBACK_MISMATCH min=\$actualmin max=\$actualmax'; rollback; exit 12; }; echo 'OK CPU min=\$actualmin max=\$actualmax governor=\$(cat \"\$d/scaling_governor\" 2>/dev/null)'"
    return result(shell.runRoot(s),"Falha ao aplicar CPU.")
  }

  fun applyGpu(minHz:Long,maxHz:Long,governor:String?):String {
    require(minHz>0 && maxHz>=minHz); require(governor==null || governor.matches(Regex("[A-Za-z0-9_]+")))
    val d="/sys/class/devfreq/13000000.mali"
    val govCheck=if(governor==null) "" else "available=\$(cat \"\$d/available_governors\" 2>/dev/null); case \" \$available \" in *' "+governor+" '*) ;; *) echo 'GPU_GOVERNOR_UNAVAILABLE'; exit 7;; esac; "
    val govWrite=if(governor==null) "" else "if [ \"\$oldgov\" != '"+governor+"' ]; then if ! printf '%s' '"+governor+"' > \"\$d/governor\"; then echo 'GPU_GOVERNOR_WRITE_FAILED'; exit 8; fi; actualgov=\$(cat \"\$d/governor\" 2>/dev/null); [ \"\$actualgov\" = '"+governor+"' ] || { echo 'GPU_GOVERNOR_READBACK_MISMATCH'; exit 9; }; fi; "
    val s="d='"+d+"'; [ -r \"\$d/available_frequencies\" ] && [ -w \"\$d/min_freq\" ] && [ -w \"\$d/max_freq\" ] || { echo 'GPU_DEVFREQ_NODES_UNAVAILABLE'; exit 2; }; oldmin=\$(cat \"\$d/min_freq\") || exit 2; oldmax=\$(cat \"\$d/max_freq\") || exit 2; oldgov=\$(cat \"\$d/governor\" 2>/dev/null); freqs=\$(cat \"\$d/available_frequencies\" 2>/dev/null); hasmin=0; hasmax=0; for f in \$freqs; do [ \"\$f\" = '"+minHz+"' ] && hasmin=1; [ \"\$f\" = '"+maxHz+"' ] && hasmax=1; done; [ \"\$hasmin\" = 1 ] && [ \"\$hasmax\" = 1 ] || { echo 'GPU_FREQUENCY_NOT_IN_OPP_TABLE'; exit 3; }; "+govCheck+govWrite+"rollback(){ cm=\$(cat \"\$d/min_freq\" 2>/dev/null); cx=\$(cat \"\$d/max_freq\" 2>/dev/null); if [ \"\$oldmin\" -gt \"\$cx\" ]; then printf '%s' \"\$oldmax\" > \"\$d/max_freq\" 2>/dev/null; printf '%s' \"\$oldmin\" > \"\$d/min_freq\" 2>/dev/null; elif [ \"\$oldmax\" -lt \"\$cm\" ]; then printf '%s' \"\$oldmin\" > \"\$d/min_freq\" 2>/dev/null; printf '%s' \"\$oldmax\" > \"\$d/max_freq\" 2>/dev/null; else printf '%s' \"\$oldmin\" > \"\$d/min_freq\" 2>/dev/null; printf '%s' \"\$oldmax\" > \"\$d/max_freq\" 2>/dev/null; fi; [ -z \"\$oldgov\" ] || printf '%s' \"\$oldgov\" > \"\$d/governor\" 2>/dev/null; }; if [ '"+maxHz+"' -lt \"\$oldmin\" ]; then if ! printf '%s' '"+minHz+"' > \"\$d/min_freq\"; then echo 'GPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; if ! printf '%s' '"+maxHz+"' > \"\$d/max_freq\"; then echo 'GPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; elif [ '"+minHz+"' -gt \"\$oldmax\" ]; then if ! printf '%s' '"+maxHz+"' > \"\$d/max_freq\"; then echo 'GPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; if ! printf '%s' '"+minHz+"' > \"\$d/min_freq\"; then echo 'GPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; elif [ '"+maxHz+"' -lt \"\$oldmax\" ]; then if [ \"\$oldmax\" != '"+maxHz+"' ] && ! printf '%s' '"+maxHz+"' > \"\$d/max_freq\"; then echo 'GPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; if [ \"\$oldmin\" != '"+minHz+"' ] && ! printf '%s' '"+minHz+"' > \"\$d/min_freq\"; then echo 'GPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; else if [ \"\$oldmin\" != '"+minHz+"' ] && ! printf '%s' '"+minHz+"' > \"\$d/min_freq\"; then echo 'GPU_MIN_WRITE_FAILED'; rollback; exit 10; fi; if [ \"\$oldmax\" != '"+maxHz+"' ] && ! printf '%s' '"+maxHz+"' > \"\$d/max_freq\"; then echo 'GPU_MAX_WRITE_FAILED'; rollback; exit 11; fi; fi; actualmin=\$(cat \"\$d/min_freq\"); actualmax=\$(cat \"\$d/max_freq\"); [ \"\$actualmin\" = '"+minHz+"' ] && [ \"\$actualmax\" = '"+maxHz+"' ] || { echo 'GPU_LIMIT_READBACK_MISMATCH min=\$actualmin max=\$actualmax'; rollback; exit 12; }; echo 'OK GPU min=\$actualmin max=\$actualmax governor=\$(cat \"\$d/governor\" 2>/dev/null)'"
    return result(shell.runRoot(s),"Falha ao aplicar GPU.")
  }

  fun readThermal(): ControlSnapshot {
    if (!shell.rootCheck()) return ControlSnapshot(false, message="Autorize o KLEE no KernelSU.")
    val script = "for d in /sys/class/thermal/thermal_zone[0-9]*; do [ -d \"\$d\" ] || continue; t=\$(cat \"\$d/type\" 2>/dev/null); v=\$(cat \"\$d/temp\" 2>/dev/null); [ -n \"\$t\" ] && [ -n \"\$v\" ] && printf 'TEMP|%s|%s\\n' \"\$t\" \"\$v\"; done; for d in /sys/class/thermal/cooling_device[0-9]*; do [ -d \"\$d\" ] || continue; t=\$(cat \"\$d/type\" 2>/dev/null); v=\$(cat \"\$d/cur_state\" 2>/dev/null); m=\$(cat \"\$d/max_state\" 2>/dev/null); printf 'COOL|%s|%s|%s\\n' \"\$t\" \"\$v\" \"\$m\"; done; for f in /sys/class/thermal/thermal_message/board_sensor_temp /sys/class/thermal/thermal_message/display_therm_temp /sys/class/thermal/thermal_message/temp_state /sys/class/thermal/thermal_message/balance_mode; do [ -r \"\$f\" ] && printf 'EXTRA|%s|%s\\n' \"\$(basename \"\$f\")\" \"\$(cat \"\$f\" 2>/dev/null)\"; done"
    val r=shell.runReadOnly(script)
    return ControlSnapshot(true, message=if(r.success && r.stdout.isNotEmpty()) r.stdout.joinToString("\n") else "Nenhuma zona térmica legível foi encontrada.")
  }

  fun readPower(): ControlSnapshot {
    if (!shell.rootCheck()) return ControlSnapshot(false, message="Autorize o KLEE no KernelSU.")
    val script = "d=/sys/class/power_supply/battery; for n in status capacity voltage_now current_now temp charge_counter; do [ -r \"\$d/\$n\" ] && printf '%s|%s\\n' \"\$n\" \"\$(cat \"\$d/\$n\" 2>/dev/null)\"; done; echo SUPPLIES; for d in /sys/class/power_supply/*; do [ -d \"\$d\" ] || continue; printf 'SUPPLY|%s|%s\\n' \"\$(basename \"\$d\")\" \"\$(cat \"\$d/type\" 2>/dev/null)\"; done"
    val r=shell.runReadOnly(script)
    return ControlSnapshot(true, message=if(r.success && r.stdout.isNotEmpty()) r.stdout.joinToString("\n") else "Não foi possível ler /sys/class/power_supply/battery.")
  }

  fun readMonitor(): ControlSnapshot {
    if (!shell.rootCheck()) return ControlSnapshot(false, message="Autorize o KLEE no KernelSU.")
    val script = "for d in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do [ -d \"\$d\" ] || continue; printf 'CPU|%s|%s|%s\\n' \"\$(basename \"\$d\")\" \"\$(cat \"\$d/scaling_cur_freq\" 2>/dev/null)\" \"\$(cat \"\$d/scaling_governor\" 2>/dev/null)\"; done; for d in /sys/class/devfreq/13000000.mali /sys/class/devfreq/*mali*; do [ -d \"\$d\" ] || continue; printf 'GPU|%s|%s\\n' \"\$(cat \"\$d/cur_freq\" 2>/dev/null)\" \"\$(cat \"\$d/governor\" 2>/dev/null)\"; break; done; printf 'LOAD|%s\\n' \"\$(cat /proc/loadavg 2>/dev/null)\"; printf 'STAT|%s\\n' \"\$(head -n 1 /proc/stat 2>/dev/null)\""
    val r=shell.runReadOnly(script)
    return ControlSnapshot(true, message=if(r.success && r.stdout.isNotEmpty()) r.stdout.joinToString("\n") else "Não foi possível coletar métricas do sistema.")
  }

}
