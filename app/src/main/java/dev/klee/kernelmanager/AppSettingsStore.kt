package dev.klee.kernelmanager

import android.content.Context
import dev.klee.kernelmanager.ui.theme.KleeThemePreset
import org.json.JSONArray
import org.json.JSONObject

data class FloatingHudConfig(
    val showCpu: Boolean = true,
    val showGpu: Boolean = true,
    val showTemp: Boolean = true,
    val showPower: Boolean = true,
    val showDram: Boolean = true,
    val showRam: Boolean = true,
    val showRefreshRate: Boolean = true,
    val intervalMs: Long = 1500L,
    val opacity: Float = 0.88f,
    val startCompact: Boolean = false
)

data class PerAppRule(
    val packageName: String,
    val appLabel: String,
    val profileId: String = "NONE",      // NONE, ECO, BALANCED, PERFORMANCE, GAMING, or custom SavedProfile.id
    val mtkPerfPreset: String = "NONE",  // NONE, GAMING, STOCK, COOL
    val refreshRateHz: Int = 0,          // 0 = Auto/Default, 60, 90, 120, etc.
    val enableBypassCharging: Boolean = false,
    val vulkanDriverId: String = "SYSTEM", // SYSTEM or VulkanDriverPackage.id
    val forceVulkanAngle: Boolean = false
)

data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean
)

data class DisplayRefreshInfo(
    val currentHz: Int = 120,
    val supportedHz: List<Int> = listOf(60, 90, 120),
    val minSetting: String = "",
    val peakSetting: String = "",
    val userSetting: String = "",
    val miuiSetting: String = ""
)

class AppSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("klee_settings_v1", Context.MODE_PRIVATE)

    fun loadTheme(): KleeThemePreset = KleeThemePreset.fromId(prefs.getString(KEY_THEME, null))

    fun saveTheme(preset: KleeThemePreset) {
        prefs.edit().putString(KEY_THEME, preset.id).apply()
    }

    fun loadHudConfig(): FloatingHudConfig {
        val raw = prefs.getString(KEY_HUD_CONFIG, null) ?: return FloatingHudConfig()
        return try {
            val o = JSONObject(raw)
            FloatingHudConfig(
                showCpu = o.optBoolean("showCpu", true),
                showGpu = o.optBoolean("showGpu", true),
                showTemp = o.optBoolean("showTemp", true),
                showPower = o.optBoolean("showPower", true),
                showDram = o.optBoolean("showDram", true),
                showRam = o.optBoolean("showRam", true),
                showRefreshRate = o.optBoolean("showRefreshRate", true),
                intervalMs = o.optLong("intervalMs", 1500L).coerceIn(500L, 5000L),
                opacity = o.optDouble("opacity", 0.88).toFloat().coerceIn(0.45f, 1.0f),
                startCompact = o.optBoolean("startCompact", false)
            )
        } catch (_: Throwable) {
            FloatingHudConfig()
        }
    }

    fun saveHudConfig(cfg: FloatingHudConfig) {
        val o = JSONObject().apply {
            put("showCpu", cfg.showCpu)
            put("showGpu", cfg.showGpu)
            put("showTemp", cfg.showTemp)
            put("showPower", cfg.showPower)
            put("showDram", cfg.showDram)
            put("showRam", cfg.showRam)
            put("showRefreshRate", cfg.showRefreshRate)
            put("intervalMs", cfg.intervalMs)
            put("opacity", cfg.opacity.toDouble())
            put("startCompact", cfg.startCompact)
        }
        prefs.edit().putString(KEY_HUD_CONFIG, o.toString()).apply()
    }

    fun loadDefaultRefreshRate(): Int = prefs.getInt(KEY_DEFAULT_HZ, 0)

    fun saveDefaultRefreshRate(hz: Int) {
        prefs.edit().putInt(KEY_DEFAULT_HZ, hz).apply()
    }

    fun loadVulkanAutoUnmountSec(): Int = prefs.getInt(KEY_VULKAN_UNMOUNT_SEC, 8)

    fun saveVulkanAutoUnmountSec(sec: Int) {
        prefs.edit().putInt(KEY_VULKAN_UNMOUNT_SEC, sec).apply()
    }

    fun loadPerAppRules(): List<PerAppRule> {
        val raw = prefs.getString(KEY_PER_APP_RULES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        PerAppRule(
                            packageName = o.getString("packageName"),
                            appLabel = o.optString("appLabel", o.getString("packageName")),
                            profileId = o.optString("profileId", "NONE"),
                            mtkPerfPreset = o.optString("mtkPerfPreset", "NONE"),
                            refreshRateHz = o.optInt("refreshRateHz", 0),
                            enableBypassCharging = o.optBoolean("enableBypassCharging", false),
                            vulkanDriverId = o.optString("vulkanDriverId", "SYSTEM"),
                            forceVulkanAngle = o.optBoolean("forceVulkanAngle", false)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun savePerAppRules(rules: List<PerAppRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("packageName", r.packageName)
                    put("appLabel", r.appLabel)
                    put("profileId", r.profileId)
                    put("mtkPerfPreset", r.mtkPerfPreset)
                    put("refreshRateHz", r.refreshRateHz)
                    put("enableBypassCharging", r.enableBypassCharging)
                    put("vulkanDriverId", r.vulkanDriverId)
                    put("forceVulkanAngle", r.forceVulkanAngle)
                }
            )
        }
        prefs.edit().putString(KEY_PER_APP_RULES, arr.toString()).apply()
    }

    companion object {
        private const val KEY_THEME = "theme_preset"
        private const val KEY_HUD_CONFIG = "hud_config_json"
        private const val KEY_DEFAULT_HZ = "default_refresh_rate_hz"
        private const val KEY_VULKAN_UNMOUNT_SEC = "vulkan_auto_unmount_sec"
        private const val KEY_PER_APP_RULES = "per_app_rules_json"
    }
}
