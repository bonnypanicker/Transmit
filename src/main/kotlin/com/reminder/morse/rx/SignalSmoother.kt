package com.reminder.morse.rx

class SignalSmoother(
    private val windowSize: Int = 5
) {
    private val history = ArrayDeque<Float>()

    fun reset() {
        history.clear()
    }

    fun smooth(value: Float): Float {
        history.add(value)
        if (history.size > windowSize) {
            history.removeFirst()
        }
        var sum = 0f
        for (v in history) {
            sum += v
        }
        return if (history.isEmpty()) 0f else sum / history.size
    }
}
