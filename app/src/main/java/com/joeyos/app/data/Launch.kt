package com.joeyos.app.data

import com.joeyos.app.AppLog

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.util.Log

/*
 * The pieces every emulator launcher shares. Each launcher still builds its own intent (the
 * action, activity, extras and flags are what each emulator happens to accept); these just
 * save repeating the plumbing around it.
 */

/**
 * Starts [intent] as a game (see [startGame]) and reports whether it went. A failure is
 * logged under the launcher's [tag] and returns false, so the caller can fall back to just
 * opening the emulator.
 *
 * [allowFileUri] is for emulators that only take a file:// URI: Android refuses to hand one to
 * another app by default (FileUriExposedException), so that check is relaxed for this start.
 */
fun Context.tryStartGame(tag: String, intent: Intent, allowFileUri: Boolean = false): Boolean = try {
    if (allowFileUri) withFileUrisAllowed { startGame(intent) } else startGame(intent)
    Log.d(tag, "launch: startActivity succeeded")
    true
} catch (e: Exception) {
    AppLog.e(tag, "launch: startActivity failed", e)
    false
}

/**
 * Runs [block] with Android's file:// URI exposure check turned off, then puts the previous
 * policy back. Only for emulators that read the raw path themselves with their own storage
 * access; everything else gets a content:// URI from our FileProvider instead.
 */
fun <T> withFileUrisAllowed(block: () -> T): T {
    val policy = StrictMode.getVmPolicy()
    return try {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        block()
    } finally {
        StrictMode.setVmPolicy(policy)
    }
}

/**
 * A content:// URI for [romPath] that [pkg] is allowed to read, or null if it can't be made
 * (logged under [tag]). Emulators without all-files access can't open our paths directly, so
 * the ROM goes through our FileProvider with a read grant.
 */
fun grantableRomUri(context: Context, pkg: String, romPath: String, tag: String): Uri? {
    val uri = try {
        RomFinder.pathToGrantableUri(context, romPath)
    } catch (e: Exception) {
        AppLog.e(tag, "launch: FileProvider failed for $romPath", e)
        return null
    }
    Log.d(tag, "launch: uri=$uri")
    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return uri
}

/**
 * An ACTION_VIEW intent carrying [romPath] as a readable content:// URI, with the read grant
 * and NEW_TASK set. The caller points it at the emulator (package or activity) and adds any
 * other flags. Null if the URI couldn't be made.
 */
fun viewIntent(context: Context, pkg: String, romPath: String, tag: String): Intent? {
    val uri = grantableRomUri(context, pkg, romPath, tag) ?: return null
    return Intent(Intent.ACTION_VIEW).apply {
        data = uri
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * The intent for emulators that take the ROM as a plain path in an extra (DuckStation's and
 * AetherSX2's "bootPath", for example), started fresh in its own task.
 */
fun bootIntent(pkg: String, activity: String, extra: String, value: String, action: String? = null): Intent =
    Intent().apply {
        if (action != null) setAction(action)
        component = ComponentName(pkg, activity)
        putExtra(extra, value)
        addFlags(FRESH_TASK)
    }

/** Starts the emulator in a fresh task, dropping whatever game it had open. */
const val FRESH_TASK: Int = Intent.FLAG_ACTIVITY_NEW_TASK or
    Intent.FLAG_ACTIVITY_CLEAR_TASK or
    Intent.FLAG_ACTIVITY_CLEAR_TOP
