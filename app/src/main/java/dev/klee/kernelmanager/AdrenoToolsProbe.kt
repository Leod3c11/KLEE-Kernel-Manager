package dev.klee.kernelmanager

import android.content.Context
import android.util.Log
import java.io.File

data class AdrenoToolsProbeResult(
    val driverId: String,
    val driverLabel: String,
    val success: Boolean,
    val deviceName: String = "",
    val apiVersion: String = "",
    val driverVersion: String = "",
    val driverName: String = "",
    val driverInfo: String = "",
    val driverVkId: String = "",
    val extensionCount: Int = 0,
    val elapsedMs: Long = 0L,
    val error: String = ""
)

object AdrenoToolsProbe {

    private const val TAG = "KLEE-AdrenoProbe"

    @Volatile
    private var libLoaded = false

    private fun ensureLoaded() {
        if (!libLoaded) {
            synchronized(this) {
                if (!libLoaded) {
                    System.loadLibrary("klee_vk_bridge")
                    libLoaded = true
                }
            }
        }
    }

    private external fun nativeProbeDriver(
        tempDir: String,
        hookLibDir: String,
        customDriverDir: String,
        customDriverName: String,
        fileRedirectDir: String
    ): String

    fun probeDriver(
        context: Context,
        driver: VulkanDriverPackage,
        shell: RootShell
    ): AdrenoToolsProbeResult {
        return try {
            require(driver.installDir.matches(Regex("^/data/adb/klee_drivers/drv_[a-f0-9-]+$"))) {
                "Diretório do driver inválido."
            }
            require(driver.primaryLibName.matches(Regex("^[A-Za-z0-9._-]+\\.so$"))) {
                "Biblioteca principal inválida."
            }

            ensureLoaded()

            val probeRoot = File(context.filesDir, "adrenotools_probe/${driver.id}")
            val driverDir = File(probeRoot, "driver").apply { mkdirs() }
            val tempDir = File(probeRoot, "temp").apply { mkdirs() }
            val redirectDir = File(probeRoot, "redirect").apply { mkdirs() }

            val dstPath = driverDir.absolutePath
            val filesPath = context.filesDir.absolutePath
            require(dstPath.matches(Regex("^[A-Za-z0-9._/-]+$")))
            require(filesPath.matches(Regex("^[A-Za-z0-9._/-]+$")))

            // Copy driver .so files from root-only /data/adb/klee_drivers into KLEE's own filesDir
            val stageCmd =
                "( " +
                "uidgid=\$(stat -c '%u:%g' '$filesPath' 2>/dev/null); " +
                "mkdir -p '$dstPath' && " +
                "rm -f '$dstPath'/*.so 2>/dev/null || true; " +
                "cp -f '${driver.installDir}'/*.so '$dstPath/' || { echo 'COPY_FAILED'; exit 2; }; " +
                "[ -n \"\$uidgid\" ] && chown -R \"\$uidgid\" '${probeRoot.absolutePath}' 2>/dev/null || true; " +
                "chmod 755 '${probeRoot.absolutePath}' '$dstPath' '${tempDir.absolutePath}' '${redirectDir.absolutePath}' && " +
                "chmod 755 '$dstPath'/*.so && " +
                "restorecon -RF '${probeRoot.absolutePath}' 2>/dev/null || true; " +
                "echo OK " +
                ")"
            val r = shell.runRoot(stageCmd)
            if (!r.success) {
                return AdrenoToolsProbeResult(
                    driverId = driver.id,
                    driverLabel = driver.name,
                    success = false,
                    error = "Falha ao copiar driver para teste: ${r.combinedOutput().ifBlank { "erro root" }}"
                )
            }

            val hookLibDir = context.applicationInfo.nativeLibraryDir
            Log.i(TAG, "Probing ${driver.name} (${driver.primaryLibName}) hookLibDir=$hookLibDir")

            val raw = nativeProbeDriver(
                tempDir = tempDir.absolutePath,
                hookLibDir = hookLibDir,
                customDriverDir = driverDir.absolutePath,
                customDriverName = driver.primaryLibName,
                fileRedirectDir = redirectDir.absolutePath
            )

            parseProbeOutput(driver, raw)
        } catch (t: Throwable) {
            Log.e(TAG, "Probe exception", t)
            AdrenoToolsProbeResult(
                driverId = driver.id,
                driverLabel = driver.name,
                success = false,
                error = t.message ?: t.javaClass.simpleName
            )
        }
    }

    private fun parseProbeOutput(driver: VulkanDriverPackage, raw: String): AdrenoToolsProbeResult {
        val parts = raw.split("|")
        if (parts.firstOrNull() != "OK") {
            return AdrenoToolsProbeResult(
                driverId = driver.id,
                driverLabel = driver.name,
                success = false,
                error = parts.getOrNull(1)?.ifBlank { raw } ?: raw
            )
        }
        return AdrenoToolsProbeResult(
            driverId = driver.id,
            driverLabel = driver.name,
            success = true,
            deviceName = parts.getOrNull(1).orEmpty(),
            apiVersion = parts.getOrNull(2).orEmpty(),
            driverVersion = parts.getOrNull(3).orEmpty(),
            driverName = parts.getOrNull(4).orEmpty(),
            driverInfo = parts.getOrNull(5).orEmpty(),
            driverVkId = parts.getOrNull(6).orEmpty(),
            extensionCount = parts.getOrNull(7)?.toIntOrNull() ?: 0,
            elapsedMs = parts.getOrNull(8)?.toLongOrNull() ?: 0L
        )
    }
}
