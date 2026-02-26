package com.reminder.morse.adaptation

class BitrateController {
    fun chooseBitDurationMs(signal: Float): Long {
        return when {
            signal > 120f -> 80L
            signal > 80f -> 120L
            signal > 40f -> 200L
            signal > 20f -> 350L
            else -> 500L
        }
    }
}
