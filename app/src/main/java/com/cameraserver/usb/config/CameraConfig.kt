package com.cameraserver.usb.config

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Stream quality presets
 *
 * - LOW:    720p @ 15fps - battery saver, low-end devices
 * - MEDIUM: 720p @ 30fps - balanced (recommended)
 * - HIGH:   1080p @ 30fps - flagship devices
 */
enum class StreamQuality {
    LOW, MEDIUM, HIGH
}

/**
 * Camera focus modes
 */
enum class FocusMode {
    AUTO,       // Single focus on request
    CONTINUOUS, // Continuous autofocus (for moving subjects)
    MANUAL,     // Manual focus distance
    FIXED,      // Fixed focus (hyperfocal)
    MACRO       // Close-up focus
}

/**
 * Device performance tier for adaptive optimization
 */
enum class DeviceTier {
    LOW_END,    // < 3GB RAM or old API
    MID_RANGE,  // 3-6GB RAM
    FLAGSHIP    // > 6GB RAM, modern API
}

/**
 * Camera configuration for photo booth application
 *
 * Features:
 * - Device-adaptive quality presets based on hardware capabilities
 * - Runtime quality switching without app restart
 * - Optimized buffer sizes and thread priorities per device tier
 *
 * Usage:
 * - Call [initialize] once at app startup to detect device tier
 * - Use [setQuality] or [setCustom] to change settings at runtime
 */
object CameraConfig {

    private const val TAG = "CameraConfig"

    // ═══════════════════════════════════════════════════════════════════
    // DEFAULT SETTINGS (can be changed at runtime)
    // ═══════════════════════════════════════════════════════════════════

    val INITIAL_QUALITY = StreamQuality.MEDIUM
    const val MIRROR_HORIZONTAL = false
    const val MIRROR_VERTICAL = false
    const val USE_FRONT_CAMERA = false
    val DEFAULT_FOCUS_MODE = FocusMode.CONTINUOUS
    const val MANUAL_FOCUS_DISTANCE = 0.0f  // 0.0 = infinity, 1.0 = macro

    // ═══════════════════════════════════════════════════════════════════
    // DEVICE DETECTION
    // ═══════════════════════════════════════════════════════════════════

    @Volatile
    var deviceTier: DeviceTier = DeviceTier.MID_RANGE
        private set

    @Volatile
    var totalRamMb: Long = 4096
        private set

    @Volatile
    var cpuCores: Int = 4
        private set

    /**
     * Initialize device detection - call once at app startup
     */
    fun initialize(context: Context) {
        detectDeviceTier(context)
        Log.i(TAG, "Initialized: tier=$deviceTier, RAM=${totalRamMb}MB, cores=$cpuCores, API=${Build.VERSION.SDK_INT}")
    }

    private fun detectDeviceTier(context: Context) {
        // Get total RAM
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        totalRamMb = memInfo.totalMem / (1024 * 1024)

        // Get CPU cores
        cpuCores = Runtime.getRuntime().availableProcessors()

        // Determine tier
        deviceTier = when {
            // Low-end: < 3GB RAM or old Android or <= 2 cores
            totalRamMb < 3072 || Build.VERSION.SDK_INT < 26 || cpuCores <= 2 -> DeviceTier.LOW_END

            // Flagship: > 6GB RAM and modern Android and >= 6 cores
            totalRamMb > 6144 && Build.VERSION.SDK_INT >= 30 && cpuCores >= 6 -> DeviceTier.FLAGSHIP

            // Mid-range: everything else
            else -> DeviceTier.MID_RANGE
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // QUALITY PRESETS (device-adaptive)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get quality preset optimized for current device tier
     */
    fun getPreset(quality: StreamQuality): QualityPreset {
        return when (deviceTier) {
            DeviceTier.LOW_END -> lowEndPresets[quality]!!
            DeviceTier.MID_RANGE -> midRangePresets[quality]!!
            DeviceTier.FLAGSHIP -> flagshipPresets[quality]!!
        }
    }

    // Low-end device presets (conservative settings)
    private val lowEndPresets = mapOf(
        StreamQuality.LOW to QualityPreset(
            width = 640, height = 480, fps = 15,
            jpegQuality = 65, bufferCount = 3,
            description = "480p @ 15fps (low-end)"
        ),
        StreamQuality.MEDIUM to QualityPreset(
            width = 1280, height = 720, fps = 15,
            jpegQuality = 70, bufferCount = 3,
            description = "720p @ 15fps (low-end)"
        ),
        StreamQuality.HIGH to QualityPreset(
            width = 1280, height = 720, fps = 24,
            jpegQuality = 75, bufferCount = 4,
            description = "720p @ 24fps (low-end)"
        )
    )

    // Mid-range device presets (balanced)
    private val midRangePresets = mapOf(
        StreamQuality.LOW to QualityPreset(
            width = 1280, height = 720, fps = 15,
            jpegQuality = 70, bufferCount = 4,
            description = "720p @ 15fps"
        ),
        StreamQuality.MEDIUM to QualityPreset(
            width = 1280, height = 720, fps = 30,
            jpegQuality = 75, bufferCount = 5,
            description = "720p @ 30fps"
        ),
        StreamQuality.HIGH to QualityPreset(
            width = 1920, height = 1080, fps = 30,
            jpegQuality = 80, bufferCount = 5,
            description = "1080p @ 30fps"
        )
    )

    // Flagship device presets (maximum quality)
    private val flagshipPresets = mapOf(
        StreamQuality.LOW to QualityPreset(
            width = 1280, height = 720, fps = 30,
            jpegQuality = 75, bufferCount = 5,
            description = "720p @ 30fps (power save)"
        ),
        StreamQuality.MEDIUM to QualityPreset(
            width = 1920, height = 1080, fps = 30,
            jpegQuality = 80, bufferCount = 6,
            description = "1080p @ 30fps"
        ),
        StreamQuality.HIGH to QualityPreset(
            width = 1920, height = 1080, fps = 60,
            jpegQuality = 85, bufferCount = 8,
            description = "1080p @ 60fps"
        )
    )

    // Legacy presets map for compatibility
    val presets: Map<StreamQuality, QualityPreset>
        get() = when (deviceTier) {
            DeviceTier.LOW_END -> lowEndPresets
            DeviceTier.MID_RANGE -> midRangePresets
            DeviceTier.FLAGSHIP -> flagshipPresets
        }

    // ═══════════════════════════════════════════════════════════════════
    // RUNTIME STATE
    // ═══════════════════════════════════════════════════════════════════

    @Volatile
    var currentQuality: StreamQuality = INITIAL_QUALITY
        private set

    // Alias for compatibility
    val CURRENT_QUALITY: StreamQuality get() = currentQuality

    @Volatile
    private var customPreset: QualityPreset? = null

    val current: QualityPreset
        get() = customPreset ?: getPreset(currentQuality)

    val streamWidth: Int get() = current.width
    val streamHeight: Int get() = current.height
    val targetFps: Int get() = current.fps
    val jpegQuality: Int get() = current.jpegQuality
    val bufferCount: Int get() = current.bufferCount

    /**
     * Switch to a quality preset
     */
    fun setQuality(quality: StreamQuality) {
        currentQuality = quality
        customPreset = null
        Log.i(TAG, "Quality changed to $quality: ${getPreset(quality).description}")
    }

    /**
     * Set custom parameters (overrides presets)
     */
    fun setCustom(width: Int, height: Int, fps: Int, jpegQuality: Int = 75) {
        val buffers = when {
            fps >= 60 -> 8
            fps >= 30 -> if (deviceTier == DeviceTier.LOW_END) 4 else 5
            else -> if (deviceTier == DeviceTier.LOW_END) 3 else 4
        }
        customPreset = QualityPreset(
            width = width,
            height = height,
            fps = fps,
            jpegQuality = jpegQuality,
            bufferCount = buffers,
            description = "${width}x${height} @ ${fps}fps (custom)"
        )
        Log.i(TAG, "Custom quality set: ${customPreset!!.description}")
    }

    /**
     * Reset to default quality for current device tier
     */
    fun resetToDefault() {
        currentQuality = INITIAL_QUALITY
        customPreset = null
        Log.i(TAG, "Reset to default: ${current.description}")
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONSTANTS
    // ═══════════════════════════════════════════════════════════════════

    val frameIntervalMs: Long get() = 1000L / targetFps
    const val PHOTO_QUALITY = 95
    const val SERVER_PORT = 8080

    // Thread priorities (device-adaptive)
    val cameraThreadPriority: Int
        get() = if (deviceTier == DeviceTier.FLAGSHIP) Thread.MAX_PRIORITY else Thread.MAX_PRIORITY - 1

    val imageProcessThreadPriority: Int
        get() = if (deviceTier == DeviceTier.FLAGSHIP) Thread.MAX_PRIORITY else Thread.MAX_PRIORITY - 2

    // ═══════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════════

    fun getDescription(): String = current.description

    fun getAllPresets(): Map<StreamQuality, QualityPreset> = presets

    fun getDeviceInfo(): String = buildString {
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Android: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})\n")
        append("RAM: ${totalRamMb}MB, Cores: $cpuCores\n")
        append("Tier: $deviceTier\n")
        append("Current: ${current.description}")
    }
}

/**
 * Stream quality parameters
 */
data class QualityPreset(
    val width: Int,
    val height: Int,
    val fps: Int,
    val jpegQuality: Int,
    val bufferCount: Int,
    val description: String
) {
    val resolution: String get() = "${width}x${height}"

    /** Estimated bandwidth usage */
    val bandwidthEstimate: String
        get() {
            // Rough estimate: JPEG is ~10-20% of raw size depending on quality
            val compressionRatio = 100.0 / jpegQuality * 8
            val bytesPerFrame = (width * height * 3 / compressionRatio).toLong()
            val bytesPerSecond = bytesPerFrame * fps
            val mbps = bytesPerSecond / (1024.0 * 1024.0)
            return "~%.1f MB/s".format(mbps)
        }

    /** Convert to map for JSON serialization */
    fun toMap(): Map<String, Any> = mapOf(
        "width" to width,
        "height" to height,
        "fps" to fps,
        "jpegQuality" to jpegQuality,
        "bufferCount" to bufferCount,
        "resolution" to resolution,
        "description" to description,
        "bandwidthEstimate" to bandwidthEstimate
    )
}
