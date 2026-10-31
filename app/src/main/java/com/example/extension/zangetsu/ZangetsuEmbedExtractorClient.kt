package com.example.extension.zangetsu

import com.example.extension.flixquest.FlixQuestProvider
import com.example.extension.flixquest.FlixQuestStreamLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Native Kotlin ports of a small, allow-listed subset of Zangetsu's generic video extractors.
 * Only known HTTPS hosts are fetched; HTML embed pages are never passed to Media3 as streams.
 */
object ZangetsuEmbedExtractorClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(16, TimeUnit.SECONDS)
        .callTimeout(24, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val random = SecureRandom()
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private const val STREAMLARE_API = "https://slwatch.co/api/video/stream/get"
    private const val DOOD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val doodHosts = setOf(
        "dood.to", "dood.so", "dood.ws", "dood.la", "dood.li", "dood.cx", "dood.sh", "dood.wf", "dood.yt",
        "dood.pm", "dood.re", "dood.watch", "dood.work", "doodstream.com", "doods.pro", "dsvplay.com",
        "ds2play.com", "ds2video.com", "d000d.com", "d0000d.com", "dooood.com", "vide0.net"
    )
    private val streamlareHosts = setOf("streamlare.com", "slwatch.co", "slmaxed.com", "sltube.org", "streamlare.cc")

    fun supports(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https") return false
        val host = parsed.host.lowercase()
        return isHost(host, setOf("ok.ru", "odnoklassniki.ru", "mp4upload.com")) ||
            isHost(host, streamlareHosts) || isHost(host, doodHosts)
    }

    suspend fun extract(
        url: String,
        sourceName: String,
        requestHeaders: Map<String, String>
    ): List<FlixQuestStreamLink> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val parsed = url.toHttpUrlOrNull() ?: return@withContext emptyList()
        if (parsed.scheme != "https" || !supports(url)) return@withContext emptyList()
        val host = parsed.host.lowercase()
        val extra = when {
            isHost(host, setOf("ok.ru", "odnoklassniki.ru")) -> extractOkRu(parsed, requestHeaders)
            isHost(host, setOf("mp4upload.com")) -> extractMp4Upload(parsed)
            isHost(host, streamlareHosts) -> extractStreamlare(parsed)
            isHost(host, doodHosts) -> extractDood(parsed)
            else -> emptyList()
        }
        extra.map { it.copy(server = "$sourceName · ${it.server}") }
            .filter { it.url.toHttpUrlOrNull()?.scheme == "https" }
            .map { it.copy(quality = it.quality.ifBlank { "Auto" }) }
    }

    private fun extractOkRu(url: HttpUrl, originalHeaders: Map<String, String>): List<FlixQuestStreamLink> {
        val html = getText(url, originalHeaders + ("User-Agent" to USER_AGENT)) ?: return emptyList()
        val rawOptions = Regex("""data-options="([^"]*)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)
            ?: return emptyList()
        val optionsText = decodeHtmlAttribute(rawOptions)
        val options = runCatching { JSONObject(optionsText) }.getOrNull() ?: return emptyList()
        val flashvars = options.optJSONObject("flashvars") ?: return emptyList()
        val metadataText = flashvars.optString("metadata").ifBlank { return emptyList() }
        val metadata = runCatching { JSONObject(metadataText) }.getOrNull() ?: return emptyList()
        val headers = originalHeaders + ("User-Agent" to USER_AGENT) + ("Referer" to url.toString())
        return buildList {
            val hlsUrl = listOf("hlsMasterPlaylistUrl", "hlsManifestUrl", "ondemandHls")
                .firstNotNullOfOrNull { key -> metadata.optString(key).takeIf(String::isNotBlank) }
            if (hlsUrl != null && isHttpsUrl(hlsUrl)) {
                add(FlixQuestStreamLink("OK.ru HLS", hlsUrl, "Auto", isM3u8 = true, headers = headers))
            }
            val videos = metadata.optJSONArray("videos") ?: return@buildList
            val qualityMap = mapOf(
                "mobile" to "144p", "lowest" to "240p", "low" to "360p", "sd" to "480p",
                "hd" to "720p", "full" to "1080p", "quad" to "1440p", "ultra" to "2160p"
            )
            for (index in 0 until videos.length()) {
                val item = videos.optJSONObject(index) ?: continue
                val videoUrl = item.optString("url")
                if (!isHttpsUrl(videoUrl)) continue
                val quality = qualityMap[item.optString("name")] ?: item.optString("name").ifBlank { "Auto" }
                add(FlixQuestStreamLink("OK.ru $quality", videoUrl, quality, headers = headers))
            }
        }.distinctBy(FlixQuestStreamLink::url)
    }

    private fun extractMp4Upload(url: HttpUrl): List<FlixQuestStreamLink> {
        val headers = mapOf("Referer" to "https://www.mp4upload.com/", "User-Agent" to USER_AGENT)
        val html = getText(url, headers) ?: return emptyList()
        // The upstream provider includes a shared packed-JS unpacker. We support explicit player
        // source URLs here and fail closed when a page requires unpacking that is not present.
        val rawSource = Regex("""player\.src\([\"']([^\"']+)[\"']""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""src\s*:\s*[\"']([^\"']+)[\"']""").find(html)?.groupValues?.getOrNull(1)
            ?: return emptyList()
        val source = url.resolve(rawSource.replace("\\/", "/"))?.toString() ?: return emptyList()
        if (!isHttpsUrl(source)) return emptyList()
        val height = Regex("""[^A-Za-z0-9]HEIGHT=(\d+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)
        val quality = height?.let { "${it}p" } ?: "Auto"
        return listOf(FlixQuestStreamLink("Mp4Upload", source, quality, headers = headers))
    }

    private fun extractStreamlare(url: HttpUrl): List<FlixQuestStreamLink> {
        val id = url.encodedPath.trimEnd('/').substringAfterLast('/').takeIf(String::isNotBlank) ?: return emptyList()
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://slwatch.co/",
            "Origin" to "https://slwatch.co"
        )
        val body = JSONObject().put("id", id).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url(STREAMLARE_API).post(body)
            .header("Content-Type", "application/json")
        headers.forEach { (key, value) -> builder.header(key, value) }
        val json = executeJson(builder.build()) ?: return emptyList()
        if (json.optString("status") == "error") return emptyList()
        val result = json.optJSONObject("result") ?: return emptyList()
        return buildList {
            val keys = result.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = result.optJSONObject(key) ?: continue
                val streamUrl = item.optString("file").replace("\\/", "/").replace("\\", "")
                if (!isHttpsUrl(streamUrl)) continue
                val label = item.optString("label").ifBlank { key }.ifBlank { "Auto" }
                val streamType = item.optString("type").ifBlank { json.optString("type") }
                val isHls = streamType.contains("hls", ignoreCase = true) || streamUrl.substringBefore('?').endsWith(".m3u8", true)
                add(FlixQuestStreamLink("Streamlare $label", streamUrl, label, isM3u8 = isHls, headers = headers))
            }
        }.distinctBy(FlixQuestStreamLink::url)
    }

    private fun extractDood(url: HttpUrl): List<FlixQuestStreamLink> {
        val embedUrl = url.newBuilder().encodedPath(url.encodedPath.replace("/d/", "/e/")).build()
        val first = getResponse(embedUrl, mapOf("User-Agent" to USER_AGENT)) ?: return emptyList()
        val finalUrl = first.first
        val html = first.second
        val passPath = Regex("""/pass_md5/[^'"\s<>]+""").find(html)?.value ?: return emptyList()
        val hostRoot = finalUrl.newBuilder().encodedPath("/").query(null).fragment(null).build()
        val passUrl = hostRoot.resolve(passPath) ?: return emptyList()
        val passBody = getText(passUrl, mapOf("Referer" to finalUrl.toString(), "User-Agent" to USER_AGENT))?.trim().orEmpty()
        if (passBody.isBlank() || !passBody.startsWith("https://")) return emptyList()
        val token = passPath.substringAfterLast('/').substringBefore('?').takeIf(String::isNotBlank) ?: return emptyList()
        val suffix = buildString { repeat(10) { append(DOOD_ALPHABET[random.nextInt(DOOD_ALPHABET.length)]) } }
        val streamUrl = "$passBody$suffix?token=$token&expiry=${System.currentTimeMillis()}"
        val safeStreamUrl = streamUrl.toHttpUrlOrNull()?.takeIf { it.scheme == "https" }?.toString() ?: return emptyList()
        return listOf(
            FlixQuestStreamLink(
                server = "DoodStream",
                url = safeStreamUrl,
                headers = mapOf("Referer" to "${hostRoot}", "User-Agent" to USER_AGENT)
            )
        )
    }

    private fun getText(url: HttpUrl, headers: Map<String, String>): String? = getResponse(url, headers)?.second

    private fun getResponse(url: HttpUrl, headers: Map<String, String>): Pair<HttpUrl, String>? {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> builder.header(key, value) }
        return try {
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.request.url to response.body?.string().orEmpty()
            }
        } catch (_: Exception) { null }
    }

    private fun executeJson(request: Request): JSONObject? = try {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            JSONObject(response.body?.string().orEmpty())
        }
    } catch (_: Exception) { null }

    private fun decodeHtmlAttribute(raw: String): String = raw
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#x22;", "\"", ignoreCase = true)
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")

    private fun isHttpsUrl(value: String): Boolean = value.toHttpUrlOrNull()?.scheme == "https"

    private fun isHost(host: String, roots: Set<String>): Boolean = roots.any { host == it || host.endsWith(".$it") }
}
