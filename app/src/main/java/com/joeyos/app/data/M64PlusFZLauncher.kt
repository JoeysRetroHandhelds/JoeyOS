package com.joeyos.app.data

import com.joeyos.app.AppLog

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File

private const val TAG = "M64PlusFZLauncher"

object M64PlusFZLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        // game.path is already the ROM path (set by readM64PlusFZ).
        val romPath = if (game.path.isNotEmpty() && File(game.path).isFile) game.path
                      else RomFinder.findRomByTitle(game.title, "n64")
        if (romPath == null) {
            AppLog.e(TAG, "launch: no ROM found for '${game.title}'")
            return false
        }

        Log.d(TAG, "launch: title='${game.title}' romPath=$romPath pkg=${game.emulatorPackage}")

        // M64Plus FZ expects a file:// URI (same as Daijisho's am start -d {file.uri}).
        // Uri.fromFile rather than gluing "file://" on, so spaces, '#' and '?' in a
        // filename are escaped instead of cutting the path short.
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(
                game.emulatorPackage,
                "paulscode.android.mupen64plusae.SplashActivity"
            )
            data = Uri.fromFile(File(romPath))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return context.tryStartGame(TAG, intent, allowFileUri = true)
    }
}
