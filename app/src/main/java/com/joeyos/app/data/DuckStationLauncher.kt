package com.joeyos.app.data

import android.content.Context
import android.util.Log

private const val TAG = "DuckStationLauncher"

object DuckStationLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        // "psx" also searches the console's other folder names ("ps1", "playstation", "ps").
        val romPath = RomFinder.findRomByTitle(game.title, systemFolder = "psx")
        Log.d(TAG, "launch: title='${game.title}' romPath=$romPath pkg=${game.emulatorPackage}")
        if (romPath == null) return false

        val intent = bootIntent(
            game.emulatorPackage, "${game.emulatorPackage}.EmulationActivity",
            extra = "bootPath", value = romPath,
        )
        return context.tryStartGame(TAG, intent)
    }
}
