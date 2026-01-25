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
import com.cameraserver.usb.config.FocusMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CameraController(private val context: Context) {
    companion object {
        private const val TAG = "CameraController"
        @Volatile private var dualOutputNotSupported = false
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var streamImageReader: ImageReader? = null
    private var photoImageReader: ImageReader? = null
    private var quickPhotoImageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageProcessThread: HandlerThread? = null
    private var imageProcessHandler: Handler? = null

    private val cameraOpenCloseLock = Semaphore(1)
    private val isStreaming = AtomicBoolean(false)
    private val isCameraOpen = AtomicBoolean(false)
    private val quickPhotoInProgress = AtomicBoolean(false)
    private val photoInProgress = AtomicBoolean(false)
    private val streamOperationLock = Object()
    private val streamOperationInProgress = AtomicBoolean(false)

    private val lastStreamFrame = AtomicReference<ByteArray?>(null)
    private val lastQuickPhotoFrame = AtomicReference<ByteArray?>(null)
    private var streamCallback: ((ByteArray) -> Unit)? = null

    private var photoSize: Size = Size(1920, 1080)
    private var actualStreamSize: Size? = null
    private var quickPhotoSize: Size? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var sensorArraySize: Rect? = null
    private var currentCameraId: String? = null

    @Volatile private var currentFocusMode: FocusMode = CameraConfig.DEFAULT_FOCUS_MODE
    @Volatile private var manualFocusDistance: Float = CameraConfig.MANUAL_FOCUS_DISTANCE
    private var minFocusDistance: Float = 0f

    private val mirrorMatrix = Matrix().apply {
        if (CameraConfig.MIRROR_HORIZONTAL) postScale(-1f, 1f)
        if (CameraConfig.MIRROR_VERTICAL) postScale(1f, -1f)
    }
    private val needsMirroring = CameraConfig.MIRROR_HORIZONTAL || CameraConfig.MIRROR_VERTICAL
    private val mirrorOutputStream = ByteArrayOutputStream(512 * 1024)
    private var reusableBitmap: Bitmap? = null
    private val bitmapOptions = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.RGB_565 }
    private val mirrorLock = Object()

    fun initialize() {
        backgroundThread = HandlerThread("CameraBackground", Thread.MAX_PRIORITY).also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        imageProcessThread = HandlerThread("ImageProcess", Thread.MAX_PRIORITY - 1).also { it.start() }
        imageProcessHandler = Handler(imageProcessThread!!.looper)
        Log.i(TAG, "Initialized")
    }

    fun release() {
        stopStream()
        closeCamera()
        backgroundThread?.quitSafely()
        imageProcessThread?.quitSafely()
        runCatching { backgroundThread?.join(1000); imageProcessThread?.join(1000) }
        backgroundThread = null; backgroundHandler = null
        imageProcessThread = null; imageProcessHandler = null
        Log.i(TAG, "Released")
    }

    private fun getCameraId(): String? {
        val facing = if (CameraConfig.USE_FRONT_CAMERA) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                runCatching { cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing }.getOrDefault(false)
            }
        }.getOrNull()
    }

    private fun selectPhotoSize(cameraId: String): Size {
        val map = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        return map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
    }

    private fun selectQuickPhotoSize(): Size? {
        val cameraId = currentCameraId ?: return null
        val map = cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG) ?: return null
        val streamPixels = CameraConfig.streamWidth * CameraConfig.streamHeight
        return sizes.filter { it.width * it.height in (streamPixels + 1)..(1920 * 1080) }.maxByOrNull { it.width * it.height }
    }

    private fun openCamera(): Boolean {
        if (isCameraOpen.get()) return true
        if (backgroundHandler == null) initialize()

        val cameraId = getCameraId() ?: return false
        currentCameraId = cameraId
        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
        sensorArraySize = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        minFocusDistance = cameraCharacteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        photoSize = selectPhotoSize(cameraId)

        if (!cameraOpenCloseLock.tryAcquire(3000, TimeUnit.MILLISECONDS)) return false

        return try {
            val latch = CountDownLatch(1)
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { cameraOpenCloseLock.release(); cameraDevice = camera; isCameraOpen.set(true); latch.countDown() }
                override fun onDisconnected(camera: CameraDevice) { cameraOpenCloseLock.release(); camera.close(); cameraDevice = null; isCameraOpen.set(false); latch.countDown() }
                override fun onError(camera: CameraDevice, error: Int) { cameraOpenCloseLock.release(); camera.close(); cameraDevice = null; isCameraOpen.set(false); latch.countDown(); Log.e(TAG, "Camera error: $error") }
            }, backgroundHandler)
            latch.await(2000, TimeUnit.MILLISECONDS)
            isCameraOpen.get()
        } catch (e: Exception) { cameraOpenCloseLock.release(); false }
    }

    private fun closeCamera() {
        if (!cameraOpenCloseLock.tryAcquire(3000, TimeUnit.MILLISECONDS)) { forceCloseCamera(); return }
        try {
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
            streamImageReader?.close(); streamImageReader = null
            photoImageReader?.close(); photoImageReader = null
            quickPhotoImageReader?.close(); quickPhotoImageReader = null
            isCameraOpen.set(false)
        } finally { runCatching { cameraOpenCloseLock.release() } }
    }

    private fun forceCloseCamera() {
        runCatching { captureSession?.close() }; captureSession = null
        runCatching { cameraDevice?.close() }; cameraDevice = null
        runCatching { streamImageReader?.close() }; streamImageReader = null
        runCatching { photoImageReader?.close() }; photoImageReader = null
        runCatching { quickPhotoImageReader?.close() }; quickPhotoImageReader = null
        isCameraOpen.set(false)
    }

    fun startStream(callback: ((ByteArray) -> Unit)? = null): Boolean {
        if (isStreaming.get()) return true

        // Prevent concurrent stream operations
        if (!streamOperationInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Stream operation already in progress")
            return false
        }

        try {
            return startStreamInternal(callback)
        } finally {
            streamOperationInProgress.set(false)
        }
    }

    private fun startStreamInternal(callback: ((ByteArray) -> Unit)?): Boolean {
        if (isStreaming.get()) return true
        if (!openCamera()) return false
        streamCallback = callback

        val requestedWidth = CameraConfig.streamWidth
        val requestedHeight = CameraConfig.streamHeight
        val fps = CameraConfig.targetFps
        val quality = CameraConfig.jpegQuality
        val buffers = CameraConfig.bufferCount.coerceAtLeast(4)

        // Find supported size closest to requested
        val cameraId = currentCameraId ?: return false
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()

        val selectedSize = supportedSizes.minByOrNull {
            kotlin.math.abs(it.width - requestedWidth) + kotlin.math.abs(it.height - requestedHeight)
        } ?: Size(requestedWidth, requestedHeight)

        val width = selectedSize.width
        val height = selectedSize.height

        if (width != requestedWidth || height != requestedHeight) {
            Log.w(TAG, "Requested ${requestedWidth}x${requestedHeight} not supported, using ${width}x${height}")
        }

        // Find supported FPS range
        val fpsRanges = cameraCharacteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val selectedFpsRange = fpsRanges
            .filter { it.upper >= fps }
            .minByOrNull { it.upper - fps }
            ?: fpsRanges.maxByOrNull { it.upper }
            ?: Range(15, 30)

        Log.i(TAG, "Starting stream: ${width}x${height}, FPS range: $selectedFpsRange (requested: $fps)")
        actualStreamSize = Size(width, height)

        streamImageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, buffers).apply {
            setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val processed = applyMirror(bytes)
                    lastStreamFrame.set(processed)
                    streamCallback?.invoke(processed)
                }
            }, imageProcessHandler)
        }

        quickPhotoSize = if (dualOutputNotSupported) null else selectQuickPhotoSize()
        if (quickPhotoSize != null && (quickPhotoSize!!.width * quickPhotoSize!!.height) > (width * height)) {
            quickPhotoImageReader = ImageReader.newInstance(quickPhotoSize!!.width, quickPhotoSize!!.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    reader.acquireLatestImage()?.use { image ->
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        lastQuickPhotoFrame.set(applyMirror(bytes))
                    }
                    quickPhotoInProgress.set(false)
                }, imageProcessHandler)
            }
        }

        val camera = cameraDevice ?: return false
        val surfaces = mutableListOf(streamImageReader!!.surface)
        if (quickPhotoImageReader != null) surfaces.add(quickPhotoImageReader!!.surface)

        val sessionLatch = CountDownLatch(1)
        val sessionSuccess = AtomicBoolean(false)

        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        synchronized(streamOperationLock) {
                            // Check if we still want this session
                            if (captureSession != null && captureSession !== session) {
                                Log.w(TAG, "Session replaced, closing old one")
                                session.close()
                                sessionLatch.countDown()
                                return
                            }
                            captureSession = session
                            val reader = streamImageReader
                            if (reader == null) {
                                Log.w(TAG, "ImageReader closed before session configured")
                                session.close()
                                captureSession = null
                                sessionLatch.countDown()
                                return
                            }
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(reader.surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                applyFocusSettings(this)
                                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedFpsRange)
                                set(CaptureRequest.JPEG_QUALITY, quality.toByte())
                            }.build()
                            session.setRepeatingRequest(request, null, backgroundHandler)
                            isStreaming.set(true)
                            sessionSuccess.set(true)
                            Log.i(TAG, "Stream: ${width}x${height}@${fps}fps Q$quality")
                        }
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Session was closed during configuration: ${e.message}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onConfigured: ${e.message}", e)
                    } finally {
                        sessionLatch.countDown()
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) { Log.e(TAG, "Session configuration failed for ${width}x${height}"); sessionLatch.countDown() }
            }, backgroundHandler)
            sessionLatch.await(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            if (quickPhotoImageReader != null) {
                dualOutputNotSupported = true
                quickPhotoImageReader?.close(); quickPhotoImageReader = null; quickPhotoSize = null
                streamImageReader?.close(); streamImageReader = null
                forceCloseCamera()
                return false
            }
        }

        if (!sessionSuccess.get() && quickPhotoImageReader != null) {
            dualOutputNotSupported = true
            quickPhotoImageReader?.close(); quickPhotoImageReader = null; quickPhotoSize = null
            streamImageReader?.close(); streamImageReader = null
            forceCloseCamera()
            return false
        }

        return isStreaming.get()
    }

    fun stopStream() {
        if (!isStreaming.get()) return
        runCatching { captureSession?.stopRepeating() }
        captureSession?.close(); captureSession = null
        streamImageReader?.close(); streamImageReader = null
        quickPhotoImageReader?.close(); quickPhotoImageReader = null
        lastStreamFrame.set(null); lastQuickPhotoFrame.set(null)
        streamCallback = null
        isStreaming.set(false); quickPhotoInProgress.set(false)
        actualStreamSize = null
        synchronized(mirrorLock) { reusableBitmap?.recycle(); reusableBitmap = null }
        closeCamera()
        Log.i(TAG, "Stream stopped")
    }

    fun isStreamActive(): Boolean = isStreaming.get()
    fun getLastFrame(): ByteArray? = lastStreamFrame.get()
    fun setStreamCallback(callback: ((ByteArray) -> Unit)?) { streamCallback = callback }

    fun restartStreamWithNewSettings(): Boolean {
        if (!isStreaming.get()) return true

        // Prevent concurrent stream operations
        if (!streamOperationInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Stream operation already in progress, skipping restart")
            return true // Return true to indicate "ok, will use current settings"
        }

        try {
            val savedCallback = streamCallback
            Log.i(TAG, "Restarting stream with new settings")

            synchronized(streamOperationLock) {
                runCatching { captureSession?.stopRepeating() }
                runCatching { captureSession?.close() }
                captureSession = null
                runCatching { streamImageReader?.close() }
                streamImageReader = null
                runCatching { quickPhotoImageReader?.close() }
                quickPhotoImageReader = null
                isStreaming.set(false)
                quickPhotoInProgress.set(false)
            }

            Thread.sleep(200)
            streamOperationInProgress.set(false) // Release lock before starting
            return startStream(savedCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting stream: ${e.message}", e)
            streamOperationInProgress.set(false)
            return false
        }
    }

    fun captureQuickPhoto(highRes: Boolean = false, timeoutMs: Long = 500): ByteArray? {
        if (!highRes) return lastStreamFrame.get()?.clone()
        val quickReader = quickPhotoImageReader ?: return lastStreamFrame.get()?.clone()
        val camera = cameraDevice ?: return lastStreamFrame.get()?.clone()
        val session = captureSession ?: return lastStreamFrame.get()?.clone()
        if (!isStreaming.get() || !quickPhotoInProgress.compareAndSet(false, true)) return lastStreamFrame.get()?.clone()

        return try {
            lastQuickPhotoFrame.set(null)
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(quickReader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                applyFocusSettings(this)
                set(CaptureRequest.JPEG_QUALITY, CameraConfig.PHOTO_QUALITY.toByte())
            }.build()
            val latch = CountDownLatch(1)
            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) { backgroundHandler?.postDelayed({ latch.countDown() }, 50) }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) { quickPhotoInProgress.set(false); latch.countDown() }
            }, backgroundHandler)
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { quickPhotoInProgress.set(false); return lastStreamFrame.get()?.clone() }
            Thread.sleep(50)
            lastQuickPhotoFrame.get()?.clone() ?: lastStreamFrame.get()?.clone()
        } catch (e: Exception) { quickPhotoInProgress.set(false); lastStreamFrame.get()?.clone() }
    }

    fun isHighResQuickPhotoAvailable(): Boolean = quickPhotoImageReader != null && isStreaming.get()
    fun getQuickPhotoResolution(highRes: Boolean): String = if (highRes && quickPhotoSize != null) "${quickPhotoSize!!.width}x${quickPhotoSize!!.height}" else "${CameraConfig.streamWidth}x${CameraConfig.streamHeight}"

    fun capturePhoto(): ByteArray? {
        // Prevent concurrent photo captures
        if (!photoInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Photo capture already in progress, ignoring")
            return null
        }

        val wasStreaming = isStreaming.get()
        val savedCallback = streamCallback

        try {
            if (wasStreaming) {
                runCatching { captureSession?.stopRepeating() }
                runCatching { captureSession?.close() }
                captureSession = null
                runCatching { streamImageReader?.close() }
                streamImageReader = null
                runCatching { photoImageReader?.close() }
                photoImageReader = null
                isStreaming.set(false)
                Thread.sleep(100) // Give camera time to release resources
            }
            if (!isCameraOpen.get() && !openCamera()) {
                photoInProgress.set(false)
                return null
            }
            val camera = cameraDevice ?: run { photoInProgress.set(false); return null }

            photoImageReader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 2)
            val photoResult = AtomicReference<ByteArray?>(null)
            val sessionLatch = CountDownLatch(1)
            val captureLatch = CountDownLatch(1)

            photoImageReader!!.setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    photoResult.set(applyMirror(bytes))
                }
                captureLatch.countDown()
            }, backgroundHandler)

            var photoSession: CameraCaptureSession? = null
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(photoImageReader!!.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { photoSession = session; sessionLatch.countDown() }
                override fun onConfigureFailed(session: CameraCaptureSession) { sessionLatch.countDown(); captureLatch.countDown() }
            }, backgroundHandler)

            if (!sessionLatch.await(3, TimeUnit.SECONDS) || photoSession == null) {
                Log.w(TAG, "Photo session creation timeout")
                runCatching { photoImageReader?.close() }
                photoImageReader = null
                photoInProgress.set(false)
                if (wasStreaming) runCatching { startStream(savedCallback) }
                return null
            }

            // Pre-capture
            val precaptureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(photoImageReader!!.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }.build()
            val precaptureLatch = CountDownLatch(1)
            photoSession!!.capture(precaptureRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) { precaptureLatch.countDown() }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) { precaptureLatch.countDown() }
            }, backgroundHandler)
            precaptureLatch.await(1500, TimeUnit.MILLISECONDS)
            Thread.sleep(200)

            // Main capture
            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(photoImageReader!!.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                set(CaptureRequest.JPEG_QUALITY, CameraConfig.PHOTO_QUALITY.toByte())
            }.build()
            photoSession!!.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) { captureLatch.countDown() }
            }, backgroundHandler)

            if (!captureLatch.await(5, TimeUnit.SECONDS)) {
                Log.w(TAG, "Photo capture timeout")
                runCatching { photoSession?.close() }
                runCatching { photoImageReader?.close() }
                photoImageReader = null
                photoInProgress.set(false)
                if (wasStreaming) runCatching { startStream(savedCallback) }
                return null
            }
            runCatching { photoSession?.close() }
            runCatching { photoImageReader?.close() }
            photoImageReader = null
            photoInProgress.set(false)
            if (wasStreaming) startStream(savedCallback) else closeCamera()
            return photoResult.get()
        } catch (e: Exception) {
            Log.e(TAG, "Photo failed: ${e.message}", e)
            isStreaming.set(false)
            photoInProgress.set(false)
            runCatching { photoImageReader?.close() }
            photoImageReader = null
            if (wasStreaming) runCatching { startStream(savedCallback) }
            return null
        }
    }

    private fun applyFocusSettings(builder: CaptureRequest.Builder) {
        val afMode = when (currentFocusMode) {
            FocusMode.AUTO -> CaptureRequest.CONTROL_AF_MODE_AUTO
            FocusMode.CONTINUOUS -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            FocusMode.MANUAL -> if (minFocusDistance > 0) CaptureRequest.CONTROL_AF_MODE_OFF else CaptureRequest.CONTROL_AF_MODE_AUTO
            FocusMode.FIXED -> CaptureRequest.CONTROL_AF_MODE_OFF
            FocusMode.MACRO -> CaptureRequest.CONTROL_AF_MODE_MACRO
        }
        Log.d(TAG, "Applying AF mode: $afMode for $currentFocusMode")
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)

        when (currentFocusMode) {
            FocusMode.MANUAL -> if (minFocusDistance > 0) builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance * minFocusDistance)
            FocusMode.FIXED -> if (minFocusDistance > 0) builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, minFocusDistance * 0.3f)
            else -> {}
        }
    }

    fun setFocusMode(mode: FocusMode): Boolean {
        Log.i(TAG, "Setting focus mode: $mode (was: $currentFocusMode)")
        currentFocusMode = mode
        return if (isStreaming.get()) {
            val result = updateCaptureSettings()
            Log.i(TAG, "Focus mode update result: $result")
            result
        } else true
    }
    fun setManualFocusDistance(distance: Float): Boolean { manualFocusDistance = distance.coerceIn(0f, 1f); return if (currentFocusMode == FocusMode.MANUAL && isStreaming.get()) updateCaptureSettings() else true }

    fun focusOnPoint(x: Float, y: Float): Boolean {
        val camera = cameraDevice ?: return false
        val session = captureSession ?: return false
        val imageReader = streamImageReader ?: return false
        val sensor = sensorArraySize ?: return false
        if (!isStreaming.get()) return false

        val focusX = (x * sensor.width()).toInt().coerceIn(0, sensor.width())
        val focusY = (y * sensor.height()).toInt().coerceIn(0, sensor.height())
        val halfSize = (sensor.width() * 0.05f).toInt()
        val focusRect = Rect((focusX - halfSize).coerceAtLeast(0), (focusY - halfSize).coerceAtLeast(0), (focusX + halfSize).coerceAtMost(sensor.width()), (focusY + halfSize).coerceAtMost(sensor.height()))
        val meteringRect = MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX)

        return runCatching {
            session.capture(camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(imageReader.surface); set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL) }.build(), null, backgroundHandler)
            session.capture(camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
                set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(CameraConfig.targetFps, CameraConfig.targetFps))
                set(CaptureRequest.JPEG_QUALITY, CameraConfig.jpegQuality.toByte())
            }.build(), null, backgroundHandler)
            true
        }.getOrDefault(false)
    }

    fun triggerAutoFocus(): Boolean {
        val camera = cameraDevice ?: return false
        val session = captureSession ?: return false
        val imageReader = streamImageReader ?: return false
        if (!isStreaming.get()) return false
        return runCatching {
            session.capture(camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(CameraConfig.targetFps, CameraConfig.targetFps))
                set(CaptureRequest.JPEG_QUALITY, CameraConfig.jpegQuality.toByte())
            }.build(), null, backgroundHandler)
            true
        }.getOrDefault(false)
    }

    private fun updateCaptureSettings(): Boolean {
        val camera = cameraDevice ?: run { Log.w(TAG, "updateCaptureSettings: no camera"); return false }
        val session = captureSession ?: run { Log.w(TAG, "updateCaptureSettings: no session"); return false }
        val imageReader = streamImageReader ?: run { Log.w(TAG, "updateCaptureSettings: no reader"); return false }
        return runCatching {
            Log.d(TAG, "Updating capture settings with focus: $currentFocusMode")
            session.setRepeatingRequest(camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                applyFocusSettings(this)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(CameraConfig.targetFps, CameraConfig.targetFps))
                set(CaptureRequest.JPEG_QUALITY, CameraConfig.jpegQuality.toByte())
            }.build(), null, backgroundHandler)
            true
        }.getOrDefault(false)
    }

    private fun applyMirror(jpegData: ByteArray): ByteArray {
        if (!needsMirroring) return jpegData
        synchronized(mirrorLock) {
            return runCatching {
                bitmapOptions.inBitmap = reusableBitmap
                val bitmap = runCatching { BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, bitmapOptions) }.getOrElse { bitmapOptions.inBitmap = null; BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, bitmapOptions) } ?: return jpegData
                reusableBitmap = bitmap
                val mirrored = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, mirrorMatrix, true)
                mirrorOutputStream.reset()
                mirrored.compress(Bitmap.CompressFormat.JPEG, CameraConfig.jpegQuality, mirrorOutputStream)
                if (mirrored !== bitmap) mirrored.recycle()
                mirrorOutputStream.toByteArray()
            }.getOrDefault(jpegData)
        }
    }

    fun getStatus(): CameraStatus {
        val streamRes = actualStreamSize?.let { "${it.width}x${it.height}" }
            ?: "${CameraConfig.streamWidth}x${CameraConfig.streamHeight}"
        return CameraStatus(
            isOpen = isCameraOpen.get(),
            isStreaming = isStreaming.get(),
            streamResolution = streamRes,
            photoResolution = "${photoSize.width}x${photoSize.height}",
            targetFps = CameraConfig.targetFps,
            jpegQuality = CameraConfig.jpegQuality,
            focusMode = currentFocusMode.name,
            supportsManualFocus = minFocusDistance > 0
        )
    }

    fun getSupportedFocusModes(): List<FocusMode> {
        val modes = mutableListOf(FocusMode.AUTO, FocusMode.CONTINUOUS)
        if (minFocusDistance > 0) { modes.add(FocusMode.MANUAL); modes.add(FocusMode.FIXED) }
        if (cameraCharacteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_MACRO) == true) modes.add(FocusMode.MACRO)
        return modes
    }
}

data class CameraStatus(
    val isOpen: Boolean,
    val isStreaming: Boolean,
    val streamResolution: String,
    val photoResolution: String,
    val targetFps: Int,
    val jpegQuality: Int,
    val focusMode: String,
    val supportsManualFocus: Boolean
)
