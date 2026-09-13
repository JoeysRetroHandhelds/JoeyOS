package com.joeyos.app.data

import android.content.Context
import android.util.Log

private const val TAG = "EdenLauncher"

object EdenLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        // The recent entry is Eden's save folder, named by the game's title id: find the file by
        // that id first, then by title.
        val id = java.io.File(game.path).name.takeIf { it.matches(Regex("[0-9a-fA-F]{16}")) }
        val romPath = id?.let { RomFinder.findRomById(it, systemFolder = "switch") }
            ?: RomFinder.findRomByTitle(game.title, systemFolder = "switch")
        Log.d(TAG, "launch: title='${game.title}' romPath=$romPath pkg=${game.emulatorPackage}")
        if (romPath == null) return false

        val intent = viewIntent(context, game.emulatorPackage, romPath, TAG)?.apply {
            setPackage(game.emulatorPackage)
            addFlags(FRESH_TASK)
        } ?: return false
        return context.tryStartGame(TAG, intent)
    }
}
