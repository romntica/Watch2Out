// [Module: :wear]
package com.jinn.watch2out.wear.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jinn.watch2out.wear.service.SentinelService

/**
 * Ensures the SentinelService is started automatically after the device boots up.
 * This is critical for a safety application to be always-on without user interaction.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, SentinelService::class.java).apply {
                action = SentinelService.ACTION_START_MONITORING
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
