package com.reminder.morse.core

object Manchester {
    fun encode(bits: List<Boolean>): List<Boolean> {
        val encoded = ArrayList<Boolean>(bits.size * 2)
        for (bit in bits) {
            if (bit) {
                encoded.add(false)
                encoded.add(true)
            } else {
                encoded.add(true)
                encoded.add(false)
            }
        }
        return encoded
    }

    fun decode(symbols: List<Boolean>): List<Boolean> {
        require(symbols.size % 2 == 0) { "Symbol count must be even" }
        val decoded = ArrayList<Boolean>(symbols.size / 2)
        var i = 0
        while (i < symbols.size) {
            val a = symbols[i]
            val b = symbols[i + 1]
            when {
                !a && b -> decoded.add(true)
                a && !b -> decoded.add(false)
                else -> throw IllegalArgumentException("Invalid Manchester symbol")
            }
            i += 2
        }
        return decoded
    }
}
