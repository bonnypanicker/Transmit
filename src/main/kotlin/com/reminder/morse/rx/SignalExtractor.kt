package com.reminder.morse.rx

object SignalExtractor {
    fun extract(buffer: FrameBuffer, roi: Roi, clusterSize: Int = 20): Float {
        val values = ArrayList<Int>(roi.width * roi.height)
        val width = buffer.width
        val yPlane = buffer.luminance
        var y = roi.top
        while (y <= roi.bottom) {
            val rowOffset = y * width
            var x = roi.left
            while (x <= roi.right) {
                val index = rowOffset + x
                values.add(yPlane[index].toInt() and 0xFF)
                x += 1
            }
            y += 1
        }
        values.sortDescending()
        val takeCount = clusterSize.coerceAtMost(values.size)
        if (takeCount == 0) {
            return 0f
        }
        var sum = 0f
        for (i in 0 until takeCount) {
            sum += values[i]
        }
        return sum / takeCount.toFloat()
    }
}
