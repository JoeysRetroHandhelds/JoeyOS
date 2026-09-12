package com.joeyos.app

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exceptions to a plain-text log so users without adb/logcat can send us
 * crash details. The log goes to the app's private files dir and, best-effort, to
 * /sdcard/JoeyOS/crash.log where any file manager can reach it (we already hold
 * MANAGE_EXTERNAL_STORAGE). Newest crash is appended; the file is trimmed to MAX_BYTES.
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val FILE_NAME = "crash.log"
    private const val MAX_BYTES = 256 * 1024

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(appContext, format(appContext, thread, throwable))
            } catch (_: Throwable) {
                // Never let the logger itself mask the original crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Logs a caught, non-fatal exception to the same file. */
    fun logNonFatal(context: Context, where: String, throwable: Throwable) {
        Log.e(TAG, "non-fatal in $where", throwable)
        try {
            write(context.applicationContext,
                format(context.applicationContext, Thread.currentThread(), throwable, "NON-FATAL ($where)"))
        } catch (_: Throwable) {}
    }

    /** A one-line diagnostic note (e.g. an unrecognised controller button). */
    fun logNote(context: Context, message: String) {
        Log.i(TAG, message)
        try {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            write(context.applicationContext, "----- NOTE $time: $message\n")
        } catch (_: Throwable) {}
    }

    /** The newest part of the log, capped so it fits in a share Intent; null if nothing logged. */
    fun readRecent(context: Context, maxChars: Int = 60_000): String? =
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }
            ?.readText()?.takeIf { it.isNotBlank() }?.takeLast(maxChars)

    private fun format(context: Context, thread: Thread, throwable: Throwable, kind: String = "CRASH"): String {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("===== $kind $time =====")
            appendLine("JoeyOS $versionName | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine(stack)
        }
    }

    private fun write(context: Context, entry: String) {
        appendTrimmed(File(context.filesDir, FILE_NAME), entry)
        runCatching {
            val dir = File(Environment.getExternalStorageDirectory(), "JoeyOS")
            if (dir.isDirectory || dir.mkdirs()) appendTrimmed(File(dir, FILE_NAME), entry)
        }
    }

    private fun appendTrimmed(file: File, entry: String) {
        val existing = if (file.exists()) file.readText() else ""
        var combined = existing + entry + "\n"
        if (combined.length > MAX_BYTES) combined = combined.takeLast(MAX_BYTES)
        file.writeText(combined)
    }
}
