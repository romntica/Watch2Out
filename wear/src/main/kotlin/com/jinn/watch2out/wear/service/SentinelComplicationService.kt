// [Module: :wear]
package com.jinn.watch2out.wear.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.jinn.watch2out.shared.model.IncidentState
import com.jinn.watch2out.wear.R
import com.jinn.watch2out.wear.presentation.MainActivity

/**
 * Watch Complication: Shows system status.
 * Updates dynamically based on SentinelService state.
 */
class SentinelComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        // Fetch current states from SentinelService
        val incidentState = SentinelService.lastKnownState
        val vState = SentinelService.lastKnownVState
        val context: Context = baseContext

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val iconRes = getIconRes(incidentState, vState)
        val statusText = getStatusText(incidentState, vState)

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(statusText).build(),
                    contentDescription = PlainComplicationText.Builder("Watch2Out Status: $statusText").build()
                )
                .setMonochromaticImage(
                    MonochromaticImage.Builder(Icon.createWithResource(context, iconRes)).build()
                )
                .setTapAction(pendingIntent)
                .build()
            }

            ComplicationType.MONOCHROMATIC_IMAGE -> {
                MonochromaticImageComplicationData.Builder(
                    monochromaticImage = MonochromaticImage.Builder(
                        Icon.createWithResource(context, iconRes)
                    ).build(),
                    contentDescription = PlainComplicationText.Builder("Watch2Out Status: $statusText").build()
                )
                .setTapAction(pendingIntent)
                .build()
            }

            else -> NoDataComplicationData()
        }
    }

    /**
     * Map states to AGENTS.md specified icons:
     * - MONITORING: Shield
     * - TRIGGERED: Lightning
     * - IDLE/ERROR: Broken Shield
     */
    private fun getIconRes(incidentState: IncidentState, vState: com.jinn.watch2out.shared.model.VehicleInferenceState): Int {
        if (incidentState == IncidentState.IDLE) return R.drawable.ic_complication_error
        
        return when (vState) {
            com.jinn.watch2out.shared.model.VehicleInferenceState.PRE_EVENT,
            com.jinn.watch2out.shared.model.VehicleInferenceState.FALLING,
            com.jinn.watch2out.shared.model.VehicleInferenceState.IMPACT,
            com.jinn.watch2out.shared.model.VehicleInferenceState.POST_MOTION,
            com.jinn.watch2out.shared.model.VehicleInferenceState.WAIT_CONFIRM,
            com.jinn.watch2out.shared.model.VehicleInferenceState.CONFIRMED_CRASH -> R.drawable.ic_complication_accident
            
            else -> R.drawable.ic_complication_monitoring
        }
    }

    private fun getStatusText(incidentState: IncidentState, vState: com.jinn.watch2out.shared.model.VehicleInferenceState): String {
        if (incidentState == IncidentState.IDLE) return "IDLE"
        if (incidentState == IncidentState.TRIGGERED) return "CRASH"
        
        return when (vState) {
            com.jinn.watch2out.shared.model.VehicleInferenceState.MOVING -> "MOVE"
            com.jinn.watch2out.shared.model.VehicleInferenceState.STILLNESS -> "STILL"
            com.jinn.watch2out.shared.model.VehicleInferenceState.PRE_EVENT -> "WARN"
            com.jinn.watch2out.shared.model.VehicleInferenceState.IMPACT -> "HIT!"
            else -> "RUN"
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val context: Context = baseContext
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("IDLE").build(),
                    contentDescription = PlainComplicationText.Builder("Preview").build()
                )
                .setMonochromaticImage(
                    MonochromaticImage.Builder(Icon.createWithResource(context, R.drawable.ic_complication_monitoring)).build()
                )
                .build()
            }
            ComplicationType.MONOCHROMATIC_IMAGE -> {
                MonochromaticImageComplicationData.Builder(
                    monochromaticImage = MonochromaticImage.Builder(
                        Icon.createWithResource(context, R.drawable.ic_complication_monitoring)
                    ).build(),
                    contentDescription = PlainComplicationText.Builder("Preview").build()
                ).build()
            }
            else -> NoDataComplicationData()
        }
    }

    companion object {
        /**
         * Triggers a complication update. Called by SentinelService on state change.
         */
        fun update(context: Context) {
            val componentName = ComponentName(context, SentinelComplicationService::class.java)
            ComplicationDataSourceUpdateRequester.create(context, componentName).requestUpdateAll()
        }
    }
}
