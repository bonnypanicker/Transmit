package com.reminder.morse.tx

import android.hardware.camera2.CameraManager

class FlashController(
    private val cameraManager: CameraManager,
    private val cameraId: String
) {
    fun setFlash(on: Boolean) {
        cameraManager.setTorchMode(cameraId, on)
    }

    fun turnOff() {
        setFlash(false)
    }
}
