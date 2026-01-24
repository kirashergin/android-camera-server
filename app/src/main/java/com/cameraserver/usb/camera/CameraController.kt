package com.cameraserver.usb.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import com.cameraserver.usb.config.CameraConfig
import com.cameraserver.usb.config.DeviceTier
import com.cameraserver.usb.config.FocusMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Camera controller for photo booth application
 *
 * Features:
 * - MJPEG streaming with configurable resolution and FPS
 * - Full resolution photo capture with autofocus
 * - Instant quick photo from stream (zero latency)
 * - Image mirroring (horizontal/vertical)
 * - Focus modes: AUTO, CONTINUOUS, MANUAL, FIXED, MACRO
 * - Touch-to-focus support
 * - Device-adaptive performance optimization
 *
 * Lifecycle:
 * 1. [initialize] - create background threads
 * 2. [startStream] / [stopStream] - manage streaming
 * 3. [capturePhoto] / [captureQuickPhoto] - capture photos
 * 4. [release] - free resources
 */
class CameraController(private val context: Context) {

    companion object {
        private const val TAG = "CameraController"

        // Static flag: dual JPEG output not supported on this device
        // Persists across stream restarts within app session
        @Volatile
        private var dualOutputNotSupported = false
    }

    // Dynamic parameters from CameraConfig (support runtime changes)
    private val streamWidth: Int get() = CameraConfig.streamWidth
    private val streamHeight: Int get() = CameraConfig.streamHeight
    private val targetFps: Int get() = CameraConfig.targetFps
    private val jpegQuality: Int get() = CameraConfig.jpegQuality
    private val bufferCount: Int get() = CameraConfig.bufferCount

    // Camera2 API objects
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // Image readers
    private var streamImageReader: ImageReader? = null
    private var photoImageReader: ImageReader? = null
    private var quickPhotoImageReader: ImageReader? = null

    // Background threads
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageProcessThread: HandlerThread? = null
    private var imageProcessHandler: Handler? = null

    // State management
    private val cameraOpenCloseLock = Semaphore(1)
    private val isStreaming = AtomicBoolean(false)
    private val isCameraOpen = AtomicBoolean(false)
    private val quickPhotoInProgress = AtomicBoolean(false)

    // Frame storage
    private val lastStreamFrame = AtomicReference<ByteArray?>(null)
    private val lastQuickPhotoFrame = AtomicReference<ByteArray?>(null)
    private var streamCallback: ((ByteArray) -> Unit)? = null

    // Camera configuration
    private var photoSize: Size = Size(1920, 1080)
    private var quickPhotoSize: Size? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var sensorArraySize: Rect? = null
    private var currentCameraId: String? = null

    // Focus settings
    @Volatile private var currentFocusMode: FocusMode = CameraConfig.DEFAULT_FOCUS_MODE
    @Volatile private var manualFocusDistance: Float = CameraConfig.MANUAL_FOCUS_DISTANCE
    private var minFocusDistance: Float = 0f

    // Mirroring (pre-calculated for performance)
    private val mirrorMatrix = Matrix().apply {
        if (CameraConfig.MIRROR_HORIZONTAL) postScale(-1f, 1f)
        if (CameraConfig.MIRROR_VERTICAL) postScale(1f, -1f)
    }
    private val needsMirroring = CameraConfig.MIRROR_HORIZONTAL || CameraConfig.MIRROR_VERTICAL

    // Mirroring buffers (reused for performance)
    private val mirrorOutputStream = ByteArrayOutputStream(512 * 1024)
    private var reusableBitmap: Bitmap? = null
    private val bitmapOptions = BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.RGB_565 // Memory efficient
    }
    private val mirrorLock = Object()

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Initialize background threads for camera operations
     * Must be called before using any other methods. Safe to call multiple times.
     */
    fun initialize() {
        val cameraPriority = CameraConfig.cameraThreadPriority
        val processPriority = CameraConfig.imageProcessThreadPriority

        backgroundThread = HandlerThread("CameraBackground", cameraPriority).also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        imageProcessThread = HandlerThread("ImageProcess", processPriority).also { it.start() }
        imageProcessHandler = Handler(imageProcessThread!!.looper)

        Log.i(TAG, "Initialized: tier=${CameraConfig.deviceTier}, mirror=${CameraConfig.MIRROR_HORIZONTAL}/${CameraConfig.MIRROR_VERTICAL}")
    }

    /**
     * Release all camera resources
     * Stops stream, closes camera, and terminates background threads.
     */
    fun release() {
        stopStream()
        closeCamera()

        backgroundThread?.quitSafely()
        imageProcessThread?.quitSafely()
        try {
            backgroundThread?.join(1000)
            imageProcessThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Thread join interrupted")
            Thread.currentThread().interrupt()
        }

        backgroundThread = null
        backgroundHandler = null
        imageProcessThread = null
        imageProcessHandler = null

        Log.i(TAG, "Released")
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAMERA MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    private fun getCameraId(): String? {
        val facing = if (CameraConfig.USE_FRONT_CAMERA)
            CameraCharacteristics.LENS_FACING_FRONT
        else
            CameraCharacteristics.LENS_FACING_BACK

        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                try {
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == facing
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot get characteristics for camera $id")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot enumerate cameras: ${e.message}")
            null
        }
    }

    private fun selectPhotoSize(cameraId: String): Size {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG) ?: return Size(1920, 1080)

        val maxSize = sizes.maxByOrNull { it.width * it.height }
        Log.i(TAG, "Photo sizes: ${sizes.size} available, max: ${maxSize?.width}x${maxSize?.height}")
        return maxSize ?: sizes.first()
    }

    /**
     * Select resolution for high-res quick photo
     * Limited to 1920x1080 for dual JPEG output compatibility
     */
    private fun selectQuickPhotoSize(): Size? {
        val cameraId = currentCameraId ?: return null
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG) ?: return null

        val maxDualOutputPixels = 1920 * 1080
        val streamPixels = streamWidth * streamHeight

        return sizes.filter {
            val pixels = it.width * it.height
            pixels > streamPixels && pixels <= maxDualOutputPixels
        }.maxByOrNull { it.width * it.height }
    }

    @Throws(CameraAccessException::class, SecurityException::class)
    private fun openCamera(): Boolean {
        if (isCameraOpen.get()) return true

        // Ensure threads are initialized
        if (backgroundHandler == null || imageProcessHandler == null) {
            Log.w(TAG, "Handlers not initialized, calling initialize()")
            initialize()
        }

        val cameraId = getCameraId() ?: run {
            Log.e(TAG, "No suitable camera found")
            return false
        }

        currentCameraId = cameraId
        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
        sensorArraySize = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        minFocusDistance = cameraCharacteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        photoSize = selectPhotoSize(cameraId)

        val timeout = if (CameraConfig.deviceTier == DeviceTier.LOW_END) 4000L else 2500L
        if (!cameraOpenCloseLock.tryAcquire(timeout, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("Timeout waiting to lock camera")
        }

        try {
            val latch = CountDownLatch(1)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    isCameraOpen.set(true)
                    latch.countDown()
                    Log.i(TAG, "Camera opened: $cameraId")
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    isCameraOpen.set(false)
                    latch.countDown()
                    Log.w(TAG, "Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    isCameraOpen.set(false)
                    latch.countDown()
                    Log.e(TAG, "Camera error: $error")
                }
            }, backgroundHandler)

            // Wait for camera to open
            val waitTime = if (CameraConfig.deviceTier == DeviceTier.LOW_END) 2000L else 1000L
            latch.await(waitTime, TimeUnit.MILLISECONDS)
            return isCameraOpen.get()
        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            throw e
        }
    }

    private fun closeCamera() {
        try {
            if (!cameraOpenCloseLock.tryAcquire(3000, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Timeout acquiring lock for close, forcing")
                forceCloseCamera()
                return
            }

            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            streamImageReader?.close()
            streamImageReader = null
            photoImageReader?.close()
            photoImageReader = null
            quickPhotoImageReader?.close()
            quickPhotoImageReader = null
            isCameraOpen.set(false)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted closing camera")
            Thread.currentThread().interrupt()
        } finally {
            try { cameraOpenCloseLock.release() } catch (_: Exception) { }
        }
    }

    private fun forceCloseCamera() {
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { streamImageReader?.close() }
        streamImageReader = null
        runCatching { photoImageReader?.close() }
        photoImageReader = null
        runCatching { quickPhotoImageReader?.close() }
        quickPhotoImageReader = null
        isCameraOpen.set(false)
        Log.w(TAG, "Camera force closed")
    }

    // ═══════════════════════════════════════════════════════════════════
    // STREAMING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Start MJPEG stream with current quality settings
     *
     * @param callback Optional callback for each JPEG frame (push delivery model)
     * @return true if stream started or already active
     */
    fun startStream(callback: ((ByteArray) -> Unit)? = null): Boolean {
        if (isStreaming.get()) return true

        try {
            if (!openCamera()) return false
            streamCallback = callback

            // Minimum 4 buffers for smooth 30fps
            val actualBufferCount = maxOf(bufferCount, 4)

            // Stream ImageReader
            streamImageReader = ImageReader.newInstance(
                streamWidth, streamHeight, ImageFormat.JPEG, actualBufferCount
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val processed = applyMirror(bytes)
                        lastStreamFrame.set(processed)
                        streamCallback?.invoke(processed)
                    } finally {
                        image.close()
                    }
                }, imageProcessHandler)
            }

            // High-res quick photo (skip if dual output not supported)
            quickPhotoSize = if (dualOutputNotSupported) null else selectQuickPhotoSize()
            val useHighResQuickPhoto = !dualOutputNotSupported && quickPhotoSize != null &&
                    (quickPhotoSize!!.width * quickPhotoSize!!.height) > (streamWidth * streamHeight)

            if (useHighResQuickPhoto && quickPhotoSize != null) {
                quickPhotoImageReader = ImageReader.newInstance(
                    quickPhotoSize!!.width, quickPhotoSize!!.height, ImageFormat.JPEG, 2
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            lastQuickPhotoFrame.set(applyMirror(bytes))
                        } finally {
                            image.close()
                            quickPhotoInProgress.set(false)
                        }
                    }, imageProcessHandler)
                }
                Log.i(TAG, "High-res quick photo: ${quickPhotoSize!!.width}x${quickPhotoSize!!.height}")
            }

            val camera = cameraDevice ?: return false

            // Try dual output, fallback to single if not supported
            val surfaces = mutableListOf(streamImageReader!!.surface)
            val tryDualOutput = quickPhotoImageReader != null
            if (tryDualOutput) {
                surfaces.add(quickPhotoImageReader!!.surface)
            }

            val sessionLatch = CountDownLatch(1)
            val sessionSuccess = AtomicBoolean(false)
            var dualOutputFailed = false

            try {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session

                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(streamImageReader!!.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            applyFocusSettings(this)
                            // Disable stabilization for lower latency
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                            // Fixed FPS for stability
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
                            set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                        }.build()

                        session.setRepeatingRequest(request, null, backgroundHandler)
                        isStreaming.set(true)
                        sessionSuccess.set(true)

                        val highResInfo = quickPhotoSize?.let { ", highRes=${it.width}x${it.height}" } ?: ""
                        Log.i(TAG, "Stream: ${streamWidth}x${streamHeight}@${targetFps}fps$highResInfo")
                        sessionLatch.countDown()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Stream configuration failed")
                        sessionLatch.countDown()
                    }
                }, backgroundHandler)

                sessionLatch.await(3, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.e(TAG, "Session creation failed: ${e.message}")
                dualOutputFailed = true
            }

            // Dual output failed - cleanup and signal for retry
            if ((!sessionSuccess.get() || dualOutputFailed) && tryDualOutput) {
                Log.w(TAG, "Dual output not supported, will use single output")
                dualOutputNotSupported = true

                quickPhotoImageReader?.close()
                quickPhotoImageReader = null
                quickPhotoSize = null
                streamImageReader?.close()
                streamImageReader = null
                captureSession?.close()
                captureSession = null

                forceCloseCamera()
                return false
            }

            return isStreaming.get()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start stream: ${e.message}")
            return false
        }
    }

    /**
     * Stop stream and release camera resources
     */
    fun stopStream() {
        if (!isStreaming.get()) return

        runCatching { captureSession?.stopRepeating() }
        captureSession?.close()
        captureSession = null
        streamImageReader?.close()
        streamImageReader = null
        quickPhotoImageReader?.close()
        quickPhotoImageReader = null
        lastStreamFrame.set(null)
        lastQuickPhotoFrame.set(null)
        streamCallback = null
        isStreaming.set(false)
        quickPhotoInProgress.set(false)

        synchronized(mirrorLock) {
            reusableBitmap?.recycle()
            reusableBitmap = null
        }

        closeCamera()
        Log.i(TAG, "Stream stopped")
    }

    /** Check if stream is active */
    fun isStreamActive(): Boolean = isStreaming.get()

    /** Get last stream frame or null */
    fun getLastFrame(): ByteArray? = lastStreamFrame.get()

    /** Set or change frame callback (can be called while streaming) */
    fun setStreamCallback(callback: ((ByteArray) -> Unit)?) {
        streamCallback = callback
    }

    /**
     * Restart stream with new quality settings
     * Preserves current callback
     */
    fun restartStreamWithNewSettings(): Boolean {
        if (!isStreaming.get()) return true

        val savedCallback = streamCallback
        Log.i(TAG, "Restarting: ${streamWidth}x${streamHeight}@${targetFps}fps")

        runCatching { captureSession?.stopRepeating() }
        captureSession?.close()
        captureSession = null
        streamImageReader?.close()
        streamImageReader = null
        quickPhotoImageReader?.close()
        quickPhotoImageReader = null
        isStreaming.set(false)
        quickPhotoInProgress.set(false)

        Thread.sleep(100)
        return startStream(savedCallback)
    }

    // ═══════════════════════════════════════════════════════════════════
    // PHOTO CAPTURE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Instant photo from stream
     *
     * @param highRes true = capture at higher resolution without stopping stream
     *                false = instant return of last stream frame (default)
     * @param timeoutMs timeout for high-res capture
     * @return JPEG data or null if stream not active
     */
    fun captureQuickPhoto(highRes: Boolean = false, timeoutMs: Long = 500): ByteArray? {
        if (!highRes) {
            return lastStreamFrame.get()?.clone()
        }

        val quickReader = quickPhotoImageReader
        val camera = cameraDevice
        val session = captureSession

        if (quickReader == null || camera == null || session == null || !isStreaming.get()) {
            return lastStreamFrame.get()?.clone()
        }

        if (!quickPhotoInProgress.compareAndSet(false, true)) {
            return lastStreamFrame.get()?.clone()
        }

        try {
            lastQuickPhotoFrame.set(null)

            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(quickReader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                applyFocusSettings(this)
                set(CaptureRequest.JPEG_QUALITY, CameraConfig.PHOTO_QUALITY.toByte())
            }.build()

            val latch = CountDownLatch(1)

            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    backgroundHandler?.postDelayed({ latch.countDown() }, 50)
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    quickPhotoInProgress.set(false)
                    latch.countDown()
                }
            }, backgroundHandler)

            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                quickPhotoInProgress.set(false)
                return lastStreamFrame.get()?.clone()
            }

            Thread.sleep(50)
            return lastQuickPhotoFrame.get()?.clone() ?: lastStreamFrame.get()?.clone()
        } catch (e: Exception) {
            Log.e(TAG, "Quick photo failed: ${e.message}")
            quickPhotoInProgress.set(false)
            return lastStreamFrame.get()?.clone()
        }
    }

    /** Check if high-res quick photo is available */
    fun isHighResQuickPhotoAvailable(): Boolean = quickPhotoImageReader != null && isStreaming.get()

    /** Get quick photo resolution */
    fun getQuickPhotoResolution(highRes: Boolean): String {
        return if (highRes && quickPhotoImageReader != null && quickPhotoSize != null) {
            "${quickPhotoSize!!.width}x${quickPhotoSize!!.height}"
        } else {
            "${streamWidth}x${streamHeight}"
        }
    }

    /**
     * Full resolution photo capture
     *
     * Temporarily interrupts stream (~1-2 sec) for maximum quality:
     * - Pre-focus (3A convergence)
     * - High quality noise reduction
     * - Edge enhancement
     * - 98% JPEG quality
     *
     * Stream automatically resumes after capture.
     */
    fun capturePhoto(): ByteArray? {
        val wasStreaming = isStreaming.get()
        val savedCallback = streamCallback

        try {
            // Stop stream for resolution change
            if (wasStreaming) {
                captureSession?.stopRepeating()
                captureSession?.close()
                captureSession = null
                streamImageReader?.close()
                streamImageReader = null
                photoImageReader?.close()
                photoImageReader = null
                isStreaming.set(false)
            }

            if (!isCameraOpen.get() && !openCamera()) return null

            val camera = cameraDevice ?: return null
            Log.i(TAG, "Capturing: ${photoSize.width}x${photoSize.height}")

            photoImageReader = ImageReader.newInstance(
                photoSize.width, photoSize.height, ImageFormat.JPEG, 2
            )

            val photoResult = AtomicReference<ByteArray?>(null)
            val sessionLatch = CountDownLatch(1)
            val captureLatch = CountDownLatch(1)

            photoImageReader!!.setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    photoResult.set(applyMirror(bytes))
                    Log.i(TAG, "Photo: ${bytes.size} bytes")
                }
                captureLatch.countDown()
            }, backgroundHandler)

            var photoSession: CameraCaptureSession? = null

            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(photoImageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        photoSession = session
                        sessionLatch.countDown()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        sessionLatch.countDown()
                        captureLatch.countDown()
                    }
                }, backgroundHandler)

            if (!sessionLatch.await(3, TimeUnit.SECONDS) || photoSession == null) {
                Log.e(TAG, "Photo session timeout")
                return null
            }

            // Step 1: Pre-capture for focus and exposure
            val precaptureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(photoImageReader!!.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }.build()

            val precaptureLatch = CountDownLatch(1)
            photoSession!!.capture(precaptureRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    precaptureLatch.countDown()
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    precaptureLatch.countDown()
                }
            }, backgroundHandler)

            precaptureLatch.await(1500, TimeUnit.MILLISECONDS)
            Thread.sleep(200)

            // Step 2: Main capture with maximum quality
            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(photoImageReader!!.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                // High quality processing
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
                set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
                set(CaptureRequest.JPEG_QUALITY, 98.toByte())
            }.build()

            photoSession!!.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    captureLatch.countDown()
                }
            }, backgroundHandler)

            if (!captureLatch.await(5, TimeUnit.SECONDS)) {
                Log.e(TAG, "Photo capture timeout")
                return null
            }

            photoSession?.close()
            photoImageReader?.close()
            photoImageReader = null

            // Restart stream if was active
            if (wasStreaming) {
                startStream(savedCallback)
            } else {
                closeCamera()
            }

            return photoResult.get()
        } catch (e: Exception) {
            Log.e(TAG, "Photo failed: ${e.message}")
            isStreaming.set(false)
            if (wasStreaming) {
                runCatching { startStream(savedCallback) }
            }
            return null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FOCUS CONTROL
    // ═══════════════════════════════════════════════════════════════════

    /** Apply focus settings to capture request */
    private fun applyFocusSettings(builder: CaptureRequest.Builder) {
        when (currentFocusMode) {
            FocusMode.AUTO -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
            FocusMode.CONTINUOUS -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            FocusMode.MANUAL -> {
                if (minFocusDistance > 0) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance * minFocusDistance)
                } else {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                }
            }
            FocusMode.FIXED -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                if (minFocusDistance > 0) {
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, minFocusDistance * 0.3f)
                }
            }
            FocusMode.MACRO -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_MACRO)
            }
        }
    }

    /** Set focus mode */
    fun setFocusMode(mode: FocusMode): Boolean {
        currentFocusMode = mode
        return if (isStreaming.get()) updateCaptureSettings() else true
    }

    /** Set manual focus distance (0.0 = infinity, 1.0 = macro) */
    fun setManualFocusDistance(distance: Float): Boolean {
        manualFocusDistance = distance.coerceIn(0f, 1f)
        return if (currentFocusMode == FocusMode.MANUAL && isStreaming.get()) updateCaptureSettings() else true
    }

    /** Focus on point (x, y in 0.0-1.0 range) */
    fun focusOnPoint(x: Float, y: Float): Boolean {
        val camera = cameraDevice ?: return false
        val session = captureSession ?: return false
        val imageReader = streamImageReader ?: return false
        val sensor = sensorArraySize ?: return false

        if (!isStreaming.get()) return false

        val focusX = (x * sensor.width()).toInt().coerceIn(0, sensor.width())
        val focusY = (y * sensor.height()).toInt().coerceIn(0, sensor.height())
        val halfSize = (sensor.width() * 0.05f).toInt()

        val focusRect = Rect(
            (focusX - halfSize).coerceAtLeast(0),
            (focusY - halfSize).coerceAtLeast(0),
            (focusX + halfSize).coerceAtMost(sensor.width()),
            (focusY + halfSize).coerceAtMost(sensor.height())
        )

        val meteringRect = MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX)

        return try {
            // Cancel current AF
            session.capture(
                camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(imageReader.surface)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                }.build(), null, backgroundHandler
            )

            // Focus on point
            session.capture(
                camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(imageReader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
                    set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
                    set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                }.build(), null, backgroundHandler
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Focus on point failed: ${e.message}")
            false
        }
    }

    /** Trigger single autofocus */
    fun triggerAutoFocus(): Boolean {
        val camera = cameraDevice ?: return false
        val session = captureSession ?: return false
        val imageReader = streamImageReader ?: return false

        if (!isStreaming.get()) return false

        return try {
            session.capture(
                camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(imageReader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
                    set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                }.build(), null, backgroundHandler
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Auto focus failed: ${e.message}")
            false
        }
    }

    private fun updateCaptureSettings(): Boolean {
        val camera = cameraDevice ?: return false
        val session = captureSession ?: return false
        val imageReader = streamImageReader ?: return false

        return try {
            session.setRepeatingRequest(
                camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(imageReader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    applyFocusSettings(this)
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
                    set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                }.build(), null, backgroundHandler
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Update settings failed: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // IMAGE PROCESSING
    // ═══════════════════════════════════════════════════════════════════

    /** Apply mirroring to JPEG if enabled */
    private fun applyMirror(jpegData: ByteArray): ByteArray {
        if (!needsMirroring) return jpegData

        synchronized(mirrorLock) {
            var bitmap: Bitmap? = null
            var mirrored: Bitmap? = null

            return try {
                bitmapOptions.inBitmap = reusableBitmap
                bitmap = try {
                    BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, bitmapOptions)
                } catch (e: IllegalArgumentException) {
                    bitmapOptions.inBitmap = null
                    BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, bitmapOptions)
                }

                if (bitmap == null) return jpegData

                reusableBitmap = bitmap
                mirrored = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, mirrorMatrix, true)

                mirrorOutputStream.reset()
                mirrored.compress(Bitmap.CompressFormat.JPEG, jpegQuality, mirrorOutputStream)
                mirrorOutputStream.toByteArray()
            } catch (e: Exception) {
                jpegData
            } finally {
                if (mirrored != null && mirrored !== bitmap) {
                    mirrored.recycle()
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // STATUS
    // ═══════════════════════════════════════════════════════════════════

    /** Get current camera status */
    fun getStatus(): CameraStatus {
        return CameraStatus(
            isOpen = isCameraOpen.get(),
            isStreaming = isStreaming.get(),
            streamResolution = "${streamWidth}x${streamHeight}",
            photoResolution = "${photoSize.width}x${photoSize.height}",
            quickPhotoHighResAvailable = isHighResQuickPhotoAvailable(),
            quickPhotoHighResolution = quickPhotoSize?.let { "${it.width}x${it.height}" },
            targetFps = targetFps,
            quality = CameraConfig.CURRENT_QUALITY.name,
            focusMode = currentFocusMode.name,
            manualFocusDistance = if (currentFocusMode == FocusMode.MANUAL) manualFocusDistance else null,
            mirrorHorizontal = CameraConfig.MIRROR_HORIZONTAL,
            mirrorVertical = CameraConfig.MIRROR_VERTICAL,
            isFrontCamera = CameraConfig.USE_FRONT_CAMERA,
            supportsManualFocus = minFocusDistance > 0,
            deviceTier = CameraConfig.deviceTier.name
        )
    }

    /** Get supported focus modes for this camera */
    fun getSupportedFocusModes(): List<FocusMode> {
        val modes = mutableListOf(FocusMode.AUTO, FocusMode.CONTINUOUS)
        if (minFocusDistance > 0) {
            modes.add(FocusMode.MANUAL)
            modes.add(FocusMode.FIXED)
        }
        val afModes = cameraCharacteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        if (afModes?.contains(CaptureRequest.CONTROL_AF_MODE_MACRO) == true) {
            modes.add(FocusMode.MACRO)
        }
        return modes
    }
}

/**
 * Camera status data class
 */
data class CameraStatus(
    val isOpen: Boolean,
    val isStreaming: Boolean,
    val streamResolution: String,
    val photoResolution: String,
    val quickPhotoHighResAvailable: Boolean,
    val quickPhotoHighResolution: String?,
    val targetFps: Int,
    val quality: String,
    val focusMode: String,
    val manualFocusDistance: Float?,
    val mirrorHorizontal: Boolean,
    val mirrorVertical: Boolean,
    val isFrontCamera: Boolean,
    val supportsManualFocus: Boolean,
    val deviceTier: String = "UNKNOWN"
)
