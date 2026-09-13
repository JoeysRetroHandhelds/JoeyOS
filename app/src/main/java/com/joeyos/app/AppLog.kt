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
import java.util.concurrent.Executors

/**
 * The one app log: what went wrong and what JoeyOS did, in a file a user can share.
 *
 * Everything still goes to logcat as before. On top of that, info, warnings and errors are
 * appended to `joeyos.log` in the app's files dir and mirrored to /sdcard/JoeyOS/joeyos.log (we
 * hold all-files access), so a user without a PC can send it with Settings > Share log.
 * Debug lines stay logcat-only, which keeps the file readable.
 *
 * Levels: [i] for events worth a line (a launch, a tool run, an update check), [w] for something
 * that failed but the app carried on, [e] for errors, with the stack trace when there is one.
 * Crashes are written here too ([install]), synchronously, so they survive the process dying.
 *
 * File writes happen on one background thread in order, so logging never blocks the UI. The file
 * rolls: past [MAX_BYTES] the oldest part is dropped. Never log API keys or passwords.
 */
object AppLog {

    private const val FILE_NAME = "joeyos.log"
    private const val MAX_BYTES = 512 * 1024
    private const val KEEP_BYTES = 384 * 1024

    @Volatile private var appContext: Context? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "AppLog").apply { isDaemon = true } }
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Call once at startup: starts the file log and records crashes into it. */
    fun install(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        enqueue("\n===== JoeyOS $version started ${stamp()} | ${Build.MANUFACTURER} ${Build.MODEL} | " +
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) =====")
        logLastExit(context)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Synchronous: the process is about to die, the background writer may not get to run.
            runCatching { writeNow("${stamp()} CRASH on thread ${thread.name}\n${stackOf(throwable)}") }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Why Android ended JoeyOS's last run. A crash in our code is logged by the handler above, but
     * being killed for memory or a native crash (a web view, say) leaves no trace otherwise.
     */
    private fun logLastExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return
            val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return
            val reason = when (last.reason) {
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Android freed memory (low memory)"
                android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                android.app.ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by the system (signal ${last.status})"
                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "closed itself"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user or an update"
                android.app.ApplicationExitInfo.REASON_OTHER -> "other"
                android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
                android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too many resources"
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
                else -> "reason ${last.reason}"
            }
            val mb = last.pss / 1024
            enqueue("${stamp(last.timestamp)} I Exit: Last run ended: $reason" +
                (last.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                (if (mb > 0) ", using $mb MB" else "") +
                ", importance ${last.importance}")
        }
    }

    /** Logcat only — step-by-step detail too noisy for the shareable file. */
    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        enqueue("${stamp()} I $tag: $message")
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        enqueue("${stamp()} W $tag: $message" + (throwable?.let { "\n" + stackOf(it) } ?: ""))
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        enqueue("${stamp()} E $tag: $message" + (throwable?.let { "\n" + stackOf(it) } ?: ""))
    }

    /** The newest part of the log, capped so it fits in a share Intent; null if nothing logged. */
    fun readRecent(context: Context, maxChars: Int = 90_000): String? {
        // Let queued lines land first, so what's shared includes what just happened.
        runCatching { writer.submit {}.get() }
        return File(context.filesDir, FILE_NAME).takeIf { it.exists() }
            ?.readText()?.takeIf { it.isNotBlank() }?.takeLast(maxChars)
    }

    private fun stamp(at: Long = System.currentTimeMillis()): String = synchronized(timeFormat) { timeFormat.format(Date(at)) }

    private fun stackOf(t: Throwable): String =
        StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().trimEnd()

    private fun enqueue(line: String) {
        if (appContext == null) return
        runCatching { writer.execute { runCatching { writeNow(line) } } }
    }

    private fun writeNow(line: String) {
        val context = appContext ?: return
        append(File(context.filesDir, FILE_NAME), line)
        runCatching {
            val dir = File(Environment.getExternalStorageDirectory(), "JoeyOS")
            if (dir.isDirectory || dir.mkdirs()) append(File(dir, FILE_NAME), line)
        }
    }

    private fun append(file: File, line: String) {
        file.appendText(line + "\n")
        if (file.length() > MAX_BYTES) {
            // Roll: keep the newest part, cut at a line break.
            val text = file.readText()
            val tail = text.takeLast(KEEP_BYTES)
            file.writeText(tail.substring(tail.indexOf('\n') + 1))
        }
    }
}
