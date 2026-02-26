package com.reminder.morse.tx

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FlashTransmitter(
    private val flashController: FlashController,
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
) {
    private val running = AtomicBoolean(false)
    private var future: ScheduledFuture<*>? = null

    fun transmit(symbols: List<Boolean>, bitDurationMs: Long, onComplete: (() -> Unit)? = null) {
        cancel()
        if (symbols.isEmpty()) {
            flashController.turnOff()
            onComplete?.invoke()
            return
        }
        val index = AtomicInteger(0)
        running.set(true)
        future = scheduler.scheduleAtFixedRate({
            try {
                val i = index.getAndIncrement()
                if (i < symbols.size) {
                    flashController.setFlash(symbols[i])
                } else {
                    flashController.turnOff()
                    future?.cancel(false)
                    running.set(false)
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                flashController.turnOff()
                future?.cancel(false)
                running.set(false)
                onComplete?.invoke()
            }
        }, 0, bitDurationMs, TimeUnit.MILLISECONDS)
    }

    fun cancel() {
        future?.cancel(false)
        future = null
        running.set(false)
        flashController.turnOff()
    }

    fun isRunning(): Boolean = running.get()
}
