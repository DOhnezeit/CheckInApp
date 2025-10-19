package com.dohnezeit.checkinapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dohnezeit.checkinapp.PreferencesManager

@Composable
fun MainScreen(preferencesManager: PreferencesManager) {
    val context = LocalContext.current
    val userRole by preferencesManager.userRole.collectAsStateWithLifecycle(initialValue = null)

    when (userRole) {
        null -> SetupScreen(preferencesManager)
        "checker" -> CheckerScreen(preferencesManager)
        "watcher" -> WatcherScreen(preferencesManager, context)
    }
}