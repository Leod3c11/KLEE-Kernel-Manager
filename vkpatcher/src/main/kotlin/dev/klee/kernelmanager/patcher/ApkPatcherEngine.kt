package dev.klee.kernelmanager.patcher

import com.reandroid.archive.Archive
import com.reandroid.archive.ArchiveFile
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.ZipEntryMap
import com.reandroid.archive.writer.ApkFileWriter
import com.reandroid.archive.writer.ZipAligner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.regex.Pattern
import java.util.zip.ZipFile

object ApkPatcherEngine {

    private val NATIVE_LIBS = listOf(
        "libklee_vk_bridge.so",
        "libbytehook.so",
        "libshadowhook.so",
        "libhook_impl.so",
        "libfile_redirect_hook.so",
        "libmain_hook.so",
        "libgsl_alloc_hook.so"
    )

    private const val BOOTSTRAP_DEX_RESOURCE = "/bootstrap.dex"
    private const val PAGE_ALIGN_16K = 16384

    data class PatchResult(
        val outputApk: File,
        val dexEntryName: String,
        val originalFactory: String?,
        val injectedLibsCount: Int,
        val elapsedMs: Long
    )

    fun isApkPatched(apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        return try {
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: return false
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                ManifestPatcher.isPatched(bytes)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Patches the base APK:
     * 1. Strips old META-INF/ signature files
     * 2. Patches binary AndroidManifest.xml (AppComponentFactory + extractNativeLibs=true)
     * 3. Injects bootstrap.dex as the next available classesN.dex
     * 4. Injects all 7 arm64-v8a hook/adrenotools .so libraries STORED with 16KB page alignment
     * 5. Signs output APK with v1+v2+v3 signatures
     */
    fun patchBaseApk(
        inputApk: File,
        outputSignedApk: File,
        workDir: File,
        onProgress: ((String) -> Unit)? = null
    ): PatchResult {
        val t0 = System.currentTimeMillis()
        workDir.mkdirs()

        onProgress?.invoke("Lendo base APK (${inputApk.length() / 1024} KB)...")
        val archiveFile = ArchiveFile(inputApk)
        val entryMap: ZipEntryMap = archiveFile.createZipEntryMap()

        try {
            // 1. Strip previous signature files under META-INF/
            val toRemove = entryMap.toList()
                .map { it.alias }
                .filter { name ->
                    name.startsWith("META-INF/") && (
                        name.endsWith(".SF") ||
                            name.endsWith(".RSA") ||
                            name.endsWith(".DSA") ||
                            name.endsWith(".EC") ||
                            name.equals("META-INF/MANIFEST.MF", ignoreCase = true)
                        )
                }
            for (name in toRemove) {
                entryMap.remove(name)
            }

            // 2. Patch binary AndroidManifest.xml
            onProgress?.invoke("Modificando AndroidManifest.xml (AppComponentFactory)...")
            val manifestSource = entryMap.getInputSource("AndroidManifest.xml")
                ?: throw IllegalStateException("AndroidManifest.xml ausente no APK")
            val manifestBytes = ByteArrayOutputStream().use { bos ->
                manifestSource.write(bos)
                bos.toByteArray()
            }
            val manifestRes = ManifestPatcher.patchManifestBytes(manifestBytes)
            val patchedManifestSource = ByteInputSource(manifestRes.patchedBytes, "AndroidManifest.xml")
            patchedManifestSource.setMethod(Archive.DEFLATED)
            entryMap.add(patchedManifestSource)

            // 3. Find next available classesN.dex slot and inject bootstrap.dex
            onProgress?.invoke("Injetando KLEE bootstrap.dex...")
            val bootstrapBytes = readResourceBytes(BOOTSTRAP_DEX_RESOURCE)
            val nextDexName = findNextDexSlot(entryMap)
            val dexSource = ByteInputSource(bootstrapBytes, nextDexName)
            dexSource.setMethod(Archive.DEFLATED)
            entryMap.add(dexSource)

            // 4. Inject arm64-v8a libadrenotools + ByteHook + ShadowHook + KLEE bridge .so libs
            onProgress?.invoke("Injetando libadrenotools + hooks arm64-v8a (7 libs)...")
            for (libName in NATIVE_LIBS) {
                val libBytes = readResourceBytes("/arm64-v8a/$libName.bin")
                val entryPath = "lib/arm64-v8a/$libName"
                val libSource = ByteInputSource(libBytes, entryPath)
                libSource.setMethod(Archive.STORED)
                entryMap.add(libSource)
            }

            // 5. Write unsigned APK with 16KB alignment on .so entries (Android 15/16 compliant)
            onProgress?.invoke("Alinhando ZIP (16KB .so) e empacotando...")
            val unsignedApk = File(workDir, "base_unsigned.apk")
            writeAlignedApk(entryMap, unsignedApk)

            // 6. Sign APK with v1+v2+v3
            onProgress?.invoke("Assinando APK (v1/v2/v3)...")
            ApkSignerUtil.signApk(unsignedApk, outputSignedApk, manifestRes.minSdkVersion)
            unsignedApk.delete()

            val elapsed = System.currentTimeMillis() - t0
            return PatchResult(
                outputApk = outputSignedApk,
                dexEntryName = nextDexName,
                originalFactory = manifestRes.originalFactory,
                injectedLibsCount = NATIVE_LIBS.size,
                elapsedMs = elapsed
            )
        } finally {
            runCatching { archiveFile.close() }
        }
    }

    /**
     * Re-signs a split APK (e.g., split_config.arm64_v8a.apk) with the exact same KLEE certificate
     * so multi-APK split installs pass Android's shared-signature verification.
     */
    fun reSignSplitApk(
        inputSplitApk: File,
        outputSignedSplitApk: File,
        workDir: File,
        minSdkVersion: Int = 28
    ) {
        workDir.mkdirs()
        val archiveFile = ArchiveFile(inputSplitApk)
        val entryMap = archiveFile.createZipEntryMap()
        try {
            val toRemove = entryMap.toList()
                .map { it.alias }
                .filter { name ->
                    name.startsWith("META-INF/") && (
                        name.endsWith(".SF") ||
                            name.endsWith(".RSA") ||
                            name.endsWith(".DSA") ||
                            name.endsWith(".EC") ||
                            name.equals("META-INF/MANIFEST.MF", ignoreCase = true)
                        )
                }
            for (name in toRemove) {
                entryMap.remove(name)
            }
            val unsignedSplit = File(workDir, "split_unsigned_${inputSplitApk.name}")
            writeAlignedApk(entryMap, unsignedSplit)

            ApkSignerUtil.signApk(unsignedSplit, outputSignedSplitApk, minSdkVersion)
            unsignedSplit.delete()
        } finally {
            runCatching { archiveFile.close() }
        }
    }

    private fun writeAlignedApk(entryMap: ZipEntryMap, outFile: File) {
        if (outFile.exists()) outFile.delete()
        entryMap.autoSortApkFiles()
        val aligner = ZipAligner.apkAligner()
        aligner.setFileAlignment(Pattern.compile(".*\\.so$"), PAGE_ALIGN_16K)
        ApkFileWriter(outFile, entryMap.toArray()).use { writer ->
            writer.zipAligner = aligner
            writer.write()
        }
    }

    private fun findNextDexSlot(entryMap: ZipEntryMap): String {
        if (!entryMap.contains("classes.dex")) return "classes.dex"
        var index = 2
        while (entryMap.contains("classes$index.dex")) {
            index++
        }
        return "classes$index.dex"
    }

    private fun readResourceBytes(resourcePath: String): ByteArray {
        val stream = ApkPatcherEngine::class.java.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Recurso embutido ausente: $resourcePath")
        return stream.use { it.readBytes() }
    }
}
