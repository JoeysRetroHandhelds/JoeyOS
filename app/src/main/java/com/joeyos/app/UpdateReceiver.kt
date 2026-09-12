package com.joeyos.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/**
 * Install-session callback. STATUS_PENDING_USER_ACTION is the key case: the system hands
 * back the confirmation intent, and the prompt only appears if we start it.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { confirm?.let { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                runCatching {
                    context.packageManager.getLaunchIntentForPackage(context.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> { /* user tapped Cancel — nothing to report */ }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                runCatching { Toast.makeText(context, "Update failed: ${message ?: "status $status"}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    companion object {
        fun pendingIntent(context: Context, sessionId: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, sessionId, Intent(context, UpdateReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
