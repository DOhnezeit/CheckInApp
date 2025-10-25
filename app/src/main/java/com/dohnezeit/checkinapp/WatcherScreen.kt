package com.dohnezeit.checkinapp

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.firstOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatcherScreen(preferencesManager: PreferencesManager, context: Context) {
    val scope = rememberCoroutineScope()

    val userId by preferencesManager.userId.collectAsStateWithLifecycle(initialValue = "")
    val checkerId by preferencesManager.checkerId.collectAsStateWithLifecycle(initialValue = "")
    val apiKey by preferencesManager.apiKey.collectAsStateWithLifecycle(initialValue = "")

    var lastCheckin by remember { mutableStateOf<Long?>(null) }
    var checkInterval by remember { mutableStateOf<Float?>(null) }
    var checkWindow by remember { mutableStateOf<Float?>(null) }
    var statusMessage by remember { mutableStateOf("Loading...") }
    var showSettings by remember { mutableStateOf(false) }
    var isSleeping by remember { mutableStateOf(false) }
    var isEmergency by remember { mutableStateOf(false) }

    // Health data
    var pulse by remember { mutableStateOf<String?>(null) }
    var bloodPressure by remember { mutableStateOf<String?>(null) }
    var lastHealthCheckTime by remember { mutableStateOf<String?>(null) }
    var lastHealthCheckTimestamp by remember { mutableStateOf<Long?>(null) }

    // Set API key when available
    LaunchedEffect(apiKey) {
        val currentApiKey = apiKey
        if (!currentApiKey.isNullOrBlank()) {
            RetrofitClient.setApiKey(currentApiKey)
        }
    }

    // Auto-refresh UI based on backend every 10 seconds
    LaunchedEffect(checkerId, apiKey) {
        while (true) {
            val currentCheckerId = checkerId
            val currentApiKey = apiKey

            if (!currentCheckerId.isNullOrBlank() && !currentApiKey.isNullOrBlank()) {
                try {
                    RetrofitClient.setApiKey(currentApiKey)
                    val api = RetrofitClient.create()
                    val response = api.getStatus(currentCheckerId)
                    if (response.isSuccessful) {
                        val body = response.body()
                        val newCheckin = body?.last_checkin
                        lastCheckin = newCheckin
                        checkInterval = body?.check_interval
                        checkWindow = body?.check_window
                        val newPulse = body?.pulse
                        val newBP = body?.blood_pressure
                        val newHealthCheckin = body?.last_health_checkin
                        val missed = body?.missed_notified ?: false
                        isSleeping = body?.sleeping ?: false
                        isEmergency = body?.emergency ?: false

                        // Update health data display values and timestamp
                        if (newHealthCheckin != null && newHealthCheckin != lastHealthCheckTimestamp) {
                            pulse = newPulse
                            bloodPressure = newBP
                            lastHealthCheckTimestamp = newHealthCheckin
                            lastHealthCheckTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).apply {
                                timeZone = java.util.TimeZone.getDefault()
                            }.format(java.util.Date(newHealthCheckin))
                        }

                        statusMessage = when {
                            isEmergency -> "🚨 EMERGENCY!"
                            missed -> "⚠️ Check-in missed!"
                            newCheckin != null && isSleeping -> {
                                val minutes = (System.currentTimeMillis() - newCheckin) / 60000
                                "✓ Last check-in: $minutes min ago - 💤 Sleeping"
                            }
                            newCheckin != null -> {
                                val minutes = (System.currentTimeMillis() - newCheckin) / 60000
                                "✓ Last check-in: $minutes min ago"
                            }
                            isSleeping -> "💤 Sleeping"
                            else -> "No check-ins yet"
                        }
                    } else {
                        statusMessage = "Error: ${response.code()}"
                    }
                } catch (e: Exception) {
                    statusMessage = "Error: ${e.message}"
                }
            }
            kotlinx.coroutines.delay(10000) // refresh every 10 seconds
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watching") },
                actions = {
                    // Add debug info icon
                    IconButton(onClick = {
                        // Log current token for debugging
                        val token = context.getSharedPreferences("fcm", Context.MODE_PRIVATE)
                            .getString("token", null)
                        android.util.Log.d("WatcherScreen", "📱 Current FCM token: ${token?.take(30)}...")

                        // Test local notification
                        NotificationHelper.showNotification(
                            context,
                            "checkin",
                            "🧪 Test Notification",
                            "This is a local test notification",
                            checkerId ?: "TestChecker"
                        )
                    }) {
                        Icon(Icons.Default.Settings, "Test Notification")
                    }

                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Watching: ${checkerId ?: "Not set"}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isEmergency) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Status",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        statusMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isEmergency) FontWeight.Bold else FontWeight.Normal
                    )

                    if (checkInterval != null && checkWindow != null) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Check-in Schedule",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Every ${checkInterval?.toInt()}min with ${checkWindow?.toInt()}min grace period",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!pulse.isNullOrBlank() || !bloodPressure.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Latest Health Data ${lastHealthCheckTime?.let { "($it)" } ?: ""}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        if (!pulse.isNullOrBlank()) {
                            Text(
                                "❤️ Pulse: $pulse bpm",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!bloodPressure.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "🩸 Blood Pressure: $bloodPressure",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Debug info card
            Spacer(Modifier.height(16.dp))
            QuickDiagnosticCard()

        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings") },
            text = {
                Column {
                    Text("Your ID: ${userId ?: "Not set"}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Watching: ${checkerId ?: "Not set"}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("API Key: ${if (apiKey?.isNotBlank() == true) "Set" else "Not set"}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Server: https://api.atempora.de/")
                    Spacer(modifier = Modifier.height(8.dp))

                    // Show FCM token info
                    val token = context.getSharedPreferences("fcm", Context.MODE_PRIVATE)
                        .getString("token", null)
                    Text(
                        "FCM Token: ${if (!token.isNullOrBlank()) "Registered" else "Not registered"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            val currentUserId = userId
                            val currentCheckerId = checkerId
                            val currentApiKey = apiKey

                            if (!currentUserId.isNullOrBlank() && !currentCheckerId.isNullOrBlank() && !currentApiKey.isNullOrBlank()) {
                                RetrofitClient.setApiKey(currentApiKey)
                                val api = RetrofitClient.create()
                                try {
                                    val response = api.unregisterWatcher(
                                        checkerId = currentCheckerId,
                                        watcherId = currentUserId
                                    )
                                    if (response.isSuccessful) {
                                        android.util.Log.d("WatcherScreen", "✅ Watcher unregistered successfully")
                                    } else {
                                        android.util.Log.e("WatcherScreen", "❌ Failed to unregister watcher: ${response.code()}")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("WatcherScreen", "❌ Error unregistering watcher", e)
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("WatcherScreen", "❌ Error during unregister", e)
                        }

                        preferencesManager.clearAll()
                        showSettings = false
                    }
                }) {
                    Text("Reset Setup")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text("Close")
                }
            }
        )
    }
}


@Composable
fun QuickDiagnosticCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var diagnosticResult by remember { mutableStateOf("Click 'Run Diagnostic' to check FCM setup") }
    var isRunning by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "🔍 FCM Diagnostic",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                diagnosticResult,
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            isRunning = true
                            diagnosticResult = runDiagnostic(context)
                            isRunning = false
                        }
                    },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isRunning) "..." else "Run Diagnostic")
                }

                Button(
                    onClick = {
                        // Test local notification
                        NotificationHelper.showNotification(
                            context,
                            "alarm",
                            "🧪 Local Test",
                            "If you see this, local notifications work!",
                            "TestChecker"
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Alarm")
                }
            }

            // Battery optimization button
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)

                if (!isIgnoring) {
                    Button(
                        onClick = { showBatteryDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("⚠️ Fix Battery Optimization (Required!)")
                    }
                }
            }
        }
    }

    // Battery optimization dialog
    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text("⚠️ Critical: Battery Optimization") },
            text = {
                Column {
                    Text(
                        "Battery optimization is preventing notifications from working!\n\n" +
                                "This is the #1 cause of missed notifications.\n\n" +
                                "Please tap 'Open Settings' and select:\n" +
                                "• 'Don't optimize' or\n" +
                                "• 'Unrestricted'\n\n" +
                                "This allows the app to receive critical alarms even when your screen is off."
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                intent.data = Uri.parse("package:${context.packageName}")
                                context.startActivity(intent)
                            }
                        } catch (e: Exception) {
                            Log.e("QuickDiagnostic", "Failed to open battery settings", e)
                        }
                        showBatteryDialog = false
                    }
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) {
                    Text("Later")
                }
            }
        )
    }
}

private suspend fun runDiagnostic(context: Context): String {
    val results = StringBuilder()
    results.appendLine("=== FCM DIAGNOSTIC ===\n")

    var passCount = 0
    var failCount = 0

    try {
        // 1. Check FCM Token
        results.append("1. FCM Token: ")
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isNotBlank()) {
                results.appendLine("✅ PASS (${token.take(20)}...)")
                passCount++
            } else {
                results.appendLine("❌ FAIL (Empty token)")
                failCount++
            }
        } catch (e: Exception) {
            results.appendLine("❌ FAIL (${e.message})")
            failCount++
        }

        // 2. Check Token Storage
        results.append("2. Token Storage: ")
        val storedToken = context.getSharedPreferences("fcm", Context.MODE_PRIVATE)
            .getString("token", null)
        if (!storedToken.isNullOrBlank()) {
            results.appendLine("✅ PASS")
            passCount++
        } else {
            results.appendLine("❌ FAIL (Not stored)")
            failCount++
        }

        // 3. Check Last Message Received
        results.append("3. Messages Received: ")
        val lastMessage = context.getSharedPreferences("fcm_debug", Context.MODE_PRIVATE)
            .getLong("last_message_received", 0)
        if (lastMessage > 0) {
            val minutesAgo = (System.currentTimeMillis() - lastMessage) / 1000 / 60
            results.appendLine("✅ PASS (${minutesAgo}min ago)")
            passCount++
        } else {
            results.appendLine("❌ FAIL (Never received)")
            failCount++
        }

        // 4. Check Battery Optimization
        results.append("4. Battery Optimization: ")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            if (isIgnoring) {
                results.appendLine("✅ PASS (Disabled)")
                passCount++
            } else {
                results.appendLine("❌ FAIL (Enabled - THIS IS THE PROBLEM!)")
                failCount++
            }
        } else {
            results.appendLine("✅ PASS (Not applicable)")
            passCount++
        }

        // 5. Check Notification Channels
        results.append("5. Notification Channels: ")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
            val channels = notificationManager.notificationChannels
            if (channels.isNotEmpty()) {
                results.appendLine("✅ PASS (${channels.size} channels)")
                passCount++
            } else {
                results.appendLine("❌ FAIL (No channels)")
                failCount++
            }
        } else {
            results.appendLine("✅ PASS (Not applicable)")
            passCount++
        }

        // 6. Check User Setup
        results.append("6. User Configuration: ")
        val prefs = PreferencesManager(context)
        val userId = prefs.userId.firstOrNull()
        val checkerId = prefs.checkerId.firstOrNull()
        val apiKey = prefs.apiKey.firstOrNull()

        if (!userId.isNullOrBlank() && !checkerId.isNullOrBlank() && !apiKey.isNullOrBlank()) {
            results.appendLine("✅ PASS")
            passCount++
        } else {
            results.appendLine("❌ FAIL (Missing user/checker/apiKey)")
            failCount++
        }

        results.appendLine("\n=== SUMMARY ===")
        results.appendLine("✅ Passed: $passCount")
        results.appendLine("❌ Failed: $failCount")

        if (failCount == 0) {
            results.appendLine("\n🎉 All checks passed!")
            results.appendLine("If notifications still don't work:")
            results.appendLine("1. Check backend logs")
            results.appendLine("2. Verify token in database")
            results.appendLine("3. Test with Firebase Console")
        } else {
            results.appendLine("\n⚠️ Issues found!")
            results.appendLine("Fix the failed checks above.")
        }

    } catch (e: Exception) {
        results.appendLine("\n❌ Diagnostic failed: ${e.message}")
        Log.e("QuickDiagnostic", "Diagnostic error", e)
    }

    return results.toString()
}
