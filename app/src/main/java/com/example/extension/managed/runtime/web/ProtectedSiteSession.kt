package com.example.extension.managed.runtime.web

/**
 * Local verification metadata for one provider host.
 *
 * Cookie values are transient inputs only. PerSiteSessionStore removes them before memory/disk
 * persistence; the native Android CookieManager owns live values and their Domain/Path/Secure/
 * HttpOnly/SameSite/Expires semantics. cookieNames records only the names observed at verification
 * time and is not authentication proof; each actual HTTP response remains authoritative.
 */
enum class ProtectedSessionState {
    NONE,
    ACTIVE,
    CHALLENGE_REQUIRED,
    INVALID,
    EXPIRED
}

data class ProtectedSiteSession(
    val siteKey: String,
    val canonicalHost: String,
    /** Transient map passed by a caller during verification; cleared before the session is stored. */
    val cookies: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastValidatedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + DEFAULT_SESSION_TTL_MS,
    val state: ProtectedSessionState = ProtectedSessionState.NONE,
    val userAgent: String = "",
    val requiresInteraction: Boolean = false,
    /** Cookie names only; values are always read from Android CookieManager per request URL. */
    val cookieNames: Set<String> = cookies.keys
) {
    private fun normalizedCookieNames(): Set<String> =
        (cookieNames + sanitizedCookies().keys).map { it.trim() }
            .filter { COOKIE_NAME_REGEX.matches(it) }
            .toSet()

    val hasCfClearance: Boolean
        get() = normalizedCookieNames().any { it.equals("cf_clearance", ignoreCase = true) }

    /** Bounds local verification metadata; the protected site still decides if a request is valid. */
    fun isValid(now: Long = System.currentTimeMillis()): Boolean {
        if (state != ProtectedSessionState.ACTIVE) return false
        if (expiresAt <= 0L || now >= expiresAt) return false
        return normalizedCookieNames().isNotEmpty()
    }

    fun hasValidCookies(): Boolean = normalizedCookieNames().isNotEmpty()

    fun sanitizedCookieNames(): Set<String> = normalizedCookieNames()

    /**
     * Sanitizes transient cookie pairs only. This must not be used to persist or replay them;
     * native CookieManager is the only source for outbound cookie values.
     */
    fun sanitizedCookies(): Map<String, String> = cookies.entries.mapNotNull { (rawName, rawValue) ->
        val name = rawName.trim()
        val value = rawValue.trim()
        if (!COOKIE_NAME_REGEX.matches(name) || value.isBlank() || value.any { it <= '\u001f' || it == '\u007f' || it == ';' }) {
            null
        } else {
            name to value
        }
    }.toMap(LinkedHashMap())

    @Deprecated("Do not synthesize a Cookie header from session metadata; query Android CookieManager for the exact URL.")
    fun toCookieHeader(): String = sanitizedCookies().entries.joinToString("; ") { "${it.key}=${it.value}" }

    companion object {
        /** Conservative local metadata cache lifetime only; never overrides cookie attributes from the site. */
        const val DEFAULT_SESSION_TTL_MS: Long = 15 * 60 * 1000L
        private val COOKIE_NAME_REGEX = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

        fun createEmpty(siteKey: String, canonicalHost: String): ProtectedSiteSession = ProtectedSiteSession(
            siteKey = siteKey,
            canonicalHost = canonicalHost,
            cookies = emptyMap(),
            cookieNames = emptySet(),
            expiresAt = System.currentTimeMillis(),
            state = ProtectedSessionState.NONE
        )
    }
}
