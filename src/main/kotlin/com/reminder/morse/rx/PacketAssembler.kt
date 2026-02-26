package com.reminder.morse.rx

import com.reminder.morse.core.BitCodec
import com.reminder.morse.core.ParsedPacket
import com.reminder.morse.core.Protocol

class PacketAssembler {
    private val bitBuffer = ArrayList<Boolean>()

    fun reset() {
        bitBuffer.clear()
    }

    fun appendBits(bits: List<Boolean>): ParsedPacket? {
        bitBuffer.addAll(bits)
        if (bitBuffer.size < 8) {
            return null
        }
        val headerBits = bitBuffer.subList(0, 8)
        val headerByte = BitCodec.bitsToBytes(headerBits, msbFirst = true)[0]
        val payloadLength = headerByte.toInt() and 0xFF
        val totalBytes = 1 + payloadLength + 1
        val totalBits = totalBytes * 8
        if (bitBuffer.size < totalBits) {
            return null
        }
        val packetBits = bitBuffer.subList(0, totalBits)
        val packetBytes = BitCodec.bitsToBytes(packetBits, msbFirst = true)
        bitBuffer.subList(0, totalBits).clear()
        return Protocol.parsePacket(packetBytes)
    }
}
