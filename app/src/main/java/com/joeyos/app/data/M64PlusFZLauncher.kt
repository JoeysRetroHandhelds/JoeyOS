package com.joeyos.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.util.Log

private const val TAG = "M64PlusFZLauncher"

object M64PlusFZLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        // game.path is already the ROM path (set by readM64PlusFZ).
        val romPath = if (game.path.isNotEmpty() && java.io.File(game.path).isFile) game.path
                      else RomFinder.findRomByTitle(game.title, "n64")
        if (romPath == null) {
            Log.e(TAG, "launch: no ROM found for '${game.title}'")
            return false
        }

        Log.d(TAG, "launch: title='${game.title}' romPath=$romPath pkg=${game.emulatorPackage}")

        // M64Plus FZ expects a file:// URI (same as Daijisho's am start -d {file.uri}).
        // Temporarily relax StrictMode to allow file:// exposure to another app.
        val fileUri = Uri.parse("file://$romPath")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(
                game.emulatorPackage,
                "paulscode.android.mupen64plusae.SplashActivity"
            )
            data = fileUri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val oldPolicy = StrictMode.getVmPolicy()
        return try {
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "launch: startActivity failed", e)
            false
        } finally {
            StrictMode.setVmPolicy(oldPolicy)
        }
    }
}
