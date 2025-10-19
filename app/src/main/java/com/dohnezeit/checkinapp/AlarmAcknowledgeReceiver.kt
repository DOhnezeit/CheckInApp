package com.dohnezeit.checkinapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.firstOrNull

class AlarmAcknowledgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val checkerId = intent.getStringExtra("checker_id") ?: return

        Log.d(TAG, "Alarm acknowledge button pressed for $checkerId")

        // Clear the notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(1001) // ALARM_NOTIFICATION_ID

        // Stop the alarm sound (this will be handled by the service)
        // Send acknowledgment to server
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = PreferencesManager(context)
                val apiKey = prefs.apiKey.firstOrNull()

                if (!apiKey.isNullOrEmpty()) {
                    RetrofitClient.setApiKey(apiKey)
                    val api = RetrofitClient.create()
                    api.acknowledgeAlarm(AcknowledgeAlarmRequest(checkerId))
                    Log.d(TAG, "Alarm acknowledged successfully for $checkerId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acknowledge alarm: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "AlarmAckReceiver"
    }
}