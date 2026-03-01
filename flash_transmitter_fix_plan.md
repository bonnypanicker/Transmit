# Optical Flash Communication -- Fix & High-Speed Transmitter Implementation Plan

## Project Goal

Enable reliable LED flashlight transmission for an Android optical
communication app and upgrade it to a high‑speed Manchester‑encoded
optical transmitter using CameraX.

------------------------------------------------------------------------

# 1. Root Cause of Current Issue

## Problem

The flashlight does not blink.

### Reason

The application: 1. Starts CameraX preview 2. Then tries to control the
LED using CameraManager.setTorchMode()

Android blocks this because:

CameraX is already using the camera device → CameraManager cannot
control the torch → Android throws CAMERA_IN_USE internally

------------------------------------------------------------------------

# 2. Correct Architecture

Control the LED using CameraX CameraControl instead of CameraManager.

camera.cameraControl.enableTorch(true/false)

This allows the torch to be controlled while the camera preview session
is active.

------------------------------------------------------------------------

# 3. Implementation Steps

## Step 1 -- Capture Camera Reference

Replace:

cameraProvider.bindToLifecycle(...)

With:

val camera = cameraProvider.bindToLifecycle(...) val cameraControl =
camera.cameraControl

------------------------------------------------------------------------

# 4. Flash Controller

class FlashController( private val cameraControl: CameraControl ) { fun
setFlash(on: Boolean) { cameraControl.enableTorch(on) }

    fun off() {
        cameraControl.enableTorch(false)
    }

}

------------------------------------------------------------------------

# 5. Remove Old Code

Delete: TorchSelector.kt\
CameraManager usage\
cameraId lookup

------------------------------------------------------------------------

# 6. Device Capability Check

val hasFlash = packageManager.hasSystemFeature(
PackageManager.FEATURE_CAMERA_FLASH )

------------------------------------------------------------------------

# 7. Manchester Encoding

  Bit   Pattern
  ----- ----------
  1     ON → OFF
  0     OFF → ON

Example:

1010 1 → ON OFF 0 → OFF ON 1 → ON OFF 0 → OFF ON

------------------------------------------------------------------------

# 8. Transmission Pipeline

Message ↓ UTF‑8 bytes ↓ Error correction ↓ Manchester Encoding ↓ Frame
Scheduler ↓ CameraX Torch ↓ LED

------------------------------------------------------------------------

# 9. Packet Format

PREAMBLE 10101010\
SYNC 11110000\
LENGTH\
DATA\
CRC

------------------------------------------------------------------------

# 10. Scheduler

Use: HandlerThread\
SystemClock.elapsedRealtimeNanos()

Avoid: Thread.sleep() delay()

------------------------------------------------------------------------

# 11. Example Transmitter

class FlashTransmitter( private val flash: FlashController ) { suspend
fun transmit(bits: List`<Boolean>`{=html}, bitDuration: Long) { val half
= bitDuration / 2

        for (bit in bits) {

            if (bit) {
                flash.setFlash(true)
                delay(half)
                flash.setFlash(false)
            } else {
                flash.setFlash(false)
                delay(half)
                flash.setFlash(true)
            }

            delay(half)
        }

        flash.off()
    }

}

------------------------------------------------------------------------

# 12. Adaptive Bitrate

Distance \<1m → 60 ms\
1‑3m → 120 ms\
\>3m → 250 ms

------------------------------------------------------------------------

# 13. Expected Performance

Budget phones → \~5 bps\
Mid‑range → \~10 bps\
Flagship → \~20 bps

Moto G52 estimate: 8--12 bps.

------------------------------------------------------------------------

# 14. Future Improvements

LED tracking\
Auto bitrate adaptation\
Forward error correction\
Invisible flicker transmission

------------------------------------------------------------------------

# Conclusion

The issue occurs because CameraManager conflicts with CameraX camera
usage.

Fix by: 1. Using CameraX CameraControl 2. Implementing Manchester
encoding 3. Using a precise scheduler 4. Adding adaptive bitrate and
packet synchronization
