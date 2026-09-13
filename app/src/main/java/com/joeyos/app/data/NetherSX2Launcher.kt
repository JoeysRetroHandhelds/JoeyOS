package com.joeyos.app.data

import android.content.Context
import android.util.Log

private const val TAG = "NetherSX2Launcher"

object NetherSX2Launcher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val romPath = RomFinder.findRomByTitle(game.title, systemFolder = "ps2")
        Log.d(TAG, "launch: title='${game.title}' romPath=$romPath pkg=${game.emulatorPackage}")
        if (romPath == null) return false

        val intent = bootIntent(
            game.emulatorPackage, "xyz.aethersx2.android.EmulationActivity",
            extra = "bootPath", value = romPath,
        )
        return context.tryStartGame(TAG, intent)
    }
}
