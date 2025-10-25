package com.dohnezeit.checkinapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class BootReceiver : BroadcastReceiver() {

    // Use SupervisorJob to prevent cancellation
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.d(TAG, "=".repeat(80))
        Log.d(TAG, "📱 BootReceiver triggered with action: $action")
        Log.d(TAG, "=".repeat(80))

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "🚀 Device booted, initializing app services...")

                // Create notification channels immediately
                try {
                    NotificationHelper.createNotificationChannels(context)
                    Log.d(TAG, "✅ Notification channels created on boot")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to create notification channels", e)
                }

                // Use goAsync() to keep receiver alive for async operations
                val pendingResult = goAsync()

                scope.launch {
                    try {
                        // Wait a bit for system to stabilize after boot
                        delay(5000)

                        val prefs = PreferencesManager(context)
                        val userId = prefs.userId.firstOrNull()
                        val apiKey = prefs.apiKey.firstOrNull()
                        val role = prefs.userRole.firstOrNull()
                        val checkerId = prefs.checkerId.firstOrNull()

                        Log.d(TAG, "📝 User info: userId=$userId, role=$role, checkerId=$checkerId")

                        if (!userId.isNullOrEmpty() && !apiKey.isNullOrEmpty()) {
                            Log.d(TAG, "🔄 Refreshing FCM token after boot...")

                            try {
                                // Get fresh FCM token using await() instead of addOnSuccessListener
                                val token = FirebaseMessaging.getInstance().token.await()
                                Log.d(TAG, "📱 Got FCM token: ${token.take(30)}...")

                                // Store token locally
                                context.getSharedPreferences("fcm", Context.MODE_PRIVATE)
                                    .edit()
                                    .putString("token", token)
                                    .putLong("token_timestamp", System.currentTimeMillis())
                                    .apply()

                                // Re-register with server
                                MyFirebaseMessagingService.sendTokenToServer(
                                    token = token,
                                    role = role,
                                    userId = userId,
                                    checkerId = checkerId,
                                    apiKey = apiKey
                                )
                                Log.d(TAG, "✅ FCM token re-registered after boot")
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Failed to refresh FCM token", e)
                            }

                            // Check for active alarms and resume them
                            checkAndResumeAlarms(context, userId, apiKey)
                        } else {
                            Log.d(TAG, "⚠️ User not logged in, skipping FCM registration")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in boot receiver", e)
                    } finally {
                        // Signal that async work is complete
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private suspend fun checkAndResumeAlarms(context: Context, userId: String, apiKey: String) {
        try {
            Log.d(TAG, "🔍 Checking for active alarms...")
            RetrofitClient.setApiKey(apiKey)
            val api = RetrofitClient.create()
            val response = api.getStatus(userId)

            if (response.isSuccessful) {
                val status = response.body()
                if (status?.alarm_active == true) {
                    val isEmergency = status.emergency ?: false
                    Log.d(TAG, "🚨 Active alarm detected after boot (emergency=$isEmergency), showing notification...")

                    NotificationHelper.showNotification(
                        context = context,
                        type = "alarm",
                        title = if (isEmergency) "🚨 EMERGENCY ALARM!" else "⚠️ CHECK-IN MISSED!",
                        body = if (isEmergency)
                            "⚠️ $userId TRIGGERED AN EMERGENCY!"
                        else
                            "$userId missed their check-in! Tap to acknowledge.",
                        checkerId = userId
                    )
                    Log.d(TAG, "✅ Alarm notification shown after boot")
                } else {
                    Log.d(TAG, "✅ No active alarms to resume")
                }
            } else {
                Log.e(TAG, "❌ Failed to get status: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to check for active alarms", e)
        }
    }

    companion object {
        private const val TAG = "🔥BootReceiver"
    }
}