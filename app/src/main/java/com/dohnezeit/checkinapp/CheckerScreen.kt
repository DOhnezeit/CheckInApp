package com.dohnezeit.checkinapp

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckerScreen(preferencesManager: PreferencesManager) {
    val scope = rememberCoroutineScope()

    val userId by preferencesManager.userId.collectAsStateWithLifecycle(initialValue = "")
    val apiKey by preferencesManager.apiKey.collectAsStateWithLifecycle(initialValue = "")
    val lastCheckinPref by preferencesManager.lastCheckin.collectAsStateWithLifecycle(initialValue = null)
    val savedInterval by preferencesManager.checkInterval.collectAsStateWithLifecycle(initialValue = 1f)
    val savedWindow by preferencesManager.checkWindow.collectAsStateWithLifecycle(initialValue = 0.5f)
    val userRole by preferencesManager.userRole.collectAsStateWithLifecycle(initialValue = null)

    var checkInterval by remember { mutableFloatStateOf(savedInterval) }
    var checkWindow by remember { mutableFloatStateOf(savedWindow) }
    var isChecking by remember { mutableStateOf(false) }
    var isSleeping by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready to check in") }
    var showSettings by remember { mutableStateOf(false) }

    // Update local state when saved values change
    LaunchedEffect(savedInterval, savedWindow) {
        checkInterval = savedInterval
        checkWindow = savedWindow
    }

    // Set API key when screen loads
    LaunchedEffect(apiKey) {
        val currentApiKey = apiKey
        if (!currentApiKey.isNullOrBlank()) {
            RetrofitClient.setApiKey(currentApiKey)
        }
    }

    // First check-in / fetch sleeping state on app start
    LaunchedEffect(userId, apiKey) {
        val currentUserId = userId
        val currentApiKey = apiKey
        if (!currentUserId.isNullOrEmpty() && !currentApiKey.isNullOrBlank()) {
            RetrofitClient.setApiKey(currentApiKey)
            isChecking = true
            try {
                val api = RetrofitClient.create()

                // Fetch status from backend
                val statusResponse = api.getStatus(currentUserId)
                if (statusResponse.isSuccessful) {
                    val status = statusResponse.body()
                    status?.let {
                        isSleeping = it.sleeping == true // update local state
                        if (isSleeping) {
                            statusMessage = "\uD83D\uDCA4 You're currently sleeping"
                        }
                    }
                }

                // Optionally do a first check-in only if not sleeping
                if (!isSleeping && lastCheckinPref == null) {
                    val checkinResponse = api.checkin(
                        request = CheckinRequest(
                            checker_id = currentUserId,
                            timestamp = System.currentTimeMillis(),
                            check_interval = checkInterval,
                            check_window = checkWindow
                        )
                    )
                    if (checkinResponse.isSuccessful) {
                        preferencesManager.saveLastCheckin(System.currentTimeMillis())
                        isSleeping = false
                        statusMessage = "✓ First check-in done!"
                    } else {
                        statusMessage = "Failed: ${checkinResponse.code()} - ${checkinResponse.message()}"
                    }
                }

            } catch (e: Exception) {
                statusMessage = "Error: ${e.message}"
            } finally {
                isChecking = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check In") },
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
                "Hello, ${userId ?: "User"}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                statusMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Debug: Show current role
            Text(
                "Current role: ${userRole ?: "null"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            // Check-in interval settings: only show for checkers
            if (userRole == "checker") {
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
                            "Check-in Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = checkInterval.toString(),
                            onValueChange = {
                                val newValue = it.toFloatOrNull() ?: 1f
                                checkInterval = newValue
                                scope.launch {
                                    preferencesManager.saveCheckInterval(newValue)
                                }
                            },
                            label = { Text("Interval (minutes)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = checkWindow.toString(),
                            onValueChange = {
                                val newValue = it.toFloatOrNull() ?: 0.5f
                                checkWindow = newValue
                                scope.launch {
                                    preferencesManager.saveCheckWindow(newValue)
                                }
                            },
                            label = { Text("Grace period (minutes)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            "You'll be reminded every ${checkInterval}min\nwith ${checkWindow}min grace period",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Big check-in button
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        val currentApiKey = apiKey
                        val currentUserId = userId

                        if (currentApiKey.isNullOrBlank()) {
                            statusMessage = "Error: API key not set"
                            return@launch
                        }

                        if (currentUserId.isNullOrBlank()) {
                            statusMessage = "Error: User ID not set"
                            return@launch
                        }

                        RetrofitClient.setApiKey(currentApiKey)
                        isChecking = true
                        try {
                            val api = RetrofitClient.create()
                            val response = api.checkin(
                                request = CheckinRequest(
                                    checker_id = currentUserId,
                                    timestamp = System.currentTimeMillis(),
                                    check_interval = checkInterval,
                                    check_window = checkWindow
                                )
                            )

                            if (response.isSuccessful) {
                                val now = System.currentTimeMillis()
                                preferencesManager.saveLastCheckin(now)
                                statusMessage = "✓ Checked in successfully!"
                                if (isSleeping) {
                                    isSleeping = false
                                    statusMessage = "✓ Checked in, awake now!"
                                }
                            } else {
                                statusMessage = "Failed: ${response.code()} - ${response.message()}"
                            }
                        } catch (e: Exception) {
                            statusMessage = "Error: ${e.message}"
                        } finally {
                            isChecking = false
                        }
                    }
                },
                enabled = !isChecking && apiKey?.isNotBlank() == true && userId?.isNotBlank() == true,
                modifier = Modifier
                    .size(200.dp)
                    .padding(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isChecking) "Checking..." else "CHECK IN",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Sleep button
            // Sleep button
            Button(
                onClick = {
                    scope.launch {
                        val currentApiKey = apiKey
                        val currentUserId = userId

                        if (currentApiKey.isNullOrBlank() || currentUserId.isNullOrBlank()) {
                            statusMessage = "Error: missing API key or user ID"
                            return@launch
                        }

                        RetrofitClient.setApiKey(currentApiKey)
                        isChecking = true
                        try {
                            val api = RetrofitClient.create()
                            val response = api.sleep(SleepRequest(checker_id = currentUserId))
                            if (response.isSuccessful) {
                                isSleeping = true
                                statusMessage = "😴 You are currently sleeping"
                                preferencesManager.saveLastCheckin(System.currentTimeMillis())
                            } else {
                                statusMessage = "Failed to set sleep: ${response.code()} - ${response.message()}"
                            }
                        } catch (e: Exception) {
                            statusMessage = "Error: ${e.message}"
                        } finally {
                            isChecking = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                enabled = !isChecking && !userId.isNullOrBlank() && !apiKey.isNullOrBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSleeping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isSleeping) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isSleeping) "You are currently sleeping" else "Go to Sleep")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Show last check-in
            lastCheckinPref?.let { time ->
                val formatted = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
                Text(
                    "Last check-in: $formatted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    Text("User ID: ${userId ?: "Not set"}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("API Key: ${if (apiKey?.isNotBlank() == true) "Set" else "Not set"}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Server: https://api.atempora.de/")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        // Delete the token from the server
                        try {
                            val currentUserId = userId
                            val currentApiKey = apiKey

                            if (!currentUserId.isNullOrBlank() && !currentApiKey.isNullOrBlank()) {
                                RetrofitClient.setApiKey(currentApiKey)
                                val api = RetrofitClient.create()

                                // Delete from checker_tokens table
                                try {
                                    api.unregisterChecker(currentUserId)
                                    Log.d("CheckerScreen", "Checker token unregistered")
                                } catch (e: Exception) {
                                    Log.e("CheckerScreen", "Failed to unregister", e)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("CheckerScreen", "Error during unregister", e)
                        }

                        // Then clear preferences
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