package dev.klee.kernelmanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportedLogFile(
    val fileName: String,
    val cacheFile: File,
    val downloadsPath: String?,
    val contentLength: Int
)

object LogExporter {

    private val stampFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun exportToFiles(
        context: Context,
        shell: RootShell,
        content: String,
        saveToDownloads: Boolean = true
    ): ExportedLogFile {
        val fileName = "klee_log_${stampFmt.format(Date())}.txt"
        val logDir = File(context.cacheDir, "logs").apply { mkdirs() }
        // Clean old cache logs keeping only last 5
        logDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }

        val cacheFile = File(logDir, fileName)
        cacheFile.writeText(content, Charsets.UTF_8)

        var downloadsPath: String? = null
        if (saveToDownloads && shell.rootCheck()) {
            val dest = "/sdcard/Download/$fileName"
            val src = cacheFile.absolutePath
            require(src.matches(Regex("^[A-Za-z0-9._/-]+$")))
            require(dest.matches(Regex("^[A-Za-z0-9._/-]+$")))
            val r = shell.runRoot(
                "mkdir -p /sdcard/Download && cp '$src' '$dest' && chmod 664 '$dest' && echo OK"
            )
            if (r.success) {
                downloadsPath = dest
            }
        }

        return ExportedLogFile(
            fileName = fileName,
            cacheFile = cacheFile,
            downloadsPath = downloadsPath,
            contentLength = content.length
        )
    }

    fun buildShareIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "KLEE Kernel Manager - Log de Diagnóstico (${file.name})")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
