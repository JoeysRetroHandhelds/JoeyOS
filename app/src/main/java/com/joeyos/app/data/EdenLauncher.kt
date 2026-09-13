package com.joeyos.app.data

import android.content.Context
import android.util.Log

private const val TAG = "EdenLauncher"

object EdenLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val romPath = RomFinder.findRomByTitle(game.title, systemFolder = "switch")
        Log.d(TAG, "launch: title='${game.title}' romPath=$romPath pkg=${game.emulatorPackage}")
        if (romPath == null) return false

        val intent = viewIntent(context, game.emulatorPackage, romPath, TAG)?.apply {
            setPackage(game.emulatorPackage)
            addFlags(FRESH_TASK)
        } ?: return false
        return context.tryStartGame(TAG, intent)
    }
}
