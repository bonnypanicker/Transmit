package com.reminder.morse.tx

import android.hardware.camera2.CameraManager
import android.util.Log

class FlashController(
    private val cameraManager: CameraManager,
    private val cameraId: String
) {
    fun setFlash(on: Boolean) {
        try {
            cameraManager.setTorchMode(cameraId, on)
        } catch (e: Exception) {
            Log.e("FlashController", "Failed to set flash: ${e.message}")
        }
    }

    fun turnOff() {
        setFlash(false)
    }
}
