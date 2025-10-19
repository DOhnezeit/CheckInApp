package com.dohnezeit.checkinapp

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(preferencesManager: PreferencesManager) {
    val context = LocalContext.current
    var selectedRole by remember { mutableStateOf<String?>(null) }
    var userId by remember { mutableStateOf("") }
    var checkerId by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var checkInterval by remember { mutableStateOf("1") }
    var checkWindow by remember { mutableStateOf("0.5") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup Check-In App") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Welcome! Let's set up your app.",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                "First, choose your role:",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedRole == "checker",
                    onClick = { selectedRole = "checker" },
                    label = { Text("I need to check in") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedRole == "watcher",
                    onClick = { selectedRole = "watcher" },
                    label = { Text("I'm watching someone") },
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider()

            OutlinedTextField(
                value = userId,
                onValueChange = { userId = it },
                label = { Text("Your User ID") },
                placeholder = { Text("e.g., john_doe") },
                modifier = Modifier.fillMaxWidth()
            )

            if (selectedRole == "watcher") {
                OutlinedTextField(
                    value = checkerId,
                    onValueChange = { checkerId = it },
                    label = { Text("Checker's User ID") },
                    placeholder = { Text("ID of person you're watching") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (selectedRole == "checker") {
                OutlinedTextField(
                    value = checkInterval,
                    onValueChange = { checkInterval = it },
                    label = { Text("Check-in Interval (minutes)") },
                    placeholder = { Text("1") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = checkWindow,
                    onValueChange = { checkWindow = it },
                    label = { Text("Grace Period (minutes)") },
                    placeholder = { Text("0.5") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "You'll be reminded every ${checkInterval}min with ${checkWindow}min grace period",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                placeholder = { Text("Your API key") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    // Use a scope that won't be cancelled when the composable leaves
                    CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                        Log.d("SetupScreen", "Button clicked, starting setup...")
                        val role = selectedRole ?: run {
                            Log.e("SetupScreen", "No role selected")
                            return@launch
                        }

                        Log.d("SetupScreen", "Role: $role, UserId: $userId, API Key: ${apiKey.take(5)}...")

                        try {
                            // Save preferences first and WAIT for them to complete
                            Log.d("SetupScreen", "Saving preferences...")
                            preferencesManager.saveUserRole(role)
                            preferencesManager.saveUserId(userId)
                            preferencesManager.saveApiKey(apiKey)

                            if (role == "watcher") {
                                preferencesManager.saveCheckerId(checkerId)
                            }

                            if (role == "checker") {
                                preferencesManager.saveCheckInterval(
                                    checkInterval.toFloatOrNull() ?: 1f
                                )
                                preferencesManager.saveCheckWindow(
                                    checkWindow.toFloatOrNull() ?: 0.5f
                                )
                            }

                            Log.d("SetupScreen", "Preferences saved, getting FCM token...")

                            // Now register the FCM token immediately
                            val token = FirebaseMessaging.getInstance().token.await()
                            Log.d("SetupScreen", "Got FCM token: ${token.take(20)}...")

                            // Store in SharedPreferences
                            context.getSharedPreferences("fcm", Context.MODE_PRIVATE)
                                .edit()
                                .putString("token", token)
                                .apply()

                            Log.d("SetupScreen", "Token stored in SharedPreferences")

                            RetrofitClient.setApiKey(apiKey)
                            val api = RetrofitClient.create()

                            Log.d("SetupScreen", "RetrofitClient created, registering token...")

                            // Register as checker
                            if (role == "checker") {
                                Log.d("SetupScreen", "Registering as checker: $userId")
                                val response = api.registerChecker(
                                    request = RegisterCheckerRequest(
                                        checker_id = userId,
                                        checker_token = token
                                    )
                                )
                                Log.d("SetupScreen", "Checker response: ${response.code()} - ${response.message()}")
                                if (response.isSuccessful) {
                                    Log.d("SetupScreen", "✅ Checker token registered")
                                } else {
                                    Log.e("SetupScreen", "❌ Failed: ${response.code()} - ${response.errorBody()?.string()}")
                                }
                            }

                            // Register as watcher
                            if (role == "watcher") {
                                Log.d("SetupScreen", "Registering as watcher: $userId watching $checkerId")
                                val response = api.registerWatcher(
                                    request = RegisterWatcherRequest(
                                        checker_id = checkerId,
                                        watcher_id = userId,
                                        watcher_token = token
                                    )
                                )
                                Log.d("SetupScreen", "Watcher response: ${response.code()} - ${response.message()}")
                                if (response.isSuccessful) {
                                    Log.d("SetupScreen", "✅ Watcher token registered")
                                } else {
                                    Log.e("SetupScreen", "❌ Failed: ${response.code()} - ${response.errorBody()?.string()}")
                                }
                            }

                            Log.d("SetupScreen", "Setup complete!")
                        } catch (e: Exception) {
                            Log.e("SetupScreen", "❌ Setup failed: ${e.javaClass.simpleName} - ${e.message}", e)
                            e.printStackTrace()
                        }
                    }
                },
                enabled = selectedRole != null &&
                        userId.isNotBlank() &&
                        apiKey.isNotBlank() &&
                        (selectedRole != "watcher" || checkerId.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save and Continue")
            }
        }
    }
}