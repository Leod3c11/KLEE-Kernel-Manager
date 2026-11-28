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
    val runtimeConfirmed: Boolean = false,
    val antiTamperWarning: String? = null
)

/**
 * Detects whether an installed Android app/game natively targets Vulkan, OpenGL ES, or Both (Hybrid)
 * using PackageManager reqFeatures + configPreferences + fast streaming inspection of native arm64 .so
 * files and Java classes*.dex (for GLSurfaceView / android.opengl.GLES* apps).
 *
 * Also detects known anti-tamper / signature-verification shields (Google Play Pairip, Promon/Supercell,
 * Tencent Anti-Cheat, etc.) so users are warned before re-signing an APK with AdrenoTools.
 *
 * Supports upgrading a package's entry with 100%-accurate runtime confirmation from /proc/<pid>/maps.
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

    private val GL_LIB_TOKENS = listOf(
        "libGLESv2.so".toByteArray(Charsets.US_ASCII),
        "libGLESv3.so".toByteArray(Charsets.US_ASCII),
        "libGLESv1_CM.so".toByteArray(Charsets.US_ASCII),
        "libEGL.so".toByteArray(Charsets.US_ASCII)
    )

    // Actual OpenGL ES rendering calls (helps distinguish real GLES renderers from Qt's default -lGLESv2 link)
    private val GL_DRAW_TOKENS = listOf(
        "glDrawElements".toByteArray(Charsets.US_ASCII),
        "glDrawArrays".toByteArray(Charsets.US_ASCII),
        "glCreateShader".toByteArray(Charsets.US_ASCII),
        "glUseProgram".toByteArray(Charsets.US_ASCII),
        "glTexImage2D".toByteArray(Charsets.US_ASCII)
    )

    // Java/Kotlin OpenGL ES API descriptors inside classes*.dex
    private val DEX_GLES_TOKENS = listOf(
        "Landroid/opengl/GLES20;".toByteArray(Charsets.US_ASCII),
        "Landroid/opengl/GLES30;".toByteArray(Charsets.US_ASCII),
        "Landroid/opengl/GLES31;".toByteArray(Charsets.US_ASCII),
        "Landroid/opengl/GLES32;".toByteArray(Charsets.US_ASCII),
        "Landroid/opengl/GLSurfaceView;".toByteArray(Charsets.US_ASCII),
        "Ljavax/microedition/khronos/opengles/GL10;".toByteArray(Charsets.US_ASCII)
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
        val warn = existing?.antiTamperWarning
        val info = when {
            hasAngle && existing?.kind == GraphicsApiKind.VULKAN_NATIVE -> AppGraphicsApiInfo(
                packageName = packageName,
                kind = GraphicsApiKind.VULKAN_NATIVE,
                detail = "Confirmado em execução: Vulkan nativo (ANGLE ativo apenas na UI · pode desligar ANGLE)",
                runtimeConfirmed = true,
                antiTamperWarning = warn
            )
            hasAngle -> AppGraphicsApiInfo(
                packageName = packageName,
                kind = GraphicsApiKind.OPENGL_ES,
                detail = "Confirmado em execução: OpenGL ES traduzido via ANGLE → Vulkan",
                runtimeConfirmed = true,
                antiTamperWarning = warn
            )
            hasVulkanIcd -> AppGraphicsApiInfo(
                packageName = packageName,
                kind = GraphicsApiKind.VULKAN_NATIVE,
                detail = "Confirmado em execução: Vulkan nativo ativo no processo",
                runtimeConfirmed = true,
                antiTamperWarning = warn
            )
            hasStockGles -> AppGraphicsApiInfo(
                packageName = packageName,
                kind = GraphicsApiKind.OPENGL_ES,
                detail = "Confirmado em execução: OpenGL ES nativo (sem Vulkan ICD mapeado)",
                runtimeConfirmed = true,
                antiTamperWarning = warn
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
            return existing.copy(antiTamperWarning = existing.antiTamperWarning ?: computed.antiTamperWarning)
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
        pkgInfo.configPreferences?.forEach { cfg ->
            if (cfg.reqGlEsVersion != 0) {
                val maj = (cfg.reqGlEsVersion ushr 16) and 0xFFFF
                if (maj > manifestGlEsMajor) manifestGlEsMajor = maj
            }
        }

        var engineHint: String? = null
        var hasQtFramework = false
        var antiTamperWarning: String? = detectAntiTamperFromPackage(packageName)

        var appHasVulkan = false
        var appHasGlesLib = false
        var appHasGlesDraw = false
        val vkLibs = mutableListOf<String>()
        val glLibs = mutableListOf<String>()

        fun inspectLibNameForMeta(libName: String) {
            val low = libName.lowercase()
            if (low.startsWith("libqt5") || low.startsWith("libqt6")) {
                hasQtFramework = true
            }
            if (antiTamperWarning == null) {
                antiTamperWarning = detectAntiTamperFromLibName(libName)
            }
            when {
                libName.equals("libunity.so", ignoreCase = true) -> engineHint = "Unity"
                libName.equals("libUE4.so", ignoreCase = true) || libName.equals("libUnreal.so", ignoreCase = true) -> engineHint = "Unreal"
                low.contains("godot") -> engineHint = "Godot"
                low.contains("cocos") -> engineHint = "Cocos"
                low.contains("ppsspp") -> engineHint = "PPSSPP"
                low.contains("dolphin") -> engineHint = "Dolphin"
            }
        }

        val nativeDir = appInfo.nativeLibraryDir?.let { File(it) }
        val scannedNames = hashSetOf<String>()

        fun processSoStream(libName: String, streamProvider: () -> InputStream) {
            if (!libName.endsWith(".so")) return
            if (KLEE_IGNORED_LIBS.contains(libName)) return
            inspectLibNameForMeta(libName)
            if (!scannedNames.add(libName)) return

            if (GENERIC_FRAMEWORK_PREFIXES.any { libName.startsWith(it, ignoreCase = true) }) {
                return
            }

            val res = try {
                streamProvider().use { scanStreamForGraphicsTokens(it) }
            } catch (_: Throwable) {
                0
            }
            val foundVk = (res and 1) != 0
            val foundGlLib = (res and 2) != 0
            val foundGlDraw = (res and 4) != 0
            if (foundVk) {
                appHasVulkan = true
                if (vkLibs.size < 3) vkLibs.add(libName)
            }
            if (foundGlLib) {
                appHasGlesLib = true
                if (glLibs.size < 3) glLibs.add(libName)
            }
            if (foundGlDraw) {
                appHasGlesDraw = true
                if (!glLibs.contains(libName) && glLibs.size < 3) glLibs.add(libName)
            }
        }

        // 1. Inspect extracted nativeLibraryDir first (fast direct file I/O)
        if (nativeDir != null && nativeDir.isDirectory) {
            val allSo = nativeDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".so") && !KLEE_IGNORED_LIBS.contains(it.name) }
                ?: emptyList()
            // Always inspect all lib names for Qt / Anti-Tamper / Engine tags
            allSo.forEach { inspectLibNameForMeta(it.name) }
            // Scan top 16 non-framework libs by size
            val candidates = allSo
                .filterNot { f -> GENERIC_FRAMEWORK_PREFIXES.any { f.name.startsWith(it, ignoreCase = true) } }
                .sortedByDescending { it.length() }
                .take(16)
            for (f in candidates) {
                processSoStream(f.name) { FileInputStream(f) }
            }
        }

        val apkPaths = buildList {
            appInfo.sourceDir?.let { add(it) }
            appInfo.splitSourceDirs?.let { addAll(it) }
        }

        // 2. If no app .so was scanned from nativeLibraryDir, scan arm64-v8a inside APKs
        if (scannedNames.isEmpty()) {
            for (apkPath in apkPaths) {
                val apkFile = File(apkPath)
                if (!apkFile.canRead()) continue
                try {
                    ZipFile(apkFile).use { zip ->
                        val allEntries = zip.entries().asSequence()
                            .filter { !it.isDirectory && it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }
                            .toList()
                        allEntries.forEach { inspectLibNameForMeta(it.name.substringAfterLast('/')) }
                        val candidates = allEntries
                            .filterNot { e ->
                                val base = e.name.substringAfterLast('/')
                                KLEE_IGNORED_LIBS.contains(base) ||
                                    GENERIC_FRAMEWORK_PREFIXES.any { base.startsWith(it, ignoreCase = true) }
                            }
                            .sortedByDescending { it.size }
                            .take(12)
                        for (entry in candidates) {
                            val base = entry.name.substringAfterLast('/')
                            processSoStream(base) { zip.getInputStream(entry) }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }

        // 3. If no native graphics API found yet, inspect classes*.dex for Java/Kotlin OpenGL ES (e.g., GLSurfaceView / GLES20/30)
        var dexHasGles = false
        if (!appHasVulkan && !appHasGlesLib && !appHasGlesDraw && !manifestVulkan) {
            for (apkPath in apkPaths) {
                if (dexHasGles) break
                val apkFile = File(apkPath)
                if (!apkFile.canRead()) continue
                try {
                    ZipFile(apkFile).use { zip ->
                        val dexEntries = zip.entries().asSequence()
                            .filter { !it.isDirectory && it.name.startsWith("classes") && it.name.endsWith(".dex") }
                            .sortedBy { it.name }
                            .take(3)
                            .toList()
                        for (entry in dexEntries) {
                            val hit = try {
                                zip.getInputStream(entry).use { scanStreamForAnyToken(it, DEX_GLES_TOKENS, 4 * 1024 * 1024) }
                            } catch (_: Throwable) {
                                false
                            }
                            if (hit) {
                                dexHasGles = true
                                break
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }

        val engineTag = engineHint?.let { " ($it)" }.orEmpty()

        // Qt Android apps automatically link -lGLESv2 for Qt UI even when the app itself is pure Vulkan (e.g. Vulkan Caps Viewer).
        // Treat Qt+Vulkan without explicit GLES draw/shader calls as Vulkan Nativo!
        val effectiveHasGles = when {
            (appHasVulkan || manifestVulkan) && hasQtFramework && !appHasGlesDraw && engineHint == null -> false
            else -> appHasGlesLib || appHasGlesDraw || dexHasGles
        }

        return when {
            (appHasVulkan || manifestVulkan) && effectiveHasGles -> {
                val sample = (vkLibs + glLibs).distinct().take(2).joinToString(", ")
                AppGraphicsApiInfo(
                    packageName = packageName,
                    kind = GraphicsApiKind.HYBRID,
                    detail = "Suporta Vulkan e OpenGL ES$engineTag${if (sample.isNotBlank()) " · $sample" else ""}",
                    antiTamperWarning = antiTamperWarning
                )
            }
            appHasVulkan || manifestVulkan -> {
                val sample = vkLibs.take(2).joinToString(", ")
                val qtNote = if (hasQtFramework && engineHint == null) " (Qt)" else ""
                AppGraphicsApiInfo(
                    packageName = packageName,
                    kind = GraphicsApiKind.VULKAN_NATIVE,
                    detail = "Vulkan nativo$engineTag$qtNote${if (sample.isNotBlank()) " · $sample" else " (manifest)"}",
                    antiTamperWarning = antiTamperWarning
                )
            }
            effectiveHasGles || manifestGlEsMajor >= 2 -> {
                val sample = glLibs.take(2).joinToString(", ")
                val glVer = if (manifestGlEsMajor >= 2) "OpenGL ES ${manifestGlEsMajor}.x" else "OpenGL ES"
                val srcSuffix = when {
                    sample.isNotBlank() -> " · $sample"
                    dexHasGles -> " · Java/GLSurfaceView"
                    else -> ""
                }
                AppGraphicsApiInfo(
                    packageName = packageName,
                    kind = GraphicsApiKind.OPENGL_ES,
                    detail = "$glVer nativo$engineTag$srcSuffix",
                    antiTamperWarning = antiTamperWarning
                )
            }
            else -> {
                AppGraphicsApiInfo(
                    packageName = packageName,
                    kind = GraphicsApiKind.UNKNOWN,
                    detail = "Sem engine nativa dedicada (UI padrão Android / Java)",
                    antiTamperWarning = antiTamperWarning
                )
            }
        }
    }

    private fun detectAntiTamperFromPackage(packageName: String): String? {
        val low = packageName.lowercase()
        return when {
            low.startsWith("com.supercell.") ->
                "Supercell Anti-Tamper (Promon): re-assinar o APK com AdrenoTools faz o jogo fechar por verificação de assinatura."
            else -> null
        }
    }

    private fun detectAntiTamperFromLibName(libName: String): String? {
        val low = libName.lowercase()
        return when {
            low.contains("pairipcore") ->
                "Google Play Pairip ($libName): verifica assinatura do APK — re-assinar com AdrenoTools causa crash."
            low.contains("promon") || low.startsWith("libshield") ->
                "Promon SHIELD ($libName): anti-tamper que bloqueia APKs re-assinados."
            low.contains("anogs") || low.contains("tersafe") || low.contains("tprt") ->
                "Anti-Cheat nativo ($libName): não recomendado injetar/re-assinar APK."
            low.contains("jiagu") || low.contains("dexhelper") || low.contains("secshell") ->
                "Packer / Anti-Tamper ($libName): pode fechar se o APK for re-assinado."
            else -> null
        }
    }

    /**
     * Streaming ASCII token scan capped at 12 MB per .so for instant, low-memory detection.
     * Bit 0 = Vulkan token found, Bit 1 = OpenGL ES / EGL lib token found, Bit 2 = GLES draw/shader token found.
     */
    private fun scanStreamForGraphicsTokens(input: InputStream): Int {
        val buf = ByteArray(64 * 1024)
        val overlap = 32
        val window = ByteArray(buf.size + overlap)
        var tailLen = 0
        var totalRead = 0
        val maxBytes = 12 * 1024 * 1024
        var mask = 0

        while (totalRead < maxBytes && mask != 7) {
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
                for (tok in GL_LIB_TOKENS) {
                    if (indexOfBytes(window, len, tok) >= 0) {
                        mask = mask or 2
                        break
                    }
                }
            }
            if ((mask and 4) == 0) {
                for (tok in GL_DRAW_TOKENS) {
                    if (indexOfBytes(window, len, tok) >= 0) {
                        mask = mask or 4
                        break
                    }
                }
            }

            tailLen = minOf(overlap, len)
            System.arraycopy(window, len - tailLen, window, 0, tailLen)
        }
        return mask
    }

    private fun scanStreamForAnyToken(input: InputStream, tokens: List<ByteArray>, maxBytes: Int): Boolean {
        val buf = ByteArray(64 * 1024)
        val overlap = 64
        val window = ByteArray(buf.size + overlap)
        var tailLen = 0
        var totalRead = 0

        while (totalRead < maxBytes) {
            val n = input.read(buf)
            if (n <= 0) break
            totalRead += n

            System.arraycopy(buf, 0, window, tailLen, n)
            val len = tailLen + n

            for (tok in tokens) {
                if (indexOfBytes(window, len, tok) >= 0) {
                    return true
                }
            }

            tailLen = minOf(overlap, len)
            System.arraycopy(window, len - tailLen, window, 0, tailLen)
        }
        return false
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
