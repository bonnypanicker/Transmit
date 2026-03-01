package com.reminder.morse.rx

class AdaptiveThreshold(
    private val smoothing: Float = 0.9f,
    private val delta: Float = 15f
) {
    private var ambient: Float? = null

    fun reset() {
        ambient = null
    }

    // Bug #5 Fix: Accept isLocked parameter — freeze ambient when actively receiving
    fun update(signal: Float, isLocked: Boolean = false): Float {
        if (!isLocked) {
            // Only adapt when we are NOT actively receiving
            ambient = if (ambient == null) signal
                      else ambient!! * smoothing + signal * (1f - smoothing)
        }
        return (ambient ?: signal) + delta
    }
}
