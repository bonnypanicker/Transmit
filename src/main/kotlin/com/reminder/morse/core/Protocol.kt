package com.reminder.morse.core

data class ParsedPacket(
    val payload: ByteArray,
    val crcOk: Boolean,
    val expectedLength: Int
)

object Protocol {
    val preambleBits: List<Boolean> = List(16) { index -> index % 2 == 0 }

    fun buildPacket(payload: ByteArray): ByteArray {
        require(payload.size <= 255) { "Payload too large" }
        val body = ByteArray(1 + payload.size)
        body[0] = payload.size.toByte()
        payload.copyInto(body, destinationOffset = 1)
        val crc = Crc.crc8(body).toByte()
        val packet = ByteArray(body.size + 1)
        body.copyInto(packet, destinationOffset = 0)
        packet[packet.lastIndex] = crc
        return packet
    }

    fun buildTxSymbols(payload: ByteArray): List<Boolean> {
        val packet = buildPacket(payload)
        val bits = BitCodec.bytesToBits(packet, msbFirst = true)
        val symbols = Manchester.encode(bits)
        val combined = ArrayList<Boolean>(preambleBits.size + symbols.size)
        combined.addAll(preambleBits)
        combined.addAll(symbols)
        return combined
    }

    fun parsePacket(packet: ByteArray): ParsedPacket {
        require(packet.size >= 2) { "Packet too short" }
        val expectedLength = packet[0].toInt() and 0xFF
        val expectedSize = 1 + expectedLength + 1
        require(packet.size >= expectedSize) { "Packet length mismatch" }
        val payload = packet.copyOfRange(1, 1 + expectedLength)
        val crcValue = packet[1 + expectedLength].toInt() and 0xFF
        val body = packet.copyOfRange(0, 1 + expectedLength)
        val crcOk = Crc.crc8(body) == crcValue
        return ParsedPacket(payload = payload, crcOk = crcOk, expectedLength = expectedLength)
    }
}
