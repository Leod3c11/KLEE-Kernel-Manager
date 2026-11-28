package dev.klee.kernelmanager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class VulkanDriverPackage(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val primaryLibName: String,
    val extraLibs: List<String>,
    val installDir: String,
    val sizeBytes: Long,
    val importedAt: Long
)

data class VulkanCarrierInfo(
    val packageName: String,
    val nativeLibraryDir: String,
    val mode: String, // "DATA_APP" or "TMPFS"
    val forceQueryable: Boolean = true
)

data class SystemVulkanInfo(
    val root: Boolean = false,
    val vendorHwDir: String = "/vendor/lib64/hw",
    val vulkanFileName: String = "",
    val vulkanFullPath: String = "",
    val isSymlink: Boolean = false,
    val symlinkTarget: String = "",
    val angleAvailable: Boolean = false,
    val overlayMounted: Boolean = false,
    val activeDriverId: String = "",
    val activePackage: String = "",
    val roHardwareVulkan: String = "",
    val roHardwareEgl: String = "",
    val roGfxDriver0: String = "",
    val resetpropPath: String = "",
    val carrierPackage: String = "",
    val carrierLibDir: String = "",
    val carrierMode: String = "",
    val optInApps: String = "",
    val message: String = ""
)

enum class VulkanLoadStatus {
    CHECKING,
    LOADED_CUSTOM,
    LOADED_STOCK,
    VULKAN_NOT_MAPPED,
    APP_NOT_RUNNING,
    ERROR_DETECTED
}

data class VulkanVerificationResult(
    val packageName: String,
    val appLabel: String,
    val driverName: String,
    val status: VulkanLoadStatus,
    val pid: Int? = null,
    val expectedInode: String = "",
    val matchedMaps: List<String> = emptyList(),
    val angleLoaded: Boolean = false,
    val unloadedBuiltin: Boolean = false,
    val carrierPathMatched: Boolean = false,
    val relevantLogcat: List<String> = emptyList(),
    val summary: String = "",
    val timestamp: String = ""
)

class VulkanDriverStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("klee_vulkan_drivers_v1", Context.MODE_PRIVATE)

    fun loadDrivers(): List<VulkanDriverPackage> {
        val raw = prefs.getString(KEY_DRIVERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val extrasArr = o.optJSONArray("extraLibs") ?: JSONArray()
                    val extras = buildList {
                        for (j in 0 until extrasArr.length()) add(extrasArr.getString(j))
                    }
                    add(
                        VulkanDriverPackage(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            version = o.optString("version", ""),
                            author = o.optString("author", ""),
                            description = o.optString("description", ""),
                            primaryLibName = o.getString("primaryLibName"),
                            extraLibs = extras,
                            installDir = o.getString("installDir"),
                            sizeBytes = o.optLong("sizeBytes", 0L),
                            importedAt = o.optLong("importedAt", System.currentTimeMillis())
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveDrivers(list: List<VulkanDriverPackage>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(
                JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                    put("version", d.version)
                    put("author", d.author)
                    put("description", d.description)
                    put("primaryLibName", d.primaryLibName)
                    put("extraLibs", JSONArray(d.extraLibs))
                    put("installDir", d.installDir)
                    put("sizeBytes", d.sizeBytes)
                    put("importedAt", d.importedAt)
                }
            )
        }
        prefs.edit().putString(KEY_DRIVERS, arr.toString()).apply()
    }

    fun importFromZip(uri: Uri, shell: RootShell): VulkanDriverPackage {
        if (!shell.rootCheck()) error("Acesso root necessário para instalar driver Vulkan.")

        val zipDisplayName = resolveFileName(uri).removeSuffix(".zip").ifBlank { "Custom Vulkan Driver" }
        val id = "drv_" + UUID.randomUUID().toString().take(8)
        val stageDir = File(context.cacheDir, "driver_stage/$id").apply {
            deleteRecursively()
            mkdirs()
        }

        var metaJsonText: String? = null
        val extractedSoFiles = mutableListOf<File>()
        var totalBytes = 0L

        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: error("Não foi possível abrir o arquivo .zip selecionado.")

            stream.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val rawName = entry.name.substringAfterLast('/')
                            if (rawName.isNotBlank() && !rawName.contains("..")) {
                                when {
                                    rawName.equals("meta.json", ignoreCase = true) -> {
                                        metaJsonText = zis.readBytes().toString(Charsets.UTF_8)
                                    }
                                    rawName.endsWith(".so", ignoreCase = true) &&
                                        rawName.matches(Regex("^[A-Za-z0-9._-]+\\.so$")) -> {
                                        // Prefer arm64-v8a if zip has multiple ABIs
                                        val entryLower = entry.name.lowercase()
                                        if (!entryLower.contains("armeabi") && !entryLower.contains("x86")) {
                                            val outFile = File(stageDir, rawName)
                                            FileOutputStream(outFile).use { fos ->
                                                val buf = ByteArray(16 * 1024)
                                                var n: Int
                                                while (zis.read(buf).also { n = it } > 0) {
                                                    fos.write(buf, 0, n)
                                                    totalBytes += n
                                                }
                                            }
                                            extractedSoFiles.add(outFile)
                                        }
                                    }
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (extractedSoFiles.isEmpty()) {
                error("Nenhum arquivo .so ARM64 encontrado dentro do .zip.")
            }

            // Parse AdrenoTools / PanVK meta.json if present
            var name = zipDisplayName
            var version = ""
            var author = ""
            var description = ""
            var metaLibName = ""
            metaJsonText?.let { raw ->
                try {
                    val m = JSONObject(raw)
                    m.optString("name").takeIf { it.isNotBlank() }?.let { name = it }
                    version = m.optString("driverVersion").ifBlank { m.optString("packageVersion") }
                    author = m.optString("author").ifBlank { m.optString("vendor") }
                    description = m.optString("description")
                    metaLibName = m.optString("libraryName")
                } catch (_: Throwable) {
                }
            }

            // Pick primary Vulkan .so
            val primaryFile = extractedSoFiles.firstOrNull { it.name == metaLibName }
                ?: extractedSoFiles.firstOrNull { it.name.contains("vulkan", ignoreCase = true) }
                ?: extractedSoFiles.firstOrNull { it.name.contains("panfrost", ignoreCase = true) || it.name.contains("panvk", ignoreCase = true) }
                ?: extractedSoFiles.maxByOrNull { it.length() }
                ?: error("Biblioteca principal .so não identificada.")

            // Validate ELF 64-bit AArch64 header (0x7F 'E' 'L' 'F', class=2, e_machine=0x00B7)
            val header = primaryFile.inputStream().use {
                val b = ByteArray(20)
                val read = it.read(b)
                if (read < 20) error("Arquivo ${primaryFile.name} corrompido ou muito pequeno.")
                b
            }
            val isElf = header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
            val is64Bit = header[4] == 2.toByte()
            val isAArch64 = (header[18].toInt() and 0xFF) == 0xB7 && (header[19].toInt() and 0xFF) == 0x00
            if (!isElf || !is64Bit || !isAArch64) {
                error("Biblioteca ${primaryFile.name} não é um binário ELF ARM64 (aarch64) válido.")
            }

            val installDir = "/data/adb/klee_drivers/$id"
            val stagePath = stageDir.absolutePath
            require(stagePath.matches(Regex("^[A-Za-z0-9._/-]+$")))
            require(installDir.matches(Regex("^/data/adb/klee_drivers/drv_[a-f0-9-]+$")))

            val installCmd =
                "mkdir -p '$installDir' && " +
                "cp -f '$stagePath'/*.so '$installDir/' && " +
                "chmod 755 /data/adb/klee_drivers '$installDir' && " +
                "chmod 644 '$installDir'/*.so && " +
                "(chcon -R u:object_r:same_process_hal_file:s0 '$installDir' 2>/dev/null || chcon -R u:object_r:vendor_file:s0 '$installDir' 2>/dev/null || true) && " +
                "echo OK"

            val r = shell.runRoot(installCmd)
            if (!r.success) {
                error("Falha ao instalar driver em $installDir: ${r.stderr.plus(r.stdout).joinToString(" ")}")
            }

            val extras = extractedSoFiles.map { it.name }.filterNot { it == primaryFile.name }
            val pkg = VulkanDriverPackage(
                id = id,
                name = name,
                version = version.ifBlank { "Custom" },
                author = author.ifBlank { "Desconhecido" },
                description = description.ifBlank { "Driver Vulkan importado (${primaryFile.name})" },
                primaryLibName = primaryFile.name,
                extraLibs = extras,
                installDir = installDir,
                sizeBytes = totalBytes,
                importedAt = System.currentTimeMillis()
            )

            val updated = listOf(pkg) + loadDrivers()
            saveDrivers(updated)
            return pkg
        } finally {
            stageDir.deleteRecursively()
        }
    }

    fun deleteDriver(driverId: String, shell: RootShell): List<VulkanDriverPackage> {
        val existing = loadDrivers().firstOrNull { it.id == driverId }
        val updated = loadDrivers().filterNot { it.id == driverId }
        saveDrivers(updated)
        if (existing != null && existing.installDir.matches(Regex("^/data/adb/klee_drivers/drv_[a-f0-9-]+$"))) {
            shell.runRoot("rm -rf '${existing.installDir}'")
        }
        return updated
    }

    private fun resolveFileName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "driver.zip"
        } catch (_: Throwable) {
            "driver.zip"
        }
    }

    companion object {
        private const val KEY_DRIVERS = "vulkan_drivers_json"
    }
}
