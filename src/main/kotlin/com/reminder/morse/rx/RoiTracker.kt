package com.reminder.morse.rx

class RoiTracker(
    private val halfSize: Int
) {
    private var current: Roi? = null
    @Volatile private var manualLockNormalized: Pair<Float, Float>? = null
    private var rotationDegrees: Int = 0
    private val patternDetector = FlashPatternDetector()

    /** Detection mode from the last update: "auto", "pattern", or "manual" */
    var lastDetectionMode: String = "auto"
        private set

    fun reset() {
        current = null
        manualLockNormalized = null
        patternDetector.reset()
        lastDetectionMode = "auto"
    }

    /**
     * Set a manual lock from normalized screen coordinates (0-1).
     * The coordinates are mapped to raw image space accounting for sensor rotation.
     */
    fun setManualLock(normalizedX: Float, normalizedY: Float) {
        manualLockNormalized = Pair(normalizedX, normalizedY)
    }

    fun clearManualLock() {
        manualLockNormalized = null
    }

    val isManuallyLocked: Boolean get() = manualLockNormalized != null

    fun update(buffer: FrameBuffer, rotation: Int = 0): Pair<Roi, BrightSpot> {
        rotationDegrees = rotation

        // Priority 1: Manual touch lock
        val manual = manualLockNormalized
        if (manual != null) {
            val point = mapScreenToImage(manual.first, manual.second, buffer.width, buffer.height)
            val roi = buildRoi(buffer, point)
            current = roi
            lastDetectionMode = "manual"
            return Pair(roi, BrightSpotDetector.findBrightest(buffer, roi))
        }

        // Priority 2: Temporal pattern detection (flashing pixel cluster)
        val flash = patternDetector.detect(buffer)
        if (flash != null) {
            val roi = buildRoi(buffer, flash.center)
            current = roi
            lastDetectionMode = "pattern"
            return Pair(roi, BrightSpotDetector.findBrightest(buffer, roi))
        }

        // Priority 3: Fallback — brightest pixel (original behaviour)
        val spot = BrightSpotDetector.findBrightest(buffer, current)
        val roi = buildRoi(buffer, spot.point)
        current = roi
        lastDetectionMode = "auto"
        return Pair(roi, spot)
    }

    /**
     * Maps normalised PreviewView coordinates to raw sensor image coordinates,
     * accounting for the camera sensor rotation.
     *
     * CW rotation by R° maps raw(nx,ny) → display coords.
     * Inverse gives us display → raw:
     *   0°  : (nx,  ny )
     *   90° : (1-ny, nx)
     *   180°: (1-nx, 1-ny)
     *   270°: (ny, 1-nx)
     */
    private fun mapScreenToImage(nx: Float, ny: Float, w: Int, h: Int): Point {
        val (ix, iy) = when (rotationDegrees) {
            90  -> Pair((1f - ny) * (w - 1), nx * (h - 1))
            180 -> Pair((1f - nx) * (w - 1), (1f - ny) * (h - 1))
            270 -> Pair(ny * (w - 1), (1f - nx) * (h - 1))
            else -> Pair(nx * (w - 1), ny * (h - 1))
        }
        return Point(ix.toInt().coerceIn(0, w - 1), iy.toInt().coerceIn(0, h - 1))
    }

    private fun buildRoi(buffer: FrameBuffer, center: Point): Roi {
        val left = (center.x - halfSize).coerceAtLeast(0)
        val right = (center.x + halfSize).coerceAtMost(buffer.width - 1)
        val top = (center.y - halfSize).coerceAtLeast(0)
        val bottom = (center.y + halfSize).coerceAtMost(buffer.height - 1)
        return Roi(left, top, right, bottom)
    }
}
