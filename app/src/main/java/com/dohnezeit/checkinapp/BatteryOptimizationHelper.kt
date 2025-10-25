package com.dohnezeit.checkinapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

object BatteryOptimizationHelper {
    private const val TAG = "BatteryOptimization"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            Log.d(TAG, "Is ignoring battery optimizations: $isIgnoring")
            isIgnoring
        } else {
            true // Not applicable for older versions
        }
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
                Log.d(TAG, "Requested battery optimization exemption")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request battery optimization exemption", e)
                // Fallback: open battery optimization settings
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(intent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to open battery optimization settings", e2)
                }
            }
        }
    }
}

@Composable
fun BatteryOptimizationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable Background Notifications") },
        text = {
            Text(
                "To receive reliable notifications when the app is in the background, " +
                        "please disable battery optimization for this app.\n\n" +
                        "This ensures you'll receive important check-in alerts even when the screen is off."
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later")
            }
        }
    )
}

@Composable
fun CheckAndRequestBatteryOptimization() {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var hasChecked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasChecked) {
            hasChecked = true
            val isIgnoring = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
            if (!isIgnoring) {
                // Check if we've already asked (don't annoy user)
                val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                val hasAsked = prefs.getBoolean("battery_opt_asked", false)

                if (!hasAsked) {
                    showDialog = true
                    prefs.edit().putBoolean("battery_opt_asked", true).apply()
                }
            }
        }
    }

    if (showDialog) {
        BatteryOptimizationDialog(
            onDismiss = { showDialog = false },
            onConfirm = {
                BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
                showDialog = false
            }
        )
    }
}