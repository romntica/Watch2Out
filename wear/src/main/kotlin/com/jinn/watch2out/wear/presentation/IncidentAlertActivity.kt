// [Module: :wear]
package com.jinn.watch2out.wear.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.Wearable
import com.jinn.watch2out.shared.network.ProtocolContract
import com.jinn.watch2out.wear.service.SentinelService
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Critical Alert Activity shown when an incident is confirmed by FSM.
 * Implements 15s/7s timeout logic and dispatch triggering.
 * Configured to turn screen on and show over lockscreen.
 */
class IncidentAlertActivity : ComponentActivity() {
    
    private enum class UIState { ALERTING, CANCELLATION_WINDOW }

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.jinn.watch2out.DISMISS_ALERT") finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure screen turns on and shows over lockscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val filter = IntentFilter("com.jinn.watch2out.DISMISS_ALERT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dismissReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dismissReceiver, filter)
        }

        val reason = intent.getStringExtra("reason") ?: "Unknown Incident"
        val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
        val lat = if (intent.getBooleanExtra("has_location", false)) intent.getDoubleExtra("lat", 0.0) else null
        val lon = if (intent.getBooleanExtra("has_location", false)) intent.getDoubleExtra("lon", 0.0) else null
        val maxG = intent.getFloatExtra("maxG", 0f)
        val speed = intent.getFloatExtra("speed", 0f)

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        setContent {
            var uiState by remember { mutableStateOf(UIState.ALERTING) }
            var countdown by remember { mutableIntStateOf(15) }
            var cancelCountdown by remember { mutableIntStateOf(7) }

            // Background flashing
            var isRedColor by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                while (true) {
                    isRedColor = !isRedColor
                    delay(500)
                }
            }
            val backgroundColor by animateColorAsState(
                targetValue = if (isRedColor) Color(0xFF660000) else Color.Black,
                animationSpec = infiniteRepeatable(animation = tween(500), repeatMode = RepeatMode.Reverse),
                label = "Blink"
            )

            // Core Logic: Dispatch immediately after 15s of no response
            LaunchedEffect(uiState) {
                if (uiState == UIState.ALERTING) {
                    while (countdown > 0) {
                        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                        delay(1000)
                        countdown--
                    }
                    // 15s Timeout reached: Dispatch immediately
                    startFinalDispatch(reason, timestamp, lat, lon, maxG, speed)
                } else {
                    // Manual "I'M OK" press window
                    while (cancelCountdown > 0) {
                        delay(1000)
                        cancelCountdown--
                    }
                    // If 7s window expires, dispatch (Fail-safe)
                    startFinalDispatch(reason, timestamp, lat, lon, maxG, speed)
                }
            }

            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize().background(backgroundColor).padding(8.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (uiState == UIState.ALERTING) {
                            Text("CRASH DETECTED", color = Color.Red, fontWeight = FontWeight.Black, fontSize = 14.sp)
                            Text(reason, style = MaterialTheme.typography.caption3, color = Color.White, maxLines = 1)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("$countdown", style = MaterialTheme.typography.display1, color = Color.Yellow)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { uiState = UIState.CANCELLATION_WINDOW },
                                modifier = Modifier.fillMaxWidth(0.8f).height(40.dp),
                                colors = ButtonDefaults.secondaryButtonColors()
                            ) { Text("I'M OK", fontSize = 12.sp) }
                        } else {
                            Text("REALLY CANCEL?", color = Color.Yellow, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { notifyDismissToMobile() },
                                modifier = Modifier.fillMaxWidth(0.8f).height(60.dp),
                                colors = ButtonDefaults.secondaryButtonColors()
                            ) { Text("YES, CANCEL ($cancelCountdown)", fontWeight = FontWeight.Black) }
                            Spacer(modifier = Modifier.height(4.dp))
                            Chip(
                                onClick = { uiState = UIState.ALERTING },
                                label = { Text("BACK TO ALERT", fontSize = 9.sp) },
                                modifier = Modifier.height(30.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startFinalDispatch(type: String, timestamp: Long, lat: Double?, lon: Double?, maxG: Float, speed: Float) {
        Log.w("IncidentAlert", "EMERGENCY DISPATCH INITIATED: $type at $lat, $lon")
        val intent = Intent(this, SentinelService::class.java).apply {
            action = SentinelService.ACTION_FINAL_DISPATCH
            putExtra("type", type)
            putExtra("timestamp", timestamp)
            lat?.let { putExtra("lat", it) }
            lon?.let { putExtra("lon", it) }
            putExtra("maxG", maxG)
            putExtra("speed", speed)
        }
        startService(intent)
        finish()
    }

    private fun notifyDismissToMobile() {
        lifecycleScope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@IncidentAlertActivity).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@IncidentAlertActivity)
                        .sendMessage(node.id, ProtocolContract.Paths.INCIDENT_ALERT_DISMISS, null).await()
                }
                // v27.6: Also notify local SentinelService to delete sensitive evidence
                val dismissIntent = Intent(this@IncidentAlertActivity, SentinelService::class.java).apply {
                    action = SentinelService.ACTION_DISMISS_INCIDENT
                }
                startService(dismissIntent)
            } catch (e: Exception) {
                Log.e("IncidentAlert", "Failed to send dismiss to mobile: ${e.message}")
            }
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(dismissReceiver)
    }
}
