package com.skyautoplayer.application

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import com.skyautoplayer.accessibility.AndroidGestureSink
import com.skyautoplayer.calibration.CoordinateTransformer
import com.skyautoplayer.calibration.DisplaySnapshot
import com.skyautoplayer.calibration.Insets
import com.skyautoplayer.domain.playback.PlaybackCommand
import com.skyautoplayer.playback.PlaybackEngine
import com.skyautoplayer.storage.FileSongStore
import com.skyautoplayer.storage.SharedPreferencesCalibrationStore
import com.skyautoplayer.playback.PlaybackForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SkyAutoPlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}

/** Process-wide composition root for playback and accessibility gesture dispatch. */
object PlaybackRuntime {
    @Volatile private var initialized = false
    private var serviceScope: CoroutineScope? = null

    lateinit var engine: PlaybackEngine
        private set
    lateinit var playback: PlaybackUseCases
        private set
    lateinit var songs: FileSongStore
        private set

    @Synchronized
    fun initialize(context: Context, scope: CoroutineScope? = null) {
        if (initialized) return
        val appContext = context.applicationContext
        val store = SharedPreferencesCalibrationStore(appContext)
        songs = FileSongStore(appContext)
        val sink = AndroidGestureSink {
            val windowManager = appContext.getSystemService(WindowManager::class.java)
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val profile = store.load("display-${display.displayId}") ?: return@AndroidGestureSink emptyList()
            CoordinateTransformer().resolve(profile, displaySnapshot(windowManager, profile.systemBarInsets))
        }
        engine = PlaybackEngine(sink, scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default))
        playback = PlaybackUseCases(engine)
        initialized = true
    }

    fun accessibilityDisconnected() {
        if (initialized) engine.send(PlaybackCommand.ServiceDisconnected)
    }

    fun startForegroundService(context: Context) {
        synchronized(this) {
            if (!initialized) {
                serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                initialize(context, serviceScope)
            }
        }
        val intent = Intent(context, PlaybackForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    fun serviceDestroyed() {
        serviceScope?.coroutineContext?.get(kotlinx.coroutines.Job)?.cancel()
        serviceScope = null
    }

    private fun displaySnapshot(windowManager: WindowManager, fallbackInsets: Insets): DisplaySnapshot {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val platformInsets = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            Insets(platformInsets.left, platformInsets.top, platformInsets.right, platformInsets.bottom)
        } else {
            fallbackInsets
        }
        val bounds = Rect(bars.left, bars.top, metrics.widthPixels - bars.right, metrics.heightPixels - bars.bottom)
        return DisplaySnapshot(
            displayId = display.displayId,
            orientation = display.rotation,
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            contentBounds = bounds,
            safeInsets = bars,
            systemBarInsets = bars,
            densityDpi = metrics.densityDpi
        )
    }
}
