package com.joeyos.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "MelonDSLauncher"

object MelonDSLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        Log.d(TAG, "launch: game.path=${game.path} pkg=${game.emulatorPackage}")
        val romPath = RomFinder.resolveRomFromSave(game.path, systemFolder = "nds", romExtensions = setOf("nds", "dsi", "ids"))
        if (romPath == null) {
            Log.d(TAG, "launch: resolveRomFromSave returned null — ROM not found, falling back")
            return false
        }
        Log.d(TAG, "launch: resolved ROM path=$romPath")

        val contentUri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(romPath))
        } catch (e: Exception) {
            Log.e(TAG, "launch: FileProvider failed, falling back to file URI", e)
            Uri.fromFile(File(romPath))
        }
        Log.d(TAG, "launch: uri=$contentUri")

        context.grantUriPermission(game.emulatorPackage, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val intent = Intent("me.magnum.melonds.dev.LAUNCH_ROM").apply {
            component = ComponentName(
                game.emulatorPackage,
                "me.magnum.melonds.ui.emulator.EmulatorActivity"
            )
            putExtra("uri", contentUri.toString())
            addFlags(
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
