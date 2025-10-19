package com.dohnezeit.checkinapp

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.AlertDialog
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.flow.firstOrNull

class MyFirebaseMessagingService : FirebaseMessagingService() {

    init {
        Log.d(TAG, "🔥 MyFirebaseMessagingService INITIALIZED")
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🔥 MyFirebaseMessagingService onCreate() called")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(TAG, "🔥🔥🔥 MESSAGE RECEIVED 🔥🔥🔥")
        Log.d(TAG, "From: ${message.from}")
        Log.d(TAG, "Message ID: ${message.messageId}")
        Log.d(TAG, "Data payload: ${message.data}")
        Log.d(TAG, "Notification: ${message.notification}")

        val type = message.data["type"] ?: "checkin"
        val checkerId = message.data["checker_id"] ?: "Unknown"
        val isAlarmLoop = message.data["alarm_loop"] == "true"

        Log.d(TAG, "Parsed - type: $type, checkerId: $checkerId, isAlarmLoop: $isAlarmLoop")

        // Stop alarm sound when check-in is successful
        if (type == "checkin") {
            stopAlarmSound()
        }

        val title = when (type) {
            "reminder" -> "Time to check in!"
            "alarm" -> "CHECK-IN MISSED!"
            "sleep" -> "Checker asleep 💤"
            else -> "Check-in successful"
        }

        val body = when (type) {
            "reminder" -> "Please check in now"
            "alarm" -> "$checkerId MISSED CHECK-IN! Tap to acknowledge."
            "sleep" -> "$checkerId has gone to sleep"
            else -> "$checkerId checked in!"
        }

        Log.d(TAG, "Showing notification: $title - $body")

        // Play custom sound based on type
        if (type == "alarm" && isAlarmLoop) {
            playAlarmSound()
        } else {
            playCustomSound(type)
        }


        showNotification(type, title, body, checkerId, isAlarmLoop)

        val activity = CurrentActivityHolder.currentActivity
        if (activity is AppCompatActivity && !activity.isFinishing) {
            activity.runOnUiThread {
                try {
                    val dialog = AlertDialog.Builder(activity)
                        .setTitle(title)
                        .setMessage(body)
                        .setPositiveButton("OK") { _, _ ->
                            if (type == "alarm") {
                                acknowledgeAlarm(checkerId)
                            }
                        }

                    if (type == "alarm") {
                        dialog.setCancelable(false)
                    }

                    dialog.show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to show alert dialog: ${e.message}")
                }
            }
        } else {
            Log.d(TAG, "No active AppCompat activity to show dialog")
        }
    }

    private fun playCustomSound(type: String) {
        try {
            // Get the appropriate sound URI from res/raw folder
            val soundResId = when (type) {
                "reminder" -> R.raw.reminder_sound  // Custom reminder sound
                "checkin" -> R.raw.checkin_sound    // Custom check-in success sound
                else -> null
            }

            if (soundResId != null) {
                val mediaPlayer = MediaPlayer.create(this, soundResId)
                mediaPlayer?.apply {
                    setOnCompletionListener { mp ->
                        mp.release()
                    }
                    start()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing custom sound: ${e.message}")
        }
    }

    private fun playAlarmSound() {
        try {
            // Stop any existing alarm
            stopAlarmSound()

            // Create MediaPlayer with custom alarm sound
            currentAlarmPlayer = MediaPlayer.create(this, R.raw.alarm_sound).apply {
                isLooping = false  // Don't loop in MediaPlayer, we'll get new notifications
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                setVolume(1.0f, 1.0f)

                // Acquire wake lock to ensure sound plays even when screen is off
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CheckinApp::AlarmWakeLock"
                ).apply {
                    acquire(60000) // 60 seconds max
                }

                setOnCompletionListener { mp ->
                    Log.d(TAG, "Alarm sound completed")
                }

                start()
                Log.d(TAG, "Alarm sound started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing alarm sound: ${e.message}", e)
        }
    }

    private fun stopAlarmSound() {
        try {
            currentAlarmPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
            currentAlarmPlayer = null

            wakeLock?.apply {
                if (isHeld) {
                    release()
                }
            }
            wakeLock = null

            Log.d(TAG, "Alarm sound stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping alarm: ${e.message}")
        }
    }

    private fun acknowledgeAlarm(checkerId: String) {
        stopAlarmSound()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = PreferencesManager(this@MyFirebaseMessagingService)
                val apiKey = prefs.apiKey.firstOrNull()

                if (!apiKey.isNullOrEmpty()) {
                    RetrofitClient.setApiKey(apiKey)
                    val api = RetrofitClient.create()
                    api.acknowledgeAlarm(AcknowledgeAlarmRequest(checkerId))
                    Log.d(TAG, "Alarm acknowledged for $checkerId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acknowledge alarm: ${e.message}", e)
            }
        }

        // Clear notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ALARM_NOTIFICATION_ID)
    }

    private fun showNotification(type: String, title: String, body: String, checkerId: String, isAlarmLoop: Boolean) {
        Log.d(TAG, "showNotification called: $type, $title, $body")

        val channelId = when (type) {
            "alarm" -> "checkin_alarms"
            "reminder" -> "checkin_reminders"
            else -> "checkin_notifications"
        }

        // Alarm notifications are handled via MediaPlayer
        // Other types use default notification sounds
        val soundUri = when (type) {
            "alarm" -> null
            "reminder" -> Uri.parse("android.resource://${packageName}/${R.raw.reminder_sound}")
            "checkin" -> Uri.parse("android.resource://${packageName}/${R.raw.checkin_sound}")
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }

        val intent = if (type == "alarm") {
            // Create intent that acknowledges alarm when tapped
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("acknowledge_alarm", true)
                putExtra("checker_id", checkerId)
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            if (type == "alarm") 1001 else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(type != "alarm")  // Alarm notifications shouldn't auto-cancel
            .setOngoing(type == "alarm")  // Make alarm notifications persistent

        // For old devices
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setSound(soundUri)
        }

        if (soundUri != null) {
            builder.setSound(soundUri)
        }

        // Acknowledge action for alarm notifications
        if (type == "alarm") {
            val acknowledgeIntent = Intent(this, AlarmAcknowledgeReceiver::class.java).apply {
                putExtra("checker_id", checkerId)
            }
            val acknowledgePendingIntent = PendingIntent.getBroadcast(
                this,
                1002,
                acknowledgeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Acknowledge",
                acknowledgePendingIntent
            )
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = when (type) {
                "alarm" -> NotificationManager.IMPORTANCE_HIGH
                "reminder" -> NotificationManager.IMPORTANCE_HIGH
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }

            val channel = android.app.NotificationChannel(
                channelId,
                when (type) {
                    "alarm" -> "Check-in Alarms"
                    "reminder" -> "Check-in Reminders"
                    else -> "Check-in Notifications"
                },
                importance
            ).apply {
                if (soundUri != null) {
                    setSound(soundUri, AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(
                            if (type == "alarm") AudioAttributes.USAGE_ALARM
                            else AudioAttributes.USAGE_NOTIFICATION
                        )
                        .build()
                    )
                }
                enableVibration(true)
                vibrationPattern = if (type == "alarm") {
                    longArrayOf(0, 500, 200, 500)
                } else {
                    longArrayOf(0, 250, 250, 250)
                }
            }
            manager.createNotificationChannel(channel)
        }

        // Use fixed ID for alarm notifications so they update instead of stacking
        val notificationId = when (type) {
            "alarm" -> ALARM_NOTIFICATION_ID
            else -> System.currentTimeMillis().toInt()
        }

        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "=== NEW FCM TOKEN === $token")

        // Store token locally
        getSharedPreferences("fcm", Context.MODE_PRIVATE)
            .edit()
            .putString("token", token)
            .apply()

        // Launch coroutine to read preferences and send token
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = PreferencesManager(this@MyFirebaseMessagingService)
                val userId = prefs.userId.firstOrNull()
                val apiKey = prefs.apiKey.firstOrNull()
                val role = prefs.userRole.firstOrNull()
                val checkerId = prefs.checkerId.firstOrNull()

                if (!userId.isNullOrEmpty() && !apiKey.isNullOrEmpty()) {
                    sendTokenToServer(
                        token = token,
                        role = role,
                        userId = userId,
                        checkerId = checkerId,
                        apiKey = apiKey
                    )
                } else {
                    Log.w(TAG, "Cannot send token: missing userId or apiKey")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send token in onNewToken: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "MyFirebaseMsgService"
        private const val ALARM_NOTIFICATION_ID = 1001

        @Volatile
        private var currentAlarmPlayer: MediaPlayer? = null

        @Volatile
        private var wakeLock: PowerManager.WakeLock? = null

        fun sendTokenToServer(
            token: String,
            role: String?,
            userId: String?,
            checkerId: String?,
            apiKey: String?
        ) {
            if (apiKey.isNullOrEmpty() || userId.isNullOrEmpty()) return

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    RetrofitClient.setApiKey(apiKey)
                    val api = RetrofitClient.create()

                    when (role) {
                        "checker" -> {
                            api.registerChecker(RegisterCheckerRequest(userId, token))
                        }
                        "watcher" -> {
                            if (!checkerId.isNullOrEmpty()) {
                                api.registerWatcher(RegisterWatcherRequest(checkerId, userId, token))
                            }
                        }
                    }
                    Log.d(TAG, "Token registration request sent successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register token: ${e.message}", e)
                }
            }
        }
    }
}
