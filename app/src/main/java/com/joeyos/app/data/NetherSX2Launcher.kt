package com.joeyos.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "NetherSX2Launcher"

object NetherSX2Launcher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val romPath = RomFinder.findRomByTitle(game.title, systemFolder = "ps2")
        Log.d(TAG, "launch: title='${game.title}' romPath=$romPath pkg=${game.emulatorPackage}")
        if (romPath == null) return false

        val intent = Intent().apply {
            component = ComponentName(
                game.emulatorPackage,
                "xyz.aethersx2.android.EmulationActivity"
            )
            putExtra("bootPath", romPath)
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
