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
 * Updated for v22.0: Removed Email support and added multi-contact SMS fallback.
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

        // 1. Legacy SMS Dispatch
        if (settings.isSmsEnabled && settings.smsRecipient.isNotEmpty()) {
            performDispatchWithRetry("Watch SMS (Legacy)") {
                sendSms(settings.smsRecipient, type, timestamp, lat, lon)
            }
        }

        // 2. Multi-Contact SMS Dispatch (v22.0)
        settings.contacts.forEach { contact ->
            if (contact.enableSms && contact.phoneNumber.isNotEmpty()) {
                performDispatchWithRetry("Watch SMS to ${contact.name}") {
                    sendSms(contact.phoneNumber, type, timestamp, lat, lon)
                }
            }
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
        Log.w(TAG, "🚨 [WATCH SMS] SENDING TO $recipient: $message")
        val smsManager = context.getSystemService(SmsManager::class.java)
        val parts = smsManager.divideMessage(message)
        smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
    }
}
