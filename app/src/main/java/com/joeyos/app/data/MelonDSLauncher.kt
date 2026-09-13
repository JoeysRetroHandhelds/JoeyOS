package com.joeyos.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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

        // melonDS takes the ROM as a URI string in an extra rather than as the intent's data,
        // so a file:// fallback still gets through if the FileProvider can't share it.
        val uri = grantableRomUri(context, game.emulatorPackage, romPath, TAG)
            ?: Uri.fromFile(File(romPath))

        val intent = Intent("me.magnum.melonds.dev.LAUNCH_ROM").apply {
            component = ComponentName(
                game.emulatorPackage,
                "me.magnum.melonds.ui.emulator.EmulatorActivity"
            )
            putExtra("uri", uri.toString())
            addFlags(FRESH_TASK)
        }
        return context.tryStartGame(TAG, intent)
    }
}
