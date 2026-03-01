# Transmit — Flashlight Camera Communication: Bug Analysis Report

**Project:** Transmit (Flashlight ↔ Camera Optical Communication)  
**Language:** Kotlin (Android / Jetpack Compose / CameraX)  
**Protocol:** Manchester Encoding over LED Flash  
**Report Date:** March 2026  
**Status:** ❌ Non-functional — receiver never decodes a message

---

## Table of Contents

1. [System Architecture Overview](#1-system-architecture-overview)
2. [Signal Flow Diagram](#2-signal-flow-diagram)
3. [Bug #1 — No Clock Recovery (Critical)](#3-bug-1--no-clock-recovery-critical)
4. [Bug #2 — symbolBuffer.clear() Destroys Partial Packets (Critical)](#4-bug-2--symbolbufferclear-destroys-partial-packets-critical)
5. [Bug #3 — Uncaught Exception Kills Analysis Thread (Critical)](#5-bug-3--uncaught-exception-kills-analysis-thread-critical)
6. [Bug #4 — Preamble Timing Mismatch (Medium)](#6-bug-4--preamble-timing-mismatch-medium)
7. [Bug #5 — Adaptive Threshold Chases the Flash Signal (Medium)](#7-bug-5--adaptive-threshold-chases-the-flash-signal-medium)
8. [Bug #6 — BitCodec bitsToBytes Index Logic (Medium)](#8-bug-6--bitcodec-bitstobytes-index-logic-medium)
9. [Complete Fix Implementations](#9-complete-fix-implementations)
10. [Testing Checklist](#10-testing-checklist)
11. [Summary Table](#11-summary-table)

---

## 1. System Architecture Overview

The Transmit app implements a full optical communication link between two Android smartphones using the camera flash as a transmitter and the image sensor as a receiver.

### Transmit Side (TX)

```
User Message (String)
      ↓
TxPipeline.transmitPayload()
      ↓
Protocol.buildTxSymbols()
  ├── buildPacket()        → [length byte | payload bytes | CRC byte]
  ├── BitCodec.bytesToBits()  → raw bit list
  └── Manchester.encode()    → doubled symbol list
      ↓
[preamble bits] + [manchester symbols]
      ↓
FlashTransmitter.transmit()
      ↓ (ScheduledExecutorService, one symbol per bitDurationMs)
FlashController.setFlash(on/off)
      ↓
CameraX CameraControl.enableTorch()
      ↓
Physical LED flash pulses
```

### Receive Side (RX)

```
Physical LED light pulses
      ↓
CameraX ImageAnalysis → CameraAnalyzer.analyze(ImageProxy)
      ↓
FrameBuffer (YUV Y-plane extracted)
      ↓
RxPipeline.processFrame()
  ├── RoiTracker.update()       → tracks brightest region
  ├── SignalExtractor.extract() → averages top-N pixels in ROI
  ├── SignalSmoother.smooth()   → 5-frame moving average
  ├── AdaptiveThreshold.update()→ ambient + delta
  ├── OnOffDetector.detect()    → boolean symbol
  ├── PreambleDetector.findStart() → search for sync pattern
  ├── ManchesterStreamDecoder.decode() → raw bits
  └── PacketAssembler.appendBits() → ParsedPacket (CRC checked)
      ↓
ReceiverState.lastMessage displayed in UI
```

### Key Configuration Values

| Parameter | Value | Source |
|---|---|---|
| Default bit duration | 200 ms | `SenderState.bitDurationMs` |
| Preamble length | 16 bits | `Protocol.preambleBits` |
| Preamble pattern | `[T,F,T,F,T,F,...]` | alternating |
| Manchester encoding | IEEE 802.3 style | `Manchester.kt` |
| CRC algorithm | XOR-based CRC-8 | `Crc.kt` |
| Signal smoothing window | 5 frames | `SignalSmoother` |
| Adaptive threshold delta | +15 brightness units | `AdaptiveThreshold` |
| ROI half-size | 50 px | `RoiTracker` |
| Signal cluster (top-N px) | 20 pixels | `SignalExtractor` |

---

## 2. Signal Flow Diagram

### What the transmitter sends (for payload `"Hi"`)

```
Preamble (16 raw bits)      Manchester-encoded data bits
┌───────────────────────┐   ┌─────────────────────────────────────────────┐
│ T F T F T F T F T F   │   │ FT FT TF FT TF TF FT TF ... (CRC bits)      │
│ T F T F T F           │   │                                              │
└───────────────────────┘   └─────────────────────────────────────────────┘
                                 ↑ Each symbol = one bitDurationMs (200ms)
```

### What the camera sees (at 30fps = 33ms per frame)

```
One transmitted bit (200ms) = approximately 6 camera frames

Transmitted bit "ON":   ████████████████████████████████████████████████  (200ms)
Camera frames:          |frame|frame|frame|frame|frame|frame|
Symbols collected:      [ T  ,  T  ,  T  ,  T  ,  T  ,  T  ]  ← 6 identical symbols
```

This fundamental mismatch is the primary reason reception fails.

---

## 3. Bug #1 — No Clock Recovery (Critical)

**File:** `rx/RxPipeline.kt`  
**Impact:** The preamble is NEVER detected. Reception completely fails.

### The Problem

The receiver appends exactly one boolean symbol to `symbolBuffer` per camera frame, with no consideration for timing. The transmitter sends one symbol per `bitDurationMs` (200ms). The camera captures at ~30fps (33ms per frame).

```kotlin
// RxPipeline.kt — processFrame()
val symbol = OnOffDetector.detect(smoothed, thresh)
symbolBuffer.add(symbol)   // ← ONE symbol per frame, regardless of bit duration
```

**Result:** Every transmitted symbol is repeated ~6 times in the buffer.

### What the preamble looks like in symbolBuffer

The transmitted preamble is the pattern `[T,F,T,F,T,F,T,F,T,F,T,F,T,F,T,F]`.

At 200ms/bit with 30fps camera, the buffer instead contains:
```
[T,T,T,T,T,T, F,F,F,F,F,F, T,T,T,T,T,T, F,F,F,F,F,F, ...]
  ← 6 frames →  ← 6 frames →  ← 6 frames →  ← 6 frames →
```

The `PreambleDetector.findStart()` searches for the exact pattern `[T,F,T,F,...]` with only `maxErrors = 2`. It will never match a buffer full of long runs. The preamble is never found, `locked` stays `false`, and no packet is ever assembled.

### Root Cause

Clock recovery is missing entirely. The receiver must know **when bit boundaries occur** relative to the camera's frame timestamps, then vote/average across the frames that belong to each bit, collapsing them into a single symbol.

### The Fix

```kotlin
// RxPipeline.kt — add these fields
private var lastBitBoundaryMs = 0L
private val pendingFrameBrightness = ArrayList<Float>()
private var bitDurationMs = 200L   // should be passed in or configured

fun processFrame(buffer: FrameBuffer, timestampMs: Long): RxFrameResult {
    val (roi, _) = roiTracker.update(buffer)
    val signal = SignalExtractor.extract(buffer, roi)
    val smoothed = smoother.smooth(signal)
    pendingFrameBrightness.add(smoothed)

    val elapsed = timestampMs - lastBitBoundaryMs
    if (elapsed < bitDurationMs) {
        // Still within the same bit period — accumulate frames
        return RxFrameResult(packet = null, signal = smoothed, locked = locked)
    }

    // Bit boundary crossed — vote on symbol from accumulated frames
    lastBitBoundaryMs = timestampMs
    val avgBrightness = pendingFrameBrightness.average().toFloat()
    pendingFrameBrightness.clear()

    val thresh = threshold.update(avgBrightness, locked)
    val symbol = OnOffDetector.detect(avgBrightness, thresh)
    symbolBuffer.add(symbol)

    // ... preamble detection and decoding continues below
}
```

In `CameraAnalyzer`, pass the image timestamp:
```kotlin
override fun analyze(image: ImageProxy) {
    val timestampMs = image.imageInfo.timestamp / 1_000_000L
    val frame = FrameBuffer(image.width, image.height, data)
    val result = pipeline.processFrame(frame, timestampMs)
    // ...
}
```

---

## 4. Bug #2 — `symbolBuffer.clear()` Destroys Partial Packets (Critical)

**File:** `rx/RxPipeline.kt`  
**Impact:** Even if preamble is found, the packet is never fully assembled.

### The Problem

```kotlin
if (start >= 0) {
    val decodeStart = start + Protocol.preambleBits.size
    val bits = manchesterDecoder.decode(symbolBuffer, decodeStart)
    symbolBuffer.clear()          // ← ERASES EVERYTHING
    packet = assembler.appendBits(bits)
    locked = true
}
```

When the preamble is found at position `start`, the code decodes from `decodeStart` to the end of `symbolBuffer`. But at that moment the buffer likely has only a few symbols beyond the preamble — certainly not all the bits needed for a full packet.

The `PacketAssembler` is designed to accumulate bits across multiple calls. But `symbolBuffer.clear()` wipes everything, so the next frame starts from an empty buffer. The preamble won't appear again in just a few new symbols, so the decoder path is never re-entered, and the assembler never receives enough bits to return a packet.

### Example Timeline

```
Frame 30:  symbolBuffer = [preamble...][3 data symbols]
           → preamble found, decode 3 symbols, clear buffer
           → assembler needs ~80 more symbols for "Hi" packet → returns null

Frame 31:  symbolBuffer = [1 new symbol]
           → preamble NOT found, locked = false
           → assembler never called again

Frame 32–onwards: same — never enough symbols for preamble re-detection
```

### The Fix

Only remove consumed symbols from the front of the buffer, not the whole buffer:

```kotlin
if (start >= 0) {
    val decodeStart = start + Protocol.preambleBits.size
    val bits = manchesterDecoder.decode(symbolBuffer, decodeStart)

    // Remove only the preamble portion — keep any trailing symbols
    if (decodeStart > 0) {
        symbolBuffer.subList(0, decodeStart).clear()
    }

    packet = assembler.appendBits(bits)
    locked = true

    // If packet is complete, reset assembler for next packet
    if (packet != null) {
        assembler.reset()
        symbolBuffer.clear()  // safe to clear now — full packet received
    }
}
```

---

## 5. Bug #3 — Uncaught Exception Kills Analysis Thread (Critical)

**File:** `rx/Manchester.kt`, `rx/ManchesterStreamDecoder.kt`  
**Impact:** The CameraX analysis thread crashes silently after the first invalid Manchester symbol pair.

### The Problem

```kotlin
// Manchester.kt
fun decode(symbols: List<Boolean>): List<Boolean> {
    // ...
    when {
        !a && b -> decoded.add(true)
        a && !b -> decoded.add(false)
        else -> throw IllegalArgumentException("Invalid Manchester symbol")  // ← UNCAUGHT
    }
}
```

Because Bug #1 causes runs of identical symbols (e.g., `[true, true]` or `[false, false]`), the `else` branch is hit immediately. This `IllegalArgumentException` propagates up through:

- `ManchesterStreamDecoder.decode()` — no try/catch
- `RxPipeline.processFrame()` — no try/catch
- `CameraAnalyzer.analyze()` — no try/catch
- CameraX `ImageAnalysis` executor — silently swallows the exception

After this crash, the analysis executor thread terminates. The camera still delivers frames but `analyze()` is never called again. The app appears to "work" (viewfinder shows, UI is responsive) but is completely dead internally. This makes the bug very hard to notice without logcat.

### The Fix

Wrap decoding defensively at the `ManchesterStreamDecoder` level:

```kotlin
// ManchesterStreamDecoder.kt
class ManchesterStreamDecoder {
    fun decode(symbols: List<Boolean>, startIndex: Int): List<Boolean> {
        val remaining = symbols.size - startIndex
        if (remaining <= 1) return emptyList()
        val endExclusive = if (remaining % 2 == 0) symbols.size else symbols.size - 1
        val slice = symbols.subList(startIndex, endExclusive)
        return try {
            Manchester.decode(slice)
        } catch (e: IllegalArgumentException) {
            // Invalid symbol pair — signal noise or timing error, discard gracefully
            emptyList()
        }
    }
}
```

Additionally, change `Manchester.decode()` itself to skip invalid pairs rather than throw:

```kotlin
// Manchester.kt
fun decode(symbols: List<Boolean>): List<Boolean> {
    require(symbols.size % 2 == 0) { "Symbol count must be even" }
    val decoded = ArrayList<Boolean>(symbols.size / 2)
    var i = 0
    while (i < symbols.size) {
        val a = symbols[i]
        val b = symbols[i + 1]
        when {
            !a && b -> decoded.add(true)
            a && !b -> decoded.add(false)
            else -> { /* Invalid pair — skip, do not throw */ }
        }
        i += 2
    }
    return decoded
}
```

And add a top-level guard in `CameraAnalyzer.analyze()`:

```kotlin
override fun analyze(image: ImageProxy) {
    if (!isReceiving()) { image.close(); return }
    try {
        val buffer = image.planes[0].buffer
        val data = buffer.toByteArray()
        val frame = FrameBuffer(image.width, image.height, data)
        val result = pipeline.processFrame(frame)
        onUpdate(result)
    } catch (e: Exception) {
        Log.e("CameraAnalyzer", "Frame processing error", e)
    } finally {
        image.close()
    }
}
```

---

## 6. Bug #4 — Preamble Timing Mismatch (Medium)

**File:** `core/Protocol.kt`  
**Impact:** Even with clock recovery fixed, the preamble and data symbols have different effective durations at the receiver.

### The Problem

```kotlin
// Protocol.kt
fun buildTxSymbols(payload: ByteArray): List<Boolean> {
    val packet = buildPacket(payload)
    val bits = BitCodec.bytesToBits(packet, msbFirst = true)
    val symbols = Manchester.encode(bits)     // ← DATA is Manchester encoded (2 symbols per bit)
    val combined = ArrayList<Boolean>()
    combined.addAll(preambleBits)             // ← PREAMBLE is raw (1 symbol per bit)
    combined.addAll(symbols)
    return combined
}
```

The transmitter sends preamble symbols at `bitDurationMs` each. But Manchester-encoded data symbols are also each `bitDurationMs` — meaning each **data bit** takes `2 × bitDurationMs`. The preamble effectively runs at double the data rate.

A receiver doing proper clock recovery at the Manchester data symbol rate will see the preamble at half the expected timing. The two sections are out of sync.

### The Fix — Option A: Manchester-encode the preamble too

```kotlin
fun buildTxSymbols(payload: ByteArray): List<Boolean> {
    val packet = buildPacket(payload)
    val bits = BitCodec.bytesToBits(packet, msbFirst = true)
    val dataSymbols = Manchester.encode(bits)
    val preambleSymbols = Manchester.encode(preambleBits)   // ← encode preamble too
    val combined = ArrayList<Boolean>()
    combined.addAll(preambleSymbols)
    combined.addAll(dataSymbols)
    return combined
}
```

And update the receiver's preamble pattern to match:
```kotlin
val preambleBits: List<Boolean> = List(16) { i -> i % 2 == 0 }
val preambleSymbols: List<Boolean> = Manchester.encode(preambleBits)  // use this for detection
```

### The Fix — Option B: Use a byte-based sync word

Replace the bit-pattern preamble with a known sync byte (`0xAA = 10101010`) that naturally produces a clean alternating Manchester pattern:

```kotlin
// Add two sync bytes before the packet
val syncBytes = byteArrayOf(0xAA.toByte(), 0xAA.toByte())
val fullPayload = syncBytes + buildPacket(payload)
val bits = BitCodec.bytesToBits(fullPayload, msbFirst = true)
return Manchester.encode(bits)
```

---

## 7. Bug #5 — Adaptive Threshold Chases the Flash Signal (Medium)

**File:** `rx/AdaptiveThreshold.kt`  
**Impact:** During transmission, the threshold rises with each bright frame, eventually making ON and OFF indistinguishable.

### The Problem

```kotlin
class AdaptiveThreshold(
    private val smoothing: Float = 0.9f,
    private val delta: Float = 15f
) {
    fun update(signal: Float): Float {
        ambient = ambient!! * smoothing + signal * (1f - smoothing)  // ← updated every frame
        return (ambient ?: signal) + delta
    }
}
```

The exponential moving average of `ambient` is updated on every single frame, regardless of whether the flash is on or off. During a long transmission:

```
Frame 1 (flash OFF): ambient = 50,  threshold = 65
Frame 2 (flash ON):  ambient = 52,  threshold = 67
Frame 3 (flash ON):  ambient = 55,  threshold = 70
...
Frame 50 (flash ON): ambient = 180, threshold = 195
Flash actually at:   200
ON/OFF gap:          only 5 units — unreliable
```

### The Fix

Freeze the ambient estimate while the receiver is in a locked state (actively receiving data), and only update it during idle periods:

```kotlin
class AdaptiveThreshold(
    private val smoothing: Float = 0.9f,
    private val delta: Float = 15f
) {
    private var ambient: Float? = null

    fun reset() { ambient = null }

    fun update(signal: Float, isLocked: Boolean = false): Float {
        if (!isLocked) {
            // Only adapt when we are NOT actively receiving
            ambient = if (ambient == null) signal
                      else ambient!! * smoothing + signal * (1f - smoothing)
        }
        return (ambient ?: signal) + delta
    }
}
```

Pass the lock state from `RxPipeline`:
```kotlin
val thresh = threshold.update(smoothed, isLocked = locked)
```

---

## 8. Bug #6 — BitCodec `bitsToBytes` Index Logic (Medium)

**File:** `core/BitCodec.kt`  
**Impact:** Decoded bytes are wrong when LSB-first decoding is used, causing all CRC checks to fail.

### The Problem

```kotlin
fun bitsToBytes(bits: List<Boolean>, msbFirst: Boolean = true): ByteArray {
    val bytes = ByteArray(bits.size / 8)
    for (i in bytes.indices) {
        var value = 0
        for (j in 0..7) {
            val bitIndex = if (msbFirst) j else 7 - j   // ← bitIndex mirrors j when LSB
            if (bits[i * 8 + j]) {
                value = value or (1 shl (7 - bitIndex))  // ← when LSB: (7 - (7-j)) = j
            }
        }
        bytes[i] = value.toByte()
    }
    return bytes
}
```

When `msbFirst = true`: `bitIndex = j`, shift = `7 - j` → correct (bit 0 → bit 7, bit 7 → bit 0).  
When `msbFirst = false`: `bitIndex = 7 - j`, shift = `7 - (7 - j) = j` → this means bit 0 of input → position 0, bit 1 → position 1, which is actually MSB-first again. The LSB path is a no-op inversion that cancels itself.

The app currently uses `msbFirst = true` everywhere consistently, so this bug has no runtime impact today. But if LSB mode is ever used (e.g., for future protocol changes), it will silently produce incorrect bytes.

### The Fix

```kotlin
fun bitsToBytes(bits: List<Boolean>, msbFirst: Boolean = true): ByteArray {
    require(bits.size % 8 == 0) { "Bit count must be multiple of 8" }
    val bytes = ByteArray(bits.size / 8)
    for (i in bytes.indices) {
        var value = 0
        for (j in 0..7) {
            if (bits[i * 8 + j]) {
                val shift = if (msbFirst) (7 - j) else j  // ← correct logic
                value = value or (1 shl shift)
            }
        }
        bytes[i] = value.toByte()
    }
    return bytes
}
```

---

## 9. Complete Fix Implementations

### 9.1 RxPipeline.kt — Full Corrected Version

```kotlin
class RxPipeline(
    private val roiTracker: RoiTracker = RoiTracker(halfSize = 50),
    private val smoother: SignalSmoother = SignalSmoother(),
    private val threshold: AdaptiveThreshold = AdaptiveThreshold(),
    private val manchesterDecoder: ManchesterStreamDecoder = ManchesterStreamDecoder(),
    private val assembler: PacketAssembler = PacketAssembler(),
    var bitDurationMs: Long = 200L
) {
    private val symbolBuffer = ArrayList<Boolean>()
    private var locked = false
    private var lastBitBoundaryMs = 0L
    private val pendingFrameBrightness = ArrayList<Float>()

    fun reset() {
        roiTracker.reset()
        smoother.reset()
        threshold.reset()
        assembler.reset()
        symbolBuffer.clear()
        pendingFrameBrightness.clear()
        locked = false
        lastBitBoundaryMs = 0L
    }

    fun processFrame(buffer: FrameBuffer, timestampMs: Long = System.currentTimeMillis()): RxFrameResult {
        val (roi, _) = roiTracker.update(buffer)
        val signal = SignalExtractor.extract(buffer, roi)
        val smoothed = smoother.smooth(signal)
        pendingFrameBrightness.add(smoothed)

        val elapsed = timestampMs - lastBitBoundaryMs
        if (elapsed < bitDurationMs && lastBitBoundaryMs != 0L) {
            // Still within current bit period — accumulate, don't emit symbol yet
            return RxFrameResult(packet = null, signal = smoothed, locked = locked)
        }

        // Bit boundary reached — average accumulated frames into one symbol
        lastBitBoundaryMs = timestampMs
        val avgSignal = pendingFrameBrightness.average().toFloat()
        pendingFrameBrightness.clear()

        val thresh = threshold.update(avgSignal, isLocked = locked)
        val symbol = OnOffDetector.detect(avgSignal, thresh)
        symbolBuffer.add(symbol)

        val start = PreambleDetector.findStart(symbolBuffer, Protocol.preambleBits, maxErrors = 2)
        var packet: ParsedPacket? = null

        if (start >= 0) {
            val decodeStart = start + Protocol.preambleBits.size
            val bits = manchesterDecoder.decode(symbolBuffer, decodeStart)
            if (decodeStart > 0) symbolBuffer.subList(0, decodeStart).clear()
            packet = assembler.appendBits(bits)
            locked = true
            if (packet != null) {
                assembler.reset()
                symbolBuffer.clear()
                locked = false
            }
        } else {
            if (symbolBuffer.size > 512) {
                // Prevent unbounded growth if no preamble ever found
                symbolBuffer.subList(0, symbolBuffer.size - 64).clear()
            }
            locked = false
        }

        return RxFrameResult(packet = packet, signal = smoothed, locked = locked)
    }
}
```

### 9.2 CameraAnalyzer — Pass Timestamp, Guard Exceptions

```kotlin
private class CameraAnalyzer(
    private val pipeline: RxPipeline,
    private val onUpdate: (RxFrameResult) -> Unit,
    private val isReceiving: () -> Boolean
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        if (!isReceiving()) { image.close(); return }
        try {
            val timestampMs = image.imageInfo.timestamp / 1_000_000L
            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val frame = FrameBuffer(image.width, image.height, data)
            val result = pipeline.processFrame(frame, timestampMs)
            onUpdate(result)
        } catch (e: Exception) {
            Log.e("CameraAnalyzer", "Frame error: ${e.message}", e)
        } finally {
            image.close()
        }
    }
}
```

### 9.3 Manchester.kt — Skip Invalid Pairs Instead of Throwing

```kotlin
object Manchester {
    fun encode(bits: List<Boolean>): List<Boolean> {
        val encoded = ArrayList<Boolean>(bits.size * 2)
        for (bit in bits) {
            if (bit) { encoded.add(false); encoded.add(true) }
            else     { encoded.add(true);  encoded.add(false) }
        }
        return encoded
    }

    fun decode(symbols: List<Boolean>): List<Boolean> {
        require(symbols.size % 2 == 0) { "Symbol count must be even" }
        val decoded = ArrayList<Boolean>(symbols.size / 2)
        var i = 0
        while (i < symbols.size) {
            val a = symbols[i]; val b = symbols[i + 1]
            when {
                !a && b -> decoded.add(true)
                a && !b -> decoded.add(false)
                else    -> { /* Invalid pair — noise, skip gracefully */ }
            }
            i += 2
        }
        return decoded
    }
}
```

### 9.4 AdaptiveThreshold.kt — Freeze During Lock

```kotlin
class AdaptiveThreshold(
    private val smoothing: Float = 0.9f,
    private val delta: Float = 15f
) {
    private var ambient: Float? = null

    fun reset() { ambient = null }

    fun update(signal: Float, isLocked: Boolean = false): Float {
        if (!isLocked) {
            ambient = if (ambient == null) signal
                      else ambient!! * smoothing + signal * (1f - smoothing)
        }
        return (ambient ?: signal) + delta
    }
}
```

---

## 10. Testing Checklist

Use this checklist after applying fixes to validate end-to-end operation.

### Environment Setup
- [ ] Two Android phones, one transmitter and one receiver
- [ ] Room lighting is consistent and moderate (not pitch dark, not bright sunlight)
- [ ] Phones are 20–50 cm apart, flash pointed directly at rear camera lens
- [ ] `adb logcat` running on receiver phone to observe log output

### Baseline Signal Tests
- [ ] Start Receive on phone B
- [ ] Observe `Signal` value in UI — should be non-zero (ambient light reading)
- [ ] Cover camera lens — signal should drop toward 0
- [ ] Point transmitter flash at receiver with flash manually on — signal should spike above 100
- [ ] Confirm threshold adapts within 5–10 seconds of ambient

### Transmission Tests
- [ ] Send a 1-character message (e.g., `"A"`) — shortest possible packet
- [ ] Confirm `Receiver locked: true` appears briefly in UI
- [ ] Confirm received message appears in Output field
- [ ] Send a 10-character message — confirm full receive
- [ ] Send 5 messages back-to-back — confirm all received correctly

### Error Rate Tests
- [ ] Introduce mild movement between phones — error rate should stay below 20%
- [ ] Double the distance to ~100cm — confirm receive still works at `bitDurationMs = 350`
- [ ] Enable `BitrateController` to auto-select duration based on signal strength

### Logcat Validation
- [ ] No `IllegalArgumentException` from `Manchester.kt`
- [ ] No uncaught exceptions from `CameraAnalyzer`
- [ ] `preamble found` log entries appear (add log inside RxPipeline when `start >= 0`)
- [ ] `packet CRC OK` log entries appear on successful receive

---

## 11. Summary Table

| # | Bug | File | Severity | Status |
|---|-----|------|----------|--------|
| 1 | No clock recovery — each camera frame generates one symbol, causing 6× symbol repetition at 200ms/bit with 30fps camera | `RxPipeline.kt` | 🔴 Critical | Preamble never detected |
| 2 | `symbolBuffer.clear()` on preamble detection destroys data before packet is fully received | `RxPipeline.kt` | 🔴 Critical | Assembler never completes |
| 3 | Uncaught `IllegalArgumentException` from invalid Manchester pairs kills the CameraX analysis thread silently | `Manchester.kt` / `ManchesterStreamDecoder.kt` | 🔴 Critical | Receiver dies after first bad frame |
| 4 | Preamble is raw bits but data is Manchester-encoded — different symbol rates cause timing mismatch | `Protocol.kt` | 🟡 Medium | Clock misalignment after sync |
| 5 | Adaptive threshold updates on every frame including flash-ON frames, drifting up and collapsing the ON/OFF margin | `AdaptiveThreshold.kt` | 🟡 Medium | Bit detection degrades over time |
| 6 | `bitsToBytes` LSB-first mode inverts twice — produces MSB-first output, bug latent if LSB mode used | `BitCodec.kt` | 🟡 Medium | Latent — no current runtime impact |

### Severity Key
- 🔴 **Critical** — Prevents any reception whatsoever. Fix required before basic functionality.
- 🟡 **Medium** — Degrades reliability or accuracy under real conditions. Fix required for production use.

### Root Cause Summary

The receiver fails entirely due to three compounding critical bugs. Bug #1 (no clock recovery) produces garbage in the symbol buffer. Bug #3 (uncaught exception) then crashes the thread when Manchester tries to decode that garbage. Even if both were fixed, Bug #2 would clear the symbol buffer too early, preventing packets from ever being fully assembled. All three must be fixed together for the receiver to work at all.

---

*Report generated from source analysis of `Transmit-main` (Kotlin/Android). All code references are to files in `src/main/kotlin/com/reminder/morse/`.*
