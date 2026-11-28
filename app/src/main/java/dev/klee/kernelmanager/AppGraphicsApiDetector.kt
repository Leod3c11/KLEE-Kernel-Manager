package dev.klee.kernelmanager

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

enum class GraphicsApiKind(val shortLabel: String, val recommendAngle: Boolean?) {
    VULKAN_NATIVE("Vulkan Nativo", false),
    OPENGL_ES("OpenGL ES", true),
    HYBRID("Vulkan / GLES", null),
    UNKNOWN("Java / UI", null)
}

data class AppGraphicsApiInfo(
    val packageName: String,
    val kind: GraphicsApiKind,
    val detail: String,
    val runtimeConfirmed: Boolean = false
)

/**
 * Detects whether an installed Android app/game natively targets Vulkan, OpenGL ES, or Both (Hybrid)
 * using PackageManager reqFeatures + fast streaming inspection of the app's own native arm64 .so files
 * (skipping injected KLEE/AdrenoTools libraries and Qt framework glue).
 *
 * Also supports upgrading a package's entry with 100%-accurate runtime confirmation from /proc/<pid>/maps.
 */
object AppGraphicsApiDetector {

    private val cache = ConcurrentHashMap<String, AppGraphicsApiInfo>()

    private val KLEE_IGNORED_LIBS = setOf(
        "libklee_vk_bridge.so",
        "libhook_impl.so",
        "libmain_hook.so",
        "libfile_redirect_hook.so",
        "libgsl_alloc_hook.so",
        "libbytehook.so",
        "libshadowhook.so",
        "libshadowhook_nothing.so",
        "libc++_shared.so"
    )

    // Generic UI/runtime frameworks that may reference Vulkan/GLES symbols even when the app itself doesn't
    private val GENERIC_FRAMEWORK_PREFIXES = listOf(
        "libQt5",
        "libQt6",
        "libplugins_",
        "libandroidx.",
        "libflutter",
        "libreact",
        "libhermes",
        "libfbjni",
        "libfolly",
        "libyoga",
        "libimagepipeline",
        "libcronet",
        "libcrashlytics",
        "libfirebase"
    )

    private val VK_TOKENS = listOf(
        "libvulkan.so".toByteArray(Charsets.US_ASCII),
        "vkCreateInstance".toByteArray(Charsets.US_ASCII),
        "vkEnumeratePhysicalDevices".toByteArray(Charsets.US_ASCII)
    )

    private val GL_TOKENS = listOf(
        "libGLESv2.so".toByteArray(Charsets.US_ASCII),
        "libGLESv3.so".toByteArray(Charsets.US_ASCII),
        "libGLESv1_CM.so".toByteArray(Charsets.US_ASCII),
        "libEGL.so".toByteArray(Charsets.US_ASCII)
    )

    fun getCached(packageName: String): AppGraphicsApiInfo? = cache[packageName]

    fun snapshotAll(): Map<String, AppGraphicsApiInfo> = HashMap(cache)

    fun recordRuntimeObservation(
        packageName: String,
        hasVulkanIcd: Boolean,
        hasAngle: Boolean,
        hasStockGles: Boolean
    ): AppGraphicsApiInfo? {
        val existing = cache[packageName]
        val info = when {
            hasAngle && existing?.kind == GraphicsApiKind.VULKAN_NATIVE -> AppGraphicsApiInfo(
                packageName = packageName,
                kind = GraphicsApiKind.VULKAN_NATIVE,
                detail = "Confirmado em execução: Vulkan nativo (ANGLE ativo apenas na UI · pode desligar ANGLE)",
                runtimeConfirmed = true
            )
            hasAngle -> AppGraphicsApiInfo(
                packageName = packageName,
                kind = GraphicsApiKind.OPENGL_ES,
                detail = "Confirmado em execução: OpenGL ES traduzido via ANGLE → Vulkan",
                runtimeConfirmed = true
            )
            hasVulkanIcd -> AppGraphicsApiInfo(
                packageName = packageName,
                kind = GraphicsApiKind.VULKAN_NATIVE,
                detail = "Confirmado em execução: Vulkan nativo ativo no processo",
                runtimeConfirmed = true
            )
            hasStockGles -> AppGraphicsApiInfo(
                packageName = packageName,
                kind = GraphicsApiKind.OPENGL_ES,
                detail = "Confirmado em execução: OpenGL ES nativo (sem Vulkan ICD mapeado)",
                runtimeConfirmed = true
            )
            else -> null
        }
        if (info != null) {
            cache[packageName] = info
        }
        return info
    }

    fun detect(context: Context, packageName: String, forceRefresh: Boolean = false): AppGraphicsApiInfo {
        if (!forceRefresh) {
            cache[packageName]?.let { return it }
        }
        val computed = computeInternal(context, packageName)
        // Preserve runtimeConfirmed entry unless forceRefresh was requested
        val existing = cache[packageName]
        if (!forceRefresh && existing != null && existing.runtimeConfirmed) {
            return existing
        }
        cache[packageName] = computed
        return computed
    }

    private fun computeInternal(context: Context, packageName: String): AppGraphicsApiInfo {
        val pm = context.packageManager
        val pkgInfo = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_CONFIGURATIONS)
        } catch (_: Throwable) {
            return AppGraphicsApiInfo(packageName, GraphicsApiKind.UNKNOWN, "Pacote não encontrado")
        }

        val appInfo = pkgInfo.applicationInfo
            ?: return AppGraphicsApiInfo(packageName, GraphicsApiKind.UNKNOWN, "ApplicationInfo indisponível")

        var manifestVulkan = false
        var manifestGlEsMajor = 0
        pkgInfo.reqFeatures?.forEach { feat ->
            val fname = feat.name.orEmpty()
            if (fname.startsWith("android.hardware.vulkan")) {
                manifestVulkan = true
            }
            if (feat.reqGlEsVersion != 0) {
                val maj = (feat.reqGlEsVersion ushr 16) and 0xFFFF
                if (maj > manifestGlEsMajor) manifestGlEsMajor = maj
            }
        }

        var engineHint: String? = null
        var appHasVulkan = false
        var appHasGles = false
        val vkLibs = mutableListOf<String>()
        val glLibs = mutableListOf<String>()

        // 1. Inspect extracted nativeLibraryDir first (fast direct file I/O)
        val nativeDir = appInfo.nativeLibraryDir?.let { File(it) }
        val scannedNames = hashSetOf<String>()

        fun processSoStream(libName: String, streamProvider: () -> InputStream) {
            if (!libName.endsWith(".so")) return
            if (KLEE_IGNORED_LIBS.contains(libName)) return
            if (!scannedNames.add(libName)) return

            when {
                libName.equals("libunity.so", ignoreCase = true) -> engineHint = "Unity"
                libName.equals("libUE4.so", ignoreCase = true) || libName.equals("libUnreal.so", ignoreCase = true) -> engineHint = "Unreal"
                libName.contains("godot", ignoreCase = true) -> engineHint = "Godot"
                libName.contains("cocos", ignoreCase = true) -> engineHint = "Cocos"
                libName.contains("ppsspp", ignoreCase = true) -> engineHint = "PPSSPP"
                libName.contains("dolphin", ignoreCase = true) -> engineHint = "Dolphin"
            }

            if (GENERIC_FRAMEWORK_PREFIXES.any { libName.startsWith(it, ignoreCase = true) }) {
                return
            }

            val res = try {
                streamProvider().use { scanStreamForGraphicsTokens(it) }
            } catch (_: Throwable) {
                0
            }
            val foundVk = (res and 1) != 0
            val foundGl = (res and 2) != 0
            if (foundVk) {
                appHasVulkan = true
                if (vkLibs.size < 3) vkLibs.add(libName)
            }
            if (foundGl) {
                appHasGles = true
                if (glLibs.size < 3) glLibs.add(libName)
            }
        }

        if (nativeDir != null && nativeDir.isDirectory) {
            val files = nativeDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".so") && !KLEE_IGNORED_LIBS.contains(it.name) }
                // Prioritize non-framework game/app libs and limit to 16 largest/relevant libs for speed
                ?.sortedByDescending { it.length() }
                ?.take(16)
                ?: emptyList()
            for (f in files) {
                processSoStream(f.name) { FileInputStream(f) }
            }
        }

        // 2. If nativeLibraryDir was empty (extractNativeLibs=false or split APK), scan arm64-v8a inside APKs
        if (scannedNames.isEmpty()) {
            val apkPaths = buildList {
                appInfo.sourceDir?.let { add(it) }
                appInfo.splitSourceDirs?.let { addAll(it) }
            }
            for (apkPath in apkPaths) {
                val apkFile = File(apkPath)
                if (!apkFile.canRead()) continue
                try {
                    ZipFile(apkFile).use { zip ->
                        val entries = zip.entries().asSequence()
                            .filter { !it.isDirectory && it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }
                            .sortedByDescending { it.size }
                            .take(12)
                            .toList()
                        for (entry in entries) {
                            val base = entry.name.substringAfterLast('/')
                            processSoStream(base) { zip.getInputStream(entry) }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }

        val engineTag = engineHint?.let { " ($it)" }.orEmpty()

        // Classify accurately
        return when {
            (appHasVulkan || manifestVulkan) && appHasGles -> {
                val sample = (vkLibs + glLibs).distinct().take(2).joinToString(", ")
                AppGraphicsApiInfo(
                    packageName = packageName,
                    kind = GraphicsApiKind.HYBRID,
                    detail = "Suporta Vulkan e OpenGL ES$engineTag${if (sample.isNotBlank()) " · $sample" else ""}"
                )
            }
            appHasVulkan || manifestVulkan -> {
                val sample = vkLibs.take(2).joinToString(", ")
                AppGraphicsApiInfo(
                    packageName = packageName,
                    kind = GraphicsApiKind.VULKAN_NATIVE,
                    detail = "Vulkan nativo$engineTag${if (sample.isNotBlank()) " · $sample" else " (manifest)"}"
                )
            }
            appHasGles || manifestGlEsMajor >= 2 -> {
                val sample = glLibs.take(2).joinToString(", ")
                val glVer = if (manifestGlEsMajor >= 2) "OpenGL ES ${manifestGlEsMajor}.x" else "OpenGL ES"
                AppGraphicsApiInfo(
                    packageName = packageName,
                    kind = GraphicsApiKind.OPENGL_ES,
                    detail = "$glVer nativo$engineTag${if (sample.isNotBlank()) " · $sample" else ""}"
                )
            }
            else -> {
                AppGraphicsApiInfo(
                    packageName = packageName,
                    kind = GraphicsApiKind.UNKNOWN,
                    detail = "Sem engine nativa dedicada (UI padrão Android / Java)"
                )
            }
        }
    }

    /**
     * Streaming ASCII token scan capped at 12 MB per .so for instant, low-memory detection.
     * Bit 0 = Vulkan token found, Bit 1 = OpenGL ES / EGL token found.
     */
    private fun scanStreamForGraphicsTokens(input: InputStream): Int {
        val buf = ByteArray(64 * 1024)
        val overlap = 32
        val window = ByteArray(buf.size + overlap)
        var tailLen = 0
        var totalRead = 0
        val maxBytes = 12 * 1024 * 1024
        var mask = 0

        while (totalRead < maxBytes && mask != 3) {
            val n = input.read(buf)
            if (n <= 0) break
            totalRead += n

            System.arraycopy(buf, 0, window, tailLen, n)
            val len = tailLen + n

            if ((mask and 1) == 0) {
                for (tok in VK_TOKENS) {
                    if (indexOfBytes(window, len, tok) >= 0) {
                        mask = mask or 1
                        break
                    }
                }
            }
            if ((mask and 2) == 0) {
                for (tok in GL_TOKENS) {
                    if (indexOfBytes(window, len, tok) >= 0) {
                        mask = mask or 2
                        break
                    }
                }
            }

            tailLen = minOf(overlap, len)
            System.arraycopy(window, len - tailLen, window, 0, tailLen)
        }
        return mask
    }

    private fun indexOfBytes(haystack: ByteArray, length: Int, needle: ByteArray): Int {
        val nLen = needle.size
        if (nLen == 0 || length < nLen) return -1
        val first = needle[0]
        val max = length - nLen
        var i = 0
        while (i <= max) {
            if (haystack[i] == first) {
                var j = 1
                while (j < nLen && haystack[i + j] == needle[j]) j++
                if (j == nLen) return i
            }
            i++
        }
        return -1
    }
}
