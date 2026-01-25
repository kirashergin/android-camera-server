package com.cameraserver.usb.config

import android.util.Log

enum class FocusMode {
    AUTO, CONTINUOUS, MANUAL, FIXED, MACRO
}

object CameraConfig {
    private const val TAG = "CameraConfig"

    // Defaults
    const val MIRROR_HORIZONTAL = false
    const val MIRROR_VERTICAL = false
    const val USE_FRONT_CAMERA = false
    val DEFAULT_FOCUS_MODE = FocusMode.CONTINUOUS
    const val MANUAL_FOCUS_DISTANCE = 0.0f
    const val PHOTO_QUALITY = 95
    const val SERVER_PORT = 8080

    // Current settings (runtime)
    @Volatile var streamWidth: Int = 1280
        private set
    @Volatile var streamHeight: Int = 720
        private set
    @Volatile var targetFps: Int = 30
        private set
    @Volatile var jpegQuality: Int = 80
        private set

    val bufferCount: Int get() = when {
        targetFps >= 60 -> 8
        targetFps >= 30 -> 5
        else -> 4
    }

    val frameIntervalMs: Long get() = 1000L / targetFps

    fun setStreamConfig(width: Int, height: Int, fps: Int, quality: Int) {
        streamWidth = width.coerceIn(320, 3840)
        streamHeight = height.coerceIn(240, 2160)
        targetFps = fps.coerceIn(5, 60)
        jpegQuality = quality.coerceIn(30, 100)
        Log.i(TAG, "Config: ${streamWidth}x${streamHeight} @ ${targetFps}fps, JPEG $jpegQuality%")
    }

    fun getConfigSummary(): String = "${streamWidth}x${streamHeight} @ ${targetFps}fps, Q$jpegQuality"
}
