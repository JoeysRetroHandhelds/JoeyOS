package com.joeyos.app.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "ARMSX2Launcher"

object ARMSX2Launcher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val romPath = RomFinder.findRomByTitle(game.title, systemFolder = "ps2")
        Log.d(TAG, "launch: title='${game.title}' romPath=$romPath pkg=${game.emulatorPackage}")
        if (romPath == null) return false

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", File(romPath)
        )
        val base = context.packageManager.getLaunchIntentForPackage(game.emulatorPackage)
        if (base == null) {
            Log.e(TAG, "launch: ${game.emulatorPackage} not installed")
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = base.component
            setDataAndType(uri, "application/octet-stream")
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
