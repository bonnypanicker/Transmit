package com.reminder.morse.rx

object OnOffDetector {
    fun detect(signal: Float, threshold: Float): Boolean {
        return signal > threshold
    }
}
