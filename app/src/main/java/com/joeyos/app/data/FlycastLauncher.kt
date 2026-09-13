package com.joeyos.app.data

import com.joeyos.app.AppLog

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File

private const val TAG = "FlycastLauncher"

/**
 * Flycast standalone (com.flycast.emulator). Its launcher activity (the MainActivity alias of
 * NativeGLActivity) takes ACTION_VIEW with the game as the data URI and passes it to the core as
 * a string. Upstream only declares file:// in its filters, so:
 *  - content:// (a game Flycast itself opened through Android's file picker) goes as-is;
 *  - a file path goes as file://. Android refuses to hand a file:// URI to another app by default
 *    (FileUriExposedException), and Flycast reads the raw path itself with its own storage
 *    access, so that check is relaxed for this one start.
 * With no path at all Flycast just opens.
 */
object FlycastLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val pkg = game.emulatorPackage
        val base = context.packageManager.getLaunchIntentForPackage(pkg)
        if (base == null) {
            AppLog.e(TAG, "launch: $pkg not installed")
            return false
        }
        val path = game.path.ifBlank {
            RomFinder.findRomByTitle(game.title, systemFolder = "dc").orEmpty()
        }
        Log.d(TAG, "launch: title='${game.title}' path=$path")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(pkg, "com.flycast.emulator.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val file = File(path.removePrefix("file://"))
        return when {
            path.startsWith("content://") -> {
                intent.data = Uri.parse(path)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.tryStartGame(TAG, intent)
            }
            path.isNotBlank() && file.exists() -> {
                intent.data = Uri.fromFile(file)
                context.tryStartGame(TAG, intent, allowFileUri = true)
            }
            else -> context.tryStartGame(TAG, base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
