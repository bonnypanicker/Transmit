package com.reminder.morse.tx

import com.reminder.morse.core.Protocol

class TxPipeline(
    private val transmitter: FlashTransmitter
) {
    fun transmitPayload(payload: ByteArray, bitDurationMs: Long, onComplete: (() -> Unit)? = null) {
        val symbols = Protocol.buildTxSymbols(payload)
        transmitter.transmit(symbols, bitDurationMs, onComplete)
    }
}
