package com.reminder.morse.tx

import androidx.camera.core.CameraControl

class FlashController(
    private val cameraControl: CameraControl
) {
    fun setFlash(on: Boolean) {
        cameraControl.enableTorch(on)
    }

    fun turnOff() {
        setFlash(false)
    }
}
