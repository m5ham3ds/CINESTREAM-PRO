package com.example.extension.managed.runtime.web

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.web.TrustedEmbedHostPolicy
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response as OkHttpResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Response snapshot returned by the protected transport. The body is buffered before the
 * OkHttp response is closed, so a caller may parse HTML or inspect JSON without owning a socket.
 */
data class ProtectedHttpResponse internal constructor(
    val url: String,
    val statusCode: Int,
    val body: String,
    private val responseHeaders: Headers
) {
    fun header(name: String): String? = responseHeaders[name]
    fun headers(): Headers = responseHeaders
    fun parse(): Document = Jsoup.parse(body, url)
}

/**
 * Shared protected-site transport for every bundled source adapter.
 *
 * The HTTP cookie jar reads cookies from Android CookieManager for each exact request URL and
 * receives the original Set-Cookie response fields (including Domain, Path, Expires, Secure,
 * HttpOnly and SameSite attributes) before OkHttp follows a redirect. Session snapshots are
 * metadata only and are never used to synthesize a Cookie request header.
 */
object ProtectedSiteHttpClient {
    private val cookieManager: CookieManager
        get() = CookieManager.getInstance()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val androidCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            // Raw Set-Cookie values are persisted by RawSetCookieInterceptor below. Do not
            // reconstruct cookie strings from parsed Cookie objects: doing so can lose attributes.
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val raw = try { cookieManager.getCookie(url.toString()).orEmpty() } catch (_: Throwable) { "" }
            if (raw.isBlank()) return emptyList()
            return raw.split(';').mapNotNull { pair ->
                val entry = pair.trim()
                if (entry.isBlank()) null else Cookie.parse(url, entry)
            }
        }
    }

    /** Saves untouched Set-Cookie fields before OkHttp handles redirects to the next URL. */
    private val rawSetCookieInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val values = response.headers.values("Set-Cookie")
        if (values.isNotEmpty()) persistRawSetCookies(response.request.url.toString(), values)
        response
    }

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(androidCookieJar)
            .addNetworkInterceptor(rawSetCookieInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            // Never follow an HTTPS -> HTTP downgrade. CookieManager remains authoritative for
            // every hop, so cookies are loaded again by host/path on each redirect.
            .followSslRedirects(false)
            .build()
    }

    private fun persistRawSetCookies(url: String, rawValues: List<String>) {
        val manager = try { cookieManager } catch (_: Throwable) { return }
        val currentLooper = Looper.myLooper()
        if (currentLooper == Looper.getMainLooper()) {
            // Protected scrapers run on Dispatchers.IO. This branch is only a defensive fallback;
            // never block the main thread waiting for a callback dispatched to that same thread.
            rawValues.forEach { raw -> runCatching { manager.setCookie(url, raw) } }
            return
        }

        val latch = CountDownLatch(rawValues.size)
        for (raw in rawValues) {
            val task = Runnable {
                try {
                    manager.setCookie(url, raw) { latch.countDown() }
                } catch (_: Throwable) {
                    latch.countDown()
                }
            }
            if (!mainHandler.post(task)) latch.countDown()
        }
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try { manager.flush() } catch (_: Throwable) { }
    }

    /**
     * Returns a Jsoup request builder for compatibility with existing scraper-specific headers,
     * form data, referrers and timeout settings. Execute it with executeDocument/executeText/
     * executeResponse rather than calling Jsoup's network implementation directly.
     */
    fun connect(
        url: String,
        siteKey: String,
        providerBaseUrl: String,
        fallbackUserAgent: String
    ): Connection {
        val connection = Jsoup.connect(url)
        val fallbackUa = sanitizeUserAgent(fallbackUserAgent)
        val target = parseHttpsUri(url)
        val provider = parseHttpsUri(providerBaseUrl)
        if (target == null || provider == null || siteKey.isBlank()) {
            connection.userAgent(fallbackUa)
            return connection
        }

        val providerHost = PerSiteSessionStore.canonicalizeHost(provider.host.orEmpty())
        val targetHost = PerSiteSessionStore.canonicalizeHost(target.host.orEmpty())
        if (providerHost.isBlank() || targetHost.isBlank()) {
            connection.userAgent(fallbackUa)
            return connection
        }
        val isProviderOrigin = isProviderHost(targetHost, providerHost)
        val isTrustedEmbedOrigin = TrustedEmbedHostPolicy.isKnownEmbedHost(targetHost)
        if (!isProviderOrigin && !isTrustedEmbedOrigin) {
            // Unknown origins are not sent a saved provider User-Agent or session metadata.
            connection.userAgent(fallbackUa)
            return connection
        }

        val store = PerSiteSessionStore.getInstance()
        // Ask CookieManager using the exact request URL so its native Domain/Path/Secure rules
        // determine whether a clearance cookie is actually sent for this path.
        val liveCookieNames = store.captureCookiesForUrl(url).keys
        store.markExpiredIfClearanceMissing(siteKey, url, liveCookieNames)

        val targetUa = store.getUserAgentFor(siteKey, url)
        val providerUa = if (isProviderOrigin) store.getUserAgentFor(siteKey, providerBaseUrl) else null
        connection.userAgent(sanitizeUserAgent(targetUa ?: providerUa ?: fallbackUa))
        // No Cookie header is copied into this builder. The OkHttp CookieJar asks CookieManager
        // for each exact URL, including each redirect, and therefore cannot forward provider
        // cookies to a different embed domain.
        return connection
    }

    /** Executes an HTML request, inspecting the final HTTP headers/body before parsing. */
    fun executeDocument(connection: Connection, siteKey: String, providerBaseUrl: String): Document =
        executeResponse(connection, siteKey, providerBaseUrl).parse()

    /** Executes a text/JSON request and applies the same challenge/status policy. */
    fun executeText(connection: Connection, siteKey: String, providerBaseUrl: String): String =
        executeResponse(connection, siteKey, providerBaseUrl).body

    /**
     * Executes a configured Jsoup request through OkHttp while preserving its URL, headers,
     * method, simple form fields, referrer and timeout. If allowHttpErrors is false, ordinary
     * HTTP errors remain failures, but only a confirmed challenge becomes CloudflareChallenge.
     */
    fun executeResponse(
        connection: Connection,
        siteKey: String,
        providerBaseUrl: String,
        allowHttpErrors: Boolean = false
    ): ProtectedHttpResponse {
        val jsoupRequest = connection.request()
        val requestUrl = jsoupRequest.url().toString()
        val timeoutMs = jsoupRequest.timeout().coerceIn(1_000, 120_000)
        val client = baseClient.newBuilder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(jsoupRequest.followRedirects())
            .build()

        val requestBuilder = Request.Builder().url(requestUrl)
        val headers = jsoupRequest.headers()
        for ((name, value) in headers) {
            when (name.lowercase()) {
                "cookie", "host", "content-length", "connection", "transfer-encoding" -> Unit
                else -> requestBuilder.header(name, value)
            }
        }

        val method = jsoupRequest.method()
        val body = buildRequestBody(jsoupRequest, headers)
        val request = requestBuilder.method(method.name, body).build()
        val response: OkHttpResponse = try {
            client.newCall(request).execute()
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        }

        response.use { raw ->
            val finalUrl = raw.request.url.toString()
            val rawBody = raw.body?.string().orEmpty()
            val snapshot = ProtectedHttpResponse(
                url = finalUrl,
                statusCode = raw.code,
                body = rawBody,
                responseHeaders = raw.headers
            )

            if (isCloudflareResponse(snapshot)) {
                val challengeHost = parseHttpsUri(finalUrl)?.host.orEmpty()
                val safeChallengeUrl = when {
                    isProviderHttpsUrl(finalUrl, providerBaseUrl) -> finalUrl
                    TrustedEmbedHostPolicy.isKnownEmbedHost(challengeHost) -> finalUrl
                    else -> requestUrl
                }
                throw ExtensionError.CloudflareChallenge(
                    detail = "Confirmed Cloudflare/Turnstile challenge returned by ${challengeHost.ifBlank { "protected source" }} (HTTP ${raw.code})",
                    challengeUrl = safeChallengeUrl
                )
            }

            if (!allowHttpErrors && raw.code >= 400) {
                throw org.jsoup.HttpStatusException(
                    "HTTP error ${raw.code} fetching protected URL",
                    raw.code,
                    finalUrl
                )
            }
            return snapshot
        }
    }

    private fun buildRequestBody(
        request: Connection.Request,
        headers: Map<String, String>
    ): RequestBody? {
        val method = request.method()
        val data = request.data()
        if (data.isNotEmpty()) {
            val form = FormBody.Builder()
            data.forEach { pair ->
                require(!pair.hasInputStream()) {
                    "File uploads are not supported by ProtectedSiteHttpClient form adapter"
                }
                form.add(pair.key(), pair.value())
            }
            return form.build()
        }
        return when (method) {
            Connection.Method.POST, Connection.Method.PUT, Connection.Method.PATCH -> {
                val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }
                    ?.value?.toMediaTypeOrNull()
                byteArrayOf().toRequestBody(contentType, 0, 0)
            }
            else -> null
        }
    }

    /** Response-level test used by the manual same-provider redirect loop in AnimeBlkom. */
    fun throwIfChallenge(
        response: ProtectedHttpResponse,
        siteKey: String,
        providerBaseUrl: String,
        fallbackUrl: String
    ) {
        if (!isCloudflareResponse(response)) return
        val responseHost = parseHttpsUri(response.url)?.host.orEmpty()
        val challengeUrl = when {
            isProviderHttpsUrl(response.url, providerBaseUrl) -> response.url
            TrustedEmbedHostPolicy.isKnownEmbedHost(responseHost) -> response.url
            else -> fallbackUrl
        }
        throw ExtensionError.CloudflareChallenge(
            detail = "Confirmed Cloudflare/Turnstile challenge returned by ${responseHost.ifBlank { "protected source" }} (HTTP ${response.statusCode})",
            challengeUrl = challengeUrl
        )
    }

    /** Shared page-level Cloudflare/Turnstile signature detector. */
    fun isCloudflareDocument(doc: Document): Boolean {
        val title = doc.title().lowercase()
        val bodyText = doc.body()?.text()?.lowercase().orEmpty()
        val html = doc.outerHtml().lowercase()
        val challengeTitle = title.contains("just a moment") ||
            title.contains("attention required") ||
            title.contains("please verify") ||
            title.contains("security check") ||
            title.contains("checking your browser")
        val challengeText = bodyText.contains("verify you are human") &&
            (bodyText.contains("cloudflare") || html.contains("cf-turnstile") || html.contains("cf-chl-")) ||
            bodyText.contains("checking your browser") && bodyText.contains("cloudflare") ||
            bodyText.contains("enable javascript and cookies to continue") && html.contains("cloudflare")
        val challengeDom = doc.select(
            ".cf-browser-verification, #cf-wrapper, #challenge-form, #challenge-stage, " +
                "#turnstile-wrapper, #challenge-running, [name=\"cf-turnstile-response\"], " +
                "input[name^=\"cf_chl_\"], iframe[src*='challenges.cloudflare.com'], " +
                "script[src*='challenge-platform']"
        ).isNotEmpty()
        val challengeUrl = doc.location().lowercase().contains("/cdn-cgi/challenge-platform/")
        return challengeTitle || challengeText || challengeDom || challengeUrl ||
            html.contains("__cf_chl_") || html.contains("cf_chl_opt")
    }

    /**
     * A bare 403/503 is not proof of Cloudflare. Require cf-mitigated: challenge, a challenge
     * document signature, or a Cloudflare server error plus explicit challenge markers.
     */
    fun isCloudflareResponse(response: ProtectedHttpResponse): Boolean {
        val mitigation = response.header("cf-mitigated")?.trim()?.equals("challenge", ignoreCase = true) == true
        if (mitigation) return true
        val doc = Jsoup.parse(response.body, response.url)
        if (isCloudflareDocument(doc)) return true
        val server = response.header("Server").orEmpty().lowercase()
        val body = response.body.lowercase()
        val isCloudflareServer = server.contains("cloudflare")
        val knownChallengeMarker = listOf("cf-chl-", "cf_chl_opt", "cf_chl_prog", "challenge-platform", "challenges.cloudflare.com")
            .any(body::contains)
        return response.statusCode in setOf(403, 503) && isCloudflareServer && knownChallengeMarker
    }

    fun isCloudflareException(error: Throwable): Boolean {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        repeat(12) {
            val item = current ?: return false
            if (!visited.add(item)) return false
            if (item is ExtensionError.CloudflareChallenge) return true
            val message = item.message?.lowercase().orEmpty()
            if (listOf(
                    "cloudflare challenge", "cf-mitigated: challenge", "turnstile challenge",
                    "just a moment", "attention required", "challenge-platform", "cf-chl-",
                    "verify you are human", "checking your browser", "enable javascript and cookies to continue"
                ).any(message::contains)) return true
            current = item.cause
        }
        return false
    }

    fun findCloudflareChallenge(error: Throwable?): ExtensionError.CloudflareChallenge? {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current = error
        repeat(12) {
            val item = current ?: return null
            if (!visited.add(item)) return null
            if (item is ExtensionError.CloudflareChallenge) return item
            current = item.cause
        }
        return null
    }

    /** True only for HTTPS provider URLs; used for safe challenge URL propagation. */
    fun isProviderHttpsUrl(url: String, providerBaseUrl: String): Boolean {
        val target = parseHttpsUri(url) ?: return false
        val provider = parseHttpsUri(providerBaseUrl) ?: return false
        val providerHost = PerSiteSessionStore.canonicalizeHost(provider.host.orEmpty())
        val targetHost = PerSiteSessionStore.canonicalizeHost(target.host.orEmpty())
        return providerHost.isNotBlank() && targetHost.isNotBlank() && isProviderHost(targetHost, providerHost)
    }

    private fun parseHttpsUri(value: String): URI? = try {
        URI(value.trim()).takeIf {
            it.scheme.equals("https", ignoreCase = true) &&
                !it.host.isNullOrBlank() && it.userInfo == null
        }
    } catch (_: Exception) { null }

    private fun isProviderHost(targetHost: String, providerHost: String): Boolean =
        targetHost == providerHost || targetHost.endsWith(".$providerHost")

    private fun sanitizeUserAgent(value: String): String =
        value.filter { it >= ' ' && it != '\u007f' }.trim().take(512)
            .ifBlank { "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" }
}
