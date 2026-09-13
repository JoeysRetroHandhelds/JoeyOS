package com.joeyos.app.data

import android.content.Context
import android.net.Uri
import java.io.File

private const val TAG = "Aps3eLauncher"

object Aps3eLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val romPath = RomFinder.findRomByTitle(game.title, systemFolder = "ps3") ?: return false

        // aPS3e reads the path itself, so a file:// string in an extra is fine (it isn't the
        // intent's data, so Android's file URI check doesn't apply).
        val intent = bootIntent(
            game.emulatorPackage, "aenu.aps3e.EmulatorActivity",
            extra = "iso_uri", value = Uri.fromFile(File(romPath)).toString(),
            action = "aenu.intent.action.APS3E",
        )
        return context.tryStartGame(TAG, intent)
    }
}
