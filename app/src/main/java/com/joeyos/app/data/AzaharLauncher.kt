package com.joeyos.app.data

import android.content.Context
import android.util.Log

private const val TAG = "AzaharLauncher"

object AzaharLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        // "3ds" also searches the console's other folder names ("n3ds", "nintendo3ds").
        val romPath = RomFinder.findRomByTitle(game.title, systemFolder = "3ds")
        Log.d(TAG, "launch: title='${game.title}' romPath=$romPath pkg=${game.emulatorPackage}")
        if (romPath == null) return false

        val intent = viewIntent(context, game.emulatorPackage, romPath, TAG)?.apply {
            setPackage(game.emulatorPackage)
            addFlags(FRESH_TASK)
        } ?: return false
        return context.tryStartGame(TAG, intent)
    }
}
