package dev.leonardo.mt6899

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat
import dev.leonardo.mt6899.core.Engine
import dev.leonardo.mt6899.core.Live
import dev.leonardo.mt6899.core.SocInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lê clocks via root e mantém uma pequena janela sobre outras aplicações. */
class OverlayMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private lateinit var windowManager: WindowManager
    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val cpuRows = LinkedHashMap<Int, TextView>()
    private var gpuRow: TextView? = null
    private var statusRow: TextView? = null
    private lateinit var store: PrefsStore

    override fun onCreate() {
        super.onCreate()
        store = PrefsStore(applicationContext)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            store.put("overlay_monitor", "0")
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()
        store.put("overlay_monitor", "1")
        if (monitorJob?.isActive != true) monitorJob = scope.launch { monitorLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        store.put("overlay_monitor", "0")
        scope.cancel()
        removePanel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Monitor flutuante", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("MT6899 Manager · monitor")
            .setContentText("Frequências de CPU e GPU sobre outros apps")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Parar", stop).build())
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private suspend fun monitorLoop() {
        try {
            if (!RootShell.hasRoot()) {
                showStatus("Root indisponível")
                stopSelf()
                return
            }
            val engine = Engine(RootShell, store) { LogBuffer.add(it) }
            val info = engine.detect()
            withContext(Dispatchers.Main) { createPanel(info) }
            while (scope.isActive) {
                try {
                    val live = engine.readLive()
                    withContext(Dispatchers.Main) { updatePanel(info, live) }
                } catch (e: Throwable) {
                    LogBuffer.add("Monitor flutuante: ${e.message}")
                    showStatus("Leitura indisponível")
                }
                delay(1000)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LogBuffer.add("Monitor flutuante: ${e.message}")
            showStatus("Falha ao ler frequências")
            delay(1500)
            stopSelf()
        }
    }

    private suspend fun showStatus(text: String) = withContext(Dispatchers.Main) {
        statusRow?.text = text
    }

    private fun createPanel(info: SocInfo) {
        if (panel != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xE9151B24.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF526174.toInt())
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = label("MTK LIVE", 12f, 0xFF8AB4FF.toInt(), bold = true).apply {
            setPadding(0, 0, dp(10), 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(34), 1f))
        val close = label("×", 23f, 0xFFE6EDF3.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { stopSelf() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(34), dp(34)))
        root.addView(header)
        installDragHandle(title)

        info.policies.forEach { policy ->
            val row = label("C${policy.id}  —", 14f, 0xFFE6EDF3.toInt(), mono = true)
            cpuRows[policy.id] = row
            root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)))
        }
        gpuRow = label("GPU  —", 14f, 0xFF7DE3C8.toInt(), mono = true)
        root.addView(gpuRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)))
        statusRow = label("Atualizando…", 10f, 0xFF9DA7B3.toInt()).apply {
            setPadding(0, dp(3), 0, 0)
        }
        root.addView(statusRow)

        val width = dp(190)
        val p = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = store.get("overlay_x")?.toIntOrNull() ?: dp(16)
            y = store.get("overlay_y")?.toIntOrNull() ?: dp(120)
        }
        try {
            windowManager.addView(root, p)
            panel = root
            params = p
        } catch (e: Throwable) {
            LogBuffer.add("Monitor flutuante: não foi possível criar a janela: ${e.message}")
            stopSelf()
        }
    }

    private fun installDragHandle(handle: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = p.x
                    startY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val view = panel ?: return@setOnTouchListener false
                    p.x = startX + (event.rawX - downX).toInt()
                    p.y = startY + (event.rawY - downY).toInt()
                    try {
                        windowManager.updateViewLayout(view, p)
                    } catch (_: Throwable) {
                        return@setOnTouchListener false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    store.put("overlay_x", p.x.toString())
                    store.put("overlay_y", p.y.toString())
                    true
                }
                else -> false
            }
        }
    }

    private fun updatePanel(info: SocInfo, live: Live) {
        info.policies.forEach { policy ->
            val clock = live.cpu[policy.id]?.cur
            cpuRows[policy.id]?.text = "C${policy.id}  ${clock?.takeIf { it > 0 }?.let(::formatClock) ?: "—"}"
        }
        gpuRow?.text = "GPU  ${live.gpuKHz?.takeIf { it > 0 }?.let(::formatClock) ?: "—"}"
        statusRow?.text = "CPU ×${info.policies.size}  ·  atualiza 1 s"
    }

    private fun removePanel() {
        val old = panel ?: return
        try {
            windowManager.removeView(old)
        } catch (_: Throwable) {
        }
        panel = null
        params = null
        cpuRows.clear()
        gpuRow = null
        statusRow = null
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (mono) typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }

    private fun formatClock(kHz: Long): String =
        if (kHz >= 1_000_000L) String.format(java.util.Locale.US, "%.2f GHz", kHz / 1_000_000.0)
        else "${kHz / 1000} MHz"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL = "overlay_monitor"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "dev.leonardo.mt6899.action.STOP_OVERLAY_MONITOR"
    }
}
