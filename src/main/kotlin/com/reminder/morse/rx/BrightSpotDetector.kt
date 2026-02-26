package com.reminder.morse.rx

data class BrightSpot(
    val point: Point,
    val value: Int
)

object BrightSpotDetector {
    fun findBrightest(buffer: FrameBuffer, roi: Roi?): BrightSpot {
        val width = buffer.width
        val height = buffer.height
        val left = roi?.left ?: 0
        val top = roi?.top ?: 0
        val right = roi?.right ?: (width - 1)
        val bottom = roi?.bottom ?: (height - 1)

        var maxValue = -1
        var maxX = left
        var maxY = top
        val yPlane = buffer.luminance
        var y = top
        while (y <= bottom) {
            var x = left
            val rowOffset = y * width
            while (x <= right) {
                val index = rowOffset + x
                val value = yPlane[index].toInt() and 0xFF
                if (value > maxValue) {
                    maxValue = value
                    maxX = x
                    maxY = y
                }
                x += 1
            }
            y += 1
        }
        return BrightSpot(Point(maxX, maxY), maxValue)
    }
}
