package dev.klee.kernelmanager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object VulkanMonitorNotifier {
    private const val CHANNEL_ID = "klee_vulkan_verify"
    private const val NOTIF_ID = 4301

    private val _latestResult = MutableStateFlow<VulkanVerificationResult?>(null)
    val latestResult = _latestResult.asStateFlow()

    fun publish(context: Context, result: VulkanVerificationResult, notifyUser: Boolean) {
        _latestResult.value = result
        if (notifyUser) {
            postNotification(context, result)
        }
    }

    private fun postNotification(context: Context, result: VulkanVerificationResult) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Monitor de Driver Vulkan",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisa imediatamente após abrir um app/jogo se o driver Vulkan customizado carregou ou falhou."
            }
            nm.createNotificationChannel(ch)
        }

        val openIntent = PendingIntent.getActivity(
            context,
            4302,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when (result.status) {
            VulkanLoadStatus.CHECKING -> "Verificando driver em ${result.appLabel}..."
            VulkanLoadStatus.LOADED_CUSTOM -> "Driver Vulkan carregado! (${result.appLabel})"
            VulkanLoadStatus.LOADED_STOCK -> "Driver Mali padrão ativo (${result.appLabel})"
            VulkanLoadStatus.ERROR_DETECTED -> "Erro ao carregar driver (${result.appLabel})"
            VulkanLoadStatus.VULKAN_NOT_MAPPED -> "Vulkan não detectado (${result.appLabel})"
            VulkanLoadStatus.APP_NOT_RUNNING -> "App não está rodando (${result.appLabel})"
        }

        val detailBody = buildString {
            append(result.summary)
            if (result.status == VulkanLoadStatus.ERROR_DETECTED && result.relevantLogcat.isNotEmpty()) {
                append("\nLog: ")
                append(result.relevantLogcat.first().take(180))
            }
        }

        val isChecking = result.status == VulkanLoadStatus.CHECKING
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(result.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailBody))
            .setContentIntent(openIntent)
            .setAutoCancel(!isChecking)
            .setOngoing(isChecking)
            .setSilent(isChecking)
            .setPriority(if (isChecking) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)

        nm.notify(NOTIF_ID, builder.build())
    }
}
