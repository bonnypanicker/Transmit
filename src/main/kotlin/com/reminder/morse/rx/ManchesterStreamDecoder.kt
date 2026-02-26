package com.reminder.morse.rx

import com.reminder.morse.core.Manchester

class ManchesterStreamDecoder {
    fun decode(symbols: List<Boolean>, startIndex: Int): List<Boolean> {
        val remaining = symbols.size - startIndex
        if (remaining <= 1) {
            return emptyList()
        }
        val endExclusive = if (remaining % 2 == 0) symbols.size else symbols.size - 1
        val slice = symbols.subList(startIndex, endExclusive)
        return Manchester.decode(slice)
    }
}
