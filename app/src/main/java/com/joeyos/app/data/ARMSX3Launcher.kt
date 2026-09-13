package com.joeyos.app.data

import com.joeyos.app.AppLog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "ARMSX3Launcher"

/**
 * ARMSX3 (PS3, com.armsx3). Its launcher activity takes ACTION_VIEW with the game as the data
 * URI (content:// or file://). The path we hold is the exact URI ARMSX3 wrote to its own
 * recent_games.json, so it's handed back unchanged where possible:
 *  - content:// — ARMSX3's own granted document URI; it can read it, we just pass it along.
 *  - a file — shared through our FileProvider with a read grant.
 *  - a folder (folder-based PS3 games) — a FileProvider can't share a folder, so it goes as a
 *    file:// URI, which ARMSX3 reduces to a path (it holds all-files access).
 * With no usable path (a game found only through its save folder) ARMSX3 just opens.
 */
object ARMSX3Launcher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val base = context.packageManager.getLaunchIntentForPackage(game.emulatorPackage)
        if (base == null) {
            AppLog.e(TAG, "launch: ${game.emulatorPackage} not installed")
            return false
        }
        val path = game.path
        val file = when {
            path.startsWith("file://") -> Uri.parse(path).path?.let(::File)
            path.startsWith("/")       -> File(path)
            else                       -> null
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = base.component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        Log.d(TAG, "launch: title='${game.title}' path=$path")
        return try {
            when {
                path.startsWith("content://") -> {
                    intent.data = Uri.parse(path)
                    context.startGame(intent)
                }
                file != null && file.isFile -> {
                    intent.setDataAndType(
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
                        "application/octet-stream")
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startGame(intent)
                }
                file != null && file.isDirectory -> {
                    // Android refuses to send a file:// URI to another app by default (it throws
                    // FileUriExposedException). A folder can't go through a FileProvider, and
                    // ARMSX3 reads the raw path itself, so allow it for this one start.
                    intent.data = Uri.fromFile(file)
                    val policy = StrictMode.getVmPolicy()
                    try {
                        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
                        context.startGame(intent)
                    } finally {
                        StrictMode.setVmPolicy(policy)
                    }
                }
                else -> context.startGame(base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "launch: startActivity failed", e)
            false
        }
    }
}
