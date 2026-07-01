package com.joeyos.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "DolphinLauncher"

object DolphinLauncher {

    fun launch(context: Context, game: RecentGame): Boolean {
        val romPath = resolveRom(game)
        Log.d(TAG, "launch: title='${game.title}' path='${game.path}' romPath=$romPath pkg=${game.emulatorPackage}")
        if (romPath == null) return false

        val contentUri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(romPath))
        } catch (e: Exception) {
            Log.e(TAG, "launch: FileProvider failed", e)
            Uri.fromFile(File(romPath))
        }
        Log.d(TAG, "launch: uri=$contentUri")

        context.grantUriPermission(game.emulatorPackage, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // Dolphin prefers content:// URI via ACTION_VIEW intent data for scoped storage.
        // AutoStartFile accepts a raw file path as a fallback for older builds.
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(
                game.emulatorPackage,
                "org.dolphinemu.dolphinemu.ui.main.MainActivity"
            )
            data = contentUri
            putExtra("AutoStartFile", romPath)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
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
