package com.reminder.morse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.reminder.morse.rx.FrameBuffer
import com.reminder.morse.rx.RxFrameResult
import com.reminder.morse.rx.RxPipeline
import com.reminder.morse.tx.FlashController
import com.reminder.morse.tx.FlashTransmitter
import com.reminder.morse.tx.TorchSelector
import com.reminder.morse.tx.TxPipeline
import com.reminder.morse.ui.OpticalCommScreen
import com.reminder.morse.ui.ReceiverState
import com.reminder.morse.ui.SenderState
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionGrantedState.value = result.values.all { it }
    }

    private val permissionGrantedState: MutableState<Boolean> = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionGrantedState.value = hasPermissions()
        if (!permissionGrantedState.value) {
            permissionRequest.launch(
                arrayOf(
                    Manifest.permission.CAMERA
                )
            )
        }
        setContent {
            val context = LocalContext.current
            val previewView = remember { PreviewView(context) }
            var senderState by remember { mutableStateOf(SenderState()) }
            var receiverState by remember { mutableStateOf(ReceiverState()) }
            var totalPackets by remember { mutableIntStateOf(0) }
            var crcFailures by remember { mutableIntStateOf(0) }
            val rxPipeline = remember { RxPipeline() }
            val torch = remember { rememberTorch(context) }
            val transmitter = remember(torch) { torch?.let { FlashTransmitter(it) } }
            val txPipeline = remember(transmitter) { transmitter?.let { TxPipeline(it) } }
            val receivingFlag = remember { AtomicBoolean(false) }

            LaunchedEffect(permissionGrantedState.value) {
                if (permissionGrantedState.value) {
                    startCamera(
                        context = context,
                        previewView = previewView,
                        analyzer = CameraAnalyzer(
                            pipeline = rxPipeline,
                            onUpdate = { result ->
                                handler.post {
                                    receiverState = receiverState.copy(
                                        signal = result.signal,
                                        locked = result.locked
                                    )
                                    val packet = result.packet
                                    if (packet != null) {
                                        totalPackets += 1
                                        if (!packet.crcOk) {
                                            crcFailures += 1
                                        } else {
                                            receiverState = receiverState.copy(
                                                lastMessage = packet.payload.toString(Charsets.UTF_8)
                                            )
                                        }
                                        val errorRate = if (totalPackets == 0) 0f else crcFailures.toFloat() / totalPackets
                                        receiverState = receiverState.copy(errorRate = errorRate)
                                    }
                                }
                            },
                            isReceiving = { receivingFlag.get() }
                        ),
                        executor = cameraExecutor
                    )
                }
            }

            OpticalCommScreen(
                senderState = senderState,
                receiverState = receiverState,
                viewfinder = {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxWidth().height(240.dp)
                    )
                },
                onMessageChange = { senderState = senderState.copy(message = it) },
                onSend = {
                    val pipeline = txPipeline
                    if (pipeline == null) {
                        senderState = senderState.copy(status = "Torch unavailable")
                        return@OpticalCommScreen
                    }
                    senderState = senderState.copy(isTransmitting = true, status = "Transmitting")
                    pipeline.transmitPayload(
                        payload = senderState.message.toByteArray(Charsets.UTF_8),
                        bitDurationMs = senderState.bitDurationMs
                    ) {
                        handler.post {
                            senderState = senderState.copy(isTransmitting = false, status = "Idle")
                        }
                    }
                },
                onStartReceive = {
                    rxPipeline.reset()
                    receivingFlag.set(true)
                    receiverState = receiverState.copy(isReceiving = true)
                },
                onStopReceive = {
                    receivingFlag.set(false)
                    receiverState = receiverState.copy(isReceiving = false)
                }
            )
        }
    }

    private fun hasPermissions(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return camera
    }

    private fun rememberTorch(context: Context): FlashController? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = TorchSelector.findTorchCameraId(cameraManager) ?: return null
        return FlashController(cameraManager, cameraId)
    }
}

private fun startCamera(
    context: Context,
    previewView: PreviewView,
    analyzer: ImageAnalysis.Analyzer,
    executor: ExecutorService
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener(
        {
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, analyzer)
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                context as ComponentActivity,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        },
        ContextCompat.getMainExecutor(context)
    )
}

private class CameraAnalyzer(
    private val pipeline: RxPipeline,
    private val onUpdate: (RxFrameResult) -> Unit,
    private val isReceiving: () -> Boolean
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        if (!isReceiving()) {
            image.close()
            return
        }
        val buffer = image.planes[0].buffer
        val data = buffer.toByteArray()
        val frame = FrameBuffer(width = image.width, height = image.height, luminance = data)
        val result = pipeline.processFrame(frame)
        onUpdate(result)
        image.close()
    }
}

private fun ByteBuffer.toByteArray(): ByteArray {
    val bytes = ByteArray(remaining())
    get(bytes)
    return bytes
}
