package com.reminder.morse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OpticalCommScreen(
    senderState: SenderState,
    receiverState: ReceiverState,
    viewfinder: @Composable () -> Unit,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    onStartReceive: () -> Unit,
    onStopReceive: () -> Unit,
    onViewfinderTap: (Float, Float) -> Unit = { _, _ -> },
    onClearLock: () -> Unit = {}
) {
    // Track last tap position for drawing the lock indicator
    var lockTap by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Viewfinder")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val nx = offset.x / size.width.toFloat()
                            val ny = offset.y / size.height.toFloat()
                            lockTap = Pair(nx, ny)
                            onViewfinderTap(nx, ny)
                        }
                    }
            ) {
                viewfinder()
                // Draw lock crosshair when manually locked
                val tap = lockTap
                if (tap != null && receiverState.detectionMode == "manual") {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val cx = tap.first * size.width
                        val cy = tap.second * size.height
                        drawCircle(
                            color = Color.Green,
                            radius = 28f,
                            center = Offset(cx, cy),
                            style = Stroke(width = 2.5f)
                        )
                        drawLine(Color.Green, Offset(cx - 18f, cy), Offset(cx + 18f, cy), 1.5f)
                        drawLine(Color.Green, Offset(cx, cy - 18f), Offset(cx, cy + 18f), 1.5f)
                    }
                }
                // Subtle dot when pattern-detected (center of view as indicator)
                if (receiverState.detectionMode == "pattern") {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawCircle(
                            color = Color.Cyan.copy(alpha = 0.6f),
                            radius = 10f,
                            center = Offset(size.width / 2f, 14f)
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = senderState.message,
            onValueChange = onMessageChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Message") },
            singleLine = true
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(onClick = onSend, enabled = !senderState.isTransmitting) {
                Text("Transmit")
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = if (receiverState.isReceiving) onStopReceive else onStartReceive
            ) {
                Text(if (receiverState.isReceiving) "Stop Receive" else "Start Receive")
            }
        }
        // Clear Lock button — only visible when manually locked
        if (receiverState.detectionMode == "manual") {
            Button(
                onClick = {
                    lockTap = null
                    onClearLock()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444))
            ) {
                Text("Clear Lock", fontSize = 13.sp)
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Sender status: ${senderState.status}")
            Text("Bit duration: ${senderState.bitDurationMs} ms")
            Text("Receiver locked: ${receiverState.locked}")
            Text("Signal: ${receiverState.signal}")
            Text("Error rate: ${receiverState.errorRate}")
            val modeLabel = when (receiverState.detectionMode) {
                "manual"  -> "\uD83D\uDCCD Manual lock"
                "pattern" -> "✓ Pattern detected"
                "auto"    -> "\uD83D\uDD0D Auto-scan"
                else      -> "—"
            }
            Text("Detection: $modeLabel")
            if (receiverState.debug.isNotEmpty()) {
                Text("Pipeline: ${receiverState.debug}", fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = receiverState.lastMessage,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Output") },
                readOnly = true,
                singleLine = false
            )
        }
    }
}
