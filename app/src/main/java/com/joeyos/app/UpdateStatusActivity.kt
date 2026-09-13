package com.joeyos.app

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Where the in-app updater's install session reports back. It's an invisible activity rather than
 * a broadcast receiver so that JoeyOS reopens after updating itself.
 *
 * Installing the update kills the running JoeyOS. A receiver in the new version can't reopen it:
 * Android blocks apps from starting activities from the background (Android 10+), which is why
 * the first version of the updater left you looking at the home screen (found on device). Here
 * the installer — a system component, allowed to start activities — delivers the result by
 * starting this activity itself, which then brings up JoeyOS.
 */
class UpdateStatusActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's own "Update this app?" confirmation; it only appears if we start it.
                val confirm = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                runCatching { confirm?.let { startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.i("Updater", "Update installed, reopening JoeyOS")
                // On the main screen: started from here it opened wherever the installer's result
                // landed, the Thor's bottom screen, leaving the top one on the old version (found
                // on device). The second screen follows from the home screen as usual.
                runCatching {
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle())
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> { /* the user tapped Cancel */ }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(this, "Update failed: ${message ?: "status $status"}", Toast.LENGTH_LONG).show()
                AppLog.w("Updater", "Update install failed: status $status ${message.orEmpty()}")
            }
        }
        finish()
    }

    companion object {
        /** The install session's status target: starts this activity with the result. */
        fun pendingIntent(context: Context, sessionId: Int): PendingIntent {
            val options = if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+: let the installer's start of this activity count as ours.
                ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            return PendingIntent.getActivity(
                context, sessionId,
                Intent(context, UpdateStatusActivity::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                options
            )
        }
    }
}
