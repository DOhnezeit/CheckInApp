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

    // Set API key and register watcher token on first load
    LaunchedEffect(userId, checkerId, apiKey) {
        val currentApiKey = apiKey
        val currentUserId = userId
        val currentCheckerId = checkerId

        if (!currentApiKey.isNullOrBlank()) {
            RetrofitClient.setApiKey(currentApiKey)

            // Register watcher's FCM token
            if (!currentUserId.isNullOrBlank() && !currentCheckerId.isNullOrBlank()) {
                try {
                    val token = context.getSharedPreferences("fcm", Context.MODE_PRIVATE)
                        .getString("token", null)

                    if (!token.isNullOrBlank()) {
                        val api = RetrofitClient.create()
                        val response = api.registerWatcher(
                            request = RegisterWatcherRequest(
                                checker_id = currentCheckerId,
                                watcher_id = currentUserId,
                                watcher_token = token
                            )
                        )
                        if (response.isSuccessful) {
                            android.util.Log.d("WatcherScreen", "Watcher registered successfully")
                        } else {
                            android.util.Log.e("WatcherScreen", "Failed to register watcher: ${response.code()}")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WatcherScreen", "Error registering watcher", e)
                }
            }
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
                        lastCheckin = body?.last_checkin
                        checkInterval = body?.check_interval
                        checkWindow = body?.check_window
                        val missed = body?.missed_notified ?: false

                        statusMessage = when {
                            missed -> "⚠️ Check-in missed!"
                            lastCheckin != null -> {
                                val minutes = (System.currentTimeMillis() - lastCheckin!!) / 60000
                                "✓ Last check-in: $minutes min ago"
                            }
                            else -> "No check-ins yet"
                        }
                    } else {
                        statusMessage = "Error: ${response.code()}"
                    }
                } catch (e: Exception) {
                    statusMessage = "Error: ${e.message}"
                }
            }
            kotlinx.coroutines.delay(10000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watching") },
                actions = {
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
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                        style = MaterialTheme.typography.bodyLarge
                    )

                    // Show check-in schedule if available
                    if (checkInterval != null && checkWindow != null) {
                        Spacer(Modifier.height(12.dp))
                        Divider()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Check-in Schedule",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Every ${checkInterval}min with ${checkWindow}min grace period",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Settings dialog
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
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
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
