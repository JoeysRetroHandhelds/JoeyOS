package com.joeyos.app.data

import android.content.ComponentName
import android.content.Context
import android.util.Log

private const val TAG = "CemuLauncher"

object CemuLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val romPath = RomFinder.findRomByTitle(game.title, systemFolder = "wiiu")
        Log.d(TAG, "launch: title='${game.title}' romPath=$romPath pkg=${game.emulatorPackage}")
        if (romPath == null) return false

        val intent = viewIntent(context, game.emulatorPackage, romPath, TAG)?.apply {
            component = ComponentName(
                game.emulatorPackage,
                "info.cemu.cemu.emulation.EmulationActivity"
            )
            addFlags(FRESH_TASK)
        } ?: return false
        return context.tryStartGame(TAG, intent)
    }
}
