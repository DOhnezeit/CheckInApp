package com.dohnezeit.checkinapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
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
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging
import com.dohnezeit.checkinapp.ui.theme.CheckInAppTheme
import androidx.core.content.edit
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
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
        NotificationHelper.createNotificationChannels(this)
        askNotificationPermission()

        // Get initial FCM token
        getInitialToken()

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

        // Handle FCM data from intent (when app opened from background via data-only message)
        handleFCMIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent called")
        intent?.let { handleFCMIntent(it) }
    }

    private fun handleFCMIntent(intent: Intent) {
        Log.d(TAG, "handleFCMIntent - checking for FCM data")

        // Log all extras for debugging
        intent.extras?.let { bundle ->
            Log.d(TAG, "Intent extras keys: ${bundle.keySet().joinToString()}")
            for (key in bundle.keySet()) {
                Log.d(TAG, "  $key = ${bundle.get(key)}")
            }
        }

        // Check for alarm acknowledgment flag
        if (intent.getBooleanExtra("acknowledge_alarm", false)) {
            val checkerId = intent.getStringExtra("checker_id")
            if (checkerId != null) {
                Log.d(TAG, "Alarm acknowledgment requested for $checkerId")
                acknowledgeAlarm(checkerId)
                return
            }
        }

        // Extract FCM data payload from intent extras
        // When a data-only message is received in background, FCM puts the data in extras
        val type = intent.getStringExtra("type")
        val checkerId = intent.getStringExtra("checker_id")
        val title = intent.getStringExtra("title")
        val body = intent.getStringExtra("body")
        val isAlarmLoop = intent.getStringExtra("alarm_loop") == "true"

        if (type != null) {
            Log.d(TAG, "FCM data received from intent: type=$type, checkerId=$checkerId")

            // With notification payload, onMessageReceived() is always called
            // So we don't need to create notifications here - just handle user actions

            // Handle alarm acknowledgment if app was opened by tapping notification
            if (type == "alarm" && intent.getBooleanExtra("acknowledge_alarm", false)) {
                Log.d(TAG, "Auto-acknowledging alarm from notification tap")
                checkerId?.let { acknowledgeAlarm(it) }
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
                    notificationManager.cancel(NotificationHelper.ALARM_NOTIFICATION_ID)

                    Toast.makeText(this@MainActivity, "Alarm acknowledged", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "✅ Alarm acknowledged from MainActivity")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acknowledge alarm: ${e.message}", e)
                Toast.makeText(this@MainActivity, "Failed to acknowledge alarm", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getInitialToken() {
        lifecycleScope.launch {
            try {
                val token = Firebase.messaging.token.await()
                Log.d(TAG, "📱 Initial FCM Token: ${token.take(20)}...")

                // Store token locally
                getSharedPreferences("fcm", Context.MODE_PRIVATE).edit {
                    putString("token", token)
                }

                // Register with server if user is already logged in
                val userId = preferencesManager.userId.firstOrNull()
                val checkerId = preferencesManager.checkerId.firstOrNull()
                val apiKey = preferencesManager.apiKey.firstOrNull()
                val role = preferencesManager.userRole.firstOrNull()  // <-- ADD THIS

                if (!apiKey.isNullOrBlank() && !userId.isNullOrBlank() && !role.isNullOrBlank()) {
                    RetrofitClient.setApiKey(apiKey)
                    val api = RetrofitClient.create()

                    // Only register as checker if role is "checker"
                    if (role == "checker") {  // <-- ADD THIS CHECK
                        try {
                            val response = api.registerChecker(
                                RegisterCheckerRequest(
                                    checker_id = userId,
                                    checker_token = token
                                )
                            )
                            if (response.isSuccessful) {
                                Log.d(TAG, "✅ Initial checker token registered")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to register checker", e)
                        }
                    }

                    if (role == "watcher" && !checkerId.isNullOrBlank()) {
                        try {
                            val response = api.registerWatcher(
                                RegisterWatcherRequest(
                                    checker_id = checkerId,
                                    watcher_id = userId,
                                    watcher_token = token
                                )
                            )
                            if (response.isSuccessful) {
                                Log.d(TAG, "✅ Initial watcher token registered")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to register watcher", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get initial token: ${e.message}", e)
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

    companion object {
        private const val TAG = "MainActivity"
    }
}