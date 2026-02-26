package com.reminder.morse.rx

object PreambleDetector {
    fun findStart(symbols: List<Boolean>, preamble: List<Boolean>, maxErrors: Int = 0): Int {
        if (preamble.isEmpty() || symbols.size < preamble.size) {
            return -1
        }
        val lastStart = symbols.size - preamble.size
        var start = 0
        while (start <= lastStart) {
            var errors = 0
            var i = 0
            while (i < preamble.size) {
                if (symbols[start + i] != preamble[i]) {
                    errors += 1
                    if (errors > maxErrors) {
                        break
                    }
                }
                i += 1
            }
            if (errors <= maxErrors) {
                return start
            }
            start += 1
        }
        return -1
    }
}
