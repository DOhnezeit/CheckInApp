package com.dohnezeit.checkinapp

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val TAG = "NotificationHelper"
    private const val PREFS_NAME = "notification_ids"

    const val ALARM_NOTIFICATION_ID = 1001
    const val SUCCESS_NOTIFICATION_ID = 1002
    const val REMINDER_NOTIFICATION_ID = 1003

    const val CHANNEL_ID_ALARM = "checkin_alarms_v2"
    const val CHANNEL_ID_REMINDER = "checkin_reminders_v2"
    const val CHANNEL_ID_CHECKIN = "checkin_notifications_v2"

    const val REQUEST_CODE_ACKNOWLEDGE = 2001

    private fun getLastNotificationId(context: Context, type: String): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getInt("last_${type}_id", -1)
        return if (id == -1) null else id
    }

    private fun saveLastNotificationId(context: Context, type: String, id: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("last_${type}_id", id)
            .apply()
    }

    fun clearNotification(context: Context, type: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Cancel the last known notification ID for this type
        getLastNotificationId(context, type)?.let { lastId ->
            notificationManager.cancel(lastId)
            Log.d(TAG, "🔕 Cleared $type notification with ID $lastId")
        }

        // Also try base IDs as fallback
        val notificationId = when(type) {
            "alarm" -> ALARM_NOTIFICATION_ID
            "reminder" -> REMINDER_NOTIFICATION_ID
            "checkin", "sleep" -> SUCCESS_NOTIFICATION_ID
            else -> 0
        }
        if (notificationId != 0) {
            notificationManager.cancel(notificationId)
        }
    }

    fun clearAllNotifications(context: Context) {
        listOf("alarm", "reminder", "checkin", "sleep").forEach {
            clearNotification(context, it)
        }
    }

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Alarm Channel
            val alarmSound = Uri.parse("android.resource://${context.packageName}/${R.raw.alarm_sound}")
            val alarmChannel = android.app.NotificationChannel(
                CHANNEL_ID_ALARM,
                "Check-in Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts when check-ins are missed"
                setSound(
                    alarmSound,
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(alarmChannel)

            // Reminder Channel
            val reminderSound = Uri.parse("android.resource://${context.packageName}/${R.raw.reminder_sound}")
            val reminderChannel = android.app.NotificationChannel(
                CHANNEL_ID_REMINDER,
                "Check-in Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders when it's time to check in"
                setSound(
                    reminderSound,
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
            }
            notificationManager.createNotificationChannel(reminderChannel)

            // Check-in Success Channel
            val checkinSound = Uri.parse("android.resource://${context.packageName}/${R.raw.checkin_sound}")
            val checkinChannel = android.app.NotificationChannel(
                CHANNEL_ID_CHECKIN,
                "Check-in Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for successful check-ins"
                setSound(
                    checkinSound,
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(checkinChannel)
        }
    }

    fun showNotification(
        context: Context,
        type: String,
        title: String,
        body: String,
        checkerId: String
    ) {
        Log.d(TAG, "showNotification called - type: $type, title: $title, body: $body")

        val (channelId, notificationId, vibration) = when (type) {
            "alarm" -> Triple(CHANNEL_ID_ALARM, ALARM_NOTIFICATION_ID, longArrayOf(0, 500, 200, 500))
            "reminder" -> Triple(CHANNEL_ID_REMINDER, REMINDER_NOTIFICATION_ID, longArrayOf(0, 250, 250, 250))
            else -> Triple(CHANNEL_ID_CHECKIN, SUCCESS_NOTIFICATION_ID, longArrayOf(0, 250, 250, 250))
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Use a consistent tag for this notification type
        val notificationTag = "checkin_${type}"

        // Cancel with tag to ensure proper replacement
        notificationManager.cancel(notificationTag, notificationId)
        Log.d(TAG, "Cancelled $type notification with tag: $notificationTag")

        Log.d(TAG, "Preparing new $type notification with tag: $notificationTag, ID: $notificationId")

        // Build the PendingIntent with unique request code based on timestamp
        val uniqueRequestCode = (System.currentTimeMillis() % 100000).toInt()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (type == "alarm") {
                putExtra("acknowledge_alarm", true)
                putExtra("checker_id", checkerId)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            uniqueRequestCode,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)) // Expand text
            .setContentIntent(pendingIntent)
            .setAutoCancel(type != "alarm")
            .setOngoing(type == "alarm")
            .setPriority(NotificationCompat.PRIORITY_MAX) // Changed to MAX
            .setCategory(NotificationCompat.CATEGORY_MESSAGE) // Treat as message
            .setOnlyAlertOnce(false)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setVibrate(vibration)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(0)
            .setGroup(null)
            .setGroupSummary(false)
            .setNumber(0)
            .setTimeoutAfter(0) // Never timeout
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)

        if (type == "alarm") {
            val acknowledgeIntent = Intent(context, AlarmAcknowledgeReceiver::class.java).apply {
                putExtra("checker_id", checkerId)
            }
            val acknowledgePendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_ACKNOWLEDGE,
                acknowledgeIntent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Acknowledge",
                acknowledgePendingIntent
            )
        }

        // Post notification with tag - delayed to ensure cancellation is processed
        Handler(Looper.getMainLooper()).postDelayed({
            notificationManager.notify(notificationTag, notificationId, builder.build())
            Log.d(TAG, "✅ Notification posted: $type with tag $notificationTag and ID $notificationId")
        }, 250) // 250ms delay
    }
}

// TODO: Sleep notification doesn't clear checkin notification anymore