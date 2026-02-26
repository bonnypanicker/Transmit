package com.reminder.morse.tx

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

object TorchSelector {
    fun findTorchCameraId(cameraManager: CameraManager): String? {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (hasFlash) {
                return cameraId
            }
        }
        return null
    }
}
