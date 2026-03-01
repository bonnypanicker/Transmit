package com.reminder.morse.rx

import com.reminder.morse.core.Manchester
import com.reminder.morse.core.ParsedPacket
import com.reminder.morse.core.Protocol

data class RxFrameResult(
    val packet: ParsedPacket?,
    val signal: Float,
    val locked: Boolean,
    val detectionMode: String = ""
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
    private var receivedSymbolCount = 0  // symbols received since lock, for timeout

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
    }

    /* ---- Frame processing ---- */

    fun processFrame(
        buffer: FrameBuffer,
        timestampMs: Long = System.currentTimeMillis(),
        rotationDegrees: Int = 0
    ): RxFrameResult {
        val (roi, _) = roiTracker.update(buffer, rotationDegrees)
        val signal = SignalExtractor.extract(buffer, roi)
        val smoothed = smoother.smooth(signal)
        pendingFrameBrightness.add(smoothed)

        // Clock recovery: accumulate frames within a bit period
        val elapsed = timestampMs - lastBitBoundaryMs
        if (elapsed < bitDurationMs && lastBitBoundaryMs != 0L) {
            return RxFrameResult(
                packet = null, signal = smoothed,
                locked = locked, detectionMode = roiTracker.lastDetectionMode
            )
        }

        // Bit boundary — average accumulated frames into one symbol
        lastBitBoundaryMs = timestampMs
        val avgSignal = pendingFrameBrightness.average().toFloat()
        pendingFrameBrightness.clear()

        val thresh = threshold.update(avgSignal, isLocked = locked)
        val symbol = OnOffDetector.detect(avgSignal, thresh)
        symbolBuffer.add(symbol)

        var packet: ParsedPacket? = null

        if (!locked) {
            // ── SEARCHING state: scan for preamble ──
            val start = PreambleDetector.findStart(
                symbolBuffer, Protocol.preambleSymbols, maxErrors = 2
            )
            if (start >= 0) {
                val decodeStart = start + Protocol.preambleSymbols.size
                val bits = manchesterDecoder.decode(symbolBuffer, decodeStart)

                // Clear everything — we extracted what we need from the buffer
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
                // Prevent unbounded growth while searching
                if (symbolBuffer.size > 512) {
                    symbolBuffer.subList(0, symbolBuffer.size - 64).clear()
                }
            }
        } else {
            // ── RECEIVING state: keep feeding symbols to the Manchester decoder ──
            receivedSymbolCount++

            if (symbolBuffer.size >= 2) {
                // Decode complete pairs, keep any leftover odd symbol
                val pairCount = symbolBuffer.size / 2
                val consumeCount = pairCount * 2
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

            // Timeout: if we've received too many symbols without completing
            // a packet, something went wrong — go back to searching
            // (max packet ~ 255 bytes = 2040 bits = 4080 Manchester symbols)
            if (receivedSymbolCount > 4200) {
                assembler.reset()
                symbolBuffer.clear()
                locked = false
                receivedSymbolCount = 0
            }
        }

        return RxFrameResult(
            packet = packet, signal = smoothed,
            locked = locked, detectionMode = roiTracker.lastDetectionMode
        )
    }
}
