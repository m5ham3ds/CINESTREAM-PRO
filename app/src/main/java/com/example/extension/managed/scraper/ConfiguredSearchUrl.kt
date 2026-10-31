package com.example.extension.managed.scraper

import com.example.extension.managed.model.ManagedExtension
import java.net.URI
import java.net.URLEncoder

/**
 * Resolves the Admin-managed searchUrl template for a bundled scraper.
 *
 * Supported query tokens: %s, {query}, {q}, {search}, {{query}}.
 * Supported page tokens: {page}, {{page}}, %page%.
 * Supported conditional path token: {pagePath} (empty for page 1, "page/N/" for later pages).
 * Empty/absent templates keep the scraper's bundled fallback route for backwards compatibility.
 * Invalid configured templates fail closed so a typo cannot silently redirect the scraper to a
 * different hard-coded route.
 */
internal object ConfiguredSearchUrl {
    fun build(
        extension: ManagedExtension,
        query: String,
        page: Int,
        fallbackUrl: String
    ): String {
        val configured = extension.searchUrl?.trim().orEmpty()
        if (configured.isBlank()) return validateHttps(fallbackUrl, "fallback search URL")

        require(page >= 1) { "Search page must be >= 1" }
        val encodedQuery = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        var result = configured
        var queryTokenFound = false

        val queryTokens = listOf("{{query}}", "{query}", "{search}", "{q}", "%s")
        for (token in queryTokens) {
            if (result.contains(token, ignoreCase = true)) {
                result = result.replace(token, encodedQuery, ignoreCase = true)
                queryTokenFound = true
            }
        }

        val pageTokens = listOf("{{page}}", "{page}", "%page%")
        var pageTokenFound = false
        for (token in pageTokens) {
            if (result.contains(token, ignoreCase = true)) {
                result = result.replace(token, page.toString(), ignoreCase = true)
                pageTokenFound = true
            }
        }

        // WordPress-style sources such as Anime4Up and EgyDead use /page/N/?s=...
        // rather than a query-string page parameter. This token preserves their native route
        // while keeping page 1 at /?s=....
        val pagePathTokens = listOf("{{pagePath}}", "{pagePath}")
        var pagePathTokenFound = false
        for (token in pagePathTokens) {
            if (result.contains(token, ignoreCase = true)) {
                val pagePath = if (page > 1) "page/$page/" else ""
                result = result.replace(token, pagePath, ignoreCase = true)
                pagePathTokenFound = true
            }
        }

        if (!queryTokenFound) {
            val emptySearchParameter = Regex("([?&](?:q|query|s|keywords)=)(?=(&|#|$))", RegexOption.IGNORE_CASE)
            val emptyParamMatch = emptySearchParameter.find(result)
            result = if (emptyParamMatch != null) {
                result.replaceRange(emptyParamMatch.range, emptyParamMatch.groupValues[1] + encodedQuery)
            } else {
                appendQueryParameter(result, "q", encodedQuery)
            }
        }

        if (!pageTokenFound && !pagePathTokenFound) {
            val pageParameter = Regex("([?&]page=)[^&#]*", RegexOption.IGNORE_CASE)
            val currentPageMatch = pageParameter.find(result)
            result = if (currentPageMatch != null) {
                result.replaceRange(currentPageMatch.range, currentPageMatch.groupValues[1] + page)
            } else if (page > 1) {
                appendQueryParameter(result, "page", page.toString())
            } else result
        }

        return validateHttps(result, "configured search URL")
    }

    private fun appendQueryParameter(url: String, name: String, value: String): String {
        val fragmentIndex = url.indexOf('#')
        val main = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
        val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
        val separator = if (main.contains('?')) {
            if (main.endsWith('?') || main.endsWith('&')) "" else "&"
        } else "?"
        return "$main$separator$name=$value$fragment"
    }

    private fun validateHttps(raw: String, label: String): String {
        val uri = try {
            URI(raw.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid $label: ${e.message}", e)
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.trim()?.lowercase().orEmpty()
        require(scheme == "https") { "$label must use HTTPS" }
        require(host.isNotBlank()) { "$label must have a valid host" }
        require(uri.rawUserInfo == null) { "$label must not contain URL credentials" }
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") && !host.endsWith(".internal")) {
            "$label cannot target a local hostname"
        }
        require(!isIpLiteral(host)) { "$label cannot use an IP literal" }
        return uri.toASCIIString()
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true // IPv6 literals
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}
