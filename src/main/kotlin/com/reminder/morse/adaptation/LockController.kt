package com.reminder.morse.adaptation

class LockController(
    private val lossThreshold: Int = 5
) {
    private var misses = 0

    fun onLock() {
        misses = 0
    }

    fun onMiss() {
        misses += 1
    }

    fun shouldResync(): Boolean {
        return misses >= lossThreshold
    }
}
