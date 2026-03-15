// [Module: :wear]
package com.jinn.watch2out.wear.service

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import com.jinn.watch2out.shared.model.IncidentData
import com.jinn.watch2out.shared.model.WatchSettings
import com.jinn.watch2out.shared.util.AlertFormatter
import kotlinx.coroutines.*

/**
 * Handles emergency notifications directly from the Wear device with retry logic.
 */
class WearAlertDispatcher(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "WearAlertDispatcher"
    private val retryIntervals = listOf(5000L, 10000L, 15000L)

    /**
     * Dispatches notifications from the Watch.
     */
    fun dispatch(
        settings: WatchSettings,
        type: String,
        timestamp: Long,
        lat: Double?,
        lon: Double?,
        maxG: Float,
        speed: Float
    ) {
        if (!settings.useWatchDirectDispatch) {
            Log.d(TAG, "Direct dispatch disabled on Watch.")
            return
        }

        // SMS Dispatch
        if (settings.isSmsEnabled && settings.smsRecipient.isNotEmpty()) {
            performDispatchWithRetry("Watch SMS") {
                sendSms(settings.smsRecipient, type, timestamp, lat, lon)
            }
        }

        // Email Logging (Watch usually doesn't send mail directly, but we log the attempt)
        if (settings.isEmailEnabled && settings.emailRecipient.isNotEmpty()) {
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
            Log.w(TAG, "📧 [WATCH EMAIL] Prepared for: ${settings.emailRecipient}")
            Log.w(TAG, "📧 [WATCH EMAIL] Subject: $subject")
            Log.w(TAG, "📧 [WATCH EMAIL] Body: $body")
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
                    Log.i(TAG, "$label successful on attempt ${attempt + 1}")
                } catch (e: Exception) {
                    Log.e(TAG, "$label attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt < retryIntervals.size) {
                        delay(retryIntervals[attempt])
                    }
                    attempt++
                }
            }
        }
    }

    private suspend fun sendSms(recipient: String, type: String, timestamp: Long, lat: Double?, lon: Double?) {
        val message = AlertFormatter.formatSms(type, timestamp, lat, lon)
        Log.w(TAG, "🚨 [WATCH SMS] SENDING: $message")
        val smsManager = context.getSystemService(SmsManager::class.java)
        val parts = smsManager.divideMessage(message)
        smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
    }
}
