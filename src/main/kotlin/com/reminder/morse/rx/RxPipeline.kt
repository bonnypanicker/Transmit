package com.reminder.morse.rx

import com.reminder.morse.core.ParsedPacket
import com.reminder.morse.core.Protocol

data class RxFrameResult(
    val packet: ParsedPacket?,
    val signal: Float,
    val locked: Boolean
)

class RxPipeline(
    private val roiTracker: RoiTracker = RoiTracker(halfSize = 50),
    private val smoother: SignalSmoother = SignalSmoother(),
    private val threshold: AdaptiveThreshold = AdaptiveThreshold(),
    private val manchesterDecoder: ManchesterStreamDecoder = ManchesterStreamDecoder(),
    private val assembler: PacketAssembler = PacketAssembler()
) {
    private val symbolBuffer = ArrayList<Boolean>()
    private var locked = false

    fun reset() {
        roiTracker.reset()
        smoother.reset()
        threshold.reset()
        assembler.reset()
        symbolBuffer.clear()
        locked = false
    }

    fun processFrame(buffer: FrameBuffer): RxFrameResult {
        val (roi, _) = roiTracker.update(buffer)
        val signal = SignalExtractor.extract(buffer, roi)
        val smoothed = smoother.smooth(signal)
        val thresh = threshold.update(smoothed)
        val symbol = OnOffDetector.detect(smoothed, thresh)
        symbolBuffer.add(symbol)

        val start = PreambleDetector.findStart(symbolBuffer, Protocol.preambleBits, maxErrors = 2)
        var packet: ParsedPacket? = null
        if (start >= 0) {
            val decodeStart = start + Protocol.preambleBits.size
            val bits = manchesterDecoder.decode(symbolBuffer, decodeStart)
            symbolBuffer.clear()
            packet = assembler.appendBits(bits)
            locked = true
        } else {
            locked = false
        }
        return RxFrameResult(packet = packet, signal = smoothed, locked = locked)
    }
}
