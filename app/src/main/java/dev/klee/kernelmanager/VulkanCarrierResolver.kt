package dev.klee.kernelmanager

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

object VulkanCarrierResolver {

    private val preferredPackages = listOf(
        "com.google.android.apps.wellbeing",
        "com.google.android.tts",
        "com.google.android.ext.services",
        "com.android.ext.services",
        "com.google.android.printservice.recommendation",
        "com.google.android.marvin.talkback",
        "com.google.android.apps.turbo",
        "com.google.android.as",
        "com.google.android.projection.gearhead"
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

    private data class Candidate(
        val pkg: String,
        val nativeLibDir: String,
        val isDataApp: Boolean,
        val rank: Int
    )

    fun resolveCarrier(context: Context, shell: RootShell): VulkanCarrierInfo? {
        if (!shell.rootCheck()) return null

        val origProp = shell.runReadOnly(
            "( if [ -f /data/adb/klee_drivers/orig_gfx_driver.txt ]; then cat /data/adb/klee_drivers/orig_gfx_driver.txt; else getprop ro.gfx.driver.0; fi ) 2>/dev/null"
        ).stdout.firstOrNull()?.trim().orEmpty()

        val pm = context.packageManager
        val flags = PackageManager.MATCH_SYSTEM_ONLY or PackageManager.GET_META_DATA
        @Suppress("DEPRECATION")
        val pkgs = try {
            pm.getInstalledPackages(flags)
        } catch (_: Throwable) {
            return null
        }

        val candidates = mutableListOf<Candidate>()
        for (pi in pkgs) {
            val ai = pi.applicationInfo ?: continue
            val pkg = pi.packageName ?: continue
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0) continue
            if (ai.targetSdkVersion < 26) continue
            if (ai.metaData == null || ai.metaData.isEmpty) continue
            val natDir = ai.nativeLibraryDir?.trim().orEmpty()
            if (natDir.isBlank()) continue
            if (!natDir.endsWith("/arm64") && !natDir.endsWith("/arm64-v8a")) continue
            if (pkg != origProp && isBlocked(pkg)) continue

            val isDataApp = natDir.startsWith("/data/app/")
            val prefIdx = preferredPackages.indexOf(pkg)
            val rank = when {
                pkg == origProp && origProp.isNotBlank() -> 0
                prefIdx >= 0 && isDataApp -> 10 + prefIdx
                isDataApp -> 100
                prefIdx >= 0 -> 200 + prefIdx
                else -> 300
            }
            candidates.add(Candidate(pkg, natDir, isDataApp, rank))
        }

        if (candidates.isEmpty()) return null
        val top = candidates.sortedBy { it.rank }.take(28)

        // Verify primaryCpuAbi=arm64-v8a, forceQueryable=true, and filesystem mountability in one shell pass
        val scriptBuilder = StringBuilder("( ")
        for (c in top) {
            if (!c.pkg.matches(Regex("^[A-Za-z0-9._-]+$"))) continue
            if (!c.nativeLibDir.matches(Regex("^[A-Za-z0-9._/~=+@-]+$"))) continue
            scriptBuilder.append(
                "d=\$(dumpsys package '${c.pkg}' 2>/dev/null | head -n 90); " +
                "abi=0; fq=0; mode='NONE'; " +
                "printf '%s' \"\$d\" | grep -q 'primaryCpuAbi=arm64-v8a' && abi=1; " +
                "printf '%s' \"\$d\" | grep -q 'forceQueryable=true' && fq=1; " +
                "nat='${c.nativeLibDir}'; " +
                "case \"\$nat\" in /data/app/*) p=\$(dirname \"\$nat\"); pp=\$(dirname \"\$p\"); if [ -d \"\$nat\" ] || [ -d \"\$p\" ] || [ -d \"\$pp\" ]; then mode='DATA_APP'; fi ;; *) if [ -d \"\$nat\" ]; then mode='TMPFS'; fi ;; esac; " +
                "printf 'CAND|${c.pkg}|%s|%s|%s|%s\\n' \"\$abi\" \"\$fq\" \"\$mode\" \"\$nat\"; "
            )
        }
        scriptBuilder.append(")")

        val r = shell.runReadOnly(scriptBuilder.toString())
        val verifiedFq = mutableListOf<VulkanCarrierInfo>()
        val verifiedFallback = mutableListOf<VulkanCarrierInfo>()

        r.stdout.forEach { line ->
            if (!line.startsWith("CAND|")) return@forEach
            val parts = line.split("|")
            if (parts.size < 6) return@forEach
            val pkg = parts[1]
            val abiOk = parts[2] == "1"
            val fqOk = parts[3] == "1"
            val mode = parts[4]
            val natDir = parts[5]
            if (!abiOk || mode == "NONE") return@forEach
            val info = VulkanCarrierInfo(
                packageName = pkg,
                nativeLibraryDir = natDir,
                mode = mode,
                forceQueryable = fqOk
            )
            if (fqOk) verifiedFq.add(info) else verifiedFallback.add(info)
        }

        // Strongly prefer forceQueryable=true + DATA_APP first, then forceQueryable=true + TMPFS
        return verifiedFq.firstOrNull { it.mode == "DATA_APP" }
            ?: verifiedFq.firstOrNull()
            ?: verifiedFallback.firstOrNull { it.packageName in preferredPackages && it.mode == "DATA_APP" }
            ?: verifiedFallback.firstOrNull { it.packageName in preferredPackages }
            ?: verifiedFallback.firstOrNull { it.mode == "DATA_APP" }
            ?: verifiedFallback.firstOrNull()
    }

    private fun isBlocked(pkg: String): Boolean =
        blockedPrefixes.any { prefix ->
            if (prefix.endsWith(".")) pkg.startsWith(prefix) else pkg == prefix
        }
}
