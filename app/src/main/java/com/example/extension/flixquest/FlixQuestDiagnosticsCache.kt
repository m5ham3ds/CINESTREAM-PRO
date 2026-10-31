package com.example.extension.flixquest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small last-known-good cache for the FlixQuest provider/status response. */
object FlixQuestDiagnosticsCache {
    private const val PREFS = "flixquest_provider_diagnostics_v1"
    private const val KEY = "last_snapshot"

    fun read(context: Context): FlixQuestProviderDiagnostics? = runCatching {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        val root = JSONObject(raw)
        val providerArray = root.optJSONArray("providers") ?: JSONArray()
        val providers = buildList {
            for (index in 0 until providerArray.length()) {
                val item = providerArray.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) continue
                add(
                    FlixQuestProvider(
                        id = id,
                        name = item.optString("name").ifBlank { id },
                        alias = item.optString("alias"),
                        content = item.optString("content"),
                        enabled = item.optBoolean("enabled", true)
                    )
                )
            }
        }
        val healthJson = root.optJSONObject("health")
        val health = healthJson?.let { json ->
            val rawRows = json.optJSONArray("providers") ?: JSONArray()
            val rows = buildList {
                for (index in 0 until rawRows.length()) {
                    val item = rawRows.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isBlank()) continue
                    add(
                        FlixQuestProviderHealthEntry(
                            id = id,
                            alias = item.optString("alias").ifBlank { id },
                            name = item.optString("name").takeIf(String::isNotBlank),
                            online = item.optBoolean("online", false),
                            requestTimeMs = item.optLong("requestTimeMs", -1L)
                        )
                    )
                }
            }
            FlixQuestProviderHealthSnapshot(
                updatedAt = json.optString("updatedAt").takeIf(String::isNotBlank),
                startedAt = json.optString("startedAt").takeIf(String::isNotBlank),
                intervalMs = json.optLong("intervalMs", 0L).coerceAtLeast(0L),
                methodology = json.optString("methodology").takeIf(String::isNotBlank),
                total = json.optInt("total", rows.size).coerceAtLeast(0),
                online = json.optInt("online", rows.count { it.online }).coerceAtLeast(0),
                offline = json.optInt("offline", 0).coerceAtLeast(0),
                providers = rows
            )
        }
        FlixQuestProviderDiagnostics(providers, health)
    }.getOrNull()

    fun write(context: Context, diagnostics: FlixQuestProviderDiagnostics) {
        val root = JSONObject()
        val providers = JSONArray()
        diagnostics.providers.forEach { provider ->
            providers.put(
                JSONObject()
                    .put("id", provider.id)
                    .put("name", provider.name)
                    .put("alias", provider.alias)
                    .put("content", provider.content)
                    .put("enabled", provider.enabled)
            )
        }
        root.put("providers", providers)
        diagnostics.health?.let { snapshot ->
            val rows = JSONArray()
            snapshot.providers.forEach { provider ->
                rows.put(
                    JSONObject()
                        .put("id", provider.id)
                        .put("alias", provider.alias)
                        .put("name", provider.name ?: "")
                        .put("online", provider.online)
                        .put("requestTimeMs", provider.requestTimeMs)
                )
            }
            root.put(
                "health",
                JSONObject()
                    .put("updatedAt", snapshot.updatedAt ?: "")
                    .put("startedAt", snapshot.startedAt ?: "")
                    .put("intervalMs", snapshot.intervalMs)
                    .put("methodology", snapshot.methodology ?: "")
                    .put("total", snapshot.total)
                    .put("online", snapshot.online)
                    .put("offline", snapshot.offline)
                    .put("providers", rows)
            )
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, root.toString())
            .apply()
    }
}
