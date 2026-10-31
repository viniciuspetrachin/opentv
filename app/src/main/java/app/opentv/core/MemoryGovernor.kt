/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import coil.Coil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps OpenTV inside the RAM the device can actually spare.
 *
 * A 1 GB Fire Stick is already sharing that gigabyte with Fire OS. The useful ceiling is whatever
 * is free *above* the kernel's low-memory threshold, not a hard "use 1 GB" cap — claiming the
 * whole stick is what gets the process killed. This reads [ActivityManager.MemoryInfo] on a slow
 * foreground tick and on [onTrimMemory], and drops the Coil memory cache as soon as the box is
 * tight. It never enables `largeHeap`.
 */
class MemoryGovernor private constructor(
    private val app: Application,
) : ComponentCallbacks2 {

    enum class Pressure { OK, ELEVATED, CRITICAL }

    data class Snapshot(
        val availBytes: Long,
        val totalBytes: Long,
        val imageCacheBytes: Int,
        val pressure: Pressure,
        val lite: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val _snapshot = MutableStateFlow(read(null))
    val snapshot: StateFlow<Snapshot> = _snapshot

    private var watchJob: Job? = null
    private var attached = false

    fun attach() {
        if (attached) return
        attached = true
        app.registerComponentCallbacks(this)
        refresh()
    }

    /** While the activity is started, re-read free RAM a few times a minute. Cheap (one syscall). */
    fun startForegroundWatch() {
        attach()
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            while (isActive) {
                refresh()
                delay(WATCH_INTERVAL_MILLIS)
            }
        }
    }

    fun stopForegroundWatch() {
        watchJob?.cancel()
        watchJob = null
    }

    fun refresh(forced: Pressure? = null) {
        val next = read(forced)
        val previous = _snapshot.value
        _snapshot.value = next
        if (next.pressure > previous.pressure && next.pressure >= Pressure.ELEVATED) {
            clearImages()
        }
    }

    fun clearImages() {
        runCatching { Coil.imageLoader(app).memoryCache?.clear() }
    }

    private fun read(forced: Pressure?): Snapshot {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val spare = info.availMem - info.threshold - HEADROOM_BYTES
        val detected = when {
            info.lowMemory || spare <= 0L -> Pressure.CRITICAL
            spare < ELEVATED_SPARE_BYTES -> Pressure.ELEVATED
            else -> Pressure.OK
        }
        val pressure = when {
            forced == Pressure.CRITICAL || detected == Pressure.CRITICAL -> Pressure.CRITICAL
            forced == Pressure.ELEVATED || detected == Pressure.ELEVATED -> Pressure.ELEVATED
            else -> Pressure.OK
        }
        val lite = AppSettings.get(app).liteMode.value
        return Snapshot(
            availBytes = info.availMem,
            totalBytes = info.totalMem,
            imageCacheBytes = imageCacheBytes(info, lite),
            pressure = pressure,
            lite = lite,
        )
    }

    override fun onTrimMemory(level: Int) {
        val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        val tight = critical ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (!tight) {
            refresh()
            return
        }
        refresh(if (critical) Pressure.CRITICAL else Pressure.ELEVATED)
        clearImages()
        LoadTrace.mark("onTrimMemory level=$level")
    }

    override fun onLowMemory() {
        refresh(Pressure.CRITICAL)
        clearImages()
        LoadTrace.mark("onLowMemory")
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    companion object {
        private const val MB = 1024 * 1024
        private const val HEADROOM_BYTES = 64L * MB
        private const val ELEVATED_SPARE_BYTES = 48L * MB
        private const val WATCH_INTERVAL_MILLIS = 8_000L
        private const val LOW_RAM_BYTES = (1.5 * 1024 * 1024 * 1024).toLong()

        @Volatile private var instance: MemoryGovernor? = null

        fun get(context: Context): MemoryGovernor =
            instance ?: synchronized(this) {
                instance ?: MemoryGovernor(context.applicationContext as Application).also { instance = it }
            }

        /**
         * First-run default: a Fire TV, or any box with about 1.5 GB or less, starts in LITE.
         * Once the user flips the switch the saved value wins — this is only for a missing pref.
         */
        fun shouldDefaultLite(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            if (info.totalMem in 1..LOW_RAM_BYTES) return true
            val model = Build.MODEL.orEmpty()
            if (model.startsWith("AFT", ignoreCase = true)) return true
            return Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        }

        /** Byte cap for the Coil memory cache, from the app heap and the RAM the OS can spare. */
        fun imageCacheBytes(context: Context, lite: Boolean): Int {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            return imageCacheBytes(info, lite)
        }

        private fun imageCacheBytes(info: ActivityManager.MemoryInfo, lite: Boolean): Int {
            val heap = Runtime.getRuntime().maxMemory()
            val spare = (info.availMem - info.threshold - HEADROOM_BYTES).coerceAtLeast(32L * MB)
            val cap = if (lite) {
                minOf(12L * MB, heap / 16, spare / 8)
            } else {
                minOf(32L * MB, heap / 8, spare / 4)
            }
            return cap.coerceAtLeast(8L * MB).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
}
