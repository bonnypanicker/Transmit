package com.reminder.morse.rx

class RoiTracker(
    private val halfSize: Int
) {
    private var current: Roi? = null

    fun reset() {
        current = null
    }

    fun update(buffer: FrameBuffer): Pair<Roi, BrightSpot> {
        val spot = BrightSpotDetector.findBrightest(buffer, current)
        val roi = buildRoi(buffer, spot.point)
        current = roi
        return Pair(roi, spot)
    }

    private fun buildRoi(buffer: FrameBuffer, center: Point): Roi {
        val left = (center.x - halfSize).coerceAtLeast(0)
        val right = (center.x + halfSize).coerceAtMost(buffer.width - 1)
        val top = (center.y - halfSize).coerceAtLeast(0)
        val bottom = (center.y + halfSize).coerceAtMost(buffer.height - 1)
        return Roi(left, top, right, bottom)
    }
}
