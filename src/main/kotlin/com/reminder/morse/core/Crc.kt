package com.reminder.morse.core

object Crc {
    fun crc8(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
        }
        return crc and 0xFF
    }
}
