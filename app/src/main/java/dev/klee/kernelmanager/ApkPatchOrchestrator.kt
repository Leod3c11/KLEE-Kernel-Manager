package dev.klee.kernelmanager

import android.content.Context
import dev.klee.kernelmanager.patcher.ApkPatcherEngine
import org.json.JSONObject
import java.io.File

object ApkPatchOrchestrator {

    private const val BACKUP_ROOT = "/data/adb/klee_drivers/apk_backups"
    private const val TMP_CFG_ROOT = "/data/local/tmp/klee_adrenotools"

    private val PKG_REGEX = Regex("^[A-Za-z0-9._-]+$")

    fun listPatchedPackages(shell: RootShell): Set<String> {
        if (!shell.rootCheck()) return emptySet()
        val script =
            "for d in $BACKUP_ROOT/*; do " +
            "  [ -f \"\$d/patched.flag\" ] || continue; " +
            "  basename \"\$d\"; " +
            "done"
        val r = shell.runReadOnly(script)
        return r.stdout
            .map { it.trim() }
            .filter { it.matches(PKG_REGEX) }
            .toSet()
    }

    fun isPackagePatched(packageName: String, shell: RootShell): Boolean {
        if (!packageName.matches(PKG_REGEX) || !shell.rootCheck()) return false
        val r = shell.runReadOnly("[ -f '$BACKUP_ROOT/$packageName/patched.flag' ] && echo 1 || echo 0")
        return r.stdout.firstOrNull()?.trim() == "1"
    }

    /**
     * Stages (or switches) the custom Vulkan driver config for an already-patched app.
     * Takes <200ms and requires NO re-patching or reboot.
     */
    fun stageDriverConfigForPatchedApp(
        packageName: String,
        driver: VulkanDriverPackage?,
        shell: RootShell
    ): String {
        require(packageName.matches(PKG_REGEX)) { "Pacote inválido." }
        if (!shell.rootCheck()) error("Acesso root necessário.")

        val jsonObj = JSONObject().apply {
            if (driver != null) {
                require(driver.installDir.matches(Regex("^/data/adb/klee_drivers/drv_[a-f0-9-]+$"))) {
                    "Diretório de driver inválido."
                }
                require(driver.primaryLibName.matches(Regex("^[A-Za-z0-9._-]+\\.so$"))) {
                    "Biblioteca principal inválida."
                }
                put("mode", "CUSTOM")
                put("driverId", driver.id)
                put("driverLib", driver.primaryLibName)
                put("driverTitle", driver.name)
            } else {
                put("mode", "SYSTEM")
            }
        }
        val safeJson = jsonObj.toString().replace("'", "'\"'\"'")

        val appBase = "/data/user/0/$packageName/files/klee_adrenotools"
        val tmpBase = "$TMP_CFG_ROOT/$packageName"

        val copySoCmd = if (driver != null) {
            "rm -f '$appBase/driver'/*.so 2>/dev/null || true; " +
            "cp -f '${driver.installDir}'/*.so '$appBase/driver/' || { echo 'DRIVER_COPY_FAILED'; exit 3; }; " +
            "chmod 755 '$appBase/driver'/*.so && "
        } else {
            "rm -f '$appBase/driver'/*.so 2>/dev/null || true; "
        }

        val script =
            "( " +
            "appdir='/data/user/0/$packageName'; " +
            "[ -d \"\$appdir\" ] || { echo 'APP_DATA_DIR_MISSING'; exit 2; }; " +
            "uidgid=\$(stat -c '%u:%g' \"\$appdir\" 2>/dev/null); " +
            "mkdir -p '$appBase/driver' '$appBase/temp' '$appBase/vk_file_redirect' '$tmpBase' && " +
            copySoCmd +
            "printf '%s\\n' '$safeJson' > '$appBase/config.json' && " +
            "printf '%s\\n' '$safeJson' > '$tmpBase/config.json' && " +
            "rm -f \"\$appdir/files/klee_vk_crash_flag\" 2>/dev/null || true; " +
            "[ -n \"\$uidgid\" ] && chown -R \"\$uidgid\" \"\$appdir/files\" 2>/dev/null || true; " +
            "chmod 755 '$appBase' '$appBase/driver' '$appBase/temp' '$appBase/vk_file_redirect' '$tmpBase' && " +
            "chmod 644 '$appBase/config.json' '$tmpBase/config.json' && " +
            "restorecon -RF '$appBase' 2>/dev/null || true; " +
            "echo \"OK AdrenoTools configurado para $packageName (${driver?.name ?: "Mali Padrão"})\" " +
            ")"

        val r = shell.runRoot(script)
        return if (r.success) {
            r.combinedOutput().ifBlank { "OK AdrenoTools configurado." }
        } else {
            "Erro: ${r.combinedOutput().ifBlank { "falha ao configurar AdrenoTools" }}"
        }
    }

    /**
     * Backs up stock APK(s), patches base.apk with KLEE libadrenotools + ByteHook + ShadowHook
     * bootstrapper, re-signs any split APKs with the same certificate, installs cleanly via root,
     * and stages the selected driver.
     */
    fun patchAndInstallApp(
        context: Context,
        packageName: String,
        driver: VulkanDriverPackage?,
        shell: RootShell,
        onProgress: (String) -> Unit
    ): String {
        require(packageName.matches(PKG_REGEX)) { "Pacote inválido." }
        if (!shell.rootCheck()) error("Acesso root necessário para injetar AdrenoTools.")

        onProgress("Localizando APKs de $packageName...")
        val pathRes = shell.runReadOnly("pm path '$packageName' 2>/dev/null")
        val apkPaths = pathRes.stdout
            .map { it.trim().removePrefix("package:") }
            .filter { it.startsWith("/") && it.endsWith(".apk") }

        if (apkPaths.isEmpty()) {
            error("Não foi possível localizar os APKs de $packageName via pm path.")
        }

        val backupOrigDir = "$BACKUP_ROOT/$packageName/orig"
        val workDir = File(context.cacheDir, "apk_patch/$packageName").apply {
            deleteRecursively()
            mkdirs()
        }
        val origWorkDir = File(workDir, "orig").apply { mkdirs() }
        val signedWorkDir = File(workDir, "signed").apply { mkdirs() }
        val tempWorkDir = File(workDir, "tmp").apply { mkdirs() }

        try {
            // 1. Backup untouched stock APKs (only once if no backup exists yet) & copy into KLEE cache
            onProgress("Fazendo backup dos APKs originais (${apkPaths.size} arquivo(s))...")
            val copyScript = buildString {
                append("( ")
                append("uidgid=\$(stat -c '%u:%g' '${context.cacheDir.absolutePath}' 2>/dev/null); ")
                append("mkdir -p '$backupOrigDir' '${origWorkDir.absolutePath}' && ")
                append("if [ ! -f '$backupOrigDir/base.apk' ]; then ")
                apkPaths.forEachIndexed { idx, p ->
                    val safeName = if (p.endsWith("/base.apk")) "base.apk" else "split_${idx}_${p.substringAfterLast('/')}"
                    append("cp -f '$p' '$backupOrigDir/$safeName' || { echo 'BACKUP_COPY_FAILED'; exit 2; }; ")
                }
                append("fi; ")
                append("cp -f '$backupOrigDir'/*.apk '${origWorkDir.absolutePath}/' || { echo 'WORK_COPY_FAILED'; exit 3; }; ")
                append("[ -n \"\$uidgid\" ] && chown -R \"\$uidgid\" '${workDir.absolutePath}' 2>/dev/null || true; ")
                append("chmod -R 755 '${workDir.absolutePath}'; ")
                append("echo OK )")
            }
            val copyRes = shell.runRoot(copyScript)
            if (!copyRes.success) {
                error("Falha ao copiar APK original: ${copyRes.combinedOutput()}")
            }

            val localApks = origWorkDir.listFiles()?.filter { it.isFile && it.extension == "apk" }
                ?.sortedBy { if (it.name == "base.apk") 0 else 1 }
                ?: emptyList()
            val baseApk = localApks.firstOrNull { it.name == "base.apk" } ?: localApks.firstOrNull()
                ?: error("base.apk não encontrado após backup.")
            val splitApks = localApks.filter { it != baseApk }

            // 2. Patch base.apk
            val signedBase = File(signedWorkDir, "base.apk")
            val patchInfo = ApkPatcherEngine.patchBaseApk(
                inputApk = baseApk,
                outputSignedApk = signedBase,
                workDir = tempWorkDir,
                onProgress = onProgress
            )

            // 3. Re-sign any split APKs with the same KLEE certificate
            val signedList = mutableListOf(signedBase)
            splitApks.forEachIndexed { idx, splitFile ->
                onProgress("Reassinando split ${idx + 1}/${splitApks.size} (${splitFile.name})...")
                val signedSplit = File(signedWorkDir, splitFile.name)
                ApkPatcherEngine.reSignSplitApk(
                    inputSplitApk = splitFile,
                    outputSignedSplitApk = signedSplit,
                    workDir = tempWorkDir
                )
                signedList.add(signedSplit)
            }

            // 4. Uninstall stock signature & install patched APK set cleanly via pm install-create
            onProgress("Instalando APK com AdrenoTools (${signedList.size} arquivo(s))...")
            val installRes = installApkSetViaRoot(packageName, signedList, uninstallFirst = true, shell = shell)
            if (!installRes.success) {
                // Automatic rollback to untouched stock backup
                onProgress("Falha na instalação; restaurando APK original...")
                val rollbackApks = shell.runReadOnly("ls -1 '$backupOrigDir'/*.apk 2>/dev/null").stdout
                    .map { it.trim() }
                    .filter { it.endsWith(".apk") }
                if (rollbackApks.isNotEmpty()) {
                    installRemoteApkPathsViaRoot(rollbackApks, shell)
                }
                error("Falha ao instalar APK modificado: ${installRes.combinedOutput()}")
            }

            // 5. Mark patched & stage custom Vulkan driver config
            shell.runRoot(
                "printf 'patched=%s|dex=%s|libs=%s\\n' '$(date +%s)' '${patchInfo.dexEntryName}' '${patchInfo.injectedLibsCount}' > '$BACKUP_ROOT/$packageName/patched.flag'"
            )

            onProgress("Configurando driver Vulkan no app...")
            val stageRes = stageDriverConfigForPatchedApp(packageName, driver, shell)

            return "OK AdrenoTools (PPSSPP) injetado em $packageName (${patchInfo.dexEntryName} + ${patchInfo.injectedLibsCount} libs em ${patchInfo.elapsedMs}ms) · $stageRes"
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * Uninstalls the patched APK and reinstalls the untouched original backup APK(s).
     */
    fun restoreOriginalApp(
        packageName: String,
        shell: RootShell,
        onProgress: (String) -> Unit
    ): String {
        require(packageName.matches(PKG_REGEX)) { "Pacote inválido." }
        if (!shell.rootCheck()) error("Acesso root necessário.")

        val backupOrigDir = "$BACKUP_ROOT/$packageName/orig"
        val origPaths = shell.runReadOnly("ls -1 '$backupOrigDir'/*.apk 2>/dev/null").stdout
            .map { it.trim() }
            .filter { it.endsWith(".apk") }
            .sortedBy { if (it.endsWith("/base.apk")) 0 else 1 }

        if (origPaths.isEmpty()) {
            error("Backup original de $packageName não encontrado em $backupOrigDir.")
        }

        onProgress("Desinstalando versão AdrenoTools...")
        shell.runRoot("pm uninstall '$packageName' >/dev/null 2>&1 || true")

        onProgress("Reinstalando APK original (${origPaths.size} arquivo(s))...")
        val res = installRemoteApkPathsViaRoot(origPaths, shell)
        if (!res.success) {
            error("Falha ao restaurar APK original: ${res.combinedOutput()}")
        }

        shell.runRoot(
            "rm -f '$BACKUP_ROOT/$packageName/patched.flag' && rm -rf '$TMP_CFG_ROOT/$packageName'"
        )
        return "OK Aplicativo $packageName restaurado para o APK original intacto!"
    }

    private fun installApkSetViaRoot(
        packageName: String,
        apkFiles: List<File>,
        uninstallFirst: Boolean,
        shell: RootShell
    ): CommandResult {
        val stageRoot = "/data/local/tmp/klee_install_$packageName"
        val script = buildString {
            append("( ")
            append("rm -rf '$stageRoot' && mkdir -p '$stageRoot' && ")
            apkFiles.forEachIndexed { idx, f ->
                val dst = "$stageRoot/${idx}_${f.name}"
                append("cp -f '${f.absolutePath}' '$dst' && chmod 644 '$dst' && ")
            }
            if (uninstallFirst) {
                append("pm uninstall '$packageName' >/dev/null 2>&1 || true; ")
            }
            append("out=\$(pm install-create -r -g 2>&1); ")
            append("sid=\$(printf '%s' \"\$out\" | sed -n 's/.*\\[\\([0-9]*\\)\\].*/\\1/p'); ")
            append("[ -n \"\$sid\" ] || { echo \"INSTALL_CREATE_FAILED: \$out\"; rm -rf '$stageRoot'; exit 2; }; ")
            apkFiles.forEachIndexed { idx, f ->
                val dst = "$stageRoot/${idx}_${f.name}"
                val sz = f.length()
                append("wout=\$(pm install-write -S $sz \"\$sid\" '${idx}_${f.name}' '$dst' 2>&1) || { echo \"INSTALL_WRITE_FAILED: \$wout\"; pm install-abandon \"\$sid\" 2>/dev/null; rm -rf '$stageRoot'; exit 3; }; ")
            }
            append("cout=\$(pm install-commit \"\$sid\" 2>&1); rc=\$?; ")
            append("rm -rf '$stageRoot'; ")
            append("if [ \"\$rc\" != 0 ] || ! printf '%s' \"\$cout\" | grep -qi 'Success'; then echo \"INSTALL_COMMIT_FAILED: \$cout\"; exit 4; fi; ")
            append("echo \"OK \$cout\" )")
        }
        return shell.runRoot(script)
    }

    private fun installRemoteApkPathsViaRoot(apkPaths: List<String>, shell: RootShell): CommandResult {
        val script = buildString {
            append("( ")
            append("out=\$(pm install-create -r -g 2>&1); ")
            append("sid=\$(printf '%s' \"\$out\" | sed -n 's/.*\\[\\([0-9]*\\)\\].*/\\1/p'); ")
            append("[ -n \"\$sid\" ] || { echo \"INSTALL_CREATE_FAILED: \$out\"; exit 2; }; ")
            apkPaths.forEachIndexed { idx, p ->
                val bn = p.substringAfterLast('/')
                append("sz=\$(stat -c '%s' '$p' 2>/dev/null); ")
                append("wout=\$(pm install-write -S \"\$sz\" \"\$sid\" '${idx}_$bn' '$p' 2>&1) || { echo \"INSTALL_WRITE_FAILED: \$wout\"; pm install-abandon \"\$sid\" 2>/dev/null; exit 3; }; ")
            }
            append("cout=\$(pm install-commit \"\$sid\" 2>&1); rc=\$?; ")
            append("if [ \"\$rc\" != 0 ] || ! printf '%s' \"\$cout\" | grep -qi 'Success'; then echo \"INSTALL_COMMIT_FAILED: \$cout\"; exit 4; fi; ")
            append("echo \"OK \$cout\" )")
        }
        return shell.runRoot(script)
    }
}
