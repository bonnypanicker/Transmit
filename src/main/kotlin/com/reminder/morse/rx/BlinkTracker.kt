package com.reminder.morse.rx

/**
 * Ultra-efficient blink detector optimized for Android Camera.
 * Completely ignores static lights (like the sun or lamps) and 
 * exclusively locks onto flashing patterns (high temporal amplitude).
 * 
 * Hardware Optimizations:
 * 1. Grid Downsampling: Divides the frame into 32x32 blocks to minimize CPU load.
 * 2. Pixel Striding: Samples only 1 out of every 16 pixels within a block.
 * 3. Min/Max Tracking: Math-light amplitude tracking replaces heavy variance calculations.
 */
class BlinkTracker(
    private val blockSize: Int = 32,
    private val historyLength: Int = 12, // About 400ms @ 30fps
    private val minAmplitude: Float = 40f
) {
    private var gridCols = 0
    private var gridRows = 0
    private var history: FloatArray? = null
    private var historyIdx = 0
    private var framesCollected = 0

    fun reset() {
        history = null
        framesCollected = 0
    }

    fun update(buffer: FrameBuffer): Point? {
        val cols = buffer.width / blockSize
        val rows = buffer.height / blockSize
        if (cols == 0 || rows == 0) return null

        val historySize = cols * rows * historyLength
        if (history == null || gridCols != cols || gridRows != rows) {
            gridCols = cols
            gridRows = rows
            history = FloatArray(historySize)
            historyIdx = 0
            framesCollected = 0
        }

        val hist = history!!
        var bestAmplitude = -1f
        var bestCol = -1
        var bestRow = -1

        val validFrames = framesCollected.coerceAtMost(historyLength)
        val canCheck = validFrames >= historyLength / 2

        // Fast grid search
        for (r in 0 until rows) {
            val sy = r * blockSize
            for (c in 0 until cols) {
                val sx = c * blockSize
                
                // Downsampled block average (stride by 4 to save CPU)
                var sum = 0
                var count = 0
                for (y in sy until sy + blockSize step 4) {
                    val rowOffset = y * buffer.width
                    for (x in sx until sx + blockSize step 4) {
                        sum += buffer.luminance[rowOffset + x].toInt() and 0xFF
                        count++
                    }
                }
                val avg = sum.toFloat() / count

                val blockIdx = (r * cols + c) * historyLength
                hist[blockIdx + historyIdx] = avg

                // Check amplitude if we have enough history
                if (canCheck) {
                    var min = 255f
                    var max = 0f
                    for (i in 0 until validFrames) {
                        val v = hist[blockIdx + i]
                        if (v < min) min = v
                        if (v > max) max = v
                    }
                    val amplitude = max - min
                    if (amplitude > bestAmplitude) {
                        bestAmplitude = amplitude
                        bestCol = c
                        bestRow = r
                    }
                }
            }
        }

        historyIdx = (historyIdx + 1) % historyLength
        framesCollected++

        if (bestAmplitude >= minAmplitude && bestCol >= 0 && bestRow >= 0) {
            return Point(
                bestCol * blockSize + blockSize / 2,
                bestRow * blockSize + blockSize / 2
            )
        }

        return null
    }
}
