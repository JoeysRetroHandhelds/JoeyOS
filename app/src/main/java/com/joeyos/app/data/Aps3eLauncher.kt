package com.joeyos.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

object Aps3eLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val romPath = RomFinder.findRomByTitle(game.title, systemFolder = "ps3") ?: return false

        val intent = Intent("aenu.intent.action.APS3E").apply {
            component = ComponentName(
                game.emulatorPackage,
                "aenu.aps3e.EmulatorActivity"
            )
            putExtra("iso_uri", Uri.fromFile(File(romPath)).toString())
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
