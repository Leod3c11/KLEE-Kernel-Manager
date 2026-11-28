package dev.klee.kernelmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import dev.klee.kernelmanager.AppSettingsStore
import dev.klee.kernelmanager.CpuStatTicks
import dev.klee.kernelmanager.FloatingHudConfig
import dev.klee.kernelmanager.KernelControlRepository
import dev.klee.kernelmanager.MainActivity
import dev.klee.kernelmanager.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var settingsStore: AppSettingsStore
    private val repository = KernelControlRepository(RootShell())

    private var overlayView: LinearLayout? = null
    private var headerTitleView: TextView? = null
    private var bodyTextView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var pollJob: Job? = null

    private var config = FloatingHudConfig()
    private var compactMode = false
    private var lastTicks: CpuStatTicks? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsStore = AppSettingsStore(applicationContext)
        config = settingsStore.loadHudConfig()
        compactMode = config.startCompact
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_CONFIG -> {
                config = settingsStore.loadHudConfig()
                applyOverlayStyle()
                startPollingLoop()
                return START_STICKY
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        if (overlayView == null) {
            createOverlayView()
        } else {
            config = settingsStore.loadHudConfig()
            applyOverlayStyle()
        }
        _running.value = true
        startPollingLoop()
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KLEE Monitor Flutuante (HUD)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Exibe telemetria do kernel em tempo real sobre outros aplicativos."
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("KLEE HUD Flutuante ativo")
            .setContentText("Toque no HUD para alternar compacto/completo ou arraste para mover.")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Fechar HUD", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createOverlayView() {
        val dp = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * dp).toInt(), (7 * dp).toInt(), (10 * dp).toInt(), (7 * dp).toInt())
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleTv = TextView(this).apply {
            text = "KLEE HUD"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeTv = TextView(this).apply {
            text = " ✕ "
            setTextColor(Color.parseColor("#EF4444"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setOnClickListener { stopSelf() }
        }

        headerRow.addView(titleTv)
        headerRow.addView(closeTv)

        val bodyTv = TextView(this).apply {
            text = "Coletando MT6899..."
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(2f * dp, 1f)
            setPadding(0, (3 * dp).toInt(), 0, 0)
        }

        container.addView(headerRow)
        container.addView(bodyTv)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * dp).toInt()
            y = (90 * dp).toInt()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try {
                        windowManager.updateViewLayout(container, params)
                    } catch (_: Throwable) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        compactMode = !compactMode
                    }
                    true
                }
                else -> false
            }
        }

        overlayView = container
        headerTitleView = titleTv
        bodyTextView = bodyTv
        layoutParams = params

        applyOverlayStyle()
        try {
            windowManager.addView(container, params)
        } catch (_: Throwable) {
            stopSelf()
        }
    }

    private fun applyOverlayStyle() {
        val theme = settingsStore.loadTheme()
        val accentInt = androidColorFromCompose(theme.palette.primary)
        val bgInt = androidColorFromCompose(theme.palette.background)
        val alphaByte = (config.opacity.coerceIn(0.45f, 1.0f) * 255).roundToInt()
        val bgWithAlpha = Color.argb(alphaByte, Color.red(bgInt), Color.green(bgInt), Color.blue(bgInt))
        val strokeColor = Color.argb(170, Color.red(accentInt), Color.green(accentInt), Color.blue(accentInt))
        val dp = resources.displayMetrics.density

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * dp
            setColor(bgWithAlpha)
            setStroke((1.2f * dp).toInt().coerceAtLeast(1), strokeColor)
        }
        overlayView?.background = bg
        headerTitleView?.setTextColor(accentInt)
    }

    private fun startPollingLoop() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val sample = withContext(Dispatchers.IO) { repository.readMonitorSample(lastTicks) }
                if (sample.statTicks != null) lastTicks = sample.statTicks
                val powerPair = if (config.showPower) {
                    withContext(Dispatchers.IO) { repository.readPowerWattsQuick() }
                } else 0.0 to 0.0
                val currentHz = readCurrentDisplayHz()

                if (!sample.root) {
                    bodyTextView?.text = "Sem acesso Root"
                } else if (compactMode) {
                    headerTitleView?.visibility = View.GONE
                    val parts = mutableListOf<String>()
                    if (config.showCpu) parts.add("CPU ${sample.cpuUsagePercent.roundToInt()}%")
                    if (config.showGpu) {
                        val g = sample.gpuUtilization?.let { "$it%" } ?: "${sample.gpuMHz}M"
                        parts.add("GPU $g")
                    }
                    if (config.showTemp && sample.socTempC != null) parts.add("${"%.1f".format(sample.socTempC)}°C")
                    if (config.showPower) parts.add("${"%.1f".format(powerPair.first)}W")
                    if (config.showRefreshRate) parts.add("${currentHz}Hz")
                    bodyTextView?.text = parts.joinToString(" · ").ifBlank { "KLEE HUD" }
                } else {
                    headerTitleView?.visibility = View.VISIBLE
                    headerTitleView?.text = if (config.showRefreshRate) "KLEE HUD · ${currentHz}Hz" else "KLEE HUD"
                    val lines = mutableListOf<String>()
                    if (config.showCpu) {
                        lines.add("CPU  ${"%2d".format(sample.cpuUsagePercent.roundToInt())}%  ${sample.peakCpuMHz} MHz")
                    }
                    if (config.showGpu) {
                        val utilStr = sample.gpuUtilization?.let { "${"%2d".format(it)}% " } ?: ""
                        lines.add("GPU  $utilStr${sample.gpuMHz} MHz")
                    }
                    if (config.showDram && sample.dramMHz > 0) {
                        lines.add("DDR  ${sample.dramMHz} MHz")
                    }
                    if (config.showTemp) {
                        val socStr = sample.socTempC?.let { "${"%.1f".format(it)}°C" } ?: "--"
                        val batStr = sample.batteryTempC?.let { " | Bat ${"%.1f".format(it)}°C" } ?: ""
                        lines.add("TMP  $socStr$batStr")
                    }
                    if (config.showPower) {
                        lines.add("PWR  ${"%.2f".format(powerPair.first)}W (${"%.0f".format(powerPair.second)}mA)")
                    }
                    if (config.showRam && sample.ramTotalMb > 0) {
                        val pct = ((sample.ramUsedMb * 100L) / sample.ramTotalMb).toInt()
                        lines.add("RAM  ${sample.ramUsedMb}/${sample.ramTotalMb}MB ($pct%)")
                    }
                    bodyTextView?.text = lines.joinToString("\n").ifBlank { "Nenhuma métrica selecionada" }
                }

                delay(config.intervalMs.coerceIn(500L, 5000L))
            }
        }
    }

    private fun readCurrentDisplayHz(): Int {
        return try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
            d?.refreshRate?.roundToInt() ?: 120
        } catch (_: Throwable) {
            120
        }
    }

    private fun androidColorFromCompose(color: androidx.compose.ui.graphics.Color): Int {
        return Color.argb(
            (color.alpha * 255f).roundToInt(),
            (color.red * 255f).roundToInt(),
            (color.green * 255f).roundToInt(),
            (color.blue * 255f).roundToInt()
        )
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Throwable) {
            }
        }
        overlayView = null
        _running.value = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "klee_floating_hud"
        private const val NOTIF_ID = 4001
        const val ACTION_STOP = "dev.klee.kernelmanager.HUD_STOP"
        const val ACTION_RELOAD_CONFIG = "dev.klee.kernelmanager.HUD_RELOAD"

        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, FloatingMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun reloadConfig(context: Context) {
            if (!_running.value) return
            val intent = Intent(context, FloatingMonitorService::class.java).setAction(ACTION_RELOAD_CONFIG)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingMonitorService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
