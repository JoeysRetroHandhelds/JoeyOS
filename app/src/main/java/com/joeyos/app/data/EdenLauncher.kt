package com.joeyos.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "EdenLauncher"

object EdenLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val romPath = RomFinder.findRomByTitle(game.title, systemFolder = "switch")
        Log.d(TAG, "launch: title='${game.title}' romPath=$romPath pkg=${game.emulatorPackage}")
        if (romPath == null) return false

        val contentUri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(romPath))
        } catch (e: Exception) {
            Log.e(TAG, "launch: FileProvider failed, falling back to file URI", e)
            Uri.fromFile(File(romPath))
        }

        context.grantUriPermission(game.emulatorPackage, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setPackage(game.emulatorPackage)
            data = contentUri
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        return try {
            context.startActivity(intent)
            Log.d(TAG, "launch: startActivity succeeded")
            true
        } catch (e: Exception) {
            Log.e(TAG, "launch: startActivity failed", e)
            false
        }
    }
}
