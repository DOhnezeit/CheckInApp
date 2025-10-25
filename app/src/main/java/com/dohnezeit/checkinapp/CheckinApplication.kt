package com.dohnezeit.checkinapp

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CheckinApplication : Application() {

    private var activityReferences = 0
    private var isConfigurationChanging = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CheckinApplication created")

        CoroutineScope(Dispatchers.IO).launch {
            val prefs = PreferencesManager(this@CheckinApplication)
            val userId = prefs.userId.firstOrNull()
            val apiKey = prefs.apiKey.firstOrNull()
            val role = prefs.userRole.firstOrNull()

            // If user isn't fully logged in yet, delete any old token
            if (userId.isNullOrBlank() || apiKey.isNullOrBlank() || role.isNullOrBlank()) {
                try {
                    FirebaseMessaging.getInstance().deleteToken().await()
                    Log.d(TAG, "🗑️ Old FCM token deleted because user not logged in yet")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to delete old FCM token", e)
                }
            }
        }

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                Log.d("FCM_TOKEN", "Current token: $token")
            } else {
                Log.e("FCM_TOKEN", "Failed to get token", task.exception)
            }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                Log.d(TAG, "Activity created: ${activity.localClassName}")
            }

            override fun onActivityStarted(activity: Activity) {
                if (++activityReferences == 1 && !isConfigurationChanging) {
                    // App entered foreground
                    Log.d(TAG, "🟢 App entered FOREGROUND")
                    onAppForegrounded()
                }
            }

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {
                isConfigurationChanging = activity.isChangingConfigurations
                if (--activityReferences == 0 && !isConfigurationChanging) {
                    // App entered background
                    Log.d(TAG, "🔴 App entered BACKGROUND")
                    onAppBackgrounded()
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                Log.d(TAG, "Activity destroyed: ${activity.localClassName}")
            }
        })
    }

    private fun onAppForegrounded() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = PreferencesManager(this@CheckinApplication)
                val userId = prefs.userId.firstOrNull()
                val apiKey = prefs.apiKey.firstOrNull()
                val role = prefs.userRole.firstOrNull()
                val checkerId = prefs.checkerId.firstOrNull()

                if (userId.isNullOrEmpty() || apiKey.isNullOrEmpty()) {
                    Log.d(TAG, "Skipping foreground actions: user not logged in")
                    return@launch
                }

                // 1. Acknowledge any active alarms
                acknowledgeActiveAlarms(userId, apiKey)

                // 2. Re-register FCM token to ensure it's up to date
                refreshAndRegisterToken(userId, apiKey, role, checkerId)

            } catch (e: Exception) {
                Log.e(TAG, "Error in onAppForegrounded: ${e.message}", e)
            }
        }
    }

    private fun onAppBackgrounded() {
        Log.d(TAG, "App is now in background")
        // Can add background logic here if needed
    }

    private suspend fun acknowledgeActiveAlarms(userId: String, apiKey: String) {
        try {
            // Check if there's an active alarm for this user
            RetrofitClient.setApiKey(apiKey)
            val api = RetrofitClient.create()

            val statusResponse = api.getStatus(userId)
            if (statusResponse.isSuccessful) {
                val status = statusResponse.body()
                val alarmActive = status?.alarm_active ?: false

                if (alarmActive) {
                    Log.d(TAG, "🚨 Active alarm detected, acknowledging...")

                    // Acknowledge the alarm
                    val ackResponse = api.acknowledgeAlarm(AcknowledgeAlarmRequest(userId))
                    if (ackResponse.isSuccessful) {
                        Log.d(TAG, "✅ Alarm acknowledged successfully")

                        // Clear the notification
                        CoroutineScope(Dispatchers.Main).launch {
                            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.cancel(1001) // ALARM_NOTIFICATION_ID
                            Log.d(TAG, "🔕 Alarm notification cleared")
                        }
                    } else {
                        Log.e(TAG, "❌ Failed to acknowledge alarm: ${ackResponse.code()}")
                    }
                } else {
                    Log.d(TAG, "No active alarms")
                }
            } else {
                Log.e(TAG, "Failed to get status: ${statusResponse.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acknowledging alarm: ${e.message}", e)
        }
    }

    private suspend fun resumeActiveAlarms(userId: String, apiKey: String) {
        try {
            RetrofitClient.setApiKey(apiKey)
            val api = RetrofitClient.create()
            val statusResponse = api.getStatus(userId)
            if (statusResponse.isSuccessful) {
                val status = statusResponse.body()
                if (status?.alarm_active == true) {
                    Log.d(TAG, "🚨 Resuming active alarm for $userId")

                    NotificationHelper.showNotification(
                        context = this@CheckinApplication,
                        type = "alarm",
                        title = "⚠️ CHECK-IN MISSED!",
                        body = "$userId missed their check-in! Tap to acknowledge.",
                        checkerId = userId
                    )
                }
            } else {
                Log.e(TAG, "Failed to fetch status to resume alarm: ${statusResponse.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming active alarm: ${e.message}", e)
        }
    }

    private suspend fun refreshAndRegisterToken(
        userId: String,
        apiKey: String,
        role: String?,
        checkerId: String?
    ) {
        try {
            Log.d(TAG, "🔄 Refreshing FCM token...")

            val token = FirebaseMessaging.getInstance().token.await()
            Log.d(TAG, "📱 Current FCM token: ${token.take(20)}...")

            // Store token locally
            getSharedPreferences("fcm", Context.MODE_PRIVATE)
                .edit()
                .putString("token", token)
                .apply()

            if (role.isNullOrBlank()) {
                Log.d(TAG, "Skipping token registration: role not set yet")
                return
            }

            RetrofitClient.setApiKey(apiKey)
            val api = RetrofitClient.create()

            when (role) {
                "checker" -> {
                    Log.d(TAG, "Re-registering checker token...")
                    registerCheckerToken(api, userId, token)
                }

                "watcher" -> {
                    if (checkerId.isNullOrBlank()) {
                        Log.d(TAG, "Skipping watcher registration: checkerId missing")
                        return
                    }
                    Log.d(TAG, "Re-registering watcher token...")
                    registerWatcherToken(api, userId, checkerId, token)
                }

                else -> Log.d(TAG, "Skipping token registration: unknown role '$role'")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Token refresh failed: ${e.message}", e)
        }
    }

    private suspend fun registerCheckerToken(api: ApiService, checkerId: String, token: String) {
        try {
            val response = api.registerChecker(RegisterCheckerRequest(checker_id = checkerId, checker_token = token))
            if (response.isSuccessful) Log.d(TAG, "✅ Checker token re-registered")
            else Log.e(TAG, "❌ Checker registration failed: ${response.code()}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register checker", e)
        }
    }

    private suspend fun registerWatcherToken(api: ApiService, watcherId: String, checkerId: String, token: String) {
        try {
            val response = api.registerWatcher(RegisterWatcherRequest(checker_id = checkerId, watcher_id = watcherId, watcher_token = token))
            if (response.isSuccessful) Log.d(TAG, "✅ Watcher token re-registered")
            else Log.e(TAG, "❌ Watcher registration failed: ${response.code()}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register watcher", e)
        }
    }

    companion object {
        private const val TAG = "CheckinApplication"
    }
}