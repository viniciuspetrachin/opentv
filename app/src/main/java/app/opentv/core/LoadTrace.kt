/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Breadcrumbs for the channel-list load. Logcat alone loses the last lines when the system kills
 * the process; each line is also appended to `files/load-trace.log` and flushed.
 *
 * Messages are scrubbed of URLs. Playlist addresses carry the account password in the query
 * string, and this file is the thing we pull off the device after a kill.
 */
object LoadTrace {
    const val TAG = "OpenTV.Load"

    private const val MAX_BYTES = 512 * 1024
    private val url = Regex("""https?://\S+""")
    private val lock = Any()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var file: File? = null

    fun install(context: Context) {
        file = File(context.applicationContext.filesDir, "load-trace.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            mark("uncaught thread=${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        mark("process start pid=${Process.myPid()}")
    }

    fun mark(message: String, error: Throwable? = null) {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        val stamp = synchronized(lock) { clock.format(Date()) }
        val line = "$stamp heap=$usedMb/${maxMb}MB ${url.replace(message, "[url]")}"
        if (error == null) Log.i(TAG, line) else Log.e(TAG, line, error)
        val target = file ?: return
        synchronized(lock) {
            try {
                if (target.exists() && target.length() > MAX_BYTES) {
                    val backup = File(target.parentFile, "load-trace.1.log")
                    if (backup.exists()) backup.delete()
                    target.renameTo(backup)
                }
                FileOutputStream(target, true).use { out ->
                    out.write(line.toByteArray())
                    out.write('\n'.code)
                    if (error != null) {
                        val sw = StringWriter()
                        error.printStackTrace(PrintWriter(sw))
                        val stack = sw.toString().lineSequence().take(40).joinToString("\n")
                        out.write(stack.toByteArray())
                        out.write('\n'.code)
                    }
                    out.fd.sync()
                }
            } catch (_: Throwable) {
                // Tracing must not become the crash.
            }
        }
    }
}
