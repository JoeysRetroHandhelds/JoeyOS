package com.joeyos.app.data

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

private const val TAG = "PpssppLauncher"

object PpssppLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val romPath = resolveRomPath(game)
        if (romPath == null) {
            Log.e(TAG, "launch: could not resolve ROM path for '${game.title}' from '${game.path}'")
            return false
        }

        val uri = RomFinder.pathToGrantableUri(context, romPath)
        Log.d(TAG, "launch: title='${game.title}' romPath=$romPath uri=$uri pkg=${game.emulatorPackage}")

        // Activity class follows the package name convention for both free and Gold builds.
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(game.emulatorPackage, "${game.emulatorPackage}.PpssppActivity")
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "launch: startActivity failed", e)
            false
        }
    }

    private fun resolveRomPath(game: RecentGame): String? {
        val path = game.path
        if (path.startsWith("content://") || path.startsWith("file://")) return path
        val file = File(path)
        if (file.isFile) return path
        if (file.isDirectory) {
            val rom = RomFinder.findRomByTitle(game.title, "psp")
                ?: RomFinder.findRomByTitle(game.title, "PSP")
            Log.d(TAG, "resolveRomPath: savedata dir, found ROM=$rom for '${game.title}'")
            return rom
        }
        return RomFinder.findRomByTitle(game.title, "psp")
            ?: RomFinder.findRomByTitle(game.title, "PSP")
    }
}
