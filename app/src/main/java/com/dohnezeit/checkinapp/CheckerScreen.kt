package com.dohnezeit.checkinapp

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckerScreen(preferencesManager: PreferencesManager) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val userId by preferencesManager.userId.collectAsStateWithLifecycle(initialValue = "")
    val apiKey by preferencesManager.apiKey.collectAsStateWithLifecycle(initialValue = "")
    val lastCheckinPref by preferencesManager.lastCheckin.collectAsStateWithLifecycle(initialValue = null)
    val savedInterval by preferencesManager.checkInterval.collectAsStateWithLifecycle(initialValue = 60.0f)
    val savedWindow by preferencesManager.checkWindow.collectAsStateWithLifecycle(initialValue = 15.0f)
    val userRole by preferencesManager.userRole.collectAsStateWithLifecycle(initialValue = null)

    var checkInterval by remember { mutableFloatStateOf(savedInterval) }
    var checkWindow by remember { mutableFloatStateOf(savedWindow) }
    var checkIntervalText by remember { mutableStateOf(savedInterval.toInt().toString()) }
    var checkWindowText by remember { mutableStateOf(savedWindow.toInt().toString()) }
    var isChecking by remember { mutableStateOf(false) }
    var isSleeping by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready to check in") }
    var showSettings by remember { mutableStateOf(false) }
    var missedNotified by remember { mutableStateOf(false) }
    var nextCheckinTime by remember { mutableStateOf<Long?>(null) }
    var graceEndTime by remember { mutableStateOf<Long?>(null) }
    var pulse by remember { mutableStateOf("") }
    var bloodPressure by remember { mutableStateOf("") }

    // Emergency button state
    var emergencyTapCount by remember { mutableIntStateOf(0) }
    var showEmergencyConfirm by remember { mutableStateOf(false) }
    var isEmergencyActive by remember { mutableStateOf(false) }

    // Update local state when saved values change
    LaunchedEffect(savedInterval, savedWindow) {
        checkInterval = savedInterval
        checkWindow = savedWindow
        checkIntervalText = savedInterval.toInt().toString()
        checkWindowText = savedWindow.toInt().toString()
    }

    // Set API key when screen loads
    LaunchedEffect(apiKey) {
        val currentApiKey = apiKey
        if (!currentApiKey.isNullOrBlank()) {
            RetrofitClient.setApiKey(currentApiKey)
        }
    }

    // Fetch status from backend
    LaunchedEffect(userId, apiKey) {
        val currentUserId = userId
        val currentApiKey = apiKey
        if (!currentUserId.isNullOrBlank() && !currentApiKey.isNullOrBlank()) {
            RetrofitClient.setApiKey(currentApiKey)
            while (true) {
                try {
                    val api = RetrofitClient.create()
                    val response = api.getStatus(currentUserId)
                    if (response.isSuccessful) {
                        val status: StatusResponse? = response.body()
                        status?.let {
                            isSleeping = it.sleeping == true
                            missedNotified = it.missed_notified ?: false
                            isEmergencyActive = it.emergency ?: false

                            // Calculate next check-in times
                            it.last_checkin?.let { lastCheckin ->
                                if (!isSleeping && lastCheckin > 0) {
                                    val intervalMs = ((it.check_interval ?: checkInterval) * 60_000).toLong()
                                    val windowMs = ((it.check_window ?: checkWindow) * 60_000).toLong()
                                    nextCheckinTime = lastCheckin + intervalMs
                                    graceEndTime = lastCheckin + intervalMs + windowMs
                                } else {
                                    nextCheckinTime = null
                                    graceEndTime = null
                                }
                            }

                            // update local last check-in to match backend
                            it.last_checkin?.let { backendCheckin ->
                                val currentLocal = lastCheckinPref ?: 0L
                                if (backendCheckin > currentLocal) {
                                    preferencesManager.saveLastCheckin(backendCheckin)
                                }
                            }

                            statusMessage = when {
                                isEmergencyActive -> "🚨 EMERGENCY ACTIVE"
                                isSleeping -> "💤 You're currently sleeping"
                                missedNotified -> "❌ Missed check-in!"
                                else -> "Ready to check in"
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CheckerScreen", "Failed to fetch status", e)
                }
                delay(5000L)
            }
        }
    }

    // Reset emergency tap count after 2 seconds
    LaunchedEffect(emergencyTapCount) {
        if (emergencyTapCount > 0) {
            delay(2000L)
            emergencyTapCount = 0
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check In") },
                actions = {
                    // Emergency button
                    IconButton(
                        onClick = {
                            emergencyTapCount++
                            if (emergencyTapCount >= 2) {
                                showEmergencyConfirm = true
                                emergencyTapCount = 0
                            }
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = if (isEmergencyActive) Color.White else Color(0xFFD32F2F)
                        )
                    ) {
                        Badge(
                            containerColor = if (isEmergencyActive) Color(0xFFD32F2F) else Color.Transparent,
                            contentColor = Color.White
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Emergency",
                                modifier = Modifier.size(28.dp)
                            )
                            if (emergencyTapCount > 0) {
                                Text(
                                    text = emergencyTapCount.toString(),
                                    modifier = Modifier.padding(start = 4.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
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
                "Hello, ${userId ?: "User"}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                statusMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isEmergencyActive) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (isEmergencyActive) FontWeight.Bold else FontWeight.Normal
            )

            Spacer(modifier = Modifier.height(24.dp))

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
                            value = checkIntervalText,
                            onValueChange = { newText ->
                                checkIntervalText = newText
                                val newValue = newText.toFloatOrNull()
                                if (newValue != null && newValue > 0) {
                                    checkInterval = newValue
                                    scope.launch {
                                        preferencesManager.saveCheckInterval(newValue)
                                    }
                                }
                            },
                            label = { Text("Interval (minutes)") },
                            placeholder = { Text("60") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = checkWindowText,
                            onValueChange = { newText ->
                                checkWindowText = newText
                                val newValue = newText.toFloatOrNull()
                                if (newValue != null && newValue > 0) {
                                    checkWindow = newValue
                                    scope.launch {
                                        preferencesManager.saveCheckWindow(newValue)
                                    }
                                }
                            },
                            label = { Text("Grace period (minutes)") },
                            placeholder = { Text("15") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = pulse,
                            onValueChange = { pulse = it },
                            label = { Text("Pulse (optional)") },
                            placeholder = { Text("e.g., 72") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = bloodPressure,
                            onValueChange = { bloodPressure = it },
                            label = { Text("Blood Pressure (optional)") },
                            placeholder = { Text("e.g., 120/80") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        val displayInterval = if (checkIntervalText.isBlank()) 60f else checkInterval
                        val displayWindow = if (checkWindowText.isBlank()) 15f else checkWindow

                        Text(
                            "You'll be reminded every ${displayInterval.toInt()}min\nwith ${displayWindow.toInt()}min grace period",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Big check-in button with sleep button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Big check-in button
                FilledTonalButton(
                    onClick = {
                        Log.d("TimeCheck", "Device epoch: ${System.currentTimeMillis() / 1000}")
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

                            // Handle blank values - reset to defaults
                            val finalInterval = if (checkIntervalText.isBlank()) {
                                60f.also {
                                    checkInterval = it
                                    checkIntervalText = "60"
                                    scope.launch { preferencesManager.saveCheckInterval(it) }
                                }
                            } else {
                                checkInterval
                            }

                            val finalWindow = if (checkWindowText.isBlank()) {
                                15f.also {
                                    checkWindow = it
                                    checkWindowText = "15"
                                    scope.launch { preferencesManager.saveCheckWindow(it) }
                                }
                            } else {
                                checkWindow
                            }

                            RetrofitClient.setApiKey(currentApiKey)
                            isChecking = true
                            try {
                                val api = RetrofitClient.create()

                                Log.d("CheckIn", "Sending check-in with interval=$finalInterval, window=$finalWindow")

                                val response = api.checkin(
                                    request = CheckinRequest(
                                        checker_id = currentUserId,
                                        timestamp = System.currentTimeMillis(),
                                        check_interval = finalInterval,
                                        check_window = finalWindow,
                                        pulse = pulse.takeIf { it.isNotBlank() },
                                        blood_pressure = bloodPressure.takeIf { it.isNotBlank() }
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
                                    // Only clear health data after successful check-in
                                    pulse = ""
                                    bloodPressure = ""
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

                Spacer(modifier = Modifier.width(16.dp))

                // Small round sleep button
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            val currentApiKey = apiKey
                            val currentUserId = userId

                            NotificationHelper.clearAllNotifications(context)

                            if (currentApiKey.isNullOrBlank() || currentUserId.isNullOrBlank()) {
                                statusMessage = "Error: missing API key or user ID"
                                return@launch
                            }

                            // Handle blank values - reset to defaults
                            val finalInterval = if (checkIntervalText.isBlank()) {
                                60f.also {
                                    checkInterval = it
                                    checkIntervalText = "60"
                                    scope.launch { preferencesManager.saveCheckInterval(it) }
                                }
                            } else {
                                checkInterval
                            }

                            val finalWindow = if (checkWindowText.isBlank()) {
                                15f.also {
                                    checkWindow = it
                                    checkWindowText = "15"
                                    scope.launch { preferencesManager.saveCheckWindow(it) }
                                }
                            } else {
                                checkWindow
                            }

                            RetrofitClient.setApiKey(currentApiKey)
                            isChecking = true
                            try {
                                val api = RetrofitClient.create()

                                // First send a check-in with health data if any is present
                                if (pulse.isNotBlank() || bloodPressure.isNotBlank()) {
                                    Log.d("Sleep", "Sending final check-in with health data before sleep")
                                    api.checkin(
                                        request = CheckinRequest(
                                            checker_id = currentUserId,
                                            timestamp = System.currentTimeMillis(),
                                            check_interval = finalInterval,
                                            check_window = finalWindow,
                                            pulse = pulse.takeIf { it.isNotBlank() },
                                            blood_pressure = bloodPressure.takeIf { it.isNotBlank() }
                                        )
                                    )
                                }

                                // Then go to sleep
                                val response = api.sleep(SleepRequest(checker_id = currentUserId))
                                if (response.isSuccessful) {
                                    isSleeping = true
                                    statusMessage = "💤 You're currently sleeping"
                                    preferencesManager.saveLastCheckin(System.currentTimeMillis())
                                    // Clear health data after successfully going to sleep
                                    pulse = ""
                                    bloodPressure = ""
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
                    modifier = Modifier.size(72.dp),
                    containerColor = if (isSleeping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isSleeping) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        "💤",
                        fontSize = 32.sp
                    )
                }
            }
        }
    }

    // Emergency confirmation dialog
    if (showEmergencyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmergencyConfirm = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFD32F2F),
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    "🚨 EMERGENCY ALERT",
                    color = Color(0xFFD32F2F),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    if (isEmergencyActive) {
                        Text(
                            "An emergency is already active. Do you want to cancel it?",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    } else {
                        Text(
                            "This will send an immediate emergency alarm to all your watchers.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "The alarm will repeat every 5 seconds until acknowledged by a watcher.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val currentApiKey = apiKey
                            val currentUserId = userId

                            if (currentApiKey.isNullOrBlank() || currentUserId.isNullOrBlank()) {
                                statusMessage = "Error: missing API key or user ID"
                                showEmergencyConfirm = false
                                return@launch
                            }

                            RetrofitClient.setApiKey(currentApiKey)
                            try {
                                val api = RetrofitClient.create()

                                if (isEmergencyActive) {
                                    // Acknowledge/cancel the emergency
                                    val response = api.acknowledgeAlarm(AcknowledgeAlarmRequest(currentUserId))
                                    if (response.isSuccessful) {
                                        isEmergencyActive = false
                                        statusMessage = "Emergency cancelled"
                                        Log.d("Emergency", "Emergency cancelled for $currentUserId")
                                    } else {
                                        statusMessage = "Failed to cancel emergency"
                                    }
                                } else {
                                    // Trigger emergency
                                    val response = api.emergency(EmergencyRequest(checker_id = currentUserId))
                                    if (response.isSuccessful) {
                                        isEmergencyActive = true
                                        statusMessage = "🚨 EMERGENCY ACTIVE"
                                        Log.d("Emergency", "Emergency triggered for $currentUserId")
                                    } else {
                                        statusMessage = "Failed to trigger emergency"
                                    }
                                }
                            } catch (e: Exception) {
                                statusMessage = "Error: ${e.message}"
                                Log.e("Emergency", "Failed to handle emergency", e)
                            }
                        }
                        showEmergencyConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F)
                    )
                ) {
                    Text(if (isEmergencyActive) "CANCEL EMERGENCY" else "TRIGGER EMERGENCY")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
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