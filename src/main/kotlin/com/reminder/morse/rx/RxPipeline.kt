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
    private var receivedSymbolCount = 0
    private var lastAvgSignal = 0f
    private var lastThresh = 0f
    private var preambleFoundOnce = false

    /* ---- Manual lock delegation ---- */

    fun setManualLock(normalizedX: Float, normalizedY: Float) {
        roiTracker.setManualLock(normalizedX, normalizedY)
    }

    fun clearManualLock() {
        roiTracker.clearManualLock()
    }

    val isManuallyLocked: Boolean get() = roiTracker.isManuallyLocked

    /* ---- Lifecycle ---- */

    fun reset() {
        roiTracker.reset()
        smoother.reset()
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
    }

    /* ---- Frame processing ---- */

    fun processFrame(
        buffer: FrameBuffer,
        timestampMs: Long = System.currentTimeMillis(),
        rotationDegrees: Int = 0
    ): RxFrameResult {
        val (roi, _) = roiTracker.update(buffer, rotationDegrees)
        val signal = SignalExtractor.extract(buffer, roi)

        // Use RAW signal for bit detection — do NOT use the smoother here.
        // The smoother's moving average bleeds across bit boundaries, corrupting
        // ON→OFF transitions and making OFF bits look like ON bits.
        // Clock recovery already averages within each bit period cleanly.
        pendingFrameBrightness.add(signal)

        // Clock recovery: accumulate frames within a bit period
        val elapsed = timestampMs - lastBitBoundaryMs
        if (elapsed < bitDurationMs && lastBitBoundaryMs != 0L) {
            val debugStr = buildDebugString()
            return RxFrameResult(
                packet = null, signal = signal,
                locked = locked, detectionMode = roiTracker.lastDetectionMode,
                debug = debugStr
            )
        }

        // Bit boundary — average accumulated frames into one symbol
        lastBitBoundaryMs = timestampMs
        val avgSignal = pendingFrameBrightness.average().toFloat()
        pendingFrameBrightness.clear()
        lastAvgSignal = avgSignal

        val thresh = threshold.update(avgSignal, isLocked = locked)
        lastThresh = thresh
        val symbol = OnOffDetector.detect(avgSignal, thresh)
        symbolBuffer.add(symbol)

        var packet: ParsedPacket? = null

        if (!locked) {
            // ── SEARCHING state: scan for preamble ──
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
            // ── RECEIVING state: feed symbols to Manchester decoder ──
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
            locked = locked, detectionMode = roiTracker.lastDetectionMode,
            debug = debugStr
        )
    }

    private fun buildDebugString(): String {
        val state = if (locked) "RX(${receivedSymbolCount}sym)" else "SEARCH(${symbolBuffer.size}sym)"
        val preamble = if (preambleFoundOnce) "pre:✓" else "pre:✗"
        return "$state $preamble avg:${"%.0f".format(lastAvgSignal)} thr:${"%.0f".format(lastThresh)}"
    }
}
