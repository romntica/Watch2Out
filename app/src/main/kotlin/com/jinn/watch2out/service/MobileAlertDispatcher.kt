// [Module: :app]
package com.jinn.watch2out.service

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import com.jinn.watch2out.shared.model.IncidentData
import com.jinn.watch2out.shared.model.WatchSettings
import com.jinn.watch2out.shared.util.AlertFormatter
import kotlinx.coroutines.*

/**
 * Handles emergency notifications on the Mobile device with retry logic.
 */
class MobileAlertDispatcher(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "MobileAlertDispatcher"
    private val retryIntervals = listOf(5000L, 10000L, 15000L)

    fun dispatch(
        settings: WatchSettings,
        type: String,
        timestamp: Long,
        lat: Double?,
        lon: Double?,
        maxG: Float,
        speed: Float
    ) {
        // Critical: Always log entry at Warning level to confirm dispatcher was reached
        Log.w(TAG, "🔔 EMERGENCY DISPATCH TRIGGERED! SMS: ${settings.isSmsEnabled}, Email: ${settings.isEmailEnabled}")

        if (settings.isSmsEnabled && settings.smsRecipient.isNotEmpty()) {
            performDispatchWithRetry("Mobile SMS") {
                sendSms(settings.smsRecipient, type, timestamp, lat, lon)
            }
        } else {
            Log.w(TAG, "SMS dispatch skipped: Disabled or no recipient.")
        }

        if (settings.isEmailEnabled && settings.emailRecipient.isNotEmpty()) {
            performDispatchWithRetry("Mobile Email") {
                sendEmail(settings.emailRecipient, type, timestamp, lat, lon, maxG, speed)
            }
        } else {
            Log.w(TAG, "Email dispatch skipped: Disabled or no recipient.")
        }
    }

    private fun performDispatchWithRetry(label: String, block: suspend () -> Unit) {
        scope.launch {
            var attempt = 0
            var success = false
            
            while (attempt <= retryIntervals.size && !success) {
                try {
                    block()
                    success = true
                    Log.i(TAG, "✅ $label successful on attempt ${attempt + 1}")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ $label attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt < retryIntervals.size) {
                        val delayTime = retryIntervals[attempt]
                        Log.d(TAG, "Retrying $label in ${delayTime / 1000}s...")
                        delay(delayTime)
                    }
                    attempt++
                }
            }
            
            if (!success) {
                Log.e(TAG, "Critical: $label dispatch failed after all retries.")
            }
        }
    }

    private suspend fun sendSms(recipient: String, type: String, timestamp: Long, lat: Double?, lon: Double?) {
        val message = AlertFormatter.formatSms(type, timestamp, lat, lon)
        Log.w(TAG, "🚨 [MOBILE SMS] SENDING TO $recipient: $message")
        
        val smsManager = context.getSystemService(SmsManager::class.java)
        val parts = smsManager.divideMessage(message)
        smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
    }

    private suspend fun sendEmail(recipient: String, type: String, timestamp: Long, lat: Double?, lon: Double?, maxG: Float, speed: Float) {
        val incident = IncidentData(
            type = type,
            timestamp = timestamp,
            latitude = lat,
            longitude = lon,
            maxG = maxG,
            speed = speed
        )
        val subject = AlertFormatter.formatEmailSubject(type, timestamp)
        val body = AlertFormatter.formatEmailBody(incident)
        
        Log.w(TAG, "📧 [MOBILE EMAIL] SENDING TO $recipient")
        Log.w(TAG, "📧 [MOBILE EMAIL] SUBJECT: $subject")
        Log.w(TAG, "📧 [MOBILE EMAIL] BODY:\n$body")
    }
}
