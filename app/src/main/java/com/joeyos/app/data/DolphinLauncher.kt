package com.joeyos.app.data

import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "DolphinLauncher"

object DolphinLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val romPath = resolveRom(game)
        Log.d(TAG, "launch: title='${game.title}' path='${game.path}' romPath=$romPath pkg=${game.emulatorPackage}")
        if (romPath == null) return false

        // Dolphin prefers content:// URI via ACTION_VIEW intent data for scoped storage.
        // AutoStartFile accepts a raw file path as a fallback for older builds.
        val intent = viewIntent(context, game.emulatorPackage, romPath, TAG)?.apply {
            component = ComponentName(
                game.emulatorPackage,
                "org.dolphinemu.dolphinemu.ui.main.MainActivity"
            )
            putExtra("AutoStartFile", romPath)
            addFlags(FRESH_TASK)
        } ?: return false
        return context.tryStartGame(TAG, intent)
    }

    private fun resolveRom(game: RecentGame): String? {
        val path = game.path
        val title = game.title

        // Wii save directory — definitely Wii
        if (path.contains("Wii/title/")) {
            return RomFinder.findRomByTitle(title, "wii")
        }

        // GCI file under GC/ — definitely GameCube
        if (path.contains("/GC/") || File(path).extension.lowercase() == "gci") {
            return RomFinder.findRomByTitle(title, "gc")
        }

        // Save state from StateSaves/ — could be either; try gc first then wii
        return RomFinder.findRomByTitle(title, "gc")
            ?: RomFinder.findRomByTitle(title, "wii")
    }
}
