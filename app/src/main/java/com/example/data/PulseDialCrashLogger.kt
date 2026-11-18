package com.example.data

import android.content.Context
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight crash & stability diagnostic logger.
 * Captures uncaught exceptions synchronously before delegating to the OS default handler
 * so crashes on user devices can be inspected and cleared cleanly.
 */
object PulseDialCrashLogger {

    private const val TAG = "PulseDialCrashLogger"
    private const val PREFS_NAME = "pulsedial_crash_log"
    private const val KEY_LAST_CRASH_TIME = "last_crash_time"
    private const val KEY_LAST_CRASH_TRACE = "last_crash_trace"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    recordCrash(appContext, thread.name, throwable)
                }
                previousHandler?.uncaughtException(thread, throwable)
            }
            installed = true
        }
    }

    fun recordCrash(context: Context, threadName: String, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val trace = "Thread: $threadName\n${sw.toString()}".take(4000)
        val now = System.currentTimeMillis()
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CRASH_TIME, now)
            .putString(KEY_LAST_CRASH_TRACE, trace)
            .commit()
        Log.e(TAG, "Recorded uncaught crash on $threadName", throwable)
    }

    fun logNonFatal(context: Context, tag: String, throwable: Throwable) {
        Log.w(TAG, "Non-fatal [$tag]: ${throwable.message}", throwable)
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val trace = "NonFatal[$tag]: ${sw.toString()}".take(4000)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
            .putString(KEY_LAST_CRASH_TRACE, trace)
            .apply()
    }

    fun getLastCrashSummary(context: Context): String? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val trace = prefs.getString(KEY_LAST_CRASH_TRACE, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val timeMs = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
        val formattedTime = if (timeMs > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))
        } else {
            "Unknown time"
        }
        return "Captured: $formattedTime\n$trace"
    }

    fun clearLastCrash(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
