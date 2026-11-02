package dev.klee.kernelmanager

data class InspectionResult(
    val rootAvailable: Boolean,
    val summary: String,
    val policies: List<String>
)

class DeviceRepository(private val shell: RootShell) {
    fun inspect(): InspectionResult {
        val root = shell.rootCheck()
        if (!root) {
            return InspectionResult(
                rootAvailable = false,
                summary = "O shell root não foi concedido. Autorize o KLEE no KernelSU e tente novamente.",
                policies = emptyList()
            )
        }

        val identity = shell.runReadOnly(
            "echo MODEL=$(getprop ro.product.model); " +
            "echo DEVICE=$(getprop ro.product.device); " +
            "echo SOC=$(cat /sys/devices/soc0/soc_id 2>/dev/null); " +
            "echo KERNEL=$(uname -r); " +
            "echo ANDROID=$(getprop ro.build.version.release)"
        )

        val policyOutput = shell.runReadOnly(
            "for d in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do " +
            "[ -d \"\$d\" ] || continue; " +
            "echo POLICY=\$(basename \"\$d\"); " +
            "echo CPUS=\$(cat \"\$d/related_cpus\" 2>/dev/null); " +
            "echo DRIVER=\$(cat \"\$d/scaling_driver\" 2>/dev/null); " +
            "echo GOVERNOR=\$(cat \"\$d/scaling_governor\" 2>/dev/null); " +
            "echo MIN=\$(cat \"\$d/cpuinfo_min_freq\" 2>/dev/null); " +
            "echo MAX=\$(cat \"\$d/cpuinfo_max_freq\" 2>/dev/null); " +
            "echo CURRENT=\$(cat \"\$d/scaling_cur_freq\" 2>/dev/null); " +
            "done"
        )

        val gpu = shell.runReadOnly(
            "echo GPU_DEVFREQ=/sys/class/devfreq/13000000.mali; " +
            "echo GPU_NAME=$(cat /sys/class/devfreq/13000000.mali/name 2>/dev/null); " +
            "echo GPU_CUR=$(cat /sys/class/devfreq/13000000.mali/cur_freq 2>/dev/null); " +
            "echo GPU_MIN=$(cat /sys/class/devfreq/13000000.mali/min_freq 2>/dev/null); " +
            "echo GPU_MAX=$(cat /sys/class/devfreq/13000000.mali/max_freq 2>/dev/null); " +
            "echo GPU_GOV=$(cat /sys/class/devfreq/13000000.mali/governor 2>/dev/null)"
        )

        val summary = (identity.stdout + gpu.stdout).joinToString("\n")
        val policies = policyOutput.stdout.joinToString("\n")
            .split("POLICY=")
            .filter { it.isNotBlank() }
            .map { "POLICY=$it" }

        return InspectionResult(true, summary, policies)
    }
}
