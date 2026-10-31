package com.example.extension.managed.scraper

import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.CredentialProjector
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.runtime.web.ProtectedSiteHttpClient
import com.example.extension.managed.web.ControlledWebViewEngine
import com.example.extension.managed.web.MediaStreamDetector
import com.example.extension.managed.web.TrustedEmbedHostPolicy
import com.example.extension.managed.web.WebExtractionEngine
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

/** Shared, security-conscious primitives for the ported AniYomi source adapters. */
internal object PortedScraperSupport {
    val userAgent: String = MediaStreamDetector.STANDARD_USER_AGENT

    fun resolve(baseUrl: String, candidate: String): String {
        val raw = candidate.trim()
        if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript:", true) ||
            raw.startsWith("data:", true) || raw.startsWith("file:", true) || raw.startsWith("content:", true)) return ""
        return try {
            val base = URI(baseUrl.trimEnd('/') + "/")
            val parsed = URI(raw)
            val resolved = if (parsed.isAbsolute) parsed else base.resolve(parsed)
            val host = resolved.host?.lowercase().orEmpty()
            if (resolved.scheme?.lowercase() !in listOf("https", "http") || host.isBlank() ||
                resolved.userInfo != null || TrustedEmbedHostPolicy.isForbiddenHost(host)) ""
            else resolved.toASCIIString().replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        } catch (_: Throwable) { "" }
    }

    fun isSafeProviderOrEmbed(url: String, providerBaseUrl: String): Boolean {
        return ProtectedSiteHttpClient.isProviderHttpsUrl(url, providerBaseUrl) || runCatching {
            val uri = URI(url)
            uri.scheme.equals("https", true) && uri.userInfo == null && !uri.host.isNullOrBlank() &&
                !TrustedEmbedHostPolicy.isForbiddenHost(uri.host) && TrustedEmbedHostPolicy.isKnownEmbedHost(uri.host)
        }.getOrDefault(false)
    }

    fun connection(extension: ManagedExtension, scraperKey: String, url: String): Connection =
        // ProtectedSiteHttpClient owns the final User-Agent selection. Do not overwrite it here:
        // Cloudflare can bind a fresh clearance to the exact WebView User-Agent.
        ProtectedSiteHttpClient.connect(url, scraperKey, extension.baseUrl, userAgent)
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .referrer(extension.baseUrl.trimEnd('/') + "/")
            .timeout(15_000)

    fun document(extension: ManagedExtension, scraperKey: String, url: String): Document {
        val safeUrl = resolve(extension.baseUrl, url)
        if (safeUrl.isBlank() || !isSafeProviderOrEmbed(safeUrl, extension.baseUrl)) {
            throw ExtensionError.InvalidConfiguration("Refused unsafe URL for $scraperKey")
        }
        val doc = connection(extension, scraperKey, safeUrl).let { ProtectedSiteHttpClient.executeDocument(it, scraperKey, extension.baseUrl) }
        if (ProtectedSiteHttpClient.isCloudflareDocument(doc)) {
            throw ExtensionError.CloudflareChallenge("Cloudflare challenge detected for $scraperKey", safeUrl)
        }
        return doc
    }

    fun failure(site: String, operation: String, url: String, error: Throwable): Throwable {
        if (error is ExtensionError.CloudflareChallenge) return error
        if (ProtectedSiteHttpClient.isCloudflareException(error)) {
            return ExtensionError.CloudflareChallenge("Cloudflare challenge on $site $operation", url)
        }
        if (error is ExtensionError) return error
        return ExtensionError.ExtractionFailed("$site $operation failed: ${error.message.orEmpty().take(180)}", error)
    }

    fun contentTypeFor(extension: ManagedExtension, fallback: ContentType): ContentType =
        extension.contentTypes.firstOrNull { it == fallback } ?: extension.contentTypes.firstOrNull() ?: fallback

    suspend fun extract(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ExtractionRequest,
        webEngine: WebExtractionEngine?,
        scraperKey: String,
        displayName: String
    ): Result<ExtractionResult> {
        val embedUrl = resolve(extension.baseUrl, request.serverItem.link)
        if (embedUrl.isBlank() || !isSafeProviderOrEmbed(embedUrl, extension.baseUrl)) {
            return Result.failure(ExtensionError.InvalidConfiguration("Unsafe or invalid server URL for $scraperKey"))
        }
        val headers = CredentialProjector.projectHeaders(
            sessionHeaders = mapOf("Referer" to request.serverItem.sourceUrl, "User-Agent" to userAgent),
            sessionCookies = session.sessionCookies,
            requiredHeaderKeys = setOf("referer", "user-agent")
        )
        if (MediaStreamDetector.isMediaUrl(embedUrl)) {
            val normalized = MediaStreamDetector.normalizeStreamUrl(embedUrl)
            if (MediaStreamDetector.validateMediaUrl(normalized)) {
                val hls = normalized.substringBefore('?').endsWith(".m3u8", true)
                return Result.success(ExtractionResult(playbackSource = PlaybackSource(
                    streamUrl = normalized,
                    mimeType = if (hls) "application/x-mpegURL" else "video/mp4",
                    headers = headers,
                    protocol = if (hls) StreamProtocol.HLS else StreamProtocol.DIRECT_FILE
                )))
            }
        }
        if (webEngine == null) {
            return Result.failure(ExtensionError.ExtractionFailed("Web extraction engine is unavailable for $displayName"))
        }
        val extractResult = try {
            webEngine.extractStreamUrl(
                targetUrl = embedUrl,
                targetServerId = request.serverItem.id,
                script = ControlledWebViewEngine.getPublicEmbedMediaExtractionScript(),
                timeoutMs = request.timeoutMs,
                expectedOrigin = extension.baseUrl
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
        if (extractResult.isSuccess) {
            val rawStream = extractResult.getOrThrow()
            val normalized = MediaStreamDetector.normalizeStreamUrl(rawStream)
            if (MediaStreamDetector.validateMediaUrl(normalized)) {
                val hls = normalized.substringBefore('?').endsWith(".m3u8", true)
                return Result.success(ExtractionResult(playbackSource = PlaybackSource(
                    streamUrl = normalized,
                    mimeType = if (hls) "application/x-mpegURL" else "video/mp4",
                    headers = headers + ("Referer" to embedUrl),
                    protocol = if (hls) StreamProtocol.HLS else StreamProtocol.DIRECT_FILE
                )))
            }
        }
        val error = extractResult.exceptionOrNull()
        if (error != null && ProtectedSiteHttpClient.isCloudflareException(error)) {
            return Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge on $displayName embed", embedUrl))
        }
        // Keep a legitimate embed handoff when a direct source cannot be extracted. No cookies
        // or provider-only credentials are attached to an unrelated host.
        return Result.success(ExtractionResult(playbackSource = PlaybackSource(
            streamUrl = embedUrl,
            mimeType = "text/html",
            headers = mapOf("Referer" to extension.baseUrl, "User-Agent" to userAgent),
            protocol = StreamProtocol.EMBED,
            metadata = mapOf("scraperKey" to scraperKey, "serverId" to request.serverItem.id)
        )))
    }

    fun mediaId(url: String): String = url.trimEnd('/').substringAfterLast('/').ifBlank { url.hashCode().toString() }

    fun episodeNumber(label: String, url: String, fallback: Int): Int {
        val pattern = Regex("(?i)(?:episode|ep|الحلقة|حلقة|e)[\\s_-]*(\\d+)")
        return pattern.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?:-|/)(\\d+)(?:/|$)").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("\\d+").find(label)?.value?.toIntOrNull()
            ?: fallback
    }
}
