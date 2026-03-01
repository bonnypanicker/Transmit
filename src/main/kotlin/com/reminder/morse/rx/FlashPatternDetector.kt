package com.reminder.morse.rx

/**
 * Detects the flash source by finding image regions with high temporal
 * brightness variance. A flashing LED alternates bright/dark rapidly,
 * producing high variance — static lights have near-zero variance.
 *
 * The image is divided into a grid of blocks. Each block maintains a
 * circular buffer of average brightness across recent frames. The block
 * with the highest variance above a threshold is the flash source.
 */
class FlashPatternDetector(
    private val blockSize: Int = 16,
    private val historyLength: Int = 12,   // ~400ms at 30fps ≈ 2 bit periods
    private val minVariance: Float = 100f
) {
    private var gridCols = 0
    private var gridRows = 0
    private var blockHistory: Array<FloatArray>? = null
    private var historyIndex = 0
    private var framesCollected = 0

    fun reset() {
        blockHistory = null
        gridCols = 0
        gridRows = 0
        historyIndex = 0
        framesCollected = 0
    }

    fun detect(buffer: FrameBuffer): FlashRegion? {
        val cols = buffer.width / blockSize
        val rows = buffer.height / blockSize
        if (cols == 0 || rows == 0) return null

        if (cols != gridCols || rows != gridRows) {
            gridCols = cols
            gridRows = rows
            blockHistory = Array(cols * rows) { FloatArray(historyLength) }
            historyIndex = 0
            framesCollected = 0
        }

        val history = blockHistory ?: return null

        // Store average luminance per block for this frame
        for (by in 0 until rows) {
            for (bx in 0 until cols) {
                history[by * cols + bx][historyIndex] = blockAverage(buffer, bx, by)
            }
        }
        historyIndex = (historyIndex + 1) % historyLength
        framesCollected++

        // Need enough history for meaningful variance
        if (framesCollected < historyLength / 2) return null

        // Find block with highest temporal variance
        val count = framesCollected.coerceAtMost(historyLength)
        var maxVar = 0f
        var maxIdx = -1
        for (i in history.indices) {
            val v = variance(history[i], count)
            if (v > maxVar) { maxVar = v; maxIdx = i }
        }

        if (maxVar < minVariance || maxIdx < 0) return null

        val row = maxIdx / gridCols
        val col = maxIdx % gridCols
        return FlashRegion(
            center = Point(col * blockSize + blockSize / 2, row * blockSize + blockSize / 2),
            variance = maxVar
        )
    }

    private fun blockAverage(buf: FrameBuffer, bx: Int, by: Int): Float {
        val sx = bx * blockSize; val sy = by * blockSize
        val ex = (sx + blockSize).coerceAtMost(buf.width)
        val ey = (sy + blockSize).coerceAtMost(buf.height)
        var sum = 0L; var n = 0
        for (y in sy until ey) {
            val ro = y * buf.width
            for (x in sx until ex) { sum += buf.luminance[ro + x].toInt() and 0xFF; n++ }
        }
        return if (n > 0) sum.toFloat() / n else 0f
    }

    private fun variance(values: FloatArray, count: Int): Float {
        if (count < 2) return 0f
        var s = 0f; var sq = 0f
        for (i in 0 until count) { s += values[i]; sq += values[i] * values[i] }
        val mean = s / count
        return sq / count - mean * mean
    }
}

data class FlashRegion(
    val center: Point,
    val variance: Float
)
