package com.dohnezeit.checkinapp

import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class MyFirebaseMessagingService : FirebaseMessagingService() {

    // Use SupervisorJob to prevent cancellation
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    init {
        Log.d(TAG, "🔥 MyFirebaseMessagingService INITIALIZED at ${System.currentTimeMillis()}")
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🔥🔥🔥 MyFirebaseMessagingService onCreate() called at ${System.currentTimeMillis()}")

        // Create notification channels immediately
        try {
            NotificationHelper.createNotificationChannels(this)
            Log.d(TAG, "✅ Notification channels created")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create notification channels", e)
        }

        // Acquire partial wake lock to keep service alive
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CheckInApp:FCMServiceLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes
        Log.d(TAG, "🔋 WakeLock acquired")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔥 MyFirebaseMessagingService onDestroy() called")
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "🔋 WakeLock released")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Acquire a temporary wake lock for this message
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val messageWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "CheckInApp:FCMMessageWakeLock"
        )

        try {
            messageWakeLock.acquire(60_000) // 60 seconds

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancelAll()
            Log.d(TAG, "🔕 Cleared all previous notifications")

            val timestamp = System.currentTimeMillis()
            Log.d(TAG, "=".repeat(80))
            Log.d(TAG, "🔥🔥🔥 MESSAGE RECEIVED at $timestamp 🔥🔥🔥")
            Log.d(TAG, "From: ${message.from}")
            Log.d(TAG, "Message ID: ${message.messageId}")
            Log.d(TAG, "Sent time: ${message.sentTime}")
            Log.d(TAG, "Priority: ${message.priority}")
            Log.d(TAG, "Original priority: ${message.originalPriority}")
            Log.d(TAG, "Data payload: ${message.data}")
            Log.d(TAG, "Notification: ${message.notification}")
            Log.d(TAG, "=".repeat(80))

            // Store message receipt time for debugging
            getSharedPreferences("fcm_debug", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_message_received", timestamp)
                .putString("last_message_type", message.data["type"])
                .putString("last_message_id", message.messageId)
                .apply()

            val type = message.data["type"] ?: "checkin"
            Log.d(TAG, "📨 Processing message type: $type")

            val checkerId = message.data["checker_id"] ?: "Unknown"
            val checkinTimeMillis = message.data["checkin_time"]?.toLongOrNull()
            val isEmergency = message.data["emergency"] == "true"

            val formattedTime = checkinTimeMillis?.let {
                val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                sdf.timeZone = java.util.TimeZone.getDefault()
                sdf.format(java.util.Date(it))
            } ?: "Unknown"

            // Notification title
            val title = when {
                type == "alarm" && isEmergency -> "🚨 EMERGENCY ALARM!"
                type == "alarm" -> "⚠️ ALARM: CHECK-IN MISSED!"
                type == "reminder" -> "Time to check in!"
                type == "missed" -> "Missed check-in"
                type == "sleep" -> "Checker asleep 💤"
                else -> "Check in at $formattedTime"
            }

            // Health data
            val pulse = message.data["pulse"]
            val bp = message.data["blood_pressure"]

            val bodyBuilder = StringBuilder(
                when {
                    type == "alarm" && isEmergency -> "⚠️ $checkerId TRIGGERED AN EMERGENCY!"
                    type == "alarm" -> "$checkerId missed their check-in at $formattedTime! Tap to acknowledge."
                    type == "reminder" -> "Please check in now"
                    type == "missed" -> "$checkerId missed their check-in at $formattedTime!"
                    type == "sleep" -> "$checkerId has gone to sleep at $formattedTime."
                    else -> "$checkerId checked in!"
                }
            )

            // Only append health data for checkins
            if (type == "checkin" && (!pulse.isNullOrBlank() || !bp.isNullOrBlank())) {
                bodyBuilder.append(" ")
                if (!pulse.isNullOrBlank()) bodyBuilder.append("❤️ $pulse")
                if (!bp.isNullOrBlank()) bodyBuilder.append(" | 🩸 $bp")
            }

            val body = bodyBuilder.toString()
            Log.d(TAG, "📢 Showing notification: title='$title', body='$body'")

            // Show notification immediately
            NotificationHelper.showNotification(this, type, title, body, checkerId)
            Log.d(TAG, "✅ Notification shown successfully")

            // Try to show in-app dialog if app is open
            Handler(Looper.getMainLooper()).post {
                try {
                    val activity = CurrentActivityHolder.currentActivity
                    if (activity is AppCompatActivity && !activity.isFinishing && !activity.isDestroyed) {
                        Log.d(TAG, "📱 App is in foreground, showing dialog")
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
                    } else {
                        Log.d(TAG, "📱 App not in foreground or activity not available")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to show alert dialog", e)
                }
            }

            Log.d(TAG, "✅ Message processing complete")
        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL ERROR processing FCM message", e)
            // Try to show error notification
            try {
                NotificationHelper.showNotification(
                    this,
                    "checkin",
                    "Error",
                    "Failed to process notification: ${e.message}",
                    "error"
                )
            } catch (ne: Exception) {
                Log.e(TAG, "❌ Failed to show error notification", ne)
            }
        } finally {
            if (messageWakeLock.isHeld) {
                messageWakeLock.release()
                Log.d(TAG, "🔋 Message WakeLock released")
            }
        }
    }

    private fun acknowledgeAlarm(checkerId: String) {
        Log.d(TAG, "🔔 Acknowledging alarm for $checkerId")
        serviceScope.launch {
            try {
                val prefs = PreferencesManager(this@MyFirebaseMessagingService)
                val apiKey = prefs.apiKey.firstOrNull()

                if (apiKey.isNullOrEmpty()) {
                    Log.e(TAG, "❌ Cannot acknowledge alarm: API key not found")
                    return@launch
                }

                RetrofitClient.setApiKey(apiKey)
                val api = RetrofitClient.create()
                val response = api.acknowledgeAlarm(AcknowledgeAlarmRequest(checkerId))

                if (response.isSuccessful) {
                    Log.d(TAG, "✅ Alarm acknowledged for $checkerId")

                    // Clear notification on main thread
                    launch(Dispatchers.Main) {
                        NotificationHelper.clearNotification(
                            this@MyFirebaseMessagingService,
                            "alarm"
                        )
                    }
                } else {
                    Log.e(TAG, "❌ Failed to acknowledge alarm: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception acknowledging alarm", e)
            }
        }
    }

    override fun onNewToken(token: String) {
        val timestamp = System.currentTimeMillis()
        Log.d(TAG, "=".repeat(80))
        Log.d(TAG, "🔄 NEW FCM TOKEN at $timestamp")
        Log.d(TAG, "Token (first 30 chars): ${token.take(30)}...")
        Log.d(TAG, "=".repeat(80))

        // Store token locally immediately
        try {
            getSharedPreferences("fcm", Context.MODE_PRIVATE)
                .edit()
                .putString("token", token)
                .putLong("token_timestamp", timestamp)
                .apply()
            Log.d(TAG, "✅ Token stored locally")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to store token locally", e)
        }

        // Register with backend
        serviceScope.launch {
            // Add a small delay to ensure preferences are ready
            delay(500)

            try {
                val prefs = PreferencesManager(this@MyFirebaseMessagingService)
                val userId = prefs.userId.firstOrNull()
                val apiKey = prefs.apiKey.firstOrNull()
                val role = prefs.userRole.firstOrNull()
                val checkerId = prefs.checkerId.firstOrNull()

                Log.d(TAG, "📝 Token registration info: userId=$userId, role=$role, checkerId=$checkerId")

                if (!userId.isNullOrEmpty() && !apiKey.isNullOrEmpty()) {
                    sendTokenToServer(token, role, userId, checkerId, apiKey)
                } else {
                    Log.w(TAG, "⚠️ Cannot send token: missing userId or apiKey")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send token in onNewToken", e)
            }
        }
    }

    override fun onDeletedMessages() {
        super.onDeletedMessages()
        Log.w(TAG, "⚠️ onDeletedMessages() - Some messages were deleted from server before delivery")
    }

    override fun onMessageSent(msgId: String) {
        super.onMessageSent(msgId)
        Log.d(TAG, "📤 onMessageSent: $msgId")
    }

    override fun onSendError(msgId: String, exception: Exception) {
        super.onSendError(msgId, exception)
        Log.e(TAG, "❌ onSendError: $msgId", exception)
    }

    companion object {
        private const val TAG = "🔥FCM"

        fun sendTokenToServer(
            token: String,
            role: String?,
            userId: String?,
            checkerId: String?,
            apiKey: String?
        ) {
            Log.d(TAG, "📡 sendTokenToServer called: role=$role, userId=$userId, checkerId=$checkerId")

            if (apiKey.isNullOrEmpty() || userId.isNullOrEmpty()) {
                Log.e(TAG, "❌ Cannot send token: apiKey or userId is null/empty")
                return
            }

            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    RetrofitClient.setApiKey(apiKey)
                    val api = RetrofitClient.create()

                    when (role) {
                        "checker" -> {
                            Log.d(TAG, "📤 Registering CHECKER token...")
                            val response = api.registerChecker(
                                RegisterCheckerRequest(userId, token)
                            )
                            if (response.isSuccessful) {
                                Log.d(TAG, "✅ Checker token registered successfully")
                            } else {
                                Log.e(TAG, "❌ Checker token registration failed: ${response.code()}")
                            }
                        }
                        "watcher" -> {
                            if (checkerId.isNullOrEmpty()) {
                                Log.e(TAG, "❌ Cannot register watcher: checkerId is null/empty")
                                return@launch
                            }
                            Log.d(TAG, "📤 Registering WATCHER token...")
                            val response = api.registerWatcher(
                                RegisterWatcherRequest(checkerId, userId, token)
                            )
                            if (response.isSuccessful) {
                                Log.d(TAG, "✅ Watcher token registered successfully")
                            } else {
                                Log.e(TAG, "❌ Watcher token registration failed: ${response.code()}")
                            }
                        }
                        else -> {
                            Log.w(TAG, "⚠️ Unknown role '$role', skipping token registration")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Exception during token registration", e)
                }
            }
        }
    }
}