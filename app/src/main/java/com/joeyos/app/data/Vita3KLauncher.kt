package com.joeyos.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent

object Vita3KLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val intent = Intent().apply {
            component = ComponentName(
                game.emulatorPackage,
                "org.vita3k.emulator.Emulator"
            )
            // game.path holds the title ID (e.g. "PCSE00120") stored by readVita3K.
            // Send both extras: "launch" (newer builds) and AppStartParameters string-array
            // ("-r TITLEID" argv, older/native builds). Vita3K ignores whichever it doesn't know.
            putExtra("launch", game.path)
            putExtra("AppStartParameters", arrayOf("-r", game.path))
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
