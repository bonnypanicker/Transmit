package com.reminder.morse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OpticalCommScreen(
    senderState: SenderState,
    receiverState: ReceiverState,
    viewfinder: @Composable () -> Unit,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    onStartReceive: () -> Unit,
    onStopReceive: () -> Unit
) {
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
            viewfinder()
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
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Sender status: ${senderState.status}")
            Text("Bit duration: ${senderState.bitDurationMs} ms")
            Text("Receiver locked: ${receiverState.locked}")
            Text("Signal: ${receiverState.signal}")
            Text("Error rate: ${receiverState.errorRate}")
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
