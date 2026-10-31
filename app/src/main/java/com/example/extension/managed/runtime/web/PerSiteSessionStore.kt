package com.example.extension.managed.runtime.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Local per-provider verification metadata. Android CookieManager owns all live cookie values and
 * their original Domain/Path/Secure/HttpOnly/SameSite/Expires attributes. This store never replays
 * cookie snapshots; legacy snapshots are read only to migrate their cookie *names* into v2 metadata.
 */
class PerSiteSessionStore(storageDir: File? = null) {
    @Volatile private var storageDir: File? = storageDir
    private val memorySessions = ConcurrentHashMap<String, ProtectedSiteSession>()
    private val memoryUserAgents = ConcurrentHashMap<String, String>()

    init { loadPersistedSessions() }

    companion object {
        private const val SESSION_FILE_VERSION = "protected-session-v2"
        @Volatile private var instance: PerSiteSessionStore? = null

        fun getInstance(context: Context? = null): PerSiteSessionStore {
            val current = instance
            if (current != null) {
                if (context != null) current.attachStorageDirectory(context)
                return current
            }
            return synchronized(this) {
                val insideLock = instance
                if (insideLock != null) {
                    if (context != null) insideLock.attachStorageDirectory(context)
                    insideLock
                } else {
                    val dir = context?.applicationContext?.filesDir?.let { File(it, "protected_sessions") }
                    PerSiteSessionStore(dir).also { instance = it }
                }
            }
        }

        fun canonicalizeHost(urlOrHost: String): String {
            val clean = urlOrHost.trim()
            if (clean.isBlank()) return ""
            val parsedHost = try {
                val candidate = if (clean.startsWith("//")) "https:$clean"
                    else if (clean.contains("://")) clean
                    else "https://$clean"
                URI(candidate).host
            } catch (_: Exception) { null }
            val host = (parsedHost ?: clean.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':'))
                .trim().trimEnd('.').lowercase()
            return host.removePrefix("www.")
        }

        private fun compositeKey(siteKey: String, canonicalHost: String): String =
            "${siteKey.trim().lowercase()}:${canonicalizeHost(canonicalHost)}"

        private val COOKIE_NAME_REGEX = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

        /** Fingerprint only; never log or persist the raw clearance value. */
        fun cookieValueFingerprint(value: String?): String? {
            val clean = value?.takeIf { it.isNotBlank() } ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(clean.toByteArray(Charsets.UTF_8))
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }

    @Synchronized
    fun attachStorageDirectory(context: Context) {
        if (storageDir != null) return
        storageDir = File(context.applicationContext.filesDir, "protected_sessions")
        loadPersistedSessions()
    }

    @Synchronized
    fun getSession(siteKey: String, canonicalHost: String): ProtectedSiteSession? {
        val key = compositeKey(siteKey, canonicalHost)
        val session = memorySessions[key] ?: run {
            CloudflareForensicLogger.cf03_sessionNotFound(siteKey)
            return null
        }
        val now = System.currentTimeMillis()
        if (session.state == ProtectedSessionState.ACTIVE && (session.expiresAt <= 0L || now >= session.expiresAt)) {
            val expired = session.copy(state = ProtectedSessionState.EXPIRED, cookies = emptyMap())
            memorySessions[key] = expired
            persistSession(expired)
            CloudflareForensicLogger.cfTrace("session_expired", candidateId = siteKey, host = session.canonicalHost)
            return expired
        }
        CloudflareForensicLogger.cf03_sessionFound(
            siteKey = siteKey,
            state = session.state,
            cookieCount = session.sanitizedCookieNames().size,
            hasCfClearance = session.hasCfClearance
        )
        return session
    }

    fun getSessionForUrl(siteKey: String, url: String): ProtectedSiteSession? {
        val uri = parseSecureUrl(url) ?: return null
        val host = canonicalizeHost(uri.host)
        return getSession(siteKey, host)?.takeIf { canonicalizeHost(it.canonicalHost) == host }
    }

    fun rememberUserAgent(siteKey: String, urlOrHost: String, userAgent: String) {
        val host = canonicalizeHost(urlOrHost)
        val ua = userAgent.filter { it >= ' ' && it != '\u007f' }.trim().take(512)
        if (siteKey.isNotBlank() && host.isNotBlank() && ua.isNotBlank()) {
            memoryUserAgents[compositeKey(siteKey, host)] = ua
        }
    }

    fun getUserAgentFor(siteKey: String, url: String): String? {
        val uri = parseSecureUrl(url) ?: return null
        val key = compositeKey(siteKey, canonicalizeHost(uri.host))
        return memoryUserAgents[key]?.takeIf { it.isNotBlank() }
            ?: memorySessions[key]?.takeIf { it.isValid() }?.userAgent?.takeIf { it.isNotBlank() }
    }

    /** Compatibility only. Values come from Android CookieManager for this exact URL, never from snapshots. */
    @Deprecated("CookieManager is the source of request cookies; do not build headers from session metadata")
    fun getCookieHeaderFor(siteKey: String, url: String): String? {
        val uri = parseSecureUrl(url) ?: return null
        val session = getSession(siteKey, uri.host) ?: return null
        if (!session.isValid()) return null
        return runCatching { CookieManager.getInstance().getCookie(uri.toASCIIString()) }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Marks stale metadata expired when the live CookieManager no longer offers the expected clearance. */
    @Synchronized
    fun markExpiredIfClearanceMissing(siteKey: String, url: String, liveCookieNames: Set<String>) {
        val uri = parseSecureUrl(url) ?: return
        val key = compositeKey(siteKey, uri.host)
        val existing = memorySessions[key] ?: return
        if (existing.state != ProtectedSessionState.ACTIVE || !existing.hasCfClearance) return
        if (liveCookieNames.any { it.equals("cf_clearance", ignoreCase = true) }) return
        val expired = existing.copy(
            state = ProtectedSessionState.EXPIRED,
            expiresAt = System.currentTimeMillis(),
            lastValidatedAt = System.currentTimeMillis(),
            cookies = emptyMap()
        )
        memorySessions[key] = expired
        persistSession(expired)
        CloudflareForensicLogger.cfTrace("clearance_absent_from_live_cookie_manager", candidateId = siteKey, host = uri.host)
    }

    @Synchronized
    fun saveSession(session: ProtectedSiteSession) {
        val host = canonicalizeHost(session.canonicalHost)
        val names = session.sanitizedCookieNames()
        val key = compositeKey(session.siteKey, host)
        if (host.isBlank() || session.siteKey.isBlank()) return
        if (session.state == ProtectedSessionState.ACTIVE && names.isEmpty()) {
            CloudflareForensicLogger.cfTrace("active_empty_or_invalid_session_rejected", candidateId = session.siteKey, host = host)
            return
        }
        val now = System.currentTimeMillis()
        val expiry = session.expiresAt.takeIf { it > 0L }
            ?: (maxOf(session.createdAt, session.lastValidatedAt) + ProtectedSiteSession.DEFAULT_SESSION_TTL_MS)
        val safeSession = session.copy(
            canonicalHost = host,
            cookies = emptyMap(),
            cookieNames = names,
            expiresAt = expiry,
            state = if (session.state == ProtectedSessionState.ACTIVE && now >= expiry) ProtectedSessionState.EXPIRED else session.state,
            userAgent = session.userAgent.filter { it >= ' ' && it != '\u007f' }.take(512)
        )
        memorySessions[key] = safeSession
        persistSession(safeSession)
        CloudflareForensicLogger.cf10_sessionEstablished(
            siteKey = safeSession.siteKey,
            canonicalHost = safeSession.canonicalHost,
            hasCfClearance = safeSession.hasCfClearance
        )
        CloudflareForensicLogger.cf11_sessionPersisted(siteKey = safeSession.siteKey, canonicalHost = safeSession.canonicalHost)
    }

    @Synchronized
    fun saveSession(siteKey: String, targetUrl: String, cookies: Map<String, String>) {
        val host = canonicalizeHost(targetUrl)
        val names = cookies.keys.filter { COOKIE_NAME_REGEX.matches(it.trim()) }.toSet()
        saveSession(ProtectedSiteSession(
            siteKey = siteKey,
            canonicalHost = host,
            cookies = emptyMap(),
            cookieNames = names,
            state = if (names.isNotEmpty()) ProtectedSessionState.ACTIVE else ProtectedSessionState.NONE
        ))
    }

    private fun markSessionInvalid(siteKey: String, canonicalHost: String, reason: String) {
        val key = compositeKey(siteKey, canonicalHost)
        val existing = memorySessions[key]
        if (existing != null) {
            val invalidated = existing.copy(
                state = ProtectedSessionState.INVALID,
                expiresAt = System.currentTimeMillis(),
                lastValidatedAt = System.currentTimeMillis(),
                cookies = emptyMap()
            )
            memorySessions[key] = invalidated
            persistSession(invalidated)
        }
        CloudflareForensicLogger.cfTrace("stored_session_invalidated", candidateId = siteKey, host = canonicalizeHost(canonicalHost), state = reason.take(80))
    }

    /** Metadata invalidation only. Use invalidateForChallenge when a live provider rejection was observed. */
    @Synchronized
    fun invalidateSession(siteKey: String, canonicalHost: String, reason: String = "Origin challenge triggered") {
        markSessionInvalid(siteKey, canonicalHost, reason)
        expireRejectedCfClearance(canonicalHost)
    }

    /** Legacy synchronous invalidation for explicit reset actions. Interactive challenges should
     * use invalidateForChallenge so the old-cookie fingerprint is captured before removal. */
    private fun expireRejectedCfClearance(canonicalHost: String) {
        val host = canonicalizeHost(canonicalHost)
        if (host.isBlank() || !host.matches(Regex("^[a-z0-9.-]+$")) || host.contains("..")) return
        val manager = try { CookieManager.getInstance() } catch (_: Throwable) { null } ?: return
        try {
            manager.setAcceptCookie(true)
            for (targetHost in listOf(host, "www.$host").distinct()) {
                val url = "https://$targetHost/"
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure")
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure; Domain=$host")
            }
            manager.flush()
        } catch (_: Throwable) { }
    }

    /**
     * Captures a fingerprint before invalidating and awaiting deletion of rejected cf_clearance.
     * The raw value is never returned, logged, or persisted. Cookies belonging to an external embed
     * host are deleted only for that host; provider apex/www aliases are touched only when the
     * challenge host is one of those exact aliases.
     */
    suspend fun invalidateForChallenge(
        siteKey: String,
        providerCanonicalHost: String,
        challengeUrl: String,
        reason: String = "Provider rejected clearance"
    ): String? {
        val uri = parseSecureUrl(challengeUrl) ?: run {
            // An invalid URL is not authorization to clear cookies on provider aliases.
            markSessionInvalid(siteKey, providerCanonicalHost, reason)
            return null
        }
        val manager = try { CookieManager.getInstance() } catch (_: Throwable) { null }
        val oldRaw = runCatching { manager?.getCookie(uri.toASCIIString()).orEmpty() }.getOrDefault("")
        val oldValue = parseCookieHeader(oldRaw).entries.firstOrNull { it.key.equals("cf_clearance", true) }?.value
        val fingerprint = cookieValueFingerprint(oldValue)
        markSessionInvalid(siteKey, providerCanonicalHost, reason)
        clearRejectedClearance(manager, uri, providerCanonicalHost)
        return fingerprint
    }

    private suspend fun clearRejectedClearance(manager: CookieManager?, challengeUri: URI, providerCanonicalHost: String) {
        manager ?: return
        val challengeHost = challengeUri.host.lowercase().trimEnd('.')
        if (challengeHost.isBlank() || !challengeHost.matches(Regex("^[a-z0-9.-]+$")) || challengeHost.contains("..")) return
        val providerHost = canonicalizeHost(providerCanonicalHost)
        val path = challengeUri.rawPath.orEmpty().ifBlank { "/" }
        val pathVariants = linkedSetOf("/")
        if (path != "/") {
            pathVariants.add(path)
            val parent = path.substringBeforeLast('/', "").ifBlank { "/" }
            if (parent != "/") {
                // RFC default-path and its slash-terminated variant are different cookie paths.
                pathVariants.add(parent)
                pathVariants.add(if (parent.endsWith('/')) parent else "$parent/")
            }
        }
        val deletions = mutableListOf<Pair<String, String>>()
        // Clear only the host that actually returned the challenge. Do not invalidate the host-only
        // clearance of www merely because the apex rejected its own cookie, or vice versa.
        for (cookiePath in pathVariants) {
            val urlPath = cookiePath.takeIf { it.startsWith("/") } ?: "/"
            val url = "https://$challengeHost$urlPath"
            deletions += url to "cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$cookiePath; Secure"
            deletions += url to "cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$cookiePath; Secure; Domain=$challengeHost"
            // A domain-wide provider cookie can be removed through the challenged host, but never
            // from an unrelated embed origin. CookieManager keeps the scope matching exact.
            if (providerHost.isNotBlank() &&
                (challengeHost == providerHost || challengeHost == "www.$providerHost" || challengeHost.endsWith(".$providerHost"))) {
                deletions += url to "cf_clearance=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$cookiePath; Secure; Domain=$providerHost"
            }
        }
        withContext(Dispatchers.Main.immediate) {
            manager.setAcceptCookie(true)
            val uniqueDeletions = deletions.distinct()
            if (uniqueDeletions.isNotEmpty()) {
                withTimeoutOrNull(3_000L) {
                    suspendCancellableCoroutine<Boolean> { continuation ->
                        val remaining = java.util.concurrent.atomic.AtomicInteger(uniqueDeletions.size)
                        uniqueDeletions.forEach { (url, value) ->
                            try {
                                manager.setCookie(url, value) {
                                    if (remaining.decrementAndGet() == 0 && continuation.isActive) continuation.resume(true)
                                }
                            } catch (_: Throwable) {
                                if (remaining.decrementAndGet() == 0 && continuation.isActive) continuation.resume(false)
                            }
                        }
                    }
                }
            }
            runCatching { manager.flush() }
        }
    }

    @Synchronized fun replaceSession(newSession: ProtectedSiteSession) = saveSession(newSession)

    @Synchronized
    fun clearSiteSession(siteKey: String, canonicalHost: String) {
        val key = compositeKey(siteKey, canonicalHost)
        memorySessions.remove(key)
        memoryUserAgents.remove(key)
        deletePersistedSessionFile(key)
    }

    @Synchronized
    fun clearAllSessions() {
        memorySessions.clear()
        memoryUserAgents.clear()
        storageDir?.listFiles()?.forEach { runCatching { it.delete() } }
    }

    fun getAllSessions(): List<ProtectedSiteSession> = memorySessions.values.toList()

    /** Compatibility shim: cookie values are never reconstructed from metadata snapshots. */
    @Deprecated("Live cookies are shared through Android CookieManager")
    fun restoreCookiesToWebView(canonicalHost: String, cookies: Map<String, String>) = Unit

    /** Exact URL only. Do not merge cookies from apex/www/subdomains; CookieManager applies path/domain rules. */
    fun captureCookiesForUrl(url: String): Map<String, String> {
        val uri = parseSecureUrl(url) ?: return emptyMap()
        val manager = try { CookieManager.getInstance() } catch (_: Throwable) { null } ?: return emptyMap()
        return parseCookieHeader(runCatching { manager.getCookie(uri.toASCIIString()).orEmpty() }.getOrDefault(""))
            .filterValues { value -> value.isNotBlank() }
    }

    /** Legacy callers are deliberately restricted to canonical apex root; no www merge. */
    fun captureCookiesFromWebView(canonicalHost: String): Map<String, String> {
        val host = canonicalizeHost(canonicalHost)
        if (host.isBlank()) return emptyMap()
        return captureCookiesForUrl("https://$host/")
    }

    fun parseCookieHeader(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.split(';').forEach { pair ->
            val trimmed = pair.trim()
            val idx = trimmed.indexOf('=')
            if (idx <= 0) return@forEach
            val name = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (COOKIE_NAME_REGEX.matches(name) && value.isNotBlank() && value.none { it <= '\u001f' || it == '\u007f' || it == ';' }) {
                result[name] = value
            }
        }
        return result
    }

    private fun parseSecureUrl(value: String): URI? = try {
        URI(value.trim()).takeIf { it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.userInfo == null }
    } catch (_: Exception) { null }

    private fun loadPersistedSessions() {
        val dir = storageDir ?: return
        if (!dir.exists() && !dir.mkdirs()) return
        if (!dir.isDirectory) return
        for (file in dir.listFiles().orEmpty()) {
            try {
                val lines = file.readLines()
                if (lines.isEmpty()) continue
                val isV2 = lines[0] == SESSION_FILE_VERSION
                val base = if (isV2) 1 else 0
                if (lines.size < base + 6) continue
                val siteKey = lines[base].trim()
                val host = canonicalizeHost(lines[base + 1])
                if (siteKey.isBlank() || host.isBlank()) continue
                val persistedState = runCatching { ProtectedSessionState.valueOf(lines[base + 2].trim()) }.getOrDefault(ProtectedSessionState.NONE)
                val createdAt = lines[base + 3].trim().toLongOrNull() ?: System.currentTimeMillis()
                val validatedAt = lines[base + 4].trim().toLongOrNull() ?: createdAt
                val storedExpiry = lines[base + 5].trim().toLongOrNull() ?: 0L
                val expiry = storedExpiry.takeIf { it > 0L } ?: (maxOf(createdAt, validatedAt) + ProtectedSiteSession.DEFAULT_SESSION_TTL_MS)
                var userAgent = ""
                var names = emptySet<String>()
                if (isV2) {
                    for (line in lines.drop(base + 6)) {
                        when {
                            line.startsWith("userAgent64=") -> userAgent = runCatching {
                                String(Base64.decode(line.removePrefix("userAgent64="), Base64.NO_WRAP), Charsets.UTF_8)
                            }.getOrDefault("")
                            line.startsWith("cookieNames64=") -> names = runCatching {
                                String(Base64.decode(line.removePrefix("cookieNames64="), Base64.NO_WRAP), Charsets.UTF_8)
                                    .lineSequence().filter { COOKIE_NAME_REGEX.matches(it) }.toSet()
                            }.getOrDefault(emptySet())
                        }
                    }
                } else {
                    val uaLine = lines.getOrNull(base + 6).orEmpty()
                    val hasUaLine = uaLine.startsWith("userAgent64=")
                    if (hasUaLine) userAgent = runCatching {
                        String(Base64.decode(uaLine.removePrefix("userAgent64="), Base64.NO_WRAP), Charsets.UTF_8)
                    }.getOrDefault("")
                    // Legacy files contained raw cookie values. Read only names, then rewrite v2 so
                    // the raw values are removed from disk as part of migration.
                    val cookieStart = if (hasUaLine) base + 7 else base + 6
                    names = lines.drop(cookieStart).mapNotNull { line ->
                        val eq = line.indexOf('=')
                        if (eq <= 0) null else line.substring(0, eq).trim().takeIf { COOKIE_NAME_REGEX.matches(it) }
                    }.toSet()
                }
                val effectiveState = if (persistedState == ProtectedSessionState.ACTIVE &&
                    (System.currentTimeMillis() >= expiry || names.isEmpty())) ProtectedSessionState.EXPIRED else persistedState
                val session = ProtectedSiteSession(
                    siteKey = siteKey,
                    canonicalHost = host,
                    cookies = emptyMap(),
                    cookieNames = names,
                    createdAt = createdAt,
                    lastValidatedAt = validatedAt,
                    expiresAt = expiry,
                    state = effectiveState,
                    userAgent = userAgent
                )
                memorySessions.putIfAbsent(compositeKey(siteKey, host), session)
                if (!isV2 || effectiveState != persistedState) {
                    // Rewrite legacy records without raw cookie values. If the legacy filename is
                    // non-canonical, remove it only after the canonical v2 record was successfully
                    // written; otherwise old clear-text credentials could remain on disk forever.
                    persistSession(session)
                    if (!isV2) {
                        val canonicalName = persistedFileName(session.siteKey, session.canonicalHost)
                        if (file.name != canonicalName) runCatching { file.delete() }
                    }
                }
            } catch (_: Throwable) { }
        }
    }

    private fun persistedFileName(siteKey: String, canonicalHost: String): String =
        compositeKey(siteKey, canonicalHost).replace(":", "_").replace("/", "_") + ".session"

    private fun persistSession(session: ProtectedSiteSession) {
        val dir = storageDir ?: return
        try {
            if (!dir.exists() && !dir.mkdirs()) return
            val file = File(dir, persistedFileName(session.siteKey, session.canonicalHost))
            val userAgentEncoded = Base64.encodeToString(session.userAgent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val namesEncoded = Base64.encodeToString(session.sanitizedCookieNames().sorted().joinToString("\n").toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val content = buildString {
                appendLine(SESSION_FILE_VERSION)
                appendLine(session.siteKey)
                appendLine(session.canonicalHost)
                appendLine(session.state.name)
                appendLine(session.createdAt)
                appendLine(session.lastValidatedAt)
                appendLine(session.expiresAt)
                appendLine("userAgent64=$userAgentEncoded")
                appendLine("cookieNames64=$namesEncoded")
            }
            val temp = File(dir, file.name + ".tmp")
            temp.writeText(content)
            if (!temp.renameTo(file)) {
                file.writeText(content)
                temp.delete()
            }
        } catch (_: Throwable) { }
    }

    private fun deletePersistedSessionFile(compositeKey: String) {
        val dir = storageDir ?: return
        val file = File(dir, compositeKey.replace(":", "_").replace("/", "_") + ".session")
        if (file.exists()) runCatching { file.delete() }
    }
}
