package com.supernova.networkswitch.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A small persistent log, so a problem seen on the phone can be sent as a text file instead of
 * described. Lines go to logcat as well. Writing happens on one background thread; the file is
 * rotated at [MAX_BYTES] and one older file is kept.
 *
 * Only the app process writes here. The Shizuku/root service runs elsewhere and cannot reach this
 * file; what it reports (sample values, call results, its own status) is logged from this side.
 */
object AppLog {

    private const val TAG = "NetworkSwitch"
    private const val FILE_NAME = "autoswitch.log"
    private const val OLD_FILE_NAME = "autoswitch.1.log"
    private const val MAX_BYTES = 400_000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "applog").apply { isDaemon = true }
    }

    @Volatile
    private var dir: File? = null

    // Only touched on the executor thread.
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        dir = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
    }

    fun i(message: String) = write('I', message, null)

    fun w(message: String, error: Throwable? = null) = write('W', message, error)

    fun e(message: String, error: Throwable? = null) = write('E', message, error)

    /** Everything logged so far, oldest first. Waits for pending writes. */
    fun readAll(): String {
        val d = dir ?: return ""
        return try {
            executor.submit(Callable {
                val parts = listOf(File(d, OLD_FILE_NAME), File(d, FILE_NAME))
                parts.filter { it.exists() }.joinToString("") { it.readText() }
            }).get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            ""
        }
    }

    fun sizeBytes(): Long {
        val d = dir ?: return 0L
        return listOf(File(d, OLD_FILE_NAME), File(d, FILE_NAME)).sumOf { if (it.exists()) it.length() else 0L }
    }

    fun clear() {
        val d = dir ?: return
        executor.execute {
            File(d, FILE_NAME).delete()
            File(d, OLD_FILE_NAME).delete()
        }
    }

    private fun write(level: Char, message: String, error: Throwable?) {
        when (level) {
            'E' -> Log.e(TAG, message, error)
            'W' -> Log.w(TAG, message, error)
            else -> Log.i(TAG, message)
        }
        val d = dir ?: return
        val time = System.currentTimeMillis()
        val text = if (error != null) message + "\n" + Log.getStackTraceString(error).trimEnd() else message
        try {
            executor.execute {
                try {
                    val file = File(d, FILE_NAME)
                    if (file.length() > MAX_BYTES) {
                        val old = File(d, OLD_FILE_NAME)
                        old.delete()
                        file.renameTo(old)
                    }
                    file.appendText("${timeFormat.format(time)} $level $text\n")
                } catch (e: Exception) {
                    // A broken log must never break the app.
                }
            }
        } catch (e: Exception) {
            // Executor rejected the task; ignore.
        }
    }
}
