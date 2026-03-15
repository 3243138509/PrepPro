package com.PrepPro.mobile

import android.content.Context
import com.PrepPro.mobile.net.TcpClient
import org.json.JSONArray

object ModelSettingsStore {
    private const val PREF_NAME = "model_settings_prefs"
    private const val KEY_PROFILES_JSON = "profiles_json"
    private const val KEY_ACTIVE_INDEX = "active_index"

    fun load(context: Context): TcpClient.ModelSettingsResult {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES_JSON, "[]").orEmpty()
        val parsed = mutableListOf<TcpClient.ModelSetting>()

        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val apiUrl = item.optString("apiUrl", "").trim()
                val apiKey = item.optString("apiKey", "").trim()
                val modelName = item.optString("modelName", "").trim()
                if (apiUrl.isEmpty() || apiKey.isEmpty() || modelName.isEmpty()) {
                    continue
                }
                parsed.add(
                    TcpClient.ModelSetting(
                        apiUrl = apiUrl,
                        apiKey = apiKey,
                        modelName = modelName,
                    )
                )
            }
        }

        val activeIndexRaw = prefs.getInt(KEY_ACTIVE_INDEX, 0)
        val safeActiveIndex = if (parsed.isEmpty()) {
            0
        } else {
            activeIndexRaw.coerceIn(0, parsed.lastIndex)
        }

        return TcpClient.ModelSettingsResult(
            profiles = parsed,
            activeIndex = safeActiveIndex,
        )
    }

    fun save(
        context: Context,
        profiles: List<TcpClient.ModelSetting>,
        activeIndex: Int,
    ) {
        val arr = JSONArray()
        profiles.forEach { item ->
            arr.put(
                org.json.JSONObject().apply {
                    put("apiUrl", item.apiUrl.trim())
                    put("apiKey", item.apiKey.trim())
                    put("modelName", item.modelName.trim())
                }
            )
        }

        val safeActiveIndex = if (profiles.isEmpty()) {
            0
        } else {
            activeIndex.coerceIn(0, profiles.lastIndex)
        }

        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES_JSON, arr.toString())
            .putInt(KEY_ACTIVE_INDEX, safeActiveIndex)
            .apply()
    }
}
