package com.joeyos.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.joeyos.app.AppLog
import com.joeyos.app.UpdateStatusActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * In-app updater — pulls the newest signed APK from the public GitHub release.
 *
 * Asks the API for the latest release and only offers it when its version is newer than
 * the installed one. Installs go through PackageInstaller, so Android shows its own
 * confirmation and refuses an APK signed with a different key; nothing installs silently.
 */
object AppUpdates {

    private const val TAG = "Updater"

    private const val REPO = "JoeysRetroHandhelds/JoeyOS"
    private val ASSET_REGEX = Regex("""JoeyOS-v?([\d.]+)\.apk""", RegexOption.IGNORE_CASE)
    private const val PREFS = "app_updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIPPED = "skipped_version"
    // Hourly on resume (GitHub allows 60 unauthenticated checks an hour), plus once every time
    // JoeyOS starts fresh. 6 hours was too long: a release could sit unseen most of a day.
    private const val AUTO_CHECK_INTERVAL_MS = 60 * 60 * 1000L
    @Volatile private var checkedThisRun = false

    data class Release(
        val versionName: String,
        val notes: String,
        val assetUrl: String,
        val bytes: Long,
    )

    fun installedVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0"

    /**
     * The version the user chose Later for. The automatic check doesn't offer it again (a newer
     * release still is); Check for updates in Tools always does.
     */
    fun skippedVersion(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SKIPPED, null)

    fun skipVersion(context: Context, version: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SKIPPED, version).apply()

    /** True if an automatic check is due (throttled so resume doesn't hit the API every time). */
    fun autoCheckDue(context: Context): Boolean {
        if (!checkedThisRun) return true
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > AUTO_CHECK_INTERVAL_MS
    }

    /** The latest release if it's newer than what's installed, else null (also null on any error). */
    suspend fun newerRelease(context: Context): Release? = withContext(Dispatchers.IO) {
        val json = runCatching { get("https://api.github.com/repos/$REPO/releases/latest") }
            .onFailure { AppLog.w(TAG, "Update check failed: ${it.message}") }
            .getOrNull()
            ?: return@withContext null.also { AppLog.w(TAG, "Update check: no answer from GitHub") }
        // Only a check that reached GitHub counts toward the wait; a failed one retries next time.
        checkedThisRun = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        runCatching {
            val root = JSONObject(json)
            val assets = root.optJSONArray("assets") ?: return@runCatching null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val version = ASSET_REGEX.find(asset.optString("name"))?.groupValues?.get(1) ?: continue
                if (compareVersions(version, installedVersion(context)) <= 0) {
                    AppLog.i(TAG, "Update check: on the latest (installed ${installedVersion(context)}, latest $version)")
                    return@runCatching null
                }
                AppLog.i(TAG, "Update check: $version available (installed ${installedVersion(context)})")
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
                // Release assets redirect to GitHub's storage host; Http follows it.
                val written = Http.download(
                    release.assetUrl, partial, accept = "application/octet-stream", userAgent = USER_AGENT,
                    connectMs = 15_000, readMs = 30_000,
                ) { soFar ->
                    if (release.bytes > 0) onProgress((soFar.toFloat() / release.bytes).coerceIn(0f, 1f))
                }
                if (release.bytes > 0 && written != release.bytes) error("expected ${release.bytes} bytes, got $written")
                target.delete()
                if (!partial.renameTo(target)) error("could not rename the download")
                target
            }.onFailure {
                partial.delete()
                AppLog.e(TAG, "Update download failed: ${release.versionName}", it)
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
            session.commit(UpdateStatusActivity.pendingIntent(context, sessionId).intentSender)
        }
        true
    }.onFailure { AppLog.e(TAG, "Update install failed to start", it) }.getOrDefault(false)

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

    // GitHub's API refuses a request without a User-Agent.
    private const val USER_AGENT = "JoeyOS"

    private fun get(url: String): String? =
        Http.get(url, accept = "application/vnd.github+json", userAgent = USER_AGENT, connectMs = 15_000, readMs = 30_000)
            .takeIf { it.ok }?.text
}
