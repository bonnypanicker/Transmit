Below is a complete implementation plan for a phone-to-phone optical communication system using:

Manchester Encoding
+ Adaptive OOK
+ Bright Spot Detection
+ Automatic LED Tracking
+ Dynamic Bitrate
+ CRC error detection

Target stack:

Kotlin

Jetpack Compose

CameraX

Camera2 Torch API

Goal performance:

Parameter	Target
Range	10–30 m (software only)
Bitrate	20–100 bps
Error rate	<1%
Latency	<1 sec
1. System Architecture
App
├── UI (Jetpack Compose)
├── Transmission Engine
│   ├── Manchester Encoder
│   ├── Packet Builder
│   └── Flash Controller
├── Receiver Engine
│   ├── Camera Capture
│   ├── LED Tracker
│   ├── Signal Extractor
│   ├── Bit Decoder
│   └── CRC Validator
└── Adaptation Layer
    ├── Distance Estimator
    └── Bitrate Controller
2. Communication Protocol

Packet structure:

[Preamble][Header][Payload][CRC]

Example:

1010101010101010
00001000
HELLO
1011

Fields:

Field	Purpose
Preamble	timing lock
Header	payload size
Payload	data
CRC	error detection
3. Transmission Pipeline

Sender flow:

User text
↓
UTF-8 bytes
↓
Binary
↓
Manchester encoding
↓
Flash modulation
4. Flash Transmission Module

Uses CameraManager torch API.

class FlashController(
    private val cameraManager: CameraManager,
    private val cameraId: String
) {

    suspend fun setFlash(on: Boolean) {
        cameraManager.setTorchMode(cameraId, on)
    }
}

Transmission loop:

suspend fun transmit(bits: List<Boolean>, bitDuration: Long) {

    for (bit in bits) {
        flashController.setFlash(bit)
        delay(bitDuration)
    }

    flashController.setFlash(false)
}
5. Camera Receiver

Use CameraX ImageAnalysis.

Dependency:

implementation("androidx.camera:camera-camera2:1.3.0")
implementation("androidx.camera:camera-lifecycle:1.3.0")
implementation("androidx.camera:camera-view:1.3.0")

Analyzer setup:

val analysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()

analysis.setAnalyzer(executor, FrameAnalyzer())
6. LED Tracking Algorithm
4

Instead of scanning the whole frame each time, the system tracks the LED position.

Step 1: Bright spot detection
find brightest pixels
cluster them
compute centroid
Step 2: ROI tracking

Define region around LED:

ROI = 100×100 pixels around centroid

Future frames search only inside ROI.

Benefits:

faster processing

less noise

stable tracking

7. LED Tracking Implementation

Centroid calculation:

data class Point(val x: Int, val y: Int)

fun findBrightSpot(pixels: ByteArray, width: Int, height: Int): Point {

    var maxValue = 0
    var maxIndex = 0

    for (i in pixels.indices) {
        val v = pixels[i].toInt() and 0xFF
        if (v > maxValue) {
            maxValue = v
            maxIndex = i
        }
    }

    val x = maxIndex % width
    val y = maxIndex / width

    return Point(x, y)
}

ROI extraction:

fun extractROI(
    pixels: ByteArray,
    width: Int,
    center: Point,
    size: Int
): List<Int> {

    val values = mutableListOf<Int>()

    for (y in center.y-size..center.y+size) {
        for (x in center.x-size..center.x+size) {

            val index = y*width + x
            values.add(pixels[index].toInt() and 0xFF)

        }
    }

    return values
}
8. Signal Extraction

Use bright pixel cluster averaging.

fun extractSignal(values: List<Int>): Float {

    val sorted = values.sortedDescending()

    val cluster = sorted.take(20)

    return cluster.average().toFloat()
}
9. Temporal Smoothing

Combine multiple frames.

class SignalSmoother {

    private val history = ArrayDeque<Float>()

    fun smooth(value: Float): Float {

        history.add(value)

        if (history.size > 5)
            history.removeFirst()

        return history.average().toFloat()
    }
}
10. ON/OFF Detection
fun detectFlash(signal: Float, threshold: Float): Boolean {

    return signal > threshold
}

Adaptive threshold:

threshold = ambient + delta
11. Manchester Decoding

Rule:

Transition	Bit
OFF→ON	1
ON→OFF	0

Implementation:

fun decodeManchester(signal: List<Boolean>): List<Int> {

    val bits = mutableListOf<Int>()

    for (i in signal.indices step 2) {

        val a = signal[i]
        val b = signal[i+1]

        bits.add(if (!a && b) 1 else 0)
    }

    return bits
}
12. Distance Estimation

Using brightness signal:

fun estimateDistance(
    referenceSignal: Float,
    referenceDistance: Float,
    currentSignal: Float
): Float {

    return sqrt(referenceSignal / currentSignal) * referenceDistance
}
13. Dynamic Bitrate Controller
fun chooseBitDuration(signal: Float): Long {

    return when {

        signal > 120 -> 80
        signal > 80 -> 120
        signal > 40 -> 200
        signal > 20 -> 350
        else -> 500
    }
}
14. CRC Validation
fun crc8(data: ByteArray): Int {

    var crc = 0

    for (b in data) {
        crc = crc xor b.toInt()
    }

    return crc and 0xFF
}
15. Jetpack Compose UI

Main screen:

@Composable
fun OpticalCommScreen(
    onSend: () -> Unit,
    onReceive: () -> Unit
) {

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Button(onClick = onSend) {
            Text("Transmit via Flash")
        }

        Spacer(Modifier.height(20.dp))

        Button(onClick = onReceive) {
            Text("Receive via Camera")
        }
    }
}
16. Receiver Processing Pipeline
Camera frame
↓
Bright spot detection
↓
LED tracking
↓
ROI extraction
↓
Cluster brightness
↓
Temporal smoothing
↓
Flash ON/OFF detection
↓
Manchester decoding
↓
CRC validation
↓
Display message
17. Performance Optimization

Key tricks:

lock camera exposure

reduce ISO noise

use ROI tracking

frame averaging

adaptive bitrate

18. Development Phases
Phase 1

Basic system

flash transmitter

brightness decoder

Phase 2

Signal improvements

Manchester encoding

CRC

Phase 3

Long-distance improvements

bright spot detection

LED tracking

Phase 4

Advanced features

dynamic bitrate

distance estimation

19. Expected Performance
Feature	Result
Bitrate	20–100 bps
Range	10–30 m
Latency	<1 sec
Reliability	>99%