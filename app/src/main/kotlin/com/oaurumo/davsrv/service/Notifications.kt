package com.oaurumo.davsrv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.oaurumo.davsrv.MainActivity
import com.oaurumo.davsrv.R
import com.oaurumo.davsrv.data.ServerState
import com.oaurumo.davsrv.LocaleHelper
object Notifications {
    const val CHANNEL_ID = "davsrv_server"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, state: ServerState): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags()
        )
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, WebDavService::class.java).setAction(WebDavService.ACTION_STOP),
            pendingFlags()
        )

        val ctx = LocaleHelper.wrap(context)
        val status = when {
            state.error != null -> ctx.getString(R.string.notif_start_failed, state.error)
            state.running -> state.address
            else -> ctx.getString(R.string.notif_starting)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_davsrv)
            .setContentTitle(if (state.running) ctx.getString(R.string.notif_running_title) else "DavSrv")
            .setContentText(status)
            .setSubText(if (state.running) ctx.getString(R.string.notif_port_suffix, state.port) else null)
            .setContentIntent(open)
            .setOngoing(state.running)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (state.running) {
                    addAction(0, context.getString(R.string.notif_action_stop), stop)
                }
            }
            .build()
    }

    fun update(context: Context, state: ServerState) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, build(context, state)) }
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
}
