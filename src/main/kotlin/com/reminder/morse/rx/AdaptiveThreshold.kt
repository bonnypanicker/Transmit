package com.reminder.morse.rx

class AdaptiveThreshold(
    private val smoothing: Float = 0.9f,
    private val delta: Float = 15f
) {
    private var ambient: Float? = null

    fun reset() {
        ambient = null
    }

    fun update(signal: Float): Float {
        ambient = if (ambient == null) {
            signal
        } else {
            ambient!! * smoothing + signal * (1f - smoothing)
        }
        return (ambient ?: signal) + delta
    }
}
