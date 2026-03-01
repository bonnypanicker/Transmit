package com.reminder.morse.rx

import com.reminder.morse.core.ParsedPacket
import com.reminder.morse.core.Protocol

data class RxFrameResult(
    val packet: ParsedPacket?,
    val signal: Float,
    val locked: Boolean,
    val detectionMode: String = "",
    val debug: String = ""
)

class RxPipeline(
    private val blinkTracker: BlinkTracker = BlinkTracker(),
    private val threshold: AdaptiveThreshold = AdaptiveThreshold(),
    private val manchesterDecoder: ManchesterStreamDecoder = ManchesterStreamDecoder(),
    private val assembler: PacketAssembler = PacketAssembler(),
    var bitDurationMs: Long = 200L
) {
    private val symbolBuffer = ArrayList<Boolean>()
    private var locked = false
    private var lastBitBoundaryMs = 0L
    private val pendingFrameBrightness = ArrayList<Float>()
    private var receivedSymbolCount = 0
    private var lastAvgSignal = 0f
    private var lastThresh = 0f
    private var preambleFoundOnce = false

    // Track the last valid blink center to tolerate 1-frame drops
    private var lastValidPoint: Point? = null
    private var pointLostFrames = 0

    /* ---- Lifecycle ---- */

    fun reset() {
        blinkTracker.reset()
        threshold.reset()
        assembler.reset()
        symbolBuffer.clear()
        pendingFrameBrightness.clear()
        locked = false
        lastBitBoundaryMs = 0L
        receivedSymbolCount = 0
        lastAvgSignal = 0f
        lastThresh = 0f
        preambleFoundOnce = false
        lastValidPoint = null
        pointLostFrames = 0
    }

    /* ---- Frame processing ---- */

    fun processFrame(
        buffer: FrameBuffer,
        timestampMs: Long = System.currentTimeMillis()
    ): RxFrameResult {
        // Track the blinking signal
        val targetPoint = blinkTracker.update(buffer)
        
        if (targetPoint != null) {
            lastValidPoint = targetPoint
            pointLostFrames = 0
        } else {
            pointLostFrames++
            if (pointLostFrames > 15) { // Drop lock after ~500ms
                lastValidPoint = null
            }
        }

        val activePoint = targetPoint ?: lastValidPoint
        
        val signal = if (activePoint != null) {
            // Sample a 40x40 box around the active point
            val roi = Roi(
                activePoint.x - 20, activePoint.y - 20, 
                activePoint.x + 20, activePoint.y + 20
            )
            SignalExtractor.extract(buffer, roi)
        } else {
            0f
        }
        
        val detectionMode = if (activePoint != null) "\uD83D\uDD0D Blink Locked" else "\uD83D\uDD04 Scanning Grid..."

        // Process temporal bits
        pendingFrameBrightness.add(signal)

        val lastSignal = if (pendingFrameBrightness.size > 1) pendingFrameBrightness[pendingFrameBrightness.size - 2] else signal
        val isEdge = kotlin.math.abs(signal - lastSignal) > 60f
        
        val elapsed = timestampMs - lastBitBoundaryMs
        val timeUp = elapsed >= bitDurationMs
        val edgeTrigger = isEdge && elapsed >= (bitDurationMs * 0.6f)
        
        val forceBoundary = timeUp || edgeTrigger

        if (!forceBoundary && lastBitBoundaryMs != 0L) {
            val debugStr = buildDebugString()
            return RxFrameResult(
                packet = null, signal = signal,
                locked = locked, detectionMode = detectionMode,
                debug = debugStr
            )
        }

        lastBitBoundaryMs = timestampMs
        
        val framesForCurrentSymbol = if (edgeTrigger && pendingFrameBrightness.size > 1) {
            val valid = ArrayList(pendingFrameBrightness.take(pendingFrameBrightness.size - 1))
            pendingFrameBrightness.clear()
            pendingFrameBrightness.add(signal)
            valid
        } else {
            val valid = ArrayList(pendingFrameBrightness)
            pendingFrameBrightness.clear()
            valid
        }
        
        val avgSignal = if (framesForCurrentSymbol.isNotEmpty()) framesForCurrentSymbol.average().toFloat() else signal
        lastAvgSignal = avgSignal

        val thresh = threshold.update(avgSignal, isLocked = locked)
        lastThresh = thresh
        val symbol = OnOffDetector.detect(avgSignal, thresh)
        symbolBuffer.add(symbol)

        var packet: ParsedPacket? = null

        if (!locked) {
            val start = PreambleDetector.findStart(
                symbolBuffer, Protocol.preambleSymbols, maxErrors = 6
            )
            if (start >= 0) {
                preambleFoundOnce = true
                val decodeStart = start + Protocol.preambleSymbols.size
                val bits = manchesterDecoder.decode(symbolBuffer, decodeStart)
                symbolBuffer.clear()
                receivedSymbolCount = 0

                if (bits.isNotEmpty()) {
                    packet = assembler.appendBits(bits)
                }
                locked = true

                if (packet != null) {
                    assembler.reset()
                    locked = false
                }
            } else {
                if (symbolBuffer.size > 512) {
                    symbolBuffer.subList(0, symbolBuffer.size - 64).clear()
                }
            }
        } else {
            receivedSymbolCount++

            if (symbolBuffer.size >= 2) {
                val consumeCount = (symbolBuffer.size / 2) * 2
                val bits = manchesterDecoder.decode(symbolBuffer, 0)

                if (consumeCount > 0) {
                    symbolBuffer.subList(0, consumeCount).clear()
                }

                if (bits.isNotEmpty()) {
                    packet = assembler.appendBits(bits)
                }

                if (packet != null) {
                    assembler.reset()
                    symbolBuffer.clear()
                    locked = false
                    receivedSymbolCount = 0
                }
            }

            if (receivedSymbolCount > 4200) {
                assembler.reset()
                symbolBuffer.clear()
                locked = false
                receivedSymbolCount = 0
            }
        }

        val debugStr = buildDebugString()
        return RxFrameResult(
            packet = packet, signal = signal,
            locked = locked, detectionMode = detectionMode,
            debug = debugStr
        )
    }

    private fun buildDebugString(): String {
        val state = if (locked) "RX(${receivedSymbolCount}sym)" else "SEARCH(${symbolBuffer.size}sym)"
        val preamble = if (preambleFoundOnce) "pre:✓" else "pre:✗"
        return "$state $preamble avg:${"%.0f".format(lastAvgSignal)} thr:${"%.0f".format(lastThresh)}"
    }
}
