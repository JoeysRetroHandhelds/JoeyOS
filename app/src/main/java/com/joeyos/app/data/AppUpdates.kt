package com.joeyos.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.joeyos.app.CrashLogger
import com.joeyos.app.UpdateReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater — pulls the newest signed APK from the public GitHub release.
 *
 * Asks the API for the latest release and only offers it when its version is newer than
 * the installed one. Installs go through PackageInstaller, so Android shows its own
 * confirmation and refuses an APK signed with a different key; nothing installs silently.
 */
object AppUpdates {

    private const val REPO = "JoeysRetroHandhelds/JoeyOS"
    private val ASSET_REGEX = Regex("""JoeyOS-v?([\d.]+)\.apk""", RegexOption.IGNORE_CASE)
    private const val PREFS = "app_updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    data class Release(
        val versionName: String,
        val notes: String,
        val assetUrl: String,
        val bytes: Long,
    )

    fun installedVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0"

    /** True if an automatic check is due (throttled so resume doesn't hit the API every time). */
    fun autoCheckDue(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > AUTO_CHECK_INTERVAL_MS
    }

    /** The latest release if it's newer than what's installed, else null (also null on any error). */
    suspend fun newerRelease(context: Context): Release? = withContext(Dispatchers.IO) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        val json = runCatching { get("https://api.github.com/repos/$REPO/releases/latest") }.getOrNull()
            ?: return@withContext null
        runCatching {
            val root = JSONObject(json)
            val assets = root.optJSONArray("assets") ?: return@runCatching null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val version = ASSET_REGEX.find(asset.optString("name"))?.groupValues?.get(1) ?: continue
                if (compareVersions(version, installedVersion(context)) <= 0) return@runCatching null
                return@runCatching Release(
                    versionName = version,
                    notes = root.optString("body").trim(),
                    assetUrl = asset.optString("browser_download_url"),
                    bytes = asset.optLong("size"),
                )
            }
            null
        }.getOrNull()
    }

    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit = {}): File? =
        withContext(Dispatchers.IO) {
            val target = File(context.cacheDir, "JoeyOS-${release.versionName}.apk")
            val partial = File(context.cacheDir, target.name + ".part")
            runCatching {
                partial.delete()
                val connection = open(release.assetUrl, accept = "application/octet-stream")
                try {
                    if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (release.bytes > 0) onProgress((written.toFloat() / release.bytes).coerceIn(0f, 1f))
                            }
                            if (release.bytes > 0 && written != release.bytes) error("expected ${release.bytes} bytes, got $written")
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                target.delete()
                if (!partial.renameTo(target)) error("could not rename the download")
                target
            }.onFailure {
                partial.delete()
                CrashLogger.logNonFatal(context, "update download", it)
            }.getOrNull()
        }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the "Install unknown apps" toggle for JoeyOS. */
    fun requestInstallPermission(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun install(context: Context, apk: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("joeyos", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            session.commit(UpdateReceiver.pendingIntent(context, sessionId).intentSender)
        }
        true
    }.onFailure { CrashLogger.logNonFatal(context, "update install", it) }.getOrDefault(false)

    /** Numeric dotted-version compare: "1.0.13" > "1.0.12", "1.1" > "1.0.99". */
    internal fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "JoeyOS")
            instanceFollowRedirects = true  // release assets redirect to GitHub's storage host
        }

    private fun get(url: String): String? {
        val connection = open(url, accept = "application/vnd.github+json")
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { connection.disconnect() }
    }
}
