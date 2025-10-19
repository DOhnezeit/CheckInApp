package com.dohnezeit.checkinapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging
import com.dohnezeit.checkinapp.ui.theme.CheckInAppTheme
import com.dohnezeit.checkinapp.MainScreen
import com.dohnezeit.checkinapp.PreferencesManager
import androidx.core.content.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    private lateinit var preferencesManager: PreferencesManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted")
        } else {
            Log.d(TAG, "Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        preferencesManager = PreferencesManager(this)
        createNotificationChannels()
        askNotificationPermission()

        // 🔥 FORCE TOKEN REFRESH
        lifecycleScope.launch {
            try {
                Log.d(TAG, "🔄 Deleting old FCM token...")
                Firebase.messaging.deleteToken().await()

                Log.d(TAG, "⏳ Waiting for new token generation...")
                delay(2000)

                val newToken = Firebase.messaging.token.await()
                Log.d(TAG, "🔥🔥🔥 NEW FCM TOKEN GENERATED 🔥🔥🔥")
                Log.d(TAG, "Token: $newToken")
                Log.d(TAG, "🔥🔥🔥 TEST THIS TOKEN IN FIREBASE CONSOLE 🔥🔥🔥")

                // Store token locally
                getSharedPreferences("fcm", Context.MODE_PRIVATE).edit {
                    putString("token", newToken)
                }

                // Auto-register the new token with server
                val userId = preferencesManager.userId.first()
                val checkerId = preferencesManager.checkerId.first()
                val apiKey = preferencesManager.apiKey.first()

                if (apiKey.isNullOrBlank()) {
                    Log.w(TAG, "⚠️ API key not set yet, token will be registered after setup")
                    return@launch
                }

                RetrofitClient.setApiKey(apiKey)
                val api = RetrofitClient.create()

                // Register as checker if userId is set
                if (!userId.isNullOrBlank()) {
                    try {
                        val response = api.registerChecker(
                            request = RegisterCheckerRequest(
                                checker_id = userId,
                                checker_token = newToken
                            )
                        )
                        if (response.isSuccessful) {
                            Log.d(TAG, "✅ NEW checker token registered successfully")
                        } else {
                            Log.e(TAG, "❌ Checker registration failed: ${response.code()}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to register checker", e)
                    }
                }

                // Register as watcher if watching someone
                if (!checkerId.isNullOrBlank() && !userId.isNullOrBlank()) {
                    try {
                        val response = api.registerWatcher(
                            request = RegisterWatcherRequest(
                                checker_id = checkerId,
                                watcher_id = userId,
                                watcher_token = newToken
                            )
                        )
                        if (response.isSuccessful) {
                            Log.d(TAG, "✅ NEW watcher token registered successfully")
                        } else {
                            Log.e(TAG, "❌ Watcher registration failed: ${response.code()}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to register watcher", e)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Token refresh failed: ${e.message}", e)
                // Fallback to old method
                getOldToken()
            }
        }

        setContent {
            CheckInAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(preferencesManager = preferencesManager)
                }
            }
        }

        handleAlarmIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleAlarmIntent(it) }
    }

    private fun handleAlarmIntent(intent: Intent) {
        if (intent.getBooleanExtra("acknowledge_alarm", false)) {
            val checkerId = intent.getStringExtra("checker_id")
            if (checkerId != null) {
                acknowledgeAlarm(checkerId)
            }
        }
    }

    private fun acknowledgeAlarm(checkerId: String) {
        lifecycleScope.launch {
            try {
                val apiKey = preferencesManager.apiKey.firstOrNull()

                if (!apiKey.isNullOrEmpty()) {
                    RetrofitClient.setApiKey(apiKey)
                    val api = RetrofitClient.create()
                    api.acknowledgeAlarm(AcknowledgeAlarmRequest(checkerId))

                    // Clear the notification
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(1001)

                    Toast.makeText(this@MainActivity, "Alarm acknowledged", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to acknowledge alarm: ${e.message}", e)
                Toast.makeText(this@MainActivity, "Failed to acknowledge alarm", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getOldToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                Log.d(TAG, "Fallback FCM Token: $token")

                getSharedPreferences("fcm", Context.MODE_PRIVATE).edit {
                    putString("token", token)
                }

                lifecycleScope.launch {
                    val userId = preferencesManager.userId.first()
                    val checkerId = preferencesManager.checkerId.first()
                    val apiKey = preferencesManager.apiKey.first()

                    if (apiKey.isNullOrBlank()) {
                        Log.w(TAG, "API key not set yet, skipping token registration")
                        return@launch
                    }

                    RetrofitClient.setApiKey(apiKey)
                    val api = RetrofitClient.create()

                    if (!userId.isNullOrBlank()) {
                        try {
                            val response = api.registerChecker(
                                request = RegisterCheckerRequest(
                                    checker_id = userId,
                                    checker_token = token
                                )
                            )
                            if (response.isSuccessful) {
                                Log.d(TAG, "✅ Checker token registered successfully")
                            } else {
                                Log.e(TAG, "❌ Checker registration failed: ${response.code()}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to register checker", e)
                        }
                    }

                    if (!checkerId.isNullOrBlank() && !userId.isNullOrBlank()) {
                        try {
                            val response = api.registerWatcher(
                                request = RegisterWatcherRequest(
                                    checker_id = checkerId,
                                    watcher_id = userId,
                                    watcher_token = token
                                )
                            )
                            if (response.isSuccessful) {
                                Log.d(TAG, "✅ Watcher token registered successfully")
                            } else {
                                Log.e(TAG, "❌ Watcher registration failed: ${response.code()}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to register watcher", e)
                        }
                    }
                }
            } else {
                Log.w(TAG, "Fetching FCM registration token failed", task.exception)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CurrentActivityHolder.currentActivity = this
    }

    override fun onPause() {
        super.onPause()
        CurrentActivityHolder.currentActivity = null
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "Notification permission already granted")
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Normal notifications
            val defaultSound = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_NOTIFICATION
            )
            val defaultChannel = NotificationChannel(
                "checkin_notifications",
                "Check-in Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Regular updates about check-ins"
                setSound(defaultSound, null)
            }

            // Reminder channel
            val reminderSound = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_NOTIFICATION
            )
            val reminderChannel = NotificationChannel(
                "checkin_reminders",
                "Check-in Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders for check-ins"
                setSound(reminderSound, null)
                enableVibration(true)
            }

            // Missed check-in alarm channel
            val alarmSound = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_ALARM
            )
            val alarmChannel = NotificationChannel(
                "checkin_alarms",
                "Missed Check-in Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when someone misses a check-in"
                setSound(alarmSound, null)
                enableVibration(true)
            }

            manager.createNotificationChannel(defaultChannel)
            manager.createNotificationChannel(reminderChannel)
            manager.createNotificationChannel(alarmChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "checkin_notifications"
        private const val TAG = "MainActivity"
    }
}