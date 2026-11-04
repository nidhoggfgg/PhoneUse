package ai.nidhoggfgg.phoneuse.device

import ai.nidhoggfgg.phoneuse.MainActivity
import ai.nidhoggfgg.phoneuse.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** A visible failure reminder, separate from the silent foreground-service status. */
internal object AppLaunchNotifications {
    const val CHANNEL = "phone_use_launch_alerts"
    const val ID = 2

    fun show(context: Context, packageName: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val localized = ContextCompat.getContextForLanguage(context)
        try {
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                localized.getString(R.string.launch_notification_channel), NotificationManager.IMPORTANCE_DEFAULT))
            if (!manager.areNotificationsEnabled()) return

            val open = PendingIntent.getActivity(context, ID,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val text = localized.getString(R.string.launch_notification_detail, packageName)
            manager.notify(ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_control)
                .setContentTitle(localized.getString(R.string.launch_notification_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                // One reminder is updated in place; retries must not fill the shade or keep sounding.
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build())
        } catch (_: SecurityException) {
            // Notification permission may be revoked while the service is running. Keep the
            // in-app warning and original launch result even when Android refuses the notification.
        }
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
}
