package com.reminder.morse.ui

data class SenderState(
    val message: String = "",
    val isTransmitting: Boolean = false,
    val bitDurationMs: Long = 200L,
    val status: String = ""
)

data class ReceiverState(
    val isReceiving: Boolean = false,
    val lastMessage: String = "",
    val signal: Float = 0f,
    val locked: Boolean = false,
    val errorRate: Float = 0f
)
