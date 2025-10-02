package com.dohnezeit.checkinapp

import android.util.Log
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage


class MyFirebaseMessagingService : FirebaseMessagingService()
{
    override fun onMessageReceived(message: RemoteMessage)
    {
        Log.d(TAG, "=== MESSAGE RECEIVED ===")
        Log.d(TAG, "From: ${message.from}")
        Log.d(TAG, "Message ID: ${message.messageId}")

        if (message.data.isNotEmpty())
        {
            Log.d(TAG, "Message data payload: ${message.data}")
        }

        message.notification?.let {
            Log.d(TAG, "Notification Title: ${it.title}")
            Log.d(TAG, "Notification Body: ${it.body}")
        }
    }

    override fun onNewToken(token: String)
    {
        Log.d(TAG, "=== NEW TOKEN ===")
        Log.d(TAG, "Refreshed token: $token")
        sendTokenToServer(token)
    }

    private fun sendTokenToServer(token: String?)
    {
        token?.let {
            Log.d(TAG, "Sending token to server: $it")
        }
    }

    companion object {
        private const val TAG = "MyFirebaseMsgService"
    }
}