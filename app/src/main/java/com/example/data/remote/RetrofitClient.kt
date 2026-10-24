package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import com.example.MyApplication
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // Cloudflare Worker TMDB Gateway
    private const val BASE_URL = "https://cinestream-api-gateway.blackmyemail1.workers.dev/api/v1/tmdb/"

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        // Redact Authorization header to prevent token leakage in debug logs
        redactHeader("Authorization")
        level = if (com.example.BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
    }

    private val cacheSize = (50 * 1024 * 1024).toLong() // 50 MB

    // Pace actual gateway requests and honor Retry-After after HTTP 429. This is a
    // process-wide guard: Compose recompositions and adjacent screens must not turn
    // one scroll into a burst of requests against the shared Worker.
    private val requestPacingLock = Any()
    @Volatile private var nextGatewayRequestAtMs: Long = 0L
    @Volatile private var gatewayRateLimitedUntilMs: Long = 0L

    private fun awaitGatewayRequestSlot() {
        while (true) {
            // Do not reserve a future slot before sleeping: otherwise many waiting
            // calls can wake and continue through a new Retry-After cooldown together.
            val waitMs = synchronized(requestPacingLock) {
                val now = System.currentTimeMillis()
                val earliestAllowedAt = maxOf(nextGatewayRequestAtMs, gatewayRateLimitedUntilMs)
                if (now >= earliestAllowedAt) {
                    nextGatewayRequestAtMs = now + 500L
                    0L
                } else {
                    earliestAllowedAt - now
                }
            }
            if (waitMs <= 0L) return
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("Interrupted while pacing TMDB gateway requests").also {
                    it.initCause(e)
                }
            }
        }
    }

    private fun recordGatewayRateLimit(retryAfterHeader: String?) {
        val retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull()?.coerceIn(3L, 30L) ?: 5L
        val blockedUntil = System.currentTimeMillis() + retryAfterSeconds * 1_000L
        synchronized(requestPacingLock) {
            gatewayRateLimitedUntilMs = maxOf(gatewayRateLimitedUntilMs, blockedUntil)
            nextGatewayRequestAtMs = maxOf(nextGatewayRequestAtMs, gatewayRateLimitedUntilMs)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            com.example.utils.NetworkUtils.isInternetAvailable(com.example.di.AppContainer.application)
        } catch (_: Exception) {
            true
        }
    }

    private val authInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val builder = originalRequest.newBuilder()

        // 1. Ensure standard API headers
        builder.header("Accept", "application/json")
        if (originalRequest.header("User-Agent") == null) {
            builder.header("User-Agent", "CineStream-Android/1.0 (Linux; Android)")
        }

        // 2. Attach Firebase ID token if user is signed in (supports both registered users and anonymous guests)
        try {
            val auth = FirebaseAuth.getInstance()
            val currentUser = auth.currentUser
            if (currentUser != null) {
                val tokenResult = Tasks.await(currentUser.getIdToken(false), 5, TimeUnit.SECONDS)
                val token = tokenResult?.token
                if (!token.isNullOrBlank()) {
                    builder.header("Authorization", "Bearer $token")
                }
            }
        } catch (_: Exception) {
            // Silently allow proceed so network can handle or cache can be used; never log or leak token
        }

        val authenticatedRequest = builder.build()
        val response = chain.proceed(authenticatedRequest)
        if (response.code == 429) {
            // The network interceptor has already recorded Retry-After and marked
            // this response no-store. Close it before making one safe GET retry.
            // This application interceptor also covers non-paginated HomeViewModel
            // flows, not only the explicit list paginator.
            response.close()
            chain.proceed(authenticatedRequest)
        } else {
            response
        }
    }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher().apply {
                // Limit simultaneous calls to the one gateway host; paging requests
                // should not fan out without bound when multiple tabs are opened.
                maxRequests = 6
                maxRequestsPerHost = 2
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .cache(Cache(File(com.example.di.AppContainer.application.cacheDir, "http_cache_v2"), cacheSize))
            .addInterceptor(authInterceptor)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val originalUrl = originalRequest.url
                
                val urlWithLanguage = if (originalUrl.queryParameter("language") == null) {
                    val currentLanguage = java.util.Locale.getDefault().language
                    val tmdbLanguage = if (currentLanguage == "ar") "ar-SA" else "en-US"
                    originalUrl.newBuilder()
                        .addQueryParameter("language", tmdbLanguage)
                        .build()
                } else {
                    originalUrl
                }
                
                var request = originalRequest.newBuilder().url(urlWithLanguage).build()
                // Prefer a fresh response online. Offline requests may use stale cached
                // data for up to seven days, but intentionally do NOT use only-if-cached:
                // that directive manufactures HTTP 504 when no entry exists, masking the
                // real IO failure and causing callers to log repeated unsatisfiable-cache errors.
                request = if (!isNetworkAvailable()) {
                    request.newBuilder()
                        .header("Cache-Control", "public, max-stale=" + 60 * 60 * 24 * 7)
                        .build()
                } else {
                    request.newBuilder()
                        .header("Cache-Control", "public, max-age=" + 60)
                        .build()
                }

                // Let OkHttp/cache and the repository's disk cache handle fallback. Do not
                // make a second request inside the interceptor after a failed network call.
                chain.proceed(request)
            }
            .addNetworkInterceptor { chain ->
                awaitGatewayRequestSlot()
                val response = chain.proceed(chain.request())

                // Never cache rate limits or server errors. If a 429 is cached, the
                // next scrolling attempt can replay it for the full cache TTL and make
                // a transient limit look like a permanently broken screen.
                if (response.code == 429) {
                    recordGatewayRateLimit(response.header("Retry-After"))
                    response.newBuilder()
                        .removeHeader("Expires")
                        .removeHeader("Pragma")
                        .header("Cache-Control", "no-store")
                        .build()
                } else if (response.code >= 500) {
                    response.newBuilder()
                        .removeHeader("Expires")
                        .removeHeader("Pragma")
                        .header("Cache-Control", "no-store")
                        .build()
                } else {
                    val cacheControl = response.header("Cache-Control")
                    // Respect Worker directives, and add a short cache only to successful
                    // responses that otherwise provide no cache policy.
                    if (response.isSuccessful && cacheControl.isNullOrBlank()) {
                        response.newBuilder()
                            .removeHeader("Pragma")
                            .header("Cache-Control", "public, max-age=60")
                            .build()
                    } else {
                        response
                    }
                }
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    val tmdbApi: TmdbApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TmdbApiService::class.java)
    }
}