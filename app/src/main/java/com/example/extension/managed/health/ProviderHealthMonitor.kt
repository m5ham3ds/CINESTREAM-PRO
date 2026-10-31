package com.example.extension.managed.health

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Reachability results for the exact managed-extension registry used by search
 * and playback. A reachable homepage does not guarantee that extraction succeeds;
 * this monitor deliberately reports host reachability only.
 */
enum class ProviderReachability {
    ONLINE,
    PROTECTED,
    DEGRADED,
    OFFLINE,
    UNKNOWN
}

enum class ProviderHealthDetail {
    REACHABLE,
    ACCESS_RESTRICTED,
    SERVER_ERROR,
    TIMEOUT,
    DNS_FAILURE,
    CONNECTION_FAILURE,
    INVALID_URL
}

data class ManagedProviderHealth(
    val providerId: String,
    val providerName: String,
    /** SHA-256 fingerprint only; the configured URL may contain source credentials. */
    val baseUrlFingerprint: String,
    val reachability: ProviderReachability,
    val detail: ProviderHealthDetail,
    val responseCode: Int?,
    val responseTimeMs: Long?,
    val checkedAt: Long
)

object ProviderHealthMonitor {
    private const val PREFS_NAME = "managed_provider_health_v1"
    private const val PREF_RESULTS = "results_json"
    private const val CACHE_INTERVAL_MS = 15L * 60L * 1000L

    private val _results = MutableStateFlow<List<ManagedProviderHealth>>(emptyList())
    val results: StateFlow<List<ManagedProviderHealth>> = _results.asStateFlow()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun restoreCached(context: Context) {
        if (_results.value.isNotEmpty()) return
        val cached = withContext(Dispatchers.IO) {
            val raw = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_RESULTS, null)
                ?: return@withContext emptyList()
            decodeResults(raw)
        }
        if (cached.isNotEmpty()) _results.value = cached
    }

    fun isFreshFor(
        providers: List<com.example.extension.managed.model.ManagedExtension>,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (providers.isEmpty()) return true
        val cached = _results.value.associateBy { it.providerId }
        return providers.distinctBy { it.id }.all { provider ->
            val row = cached[provider.id] ?: return@all false
            row.baseUrlFingerprint == fingerprint(provider.baseUrl) &&
                now >= row.checkedAt && now - row.checkedAt < CACHE_INTERVAL_MS
        }
    }

    suspend fun refresh(context: Context, providers: List<com.example.extension.managed.model.ManagedExtension>): List<ManagedProviderHealth> =
        withContext(Dispatchers.IO) {
            if (providers.isEmpty()) {
                _results.value = emptyList()
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_RESULTS, "[]")
                    .apply()
                return@withContext emptyList()
            }

            val semaphore = Semaphore(permits = 3)
            val now = System.currentTimeMillis()
            val checked = coroutineScope {
                providers.map { provider ->
                    async {
                        semaphore.withPermit { checkProvider(provider, now) }
                    }
                }.awaitAll()
            }

            _results.value = checked
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_RESULTS, encodeResults(checked))
                .apply()
            checked
        }

    private fun checkProvider(
        provider: com.example.extension.managed.model.ManagedExtension,
        checkedAt: Long
    ): ManagedProviderHealth {
        val parsed = Uri.parse(provider.baseUrl.trim())
        val scheme = parsed.scheme?.lowercase()
        val host = parsed.host
        if (host.isNullOrBlank() || (scheme != "https" && scheme != "http")) {
            return ManagedProviderHealth(
                providerId = provider.id,
                providerName = provider.name,
                baseUrlFingerprint = fingerprint(provider.baseUrl),
                reachability = ProviderReachability.UNKNOWN,
                detail = ProviderHealthDetail.INVALID_URL,
                responseCode = null,
                responseTimeMs = null,
                checkedAt = checkedAt
            )
        }

        val started = android.os.SystemClock.elapsedRealtime()
        return try {
            var code = executeProbe(provider.baseUrl, useGet = false)
            if (code == 405 || code == 501) {
                code = executeProbe(provider.baseUrl, useGet = true)
            }
            val elapsed = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            val (state, detail) = when {
                code in 200..399 || code == 404 -> ProviderReachability.ONLINE to ProviderHealthDetail.REACHABLE
                code == 401 || code == 403 || code == 407 || code == 429 ->
                    ProviderReachability.PROTECTED to ProviderHealthDetail.ACCESS_RESTRICTED
                code == 408 || code in 500..599 ->
                    ProviderReachability.DEGRADED to ProviderHealthDetail.SERVER_ERROR
                else -> ProviderReachability.DEGRADED to ProviderHealthDetail.SERVER_ERROR
            }
            ManagedProviderHealth(
                providerId = provider.id,
                providerName = provider.name,
                baseUrlFingerprint = fingerprint(provider.baseUrl),
                reachability = state,
                detail = detail,
                responseCode = code,
                responseTimeMs = elapsed,
                checkedAt = checkedAt
            )
        } catch (_: SocketTimeoutException) {
            failure(provider, ProviderReachability.DEGRADED, ProviderHealthDetail.TIMEOUT, checkedAt, started)
        } catch (_: UnknownHostException) {
            failure(provider, ProviderReachability.OFFLINE, ProviderHealthDetail.DNS_FAILURE, checkedAt, started)
        } catch (_: SSLException) {
            failure(provider, ProviderReachability.DEGRADED, ProviderHealthDetail.CONNECTION_FAILURE, checkedAt, started)
        } catch (_: IOException) {
            failure(provider, ProviderReachability.OFFLINE, ProviderHealthDetail.CONNECTION_FAILURE, checkedAt, started)
        } catch (_: Exception) {
            failure(provider, ProviderReachability.UNKNOWN, ProviderHealthDetail.CONNECTION_FAILURE, checkedAt, started)
        }
    }

    private fun executeProbe(url: String, useGet: Boolean): Int {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CineStreamPro/1.0 (provider-health-check)")
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .apply {
                if (useGet) get() else head()
                if (useGet) header("Range", "bytes=0-0")
            }
            .build()
        return httpClient.newCall(request).execute().use { response -> response.code }
    }

    private fun failure(
        provider: com.example.extension.managed.model.ManagedExtension,
        state: ProviderReachability,
        detail: ProviderHealthDetail,
        checkedAt: Long,
        started: Long
    ) = ManagedProviderHealth(
        providerId = provider.id,
        providerName = provider.name,
        baseUrlFingerprint = fingerprint(provider.baseUrl),
        reachability = state,
        detail = detail,
        responseCode = null,
        responseTimeMs = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L),
        checkedAt = checkedAt
    )

    private fun fingerprint(value: String): String = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(value.trim().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun encodeResults(rows: List<ManagedProviderHealth>): String {
        val array = org.json.JSONArray()
        rows.forEach { row ->
            array.put(
                org.json.JSONObject()
                    .put("providerId", row.providerId)
                    .put("providerName", row.providerName)
                    .put("baseUrlFingerprint", row.baseUrlFingerprint)
                    .put("reachability", row.reachability.name)
                    .put("detail", row.detail.name)
                    .put("responseCode", row.responseCode ?: org.json.JSONObject.NULL)
                    .put("responseTimeMs", row.responseTimeMs ?: org.json.JSONObject.NULL)
                    .put("checkedAt", row.checkedAt)
            )
        }
        return array.toString()
    }

    private fun decodeResults(raw: String): List<ManagedProviderHealth> = runCatching {
        val array = org.json.JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("providerId").trim()
                if (id.isBlank()) continue
                val reachability = runCatching {
                    ProviderReachability.valueOf(item.optString("reachability"))
                }.getOrDefault(ProviderReachability.UNKNOWN)
                val detail = runCatching {
                    ProviderHealthDetail.valueOf(item.optString("detail"))
                }.getOrDefault(ProviderHealthDetail.CONNECTION_FAILURE)
                val responseCode = if (item.isNull("responseCode")) null else item.optInt("responseCode")
                val responseTime = if (item.isNull("responseTimeMs")) null else item.optLong("responseTimeMs")
                add(
                    ManagedProviderHealth(
                        providerId = id,
                        providerName = item.optString("providerName"),
                        baseUrlFingerprint = item.optString("baseUrlFingerprint"),
                        reachability = reachability,
                        detail = detail,
                        responseCode = responseCode,
                        responseTimeMs = responseTime,
                        checkedAt = item.optLong("checkedAt", 0L)
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}
