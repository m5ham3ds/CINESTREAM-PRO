package com.example

import android.app.Application
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import java.util.concurrent.TimeUnit
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.Cache
import java.io.File

class MyApplication : Application(), ImageLoaderFactory {
    companion object {
        lateinit var appContext: android.content.Context
    }

    
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // CrashActivity runs in a separate lightweight process so it can remain visible
        // after the original app process terminates. Do not start Firebase, WorkManager,
        // workers, or a second crash-handler loop in that reporter process.
        val processName = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.app.Application.getProcessName()
            } else {
                File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
            }
        }.getOrDefault(packageName)
        if (processName.endsWith(":crash_report")) return

        // Cancel any legacy periodic aggressive cache wipes to preserve user covers and offline data
        try {
            WorkManager.getInstance(this).cancelUniqueWork("CacheCleanupWork")
        } catch (e: Exception) {
            android.util.Log.w("MyApplication", "WorkManager not available: ${e.message}")
        }

        // The official app/google-services.json is matched to the canonical applicationId.
        // Google Services initializes the default FirebaseApp before Application.onCreate;
        // this initializer verifies it and retains a defensive fallback for unusual runtimes.
        // Run this before repositories/services first request FirebaseAuth/Firestore.
        try {
            if (!com.example.utils.FirebaseInitializer.initialize(this)) {
                android.util.Log.e("MyApplication", "Firebase initialization failed; Firebase-backed features may be unavailable")
            }
        } catch (e: Exception) {
            android.util.Log.e("MyApplication", "Firebase initialization failed", e)
        }

        // Initialize all notification channels centrally at application startup
        com.example.data.notification.NotificationChannels.createAllChannels(this)
        
        val isRobolectric = android.os.Build.FINGERPRINT == "robolectric" || android.os.Build.HARDWARE == "robolectric"
        if (!isRobolectric) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                // Capture a durable, non-empty report before starting another Activity or
                // terminating the process. The report stays in app-private storage and is
                // never uploaded automatically.
                val stackTrace = buildString {
                    appendLine("CINESTREAM PRO uncaught exception")
                    appendLine("Time (epoch millis): ${System.currentTimeMillis()}")
                    appendLine("Thread: ${thread.name} (id=${thread.id})")
                    appendLine("Process: ${android.os.Process.myPid()}")
                    appendLine()
                    append(android.util.Log.getStackTraceString(throwable).ifBlank {
                        throwable.stackTraceToString()
                    })
                    var cause = throwable.cause
                    var depth = 0
                    while (cause != null && cause !== throwable && depth < 8) {
                        appendLine()
                        appendLine("Caused by (${depth + 1}): ${cause.javaClass.name}: ${cause.message}")
                        cause.stackTrace.take(40).forEach { appendLine("    at $it") }
                        cause = cause.cause
                        depth++
                    }
                }.takeLast(80_000)

                try {
                    File(filesDir, "last_crash.txt").writeText(stackTrace)
                    getSharedPreferences("crash_report", MODE_PRIVATE).edit()
                        .putString("last_stack_trace", stackTrace)
                        .putLong("last_crash_at", System.currentTimeMillis())
                        .commit()
                } catch (persistError: Throwable) {
                    android.util.Log.e("MyApplication", "Could not persist crash details", persistError)
                }

                try {
                    val intent = android.content.Intent(this, com.example.ui.screens.crash.CrashActivity::class.java).apply {
                        putExtra("EXTRA_STACK_TRACE", stackTrace.ifBlank {
                            "Uncaught ${throwable.javaClass.name}: ${throwable.message ?: "(no message)"}\n" +
                                throwable.stackTrace.joinToString("\n") { "at $it" }
                        })
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                } catch (launchError: Throwable) {
                    android.util.Log.e("MyApplication", "Could not open crash details screen", launchError)
                    defaultHandler?.uncaughtException(thread, throwable)
                }
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(1)
            }
        }

        com.example.di.AppContainer.application = this
        
        // Phase 05X: Initialize DownloadedPostersManager cache asynchronously
        com.example.utils.DownloadedPostersManager.initAsync(this)
        
        // Phase 05M: Start Realtime Extension Sync Listener
        try {
            com.example.extension.managed.repository.ManagedExtensionRealtimeSyncManager.getInstance().startListening()
        } catch (e: Exception) {
            android.util.Log.w("MyApplication", "ManagedExtensionRealtimeSyncManager initialization notice: ${e.message}")
        }
        
        val cloudName = com.example.BuildConfig.CLOUDINARY_CLOUD_NAME
        val cloudNameIsConfigured = cloudName.isNotBlank() &&
            !cloudName.contains("YOUR_", ignoreCase = true) &&
            !cloudName.contains("mock", ignoreCase = true) &&
            !cloudName.contains("dummy", ignoreCase = true) &&
            !cloudName.contains("example", ignoreCase = true) &&
            cloudName != "UNCONFIGURED"
        if (cloudNameIsConfigured) {
            val config = mapOf("cloud_name" to cloudName)
            try {
                com.cloudinary.android.MediaManager.init(this, config)
            } catch (e: Exception) {
                // Ignore if already initialized; uploads independently validate the configuration.
            }
        }

    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.filesDir.resolve("image_cache"))
                    .maxSizeBytes(800L * 1024 * 1024) // 800 MB safe persistent cache
                    .build()
            }
            .transitionFactory(com.example.utils.SelectiveCrossfadeTransitionFactory(100))
            .respectCacheHeaders(false) // Ignore server headers that say don't cache
            .build()
    }
}