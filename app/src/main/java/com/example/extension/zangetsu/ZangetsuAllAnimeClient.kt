package com.example.extension.zangetsu

import com.example.extension.flixquest.FlixQuestPlaybackResult
import com.example.extension.flixquest.FlixQuestProvider
import com.example.extension.flixquest.FlixQuestStreamLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Native adapter for Zangetsu's bundled AllAnime source protocol.
 *
 * It supports title search, episode source lookup, obfuscated clock URLs and direct HLS/DASH/MP4
 * links. Embedded-video handlers which require a dedicated extractor runtime are intentionally not
 * reported as playable URLs; this avoids handing an embed HTML page to ExoPlayer as if it were media.
 */
object ZangetsuAllAnimeClient {
    private const val API_URL = "https://api.allanime.day/api"
    private const val CLOCK_BASE = "https://allanime.day"
    private const val REFERER = "https://youtu-chan.com"
    private const val ORIGIN = "https://youtu-chan.com"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:150.0) Gecko/20100101 Firefox/150.0"
    private const val KEY_SEED = "Xot36i3lK3:v1"
    private const val SOURCES_HASH = "d405d0edd690624b66baba3068e0edc3ac90f1597d898a1ec8db4e5c43c00fec"

    private val apiUrl = API_URL.toHttpUrlOrNull()!!
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val hexMap = mapOf(
        "79" to "A", "7a" to "B", "7b" to "C", "7c" to "D", "7d" to "E", "7e" to "F", "7f" to "G",
        "70" to "H", "71" to "I", "72" to "J", "73" to "K", "74" to "L", "75" to "M", "76" to "N", "77" to "O",
        "68" to "P", "69" to "Q", "6a" to "R", "6b" to "S", "6c" to "T", "6d" to "U", "6e" to "V", "6f" to "W",
        "60" to "X", "61" to "Y", "62" to "Z", "59" to "a", "5a" to "b", "5b" to "c", "5c" to "d", "5d" to "e",
        "5e" to "f", "5f" to "g", "50" to "h", "51" to "i", "52" to "j", "53" to "k", "54" to "l", "55" to "m",
        "56" to "n", "57" to "o", "48" to "p", "49" to "q", "4a" to "r", "4b" to "s", "4c" to "t", "4d" to "u",
        "4e" to "v", "4f" to "w", "40" to "x", "41" to "y", "42" to "z", "08" to "0", "09" to "1", "0a" to "2",
        "0b" to "3", "0c" to "4", "0d" to "5", "0e" to "6", "0f" to "7", "00" to "8", "01" to "9", "15" to "-",
        "16" to ".", "67" to "_", "46" to "~", "02" to ":", "17" to "/", "07" to "?", "1b" to "#", "63" to "[",
        "65" to "]", "78" to "@", "19" to "!", "1c" to "$", "1e" to "&", "10" to "(", "11" to ")", "12" to "*",
        "13" to "+", "14" to ",", "03" to ";", "05" to "=", "1d" to "%"
    )

    private val sourceSearchQuery = """
        query( ${'$'}search: SearchInput ${'$'}limit: Int ${'$'}page: Int ${'$'}translationType: VaildTranslationTypeEnumType ${'$'}countryOrigin: VaildCountryOriginEnumType ) {
          shows( search: ${'$'}search limit: ${'$'}limit page: ${'$'}page translationType: ${'$'}translationType countryOrigin: ${'$'}countryOrigin ) {
            edges { _id name englishName thumbnail availableEpisodes __typename }
          }
        }
    """.trimIndent()

    suspend fun loadFirstPlayable(
        title: String,
        episodeNumber: Int,
        dubbed: Boolean
    ): FlixQuestPlaybackResult? = withContext(Dispatchers.IO) {
        val queryTitle = title.trim()
        if (queryTitle.isBlank() || episodeNumber <= 0) return@withContext null
        val mode = if (dubbed) "dub" else "sub"
        val candidates = runCatching { search(queryTitle, mode) }.getOrNull().orEmpty()
        val picked = candidates
            .map { candidate -> candidate to maxOf(titleScore(queryTitle, candidate.name), titleScore(queryTitle, candidate.englishName)) }
            .filter { it.second >= 0.45 }
            .maxByOrNull { it.second }
            ?.first ?: return@withContext null

        val sourceUrls = runCatching { episodeSources(picked.id, mode, episodeNumber) }.getOrNull().orEmpty()
        if (sourceUrls.isEmpty()) return@withContext null

        val headers = mapOf(
            "Referer" to REFERER,
            "Origin" to ORIGIN,
            "User-Agent" to USER_AGENT
        )
        val links = mutableListOf<FlixQuestStreamLink>()
        for (source in sourceUrls) {
            val sourceName = source.optString("sourceName").trim()
            var rawUrl = source.optString("sourceUrl").trim()
            if (rawUrl.isBlank() || sourceName == "Ss-Hls") continue

            if (rawUrl.startsWith("--")) {
                rawUrl = decodeSourceUrl(rawUrl)
                if (rawUrl.contains("/apivtwo/clock")) {
                    val clockLinks = runCatching { resolveClock(rawUrl, headers) }.getOrNull().orEmpty()
                    links += clockLinks
                    continue
                }
            }

            // Zangetsu delegates several source names to generic host extractors. Port the
            // supported hosts natively instead of handing their HTML embed documents to Media3.
            if (ZangetsuEmbedExtractorClient.supports(rawUrl)) {
                val extractedLinks = try {
                    ZangetsuEmbedExtractorClient.extract(rawUrl, sourceName.ifBlank { "AllAnime" }, headers)
                } catch (_: Exception) {
                    emptyList()
                }
                links += extractedLinks
                continue
            }

            val validUrl = rawUrl.toHttpUrlOrNull()?.takeIf { it.scheme == "https" }?.toString() ?: continue
            links += streamLink(validUrl, sourceName.ifBlank { "AllAnime" }, "Auto", headers)
        }
        val uniqueLinks = links.distinctBy(FlixQuestStreamLink::url)
        if (uniqueLinks.isEmpty()) return@withContext null
        FlixQuestPlaybackResult(
            provider = FlixQuestProvider("zangetsu:allanime", "AllAnime", "Zangetsu · AllAnime", "ANIME"),
            links = uniqueLinks
        )
    }

    private data class SearchCandidate(val id: String, val name: String, val englishName: String)

    private fun search(title: String, mode: String): List<SearchCandidate> {
        val variables = JSONObject()
            .put("search", JSONObject().put("allowAdult", false).put("allowUnknown", false).put("query", title))
            .put("limit", 26)
            .put("page", 1)
            .put("translationType", mode)
            .put("countryOrigin", "ALL")
        val requestBody = JSONObject().put("query", sourceSearchQuery).put("variables", variables)
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val response = executeJson(
            Request.Builder().url(apiUrl).post(requestBody).headers(commonHeaders()).build()
        )
        val edges = response.optJSONObject("data")?.optJSONObject("shows")?.optJSONArray("edges") ?: return emptyList()
        return buildList {
            for (index in 0 until edges.length()) {
                val row = edges.optJSONObject(index) ?: continue
                val id = row.optString("_id").trim()
                if (id.isBlank()) continue
                add(SearchCandidate(id, row.optString("name"), row.optString("englishName")))
            }
        }
    }

    private fun episodeSources(showId: String, mode: String, episodeNumber: Int): List<JSONObject> {
        val variables = JSONObject()
            .put("showId", showId)
            .put("translationType", mode)
            .put("episodeString", episodeNumber.toString())
        val extensions = JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", SOURCES_HASH))
        val requestUrl = apiUrl.newBuilder()
            .addQueryParameter("variables", variables.toString())
            .addQueryParameter("extensions", extensions.toString())
            .build()
        val request = Request.Builder().url(requestUrl).headers(commonHeaders()).get().build()
        val response = executeJson(request)
        val data = response.optJSONObject("data") ?: return emptyList()
        val encoded = data.optString("tobeparsed").takeIf(String::isNotBlank)
        val rawSourceArray = if (encoded != null) {
            decryptSourceArray(encoded)
        } else {
            data.optJSONObject("episode")?.optJSONArray("sourceUrls")
        } ?: return emptyList()
        return buildList {
            for (i in 0 until rawSourceArray.length()) {
                val obj = rawSourceArray.optJSONObject(i) ?: continue
                add(obj)
            }
        }
    }

    private fun decryptSourceArray(encoded: String): JSONArray? {
        val bytes = decodeBase64Compat(encoded)
        if (bytes.size < 29) return null
        val counter = ByteArray(16)
        bytes.copyInto(counter, destinationOffset = 0, startIndex = 1, endIndex = 13)
        // The source format appends the initial AES-CTR block counter 2 to its 12-byte nonce.
        counter[12] = 0
        counter[13] = 0
        counter[14] = 0
        counter[15] = 2
        val cipherText = bytes.copyOfRange(13, bytes.size - 16)
        val key = MessageDigest.getInstance("SHA-256").digest(KEY_SEED.toByteArray(StandardCharsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
        val plaintext = String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
        val objectData = JSONObject(plaintext)
        return objectData.optJSONObject("episode")?.optJSONArray("sourceUrls")
            ?: objectData.optJSONArray("sourceUrls")
    }

    private fun resolveClock(pathOrUrl: String, headers: Map<String, String>): List<FlixQuestStreamLink> {
        val url = when {
            pathOrUrl.startsWith("https://") || pathOrUrl.startsWith("http://") -> pathOrUrl
            pathOrUrl.startsWith("/") -> "$CLOCK_BASE$pathOrUrl"
            else -> "$CLOCK_BASE/$pathOrUrl"
        }.toHttpUrlOrNull() ?: return emptyList()
        val json = executeJson(Request.Builder().url(url).headers(commonHeaders(headers)).get().build())
        val rawLinks = json.optJSONArray("links") ?: return emptyList()
        return buildList {
            for (index in 0 until rawLinks.length()) {
                val row = rawLinks.optJSONObject(index) ?: continue
                val linkUrl = row.optString("link").ifBlank { row.optString("url") }
                if (linkUrl.isBlank()) continue
                val validUrl = linkUrl.toHttpUrlOrNull()?.takeIf { it.scheme == "https" }?.toString() ?: continue
                val quality = row.optString("resolutionStr").ifBlank { "Auto" }
                add(streamLink(validUrl, "AllAnime Clock ${index + 1}", quality, headers, row.optBoolean("hls", false)))
            }
        }
    }

    private fun streamLink(
        url: String,
        server: String,
        quality: String,
        headers: Map<String, String>,
        forceHls: Boolean = false
    ): FlixQuestStreamLink {
        val lower = url.substringBefore('?').lowercase(Locale.ROOT)
        val isHls = forceHls || lower.endsWith(".m3u8") || lower.contains("repackager.wixmp")
        val isDash = lower.endsWith(".mpd")
        return FlixQuestStreamLink(
            server = server,
            url = url,
            quality = quality.ifBlank { "Auto" },
            isM3u8 = isHls,
            isDash = isDash,
            headers = headers
        )
    }

    private fun decodeSourceUrl(raw: String): String {
        if (!raw.startsWith("--")) return raw
        val encoded = raw.substring(2)
        val result = StringBuilder(encoded.length / 2)
        var index = 0
        while (index + 1 < encoded.length) {
            result.append(hexMap[encoded.substring(index, index + 2).lowercase(Locale.ROOT)].orEmpty())
            index += 2
        }
        return result.toString().replaceFirst("/clock", "/clock.json")
    }

    private fun decodeBase64Compat(value: String): ByteArray {
        var normalized = value.trim().replace('-', '+').replace('_', '/')
        while (normalized.length % 4 != 0) normalized += "="
        return android.util.Base64.decode(normalized, android.util.Base64.DEFAULT)
    }

    private fun titleScore(query: String, candidate: String): Double {
        val q = normalizeTitle(query)
        val c = normalizeTitle(candidate)
        if (q.isBlank() || c.isBlank()) return 0.0
        if (q == c) return 1.0
        if (q.contains(c) || c.contains(q)) return minOf(q.length, c.length).toDouble() / maxOf(q.length, c.length)
        val qWords = q.split(' ').filter { it.length > 1 }.toSet()
        val cWords = c.split(' ').filter { it.length > 1 }.toSet()
        if (qWords.isEmpty() || cWords.isEmpty()) return 0.0
        return qWords.intersect(cWords).size.toDouble() / qWords.union(cWords).size.toDouble()
    }

    private fun normalizeTitle(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun commonHeaders(extra: Map<String, String> = emptyMap()): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Origin", ORIGIN)
            .add("Referer", REFERER)
            .add("User-Agent", USER_AGENT)
        extra.forEach { (name, value) -> builder.set(name, value) }
        return builder.build()
    }

    private fun executeJson(request: Request): JSONObject {
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("AllAnime HTTP ${response.code}")
            return try {
                JSONObject(raw)
            } catch (error: Exception) {
                throw IOException("AllAnime returned invalid JSON", error)
            }
        }
    }
}
