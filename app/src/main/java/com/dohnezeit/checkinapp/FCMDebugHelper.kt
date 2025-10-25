package com.dohnezeit.checkinapp

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun FCMDebugCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var debugInfo by remember { mutableStateOf("Loading...") }
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        debugInfo = getFCMDebugInfo(context)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "🔍 FCM Debug Info",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                debugInfo,
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            isRefreshing = true
                            debugInfo = getFCMDebugInfo(context)
                            isRefreshing = false
                        }
                    },
                    enabled = !isRefreshing,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh")
                }

                Button(
                    onClick = {
                        // Test notification
                        NotificationHelper.showNotification(
                            context,
                            "checkin",
                            "Test Notification",
                            "This is a test notification",
                            "TestUser"
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Notif")
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        try {
                            isRefreshing = true
                            // Force refresh token
                            FirebaseMessaging.getInstance().deleteToken().await()
                            kotlinx.coroutines.delay(1000)
                            val newToken = FirebaseMessaging.getInstance().token.await()
                            Log.d("FCMDebug", "New token: $newToken")
                            debugInfo = getFCMDebugInfo(context)
                            isRefreshing = false
                        } catch (e: Exception) {
                            Log.e("FCMDebug", "Failed to refresh token", e)
                            debugInfo = "Error: ${e.message}"
                            isRefreshing = false
                        }
                    }
                },
                enabled = !isRefreshing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Force Refresh FCM Token")
            }
        }
    }
}

private suspend fun getFCMDebugInfo(context: Context): String {
    val sb = StringBuilder()

    try {
        // FCM Token
        val token = FirebaseMessaging.getInstance().token.await()
        sb.appendLine("📱 Token: ${token.take(30)}...")

        // Token timestamp
        val tokenTimestamp = context.getSharedPreferences("fcm", Context.MODE_PRIVATE)
            .getLong("token_timestamp", 0)
        if (tokenTimestamp > 0) {
            val elapsed = (System.currentTimeMillis() - tokenTimestamp) / 1000 / 60
            sb.appendLine("⏱️ Token age: ${elapsed}min ago")
        }

        // Last message received
        val prefs = context.getSharedPreferences("fcm_debug", Context.MODE_PRIVATE)
        val lastReceived = prefs.getLong("last_message_received", 0)
        if (lastReceived > 0) {
            val elapsed = (System.currentTimeMillis() - lastReceived) / 1000 / 60
            sb.appendLine("📨 Last message: ${elapsed}min ago")
            sb.appendLine("   Type: ${prefs.getString("last_message_type", "unknown")}")
        } else {
            sb.appendLine("📨 No messages received yet")
        }

        // Battery optimization
        val isIgnoring = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        sb.appendLine("🔋 Battery opt: ${if (isIgnoring) "✅ Disabled" else "⚠️ Enabled"}")

        // Notification channels
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
            val channels = notificationManager.notificationChannels
            sb.appendLine("🔔 Channels: ${channels.size}")
            channels.forEach { channel ->
                sb.appendLine("   ${channel.name}: ${channel.importance}")
            }
        }

    } catch (e: Exception) {
        sb.appendLine("❌ Error: ${e.message}")
        Log.e("FCMDebug", "Error getting debug info", e)
    }

    return sb.toString()
}