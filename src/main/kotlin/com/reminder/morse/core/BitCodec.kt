package com.reminder.morse.core

object BitCodec {
    fun bytesToBits(bytes: ByteArray, msbFirst: Boolean = true): List<Boolean> {
        val bits = ArrayList<Boolean>(bytes.size * 8)
        for (b in bytes) {
            val value = b.toInt() and 0xFF
            if (msbFirst) {
                for (i in 7 downTo 0) {
                    bits.add(((value shr i) and 1) == 1)
                }
            } else {
                for (i in 0..7) {
                    bits.add(((value shr i) and 1) == 1)
                }
            }
        }
        return bits
    }

    fun bitsToBytes(bits: List<Boolean>, msbFirst: Boolean = true): ByteArray {
        require(bits.size % 8 == 0) { "Bit count must be multiple of 8" }
        val bytes = ByteArray(bits.size / 8)
        for (i in bytes.indices) {
            var value = 0
            for (j in 0..7) {
                if (bits[i * 8 + j]) {
                    // Bug #6 Fix: Correct shift — MSB-first: bit 0 → position 7; LSB-first: bit 0 → position 0
                    val shift = if (msbFirst) (7 - j) else j
                    value = value or (1 shl shift)
                }
            }
            bytes[i] = value.toByte()
        }
        return bytes
    }
}
