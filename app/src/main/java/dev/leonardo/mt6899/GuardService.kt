package dev.leonardo.mt6899

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.leonardo.mt6899.core.Engine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano: reaplica o perfil após o boot, corrige "deriva" causada por
 * mi_thermald / perf HAL e aciona o modo seguro se a temperatura passar do limite.
 */
class GuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (job?.isActive != true) job = scope.launch {
            try {
                loop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // exceção solta numa coroutine sem handler derruba o app inteiro
                LogBuffer.add("Serviço: falha inesperada (${e.javaClass.simpleName}: ${e.message}), encerrando")
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Serviço do gerenciador", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("MT6899 Manager")
            .setContentText("Mantendo o perfil e vigiando a temperatura")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    private suspend fun loop() {
        val store = PrefsStore(applicationContext)
        val engine = Engine(RootShell, store) { LogBuffer.add(it) }
        if (!RootShell.hasRoot()) {
            LogBuffer.add("Serviço: sem root, encerrando")
            stopSelf()
            return
        }
        engine.detect()
        delay(20_000) // deixa o sistema assentar depois do boot
        store.get("active")?.let {
            if (store.get("boot") != "0") {
                LogBuffer.add("Serviço: aplicando '$it'")
                engine.applyByName(it)
            }
        }
        var lastSafe = 0L
        while (scope.isActive) {
            // Sem "manter perfil" e sem proteções enfraquecidas não há motivo para ficar rodando.
            if (store.get("watchdog") != "1" && !engine.isRisky()) {
                LogBuffer.add("Serviço: nada a vigiar, encerrando")
                stopSelf()
                return
            }
            try {
                val live = engine.readLive()
                val trip = engine.guardTrip(live)
                if (trip != null && System.currentTimeMillis() - lastSafe > 60_000) {
                    lastSafe = System.currentTimeMillis()
                    engine.safeMode(trip)
                } else if (store.get("watchdog") == "1") {
                    engine.enforce()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LogBuffer.add("Serviço: ${e.message}")
            }
            val s = (store.get("interval")?.toLongOrNull() ?: 10L).coerceIn(3L, 120L)
            delay(s * 1000)
        }
    }

    companion object {
        private const val CHANNEL = "guard"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val s = PrefsStore(context)
        val wantsBoot = s.get("active") != null && s.get("boot") != "0"
        if (wantsBoot || s.get("watchdog") == "1") {
            ContextCompat.startForegroundService(context, Intent(context, GuardService::class.java))
        }
    }
}
