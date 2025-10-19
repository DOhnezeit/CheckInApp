package com.dohnezeit.checkinapp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesManager(private val context: Context) {

    companion object {
        val USER_ID = stringPreferencesKey("user_id")
        val CHECKER_ID = stringPreferencesKey("checker_id")
        val API_KEY = stringPreferencesKey("api_key")
        val SERVER_URL = stringPreferencesKey("server_url")
        val LAST_CHECKIN = longPreferencesKey("last_checkin")
        val CHECK_INTERVAL = floatPreferencesKey("check_interval")
        val CHECK_WINDOW = floatPreferencesKey("check_window")
        val USER_ROLE = stringPreferencesKey("user_role")
    }

    val userId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[USER_ID]
    }

    val checkerId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[CHECKER_ID]
    }

    val apiKey: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[API_KEY]
    }

    val lastCheckin: Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[LAST_CHECKIN]
    }

    val checkInterval: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[CHECK_INTERVAL] ?: 1f
    }

    val checkWindow: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[CHECK_WINDOW] ?: 0.5f
    }

    val userRole: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[USER_ROLE]
    }

    suspend fun saveUserId(id: String) {
        context.dataStore.edit { prefs ->
            prefs[USER_ID] = id
        }
    }

    suspend fun saveCheckerId(id: String) {
        context.dataStore.edit { prefs ->
            prefs[CHECKER_ID] = id
        }
    }

    suspend fun saveApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[API_KEY] = key
        }
    }

    suspend fun saveLastCheckin(timestamp: Long) {
        context.dataStore.edit { prefs ->
            prefs[LAST_CHECKIN] = timestamp
        }
    }

    suspend fun saveCheckInterval(interval: Float) {
        context.dataStore.edit { prefs ->
            prefs[CHECK_INTERVAL] = interval
        }
    }

    suspend fun saveCheckWindow(window: Float) {
        context.dataStore.edit { prefs ->
            prefs[CHECK_WINDOW] = window
        }
    }

    suspend fun saveUserRole(role: String) {
        context.dataStore.edit { prefs ->
            prefs[USER_ROLE] = role
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}