package com.joeyos.app.data

import com.joeyos.app.AppLog

import android.content.Context
import android.util.Log

private const val TAG = "ARMSX2Launcher"

object ARMSX2Launcher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val romPath = RomFinder.findRomByTitle(game.title, systemFolder = "ps2")
        Log.d(TAG, "launch: title='${game.title}' romPath=$romPath pkg=${game.emulatorPackage}")
        if (romPath == null) return false

        val base = context.packageManager.getLaunchIntentForPackage(game.emulatorPackage)
        if (base == null) {
            AppLog.e(TAG, "launch: ${game.emulatorPackage} not installed")
            return false
        }
        val intent = viewIntent(context, game.emulatorPackage, romPath, TAG)?.apply {
            component = base.component
            // ARMSX2 wants a MIME type with the data. Set together: setting the type alone
            // would clear the data.
            setDataAndType(data, "application/octet-stream")
            addFlags(FRESH_TASK)
        } ?: return false
        return context.tryStartGame(TAG, intent)
    }
}
