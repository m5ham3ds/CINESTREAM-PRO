package com.example.extension.flixquest

import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Optional native Kotlin adapter for the documented FlixQuest Scraper API v2.
 *
 * The service is deliberately disabled until FLIXQUEST_API_URL is configured by
 * the project owner. It does not contain a guessed/public backend endpoint.
 * Existing CINESTREAM managed extensions remain the primary source of playback.
 */
data class FlixQuestProvider(
    val id: String,
    val name: String,
    val alias: String = "",
    val content: String = "",
    val enabled: Boolean = true
)

data class FlixQuestProviderHealthEntry(
    val id: String,
    val alias: String,
    val name: String?,
    val online: Boolean,
    val requestTimeMs: Long
)

data class FlixQuestProviderHealthSnapshot(
    val updatedAt: String?,
    val startedAt: String?,
    val intervalMs: Long,
    val methodology: String?,
    val total: Int,
    val online: Int,
    val offline: Int,
    val providers: List<FlixQuestProviderHealthEntry>
) {
    val availability: Float
        get() = if (total <= 0) 0f else (online.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

data class FlixQuestProviderDiagnostics(
    val providers: List<FlixQuestProvider>,
    val health: FlixQuestProviderHealthSnapshot?
)

data class FlixQuestSubtitle(
    val url: String,
    val label: String,
    val language: String = "",
    val kind: String = "subtitles",
    val isDefault: Boolean = false
)

data class FlixQuestStreamLink(
    val server: String,
    val url: String,
    val quality: String = "Auto",
    val isM3u8: Boolean = false,
    val isDash: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<FlixQuestSubtitle> = emptyList(),
    val requiresProxy: Boolean = false
)

data class FlixQuestPlaybackResult(
    val provider: FlixQuestProvider,
    val links: List<FlixQuestStreamLink>
)

object FlixQuestScraperClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Optional semicolon-delimited instance list; first healthy instance wins. */
    private fun configuredRoots(): List<HttpUrl> {
        val raw = runCatching { BuildConfig.FLIXQUEST_API_URL }.getOrDefault("").trim()
        if (raw.isBlank() ||
            raw.contains("your-flixquest-api-instance.com", ignoreCase = true) ||
            raw.contains("your-authorized-api-host.example", ignoreCase = true)
        ) return emptyList()
        return raw.split(';', '\n')
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull(::normalizeApiRoot)
            .distinctBy(HttpUrl::toString)
    }

    private fun normalizeApiRoot(raw: String): HttpUrl? {
        val parsed = raw.trim().toHttpUrlOrNull() ?: return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null) return null
        val scheme = parsed.scheme
        val localHttpAllowed = parsed.host == "localhost" || parsed.host == "127.0.0.1" || parsed.host == "10.0.2.2"
        if (scheme != "https" && !(scheme == "http" && localHttpAllowed)) return null
        val path = parsed.encodedPath.trimEnd('/')
        val normalizedPath = (if (path.endsWith("/api/v2")) path else "$path/api/v2").trimEnd('/') + "/"
        return parsed.newBuilder().encodedPath(normalizedPath.ifBlank { "/api/v2/" }).query(null).fragment(null).build()
    }

    /** True only when a valid owner-configured FlixQuest API endpoint is available. */
    fun isApiConfigured(): Boolean = configuredRoots().isNotEmpty()

    /**
     * Loads the FlixQuest provider catalogue and its persisted health snapshot.
     * The health endpoint reports the latest server-side 15-minute check; this
     * method never triggers an unbounded client-side scrape of provider streams.
     */
    suspend fun loadProviderDiagnostics(): Result<FlixQuestProviderDiagnostics> = withContext(Dispatchers.IO) {
        val roots = configuredRoots()
        if (roots.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("FLIXQUEST_API_URL is not configured with an authorized API host")
            )
        }

        var lastError: Throwable? = null
        for (root in roots) {
            try {
                val catalogue = try {
                    loadProviders(root, includeDisabled = true)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    lastError = error
                    emptyList()
                }

                val health = try {
                    val url = root.newBuilder()
                        .addPathSegment("providers")
                        .addPathSegment("status")
                        .build()
                    parseHealthSnapshot(requestJson(url))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    lastError = error
                    null
                }

                if (catalogue.isNotEmpty() || health != null) {
                    val providers = if (catalogue.isNotEmpty()) catalogue else health!!.providers.map {
                        FlixQuestProvider(
                            id = it.id,
                            name = it.name?.takeIf(String::isNotBlank) ?: it.alias,
                            alias = it.alias,
                            enabled = true
                        )
                    }
                    return@withContext Result.success(FlixQuestProviderDiagnostics(providers, health))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
            }
        }
        Result.failure(lastError ?: IOException("FlixQuest provider status is not available"))
    }

    /** Return providers in server-defined order, matching FlixQuest's sequential fallback policy. */
    suspend fun loadFirstPlayable(
        tmdbId: String,
        isMovie: Boolean,
        season: Int,
        episode: Int
    ): FlixQuestPlaybackResult? = withContext(Dispatchers.IO) {
        if (tmdbId.toLongOrNull() == null) return@withContext null
        for (root in configuredRoots()) {
            val providers = runCatching { loadProviders(root) }.getOrNull() ?: continue
            for (provider in providers) {
                val body = runCatching {
                    root.newBuilder().addPathSegment(if (isMovie) "stream-movie" else "stream-tv")
                        .addQueryParameter("tmdbId", tmdbId)
                        .addQueryParameter("provider", provider.id)
                        .apply {
                            if (!isMovie) {
                                addQueryParameter("season", season.coerceAtLeast(1).toString())
                                addQueryParameter("episode", episode.coerceAtLeast(1).toString())
                            }
                            addQueryParameter("full", "true")
                        }.build().let(::requestJson)
                }.getOrNull() ?: continue
                if (!body.optBoolean("success", false)) continue
                val rawLinks = body.optJSONArray("links") ?: continue
                val links = buildList {
                    for (i in 0 until rawLinks.length()) {
                        val link = rawLinks.optJSONObject(i) ?: continue
                        val url = resolveAndValidateUrl(root, link.optString("url")) ?: continue
                        val headers = parseStringMap(link.optJSONObject("headers"))
                        val subtitles = parseSubtitles(root, link.optJSONArray("subtitles"))
                        add(
                            FlixQuestStreamLink(
                                server = link.optString("server").ifBlank { "Server ${i + 1}" },
                                url = url,
                                quality = link.optString("quality").ifBlank { "Auto" },
                                isM3u8 = link.optBoolean("isM3U8", false),
                                isDash = link.optBoolean("isDASH", false),
                                headers = headers,
                                subtitles = subtitles,
                                requiresProxy = link.optBoolean("requiresProxy", false)
                            )
                        )
                    }
                }.distinctBy(FlixQuestStreamLink::url)
                if (links.isNotEmpty()) return@withContext FlixQuestPlaybackResult(provider, links)
            }
        }
        // FlixQuest also includes a direct VixSrc provider independent of the API backend.
        // Preserve it as the final fallback, using ordinary HTTPS requests only.
        runCatching { loadVixSrc(tmdbId.toLong(), isMovie, season, episode) }.getOrNull()
    }

    private fun loadVixSrc(
        tmdbId: Long,
        isMovie: Boolean,
        season: Int,
        episode: Int
    ): FlixQuestPlaybackResult? {
        if (tmdbId <= 0L || (!isMovie && (season <= 0 || episode <= 0))) return null
        val vixBase = "https://vixsrc.to/".toHttpUrlOrNull() ?: return null
        val apiUrl = if (isMovie) {
            vixBase.newBuilder().addPathSegments("api/movie/$tmdbId").build()
        } else {
            vixBase.newBuilder().addPathSegments("api/tv/$tmdbId/$season/$episode").build()
        }
        var html: String? = null
        for (attempt in 0..1) {
            val apiResponse = runCatching { requestText(apiUrl, VIX_HEADERS) }.getOrNull() ?: break
            if (apiResponse.first != 200) break
            val embedSource = runCatching { JSONObject(apiResponse.second).optString("src") }
                .getOrNull()?.takeIf(String::isNotBlank) ?: break
            val embedUrl = vixBase.resolve(embedSource) ?: embedSource.toHttpUrlOrNull() ?: break
            val response = runCatching { requestText(embedUrl, VIX_HEADERS) }.getOrNull() ?: break
            if (response.first == 200) {
                html = response.second
                break
            }
            // Refresh the embed URL once when the server says its token has expired.
            if (response.first != 410 || attempt == 1) break
        }
        val page = html ?: return null
        val urlMatch = Regex("""window\.masterPlaylist\s*=\s*\{[\s\S]*?url:\s*['\"]([^'\"]+)['\"]""").find(page)?.groupValues?.getOrNull(1) ?: return null
        val token = Regex("""['\"]token['\"]\s*:\s*['\"]([^'\"]+)['\"]""").find(page)?.groupValues?.getOrNull(1) ?: return null
        val expires = Regex("""['\"]expires['\"]\s*:\s*['\"]([^'\"]+)['\"]""").find(page)?.groupValues?.getOrNull(1) ?: return null
        val playlistBase = vixBase.resolve(urlMatch) ?: urlMatch.toHttpUrlOrNull() ?: return null
        val playlistUrl = playlistBase.newBuilder()
            .setQueryParameter("token", token)
            .setQueryParameter("expires", expires)
            .setQueryParameter("h", "1")
            .setQueryParameter("lang", "en")
            .build()
        val playlistResult = runCatching { requestText(playlistUrl, VIX_HEADERS) }.getOrNull() ?: return null
        if (playlistResult.first != 200 || !playlistResult.second.lineSequence().any { it.trim().startsWith("#EXT-X-STREAM-INF") }) return null
        val subtitles = parseHlsSubtitles(playlistResult.second, playlistUrl)
        val provider = FlixQuestProvider("direct:vixsrc", "VixSrc Direct", "VixSrc", "Direct provider")
        return FlixQuestPlaybackResult(
            provider,
            listOf(
                FlixQuestStreamLink(
                    server = "VixSrc Direct",
                    url = playlistUrl.toString(),
                    quality = "Auto",
                    isM3u8 = true,
                    headers = VIX_HEADERS,
                    subtitles = subtitles
                )
            )
        )
    }

    private fun parseHlsSubtitles(playlist: String, playlistUrl: HttpUrl): List<FlixQuestSubtitle> {
        val result = mutableListOf<FlixQuestSubtitle>()
        val attributePattern = Regex("""([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)""")
        for (line in playlist.lineSequence().map(String::trim)) {
            if (!line.startsWith("#EXT-X-MEDIA") || !line.contains("TYPE=SUBTITLES")) continue
            val attributes = linkedMapOf<String, String>()
            val colon = line.indexOf(':')
            if (colon < 0) continue
            for (match in attributePattern.findAll(line.substring(colon + 1))) {
                attributes[match.groupValues[1]] = match.groupValues[2].trim('"')
            }
            val raw = attributes["URI"] ?: continue
            var resolved = playlistUrl.resolve(raw) ?: continue
            val path = resolved.encodedPath.lowercase()
            if (!path.endsWith(".vtt") && !path.endsWith(".srt")) {
                val response = runCatching { requestText(resolved, VIX_HEADERS) }.getOrNull()
                if (response?.first == 200) {
                    val subtitlePath = response.second.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if (subtitlePath != null) resolved = resolved.resolve(subtitlePath) ?: resolved
                }
            }
            val lowerPath = resolved.encodedPath.lowercase()
            if (lowerPath.endsWith(".vtt") || lowerPath.endsWith(".srt")) {
                result += FlixQuestSubtitle(
                    url = resolved.toString(),
                    label = attributes["NAME"] ?: attributes["LANGUAGE"] ?: "Subtitle",
                    language = attributes["LANGUAGE"] ?: "",
                    kind = "subtitles",
                    isDefault = attributes["DEFAULT"] == "YES"
                )
            }
        }
        return result.distinctBy(FlixQuestSubtitle::url)
    }

    private fun requestText(url: HttpUrl, headers: Map<String, String>): Pair<Int, String> {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> builder.header(key, value) }
        http.newCall(builder.build()).execute().use { response ->
            return response.code to response.body?.string().orEmpty()
        }
    }

    private val VIX_HEADERS = mapOf(
        "Accept" to "*/*",
        "Origin" to "https://vixsrc.to",
        "Referer" to "https://vixsrc.to/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    )

    private fun loadProviders(root: HttpUrl, includeDisabled: Boolean = false): List<FlixQuestProvider> {
        val requestUrl = root.newBuilder()
            .addPathSegment("providers")
            .apply { if (includeDisabled) addQueryParameter("all", "true") }
            .build()
        val body = requestJson(requestUrl)
        if (!body.optBoolean("success", false)) return emptyList()
        val providers = body.optJSONArray("providers") ?: return emptyList()
        return buildList {
            for (i in 0 until providers.length()) {
                val provider = providers.optJSONObject(i) ?: continue
                val enabled = provider.optBoolean("enabled", false)
                if (!includeDisabled && !enabled) continue
                val id = provider.optString("id").trim()
                if (id.isBlank()) continue
                add(
                    FlixQuestProvider(
                        id = id,
                        name = provider.optString("name").ifBlank { id },
                        alias = provider.optString("alias"),
                        content = provider.optString("content"),
                        enabled = enabled
                    )
                )
            }
        }
    }


    private fun parseHealthSnapshot(body: JSONObject): FlixQuestProviderHealthSnapshot? {
        if (!body.optBoolean("success", false)) return null
        val rawProviders = body.optJSONArray("providers") ?: JSONArray()
        val providers = buildList {
            for (index in 0 until rawProviders.length()) {
                val item = rawProviders.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) continue
                add(
                    FlixQuestProviderHealthEntry(
                        id = id,
                        alias = item.optString("alias").ifBlank { id },
                        name = item.optString("name").takeIf(String::isNotBlank),
                        online = item.optString("status").equals("online", ignoreCase = true),
                        requestTimeMs = item.optLong("requestTimeMs", -1L).coerceAtLeast(-1L)
                    )
                )
            }
        }
        val summary = body.optJSONObject("summary") ?: JSONObject()
        val total = summary.optInt("total", providers.size).coerceAtLeast(0)
        val online = summary.optInt("online", providers.count { it.online }).coerceIn(0, total)
        val offline = summary.optInt("offline", (total - online).coerceAtLeast(0)).coerceIn(0, total)
        return FlixQuestProviderHealthSnapshot(
            updatedAt = body.optString("updatedAt").takeIf(String::isNotBlank),
            startedAt = body.optString("startedAt").takeIf(String::isNotBlank),
            intervalMs = body.optLong("intervalMs", 0L).coerceAtLeast(0L),
            methodology = body.optString("methodology").takeIf(String::isNotBlank),
            total = total,
            online = online,
            offline = offline,
            providers = providers
        )
    }

    private fun requestJson(url: HttpUrl): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "CineStreamPro/1.0 (FlixQuest API adapter)")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("FlixQuest API HTTP ${response.code}")
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("FlixQuest API returned invalid JSON", e)
            }
        }
    }

    private fun resolveAndValidateUrl(root: HttpUrl, raw: String): String? {
        if (raw.isBlank()) return null
        var candidate = root.resolve(raw) ?: raw.toHttpUrlOrNull() ?: return null
        if (candidate.username.isNotEmpty() || candidate.password.isNotEmpty()) return null
        if (candidate.scheme != "https" && candidate.scheme != "http") return null
        if ((candidate.host == "localhost" || candidate.host == "127.0.0.1") && root.host != "localhost" && root.host != "127.0.0.1") {
            // Some API deployments emit their internal proxy origin in URLs; rewrite only that
            // local origin onto the already-configured HTTPS API origin, preserving path/token.
            candidate = root.newBuilder()
                .encodedPath(candidate.encodedPath)
                .encodedQuery(candidate.encodedQuery)
                .fragment(null)
                .build()
        }
        // The app disables general cleartext traffic; reject remote HTTP streams that would fail
        // in Media3 rather than exposing a selectable server which cannot play.
        val localHttp = candidate.scheme == "http" &&
            (candidate.host == "localhost" || candidate.host == "127.0.0.1" || candidate.host == "10.0.2.2")
        if (candidate.scheme != "https" && !localHttp) return null
        return candidate.toString()
    }

    private fun parseStringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.opt(key)
            if (value is String && value.isNotBlank()) result[key] = value
        }
        return result
    }

    private fun parseSubtitles(root: HttpUrl, array: JSONArray?): List<FlixQuestSubtitle> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val subtitle = array.optJSONObject(i) ?: continue
                val rawUrl = subtitle.optString("url").ifBlank { subtitle.optString("file") }
                val url = resolveAndValidateUrl(root, rawUrl) ?: continue
                add(
                    FlixQuestSubtitle(
                        url = url,
                        label = subtitle.optString("label").ifBlank { subtitle.optString("lang").ifBlank { "Subtitle" } },
                        language = subtitle.optString("s").ifBlank { subtitle.optString("lang") },
                        kind = subtitle.optString("kind").ifBlank { "subtitles" },
                        isDefault = subtitle.optBoolean("default", false)
                    )
                )
            }
        }.distinctBy(FlixQuestSubtitle::url)
    }
}
