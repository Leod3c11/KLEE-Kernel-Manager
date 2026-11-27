package dev.klee.kernelmanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("klee_profiles_v1", Context.MODE_PRIVATE)

    fun loadProfiles(): List<SavedProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val cpuArr = obj.optJSONArray("cpuTargets") ?: JSONArray()
                    val cpuList = buildList {
                        for (j in 0 until cpuArr.length()) {
                            val c = cpuArr.getJSONObject(j)
                            add(
                                CpuPolicyTarget(
                                    policyId = c.getString("policyId"),
                                    minKHz = c.getLong("minKHz"),
                                    maxKHz = c.getLong("maxKHz"),
                                    governor = c.optString("governor", "")
                                )
                            )
                        }
                    }
                    val gpuObj = obj.optJSONObject("gpuTarget")
                    val gpu = gpuObj?.let {
                        GpuTarget(
                            path = it.optString("path", "/sys/class/devfreq/13000000.mali"),
                            minHz = it.getLong("minHz"),
                            maxHz = it.getLong("maxHz"),
                            governor = it.optString("governor", "")
                        )
                    }
                    add(
                        SavedProfile(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            description = obj.optString("description", ""),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            cpuTargets = cpuList,
                            gpuTarget = gpu
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveProfiles(profiles: List<SavedProfile>) {
        val arr = JSONArray()
        profiles.forEach { profile ->
            val obj = JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("description", profile.description)
                put("createdAt", profile.createdAt)
                val cpuArr = JSONArray()
                profile.cpuTargets.forEach { target ->
                    cpuArr.put(
                        JSONObject().apply {
                            put("policyId", target.policyId)
                            put("minKHz", target.minKHz)
                            put("maxKHz", target.maxKHz)
                            put("governor", target.governor)
                        }
                    )
                }
                put("cpuTargets", cpuArr)
                profile.gpuTarget?.let { g ->
                    put(
                        "gpuTarget",
                        JSONObject().apply {
                            put("path", g.path)
                            put("minHz", g.minHz)
                            put("maxHz", g.maxHz)
                            put("governor", g.governor)
                        }
                    )
                }
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    companion object {
        private const val KEY_PROFILES = "saved_profiles_json"
    }
}
