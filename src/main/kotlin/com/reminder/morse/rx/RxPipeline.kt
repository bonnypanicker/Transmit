package com.reminder.morse.rx

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

        val start = PreambleDetector.findStart(symbolBuffer, Protocol.preambleSymbols, maxErrors = 2)
        var packet: ParsedPacket? = null

        if (start >= 0) {
            val decodeStart = start + Protocol.preambleSymbols.size
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
                symbolBuffer.subList(0, symbolBuffer.size - 64).clear()
            }
            locked = false
        }

        return RxFrameResult(
            packet = packet, signal = smoothed,
            locked = locked, detectionMode = roiTracker.lastDetectionMode
        )
    }
}
