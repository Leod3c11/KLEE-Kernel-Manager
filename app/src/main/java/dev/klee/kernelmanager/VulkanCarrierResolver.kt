package dev.klee.kernelmanager

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File

object VulkanCarrierResolver {

    @Volatile
    var lastProbeReport: String = ""
        private set

    private val preferredPackages = listOf(
        "com.google.android.apps.wellbeing",
        "com.google.android.tts",
        "com.google.android.ext.services",
        "com.android.ext.services",
        "com.google.android.printservice.recommendation",
        "com.google.android.marvin.talkback",
        "com.google.android.apps.turbo",
        "com.google.android.projection.gearhead",
        "com.google.android.as"
    )

    private val blockedPrefixes = listOf(
        "android",
        "com.android.systemui",
        "com.google.android.gms",
        "com.android.vending",
        "com.android.phone",
        "com.android.shell",
        "com.android.settings",
        "com.android.webview",
        "com.google.android.webview",
        "com.android.chrome",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.providers.",
        "com.miui.home",
        "com.miui.securitycenter",
        "com.xiaomi.xmsf",
        "com.mediatek.ims",
        "com.qualcomm."
    )

    private data class DumpsysPkg(
        val pkg: String,
        val primaryCpuAbi: String,
        val manifestForceQueryable: Boolean,
        val codePath: String
    )

    private val pkgRegex = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val safePkgRegex = Regex("^[A-Za-z0-9._-]+$")
    private val safePathRegex = Regex("^[A-Za-z0-9._/~=+@-]+$")

    fun resolveCarrier(context: Context, shell: RootShell): VulkanCarrierInfo? {
        if (!shell.rootCheck()) {
            lastProbeReport = "root=false"
            return null
        }

        // Single fast root pass (<0.4s):
        // 1) Read original ro.gfx.driver.0
        // 2) Read forceQueryable set from `dumpsys package queries` (captures BOTH manifest forceQueryable=true AND sysconfig <force-queryable>!)
        // 3) Scan ALL packages in `dumpsys package packages` for primaryCpuAbi=arm64-v8a, forceQueryable=true, and codePath
        val script =
            "( " +
            "if [ -f /data/adb/klee_drivers/orig_gfx_driver.txt ]; then " +
            "  printf 'ORIG|%s\\n' \"\$(cat /data/adb/klee_drivers/orig_gfx_driver.txt 2>/dev/null | tr -d '\\r\\n')\"; " +
            "else " +
            "  printf 'ORIG|%s\\n' \"\$(getprop ro.gfx.driver.0 2>/dev/null | tr -d '\\r\\n')\"; " +
            "fi; " +
            "dumpsys package queries 2>/dev/null | awk '" +
            "  /^[[:space:]]*forceQueryable:/ { in_fq=1; print \"FQ|\" \$0; next } " +
            "  in_fq && /^[[:space:]]*[A-Za-z][A-Za-z0-9_]*:/ { in_fq=0 } " +
            "  in_fq { print \"FQ|\" \$0 }" +
            "'; " +
            "dumpsys package packages 2>/dev/null | awk '" +
            "  /^[[:space:]]*Package \\[/ { " +
            "    if (pkg != \"\" && abi == \"arm64-v8a\") printf \"PKG|%s|%s|%s|%s\\n\", pkg, abi, fq, cp; " +
            "    s = \$0; sub(/^.*\\[/, \"\", s); sub(/\\].*\$/, \"\", s); " +
            "    pkg = s; abi = \"\"; fq = 0; cp = \"\"; next " +
            "  } " +
            "  pkg != \"\" && /primaryCpuAbi=/ { " +
            "    v = \$0; sub(/^.*primaryCpuAbi=/, \"\", v); sub(/[[:space:]].*\$/, \"\", v); abi = v " +
            "  } " +
            "  pkg != \"\" && /forceQueryable=true/ { fq = 1 } " +
            "  pkg != \"\" && /codePath=/ { " +
            "    v = \$0; sub(/^.*codePath=/, \"\", v); sub(/[[:space:]].*\$/, \"\", v); cp = v " +
            "  } " +
            "  END { " +
            "    if (pkg != \"\" && abi == \"arm64-v8a\") printf \"PKG|%s|%s|%s|%s\\n\", pkg, abi, fq, cp " +
            "  }" +
            "' " +
            ")"

        val r = shell.runReadOnly(script)
        var origProp = ""
        val fqFromQueries = linkedSetOf<String>()
        val arm64Pkgs = linkedMapOf<String, DumpsysPkg>()

        r.stdout.forEach { line ->
            when {
                line.startsWith("ORIG|") -> {
                    origProp = line.removePrefix("ORIG|").trim()
                }
                line.startsWith("FQ|") -> {
                    val body = line.removePrefix("FQ|")
                    pkgRegex.findAll(body).forEach { m ->
                        val p = m.value
                        if (safePkgRegex.matches(p)) fqFromQueries.add(p)
                    }
                }
                line.startsWith("PKG|") -> {
                    val parts = line.split("|")
                    if (parts.size >= 5) {
                        val pkg = parts[1].trim()
                        val abi = parts[2].trim()
                        val fq = parts[3].trim() == "1"
                        val cp = parts[4].trim()
                        if (pkg.isNotEmpty() && abi == "arm64-v8a") {
                            arm64Pkgs[pkg] = DumpsysPkg(pkg, abi, fq, cp)
                        }
                    }
                }
            }
        }

        val pm = context.packageManager
        val flags = PackageManager.MATCH_SYSTEM_ONLY or PackageManager.GET_META_DATA
        @Suppress("DEPRECATION")
        val pkgs = try {
            pm.getInstalledPackages(flags)
        } catch (t: Throwable) {
            lastProbeReport = "getInstalledPackages error: ${t.message}"
            return null
        }

        val eligible = mutableListOf<Pair<Int, VulkanCarrierInfo>>()
        val sampleLog = mutableListOf<String>()

        for (pi in pkgs) {
            val ai = pi.applicationInfo ?: continue
            val pkg = pi.packageName ?: continue
            if (!safePkgRegex.matches(pkg)) continue
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0) continue
            if (ai.targetSdkVersion < 26) continue
            val isOrig = pkg == origProp && origProp.isNotBlank()
            if (!isOrig && (ai.metaData == null || ai.metaData.isEmpty)) continue
            if (!isOrig && isBlocked(pkg)) continue

            val dp = arm64Pkgs[pkg] ?: continue // MUST have primaryCpuAbi=arm64-v8a!
            val isFq = dp.manifestForceQueryable || fqFromQueries.contains(pkg)
            if (!isFq) continue // MUST be forceQueryable so Android 11-16 untrusted apps can see it in GraphicsEnvironment!

            val sourceDir = ai.sourceDir?.trim().orEmpty()
            val codePath = (if (sourceDir.isNotBlank()) File(sourceDir).parentFile?.absolutePath.orEmpty() else "")
                .ifBlank { dp.codePath }
            if (codePath.isBlank() || !safePathRegex.matches(codePath)) continue
            if (codePath.startsWith("/apex/")) continue // Never bind-mount inside /apex

            val natDir = ai.nativeLibraryDir?.trim().orEmpty().ifBlank { "$codePath/lib/arm64" }
            if (!safePathRegex.matches(natDir)) continue
            if (!natDir.endsWith("/arm64") && !natDir.endsWith("/arm64-v8a")) continue

            val isDataApp = codePath.startsWith("/data/app/") && natDir.startsWith("/data/app/")
            val mode = if (isDataApp) "DATA_APP" else "OVERLAY"
            val prefIdx = preferredPackages.indexOf(pkg)

            val rank = when {
                isOrig && isDataApp -> 0
                isOrig -> 5
                prefIdx >= 0 && isDataApp -> 10 + prefIdx
                isDataApp -> 100
                prefIdx >= 0 -> 200 + prefIdx
                else -> 300
            }

            val info = VulkanCarrierInfo(
                packageName = pkg,
                nativeLibraryDir = natDir,
                mode = mode,
                forceQueryable = true,
                codePath = codePath
            )
            eligible.add(rank to info)
            if (sampleLog.size < 12) {
                sampleLog.add("$pkg($mode,fq=1)")
            }
        }

        val sorted = eligible.sortedBy { it.first }.map { it.second }
        val chosen = sorted.firstOrNull()

        lastProbeReport = "pm=${pkgs.size}, fqSet=${fqFromQueries.size}, arm64=${arm64Pkgs.size}, eligibleFq=${sorted.size}, chosen=${chosen?.packageName ?: "NONE"}[${chosen?.mode ?: "-"}] | matches: ${sampleLog.joinToString("; ")}"
        return chosen
    }

    private fun isBlocked(pkg: String): Boolean =
        blockedPrefixes.any { prefix ->
            if (prefix.endsWith(".")) pkg.startsWith(prefix) else pkg == prefix
        }
}
