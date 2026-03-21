// [Module: :app]
package com.jinn.watch2out.presentation

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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.wearable.Wearable
import com.jinn.watch2out.shared.network.ProtocolContract
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Mobile Alert UI for incidents.
 * v22.0: Synchronized cancellation logic with Wear OS.
 */
class IncidentAlertActivity : ComponentActivity() {

    private enum class UIState { ALERTING, CANCELLATION_WINDOW }
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.jinn.watch2out.DISMISS_ALERT") {
                Log.d("IncidentAlert", "Remote dismiss received via Broadcast")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val filter = IntentFilter("com.jinn.watch2out.DISMISS_ALERT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dismissReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dismissReceiver, filter)
        }

        val reason = intent.getStringExtra("reason") ?: "Unknown Incident"
        val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
        val lat = if (intent.hasExtra("lat")) intent.getDoubleExtra("lat", 0.0) else null
        val lon = if (intent.hasExtra("lon")) intent.getDoubleExtra("lon", 0.0) else null
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
            var isRedColor by remember { mutableStateOf(true) }
            
            LaunchedEffect(uiState) {
                if (uiState == UIState.ALERTING) {
                    while (countdown > 0) {
                        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                        delay(1000)
                        countdown--
                        isRedColor = !isRedColor
                    }
                    triggerFinalDispatch(reason, timestamp, lat, lon, maxG, speed)
                } else {
                    while (cancelCountdown > 0) {
                        delay(1000)
                        cancelCountdown--
                    }
                    triggerFinalDispatch(reason, timestamp, lat, lon, maxG, speed)
                }
            }

            val backgroundColor by animateColorAsState(
                targetValue = if (isRedColor) Color(0xFFB71C1C) else Color.Black,
                animationSpec = infiniteRepeatable(animation = tween(500), repeatMode = RepeatMode.Reverse),
                label = "Blink"
            )

            Surface(modifier = Modifier.fillMaxSize(), color = backgroundColor) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (uiState == UIState.ALERTING) {
                        Text("CRITICAL ALERT", color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(reason, color = Color.Yellow, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(32.dp))
                        Text("$countdown", color = Color.White, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(48.dp))
                        
                        Button(
                            onClick = { uiState = UIState.CANCELLATION_WINDOW },
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                        ) {
                            Text("I AM OK", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text("REALLY CANCEL?", color = Color.Yellow, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Dismissing will stop emergency dispatch.", color = Color.White, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(48.dp))
                        
                        Button(
                            onClick = { notifyDismissToWatch() },
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Red)
                        ) {
                            Text("YES, CANCEL ($cancelCountdown)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        TextButton(onClick = { uiState = UIState.ALERTING }) {
                            Text("BACK TO ALERT", color = Color.White)
                        }
                    }
                }
            }
        }
    }

    private fun triggerFinalDispatch(reason: String, timestamp: Long, lat: Double?, lon: Double?, maxG: Float, speed: Float) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "ACTION_TRIGGER_DISPATCH"
            putExtra("reason", reason)
            putExtra("timestamp", timestamp)
            lat?.let { putExtra("lat", it) }
            lon?.let { putExtra("lon", it) }
            putExtra("maxG", maxG)
            putExtra("speed", speed)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun notifyDismissToWatch() {
        activityScope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@IncidentAlertActivity).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@IncidentAlertActivity)
                        .sendMessage(node.id, ProtocolContract.Paths.INCIDENT_ALERT_DISMISS, null).await()
                }
            } catch (e: Exception) { 
                Log.e("IncidentAlert", "Failed to notify Watch: ${e.message}")
            }
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(dismissReceiver) } catch (e: Exception) { }
        activityScope.cancel()
    }
}
