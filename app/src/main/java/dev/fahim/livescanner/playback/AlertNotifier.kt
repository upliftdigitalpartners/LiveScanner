package dev.fahim.livescanner.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.fahim.livescanner.R
import dev.fahim.livescanner.ui.MainActivity

/**
 * Posts an alert to the system shade when a rule fires.
 *
 * The in-app banner only helps if you are looking at the phone; the whole point of arming a rule
 * for your own flight is that you are doing something else when it is finally called.
 */
class AlertNotifier(private val context: Context) {

    private var nextId = 2_000

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Feed alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Fires when an armed rule is heard on the live feed"
            enableVibration(true)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    fun post(title: String, body: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        try {
            manager.notify(nextId++, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the check and the call; nothing to do.
        }
    }

    private companion object {
        const val CHANNEL_ID = "feed_alerts"
    }
}
