package com.reminder.morse.rx

class AdaptiveThreshold(
    private val decayRate: Float = 0.05f // Slow decay to track AE changes
) {
    private var localMin: Float? = null
    private var localMax: Float? = null

    fun reset() {
        localMin = null
        localMax = null
    }

    fun update(signal: Float, isLocked: Boolean = false): Float {
        if (localMin == null || localMax == null) {
            localMin = signal - 10f
            localMax = signal + 10f
        }

        var min = localMin!!
        var max = localMax!!

        if (signal < min) min = signal
        if (signal > max) max = signal

        // Drift min and max towards the overall average to handle gradual light changes
        val mid = (max + min) / 2f
        max -= (max - mid) * decayRate
        min += (mid - min) * decayRate

        // Ensure minimum gap so we don't trigger on tiny noise
        if (max - min < 20f) {
            max = mid + 10f
            min = mid - 10f
        }

        localMin = min
        localMax = max

        return (max + min) / 2f
    }
}
