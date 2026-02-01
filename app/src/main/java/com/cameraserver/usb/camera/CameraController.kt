package com.cameraserver.usb.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import com.cameraserver.usb.config.CameraConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Контроллер камеры на базе Camera2 API
 *
 * Основные возможности:
 * - MJPEG стрим с настраиваемым разрешением и FPS
 * - Быстрое фото из текущего кадра стрима
 * - Full resolution фото с пересозданием сессии (~2-3 сек, клиент видит последний кадр)
 * - Постоянный автофокус (CONTINUOUS_VIDEO для стрима, CONTINUOUS_PICTURE для фото)
 *
 * Архитектура:
 * - Отдельные потоки для Camera2 callbacks и обработки изображений
 * - Recreate session подход: закрыть stream session → photo session → закрыть → stream session
 * - Семафор для защиты открытия/закрытия камеры
 * - AtomicBoolean для потокобезопасных флагов состояния
 */
class CameraController(private val context: Context) {

    companion object {
        private const val TAG = "CameraController"
    }

    // ══════════════════════════════════════════════════════════════════
    // СИСТЕМНЫЕ КОМПОНЕНТЫ
    // ══════════════════════════════════════════════════════════════════

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // ImageReader для стрима
    private var streamImageReader: ImageReader? = null

    // Фоновые потоки
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageProcessThread: HandlerThread? = null
    private var imageProcessHandler: Handler? = null

    // ══════════════════════════════════════════════════════════════════
    // СИНХРОНИЗАЦИЯ
    // ══════════════════════════════════════════════════════════════════

    private val cameraOpenCloseLock = Semaphore(1)
    private val isStreaming = AtomicBoolean(false)
    private val isCameraOpen = AtomicBoolean(false)
    private val streamOperationLock = Object()
    private val streamOperationInProgress = AtomicBoolean(false)

    // ══════════════════════════════════════════════════════════════════
    // ДАННЫЕ
    // ══════════════════════════════════════════════════════════════════

    private val lastStreamFrame = AtomicReference<ByteArray?>(null)
    private var streamCallback: ((ByteArray) -> Unit)? = null

    private var photoSize: Size = Size(1920, 1080)
    private var actualStreamSize: Size? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var sensorArraySize: Rect? = null
    private var currentCameraId: String? = null


    // ══════════════════════════════════════════════════════════════════
    // ИНИЦИАЛИЗАЦИЯ И ОСВОБОЖДЕНИЕ
    // ══════════════════════════════════════════════════════════════════

    /**
     * Инициализирует фоновые потоки для работы с камерой
     */
    fun initialize() {
        backgroundThread = HandlerThread("CameraBackground", Thread.MAX_PRIORITY).also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        imageProcessThread = HandlerThread("ImageProcess", Thread.MAX_PRIORITY - 1).also { it.start() }
        imageProcessHandler = Handler(imageProcessThread!!.looper)

        Log.i(TAG, "Инициализирован")
    }

    /**
     * Освобождает все ресурсы камеры и потоки
     */
    fun release() {
        stopStream()
        closeCamera()

        backgroundThread?.quitSafely()
        imageProcessThread?.quitSafely()

        runCatching {
            backgroundThread?.join(1000)
            imageProcessThread?.join(1000)
        }

        backgroundThread = null
        backgroundHandler = null
        imageProcessThread = null
        imageProcessHandler = null

        Log.i(TAG, "Освобождён")
    }

    // ══════════════════════════════════════════════════════════════════
    // РАБОТА С КАМЕРОЙ
    // ══════════════════════════════════════════════════════════════════

    /**
     * Возвращает ID камеры (передняя или задняя в зависимости от настроек)
     */
    private fun getCameraId(): String? {
        val facing = if (CameraConfig.USE_FRONT_CAMERA)
            CameraCharacteristics.LENS_FACING_FRONT
        else
            CameraCharacteristics.LENS_FACING_BACK

        return runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                runCatching {
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) == facing
                }.getOrDefault(false)
            }
        }.getOrNull()
    }

    /**
     * Выбирает максимальное разрешение для фото
     */
    private fun selectPhotoSize(cameraId: String): Size {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        return map?.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width * it.height }
            ?: Size(1920, 1080)
    }

    /**
     * Открывает камеру с таймаутом
     */
    private fun openCamera(): Boolean {
        if (isCameraOpen.get()) return true
        if (backgroundHandler == null) initialize()

        val cameraId = getCameraId() ?: return false
        currentCameraId = cameraId
        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
        sensorArraySize = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        photoSize = selectPhotoSize(cameraId)

        if (!cameraOpenCloseLock.tryAcquire(CameraConfig.CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return false
        }

        return try {
            val latch = CountDownLatch(1)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    isCameraOpen.set(true)
                    latch.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    isCameraOpen.set(false)
                    latch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    isCameraOpen.set(false)
                    latch.countDown()
                    Log.e(TAG, "Ошибка камеры: $error")
                }
            }, backgroundHandler)

            latch.await(2000, TimeUnit.MILLISECONDS)
            isCameraOpen.get()
        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            false
        }
    }

    /**
     * Закрывает камеру безопасно
     */
    private fun closeCamera() {
        if (!cameraOpenCloseLock.tryAcquire(CameraConfig.CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            forceCloseCamera()
            return
        }

        try {
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            streamImageReader?.close()
            streamImageReader = null

            isCameraOpen.set(false)
        } finally {
            runCatching { cameraOpenCloseLock.release() }
        }
    }

    /**
     * Принудительное закрытие камеры без ожидания
     */
    private fun forceCloseCamera() {
        runCatching { captureSession?.close() }
        captureSession = null

        runCatching { cameraDevice?.close() }
        cameraDevice = null

        runCatching { streamImageReader?.close() }
        streamImageReader = null

        isCameraOpen.set(false)
    }

    // ══════════════════════════════════════════════════════════════════
    // СТРИМ
    // ══════════════════════════════════════════════════════════════════

    /**
     * Запускает MJPEG стрим
     *
     * @param callback функция, вызываемая при получении каждого кадра
     * @return true если стрим успешно запущен
     */
    fun startStream(callback: ((ByteArray) -> Unit)? = null): Boolean {
        if (isStreaming.get()) return true

        // Защита от одновременных операций со стримом
        if (!streamOperationInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Операция со стримом уже выполняется")
            return false
        }

        try {
            return startStreamInternal(callback)
        } finally {
            streamOperationInProgress.set(false)
        }
    }

    /**
     * Внутренняя реализация запуска стрима
     */
    private fun startStreamInternal(callback: ((ByteArray) -> Unit)?): Boolean {
        if (isStreaming.get()) return true
        if (!openCamera()) return false

        streamCallback = callback

        val requestedWidth = CameraConfig.streamWidth
        val requestedHeight = CameraConfig.streamHeight
        val fps = CameraConfig.targetFps
        val quality = CameraConfig.jpegQuality
        val buffers = CameraConfig.bufferCount.coerceAtLeast(4)

        // Выбираем ближайшее поддерживаемое разрешение
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
            Log.w(TAG, "Запрошено ${requestedWidth}x${requestedHeight}, используем ${width}x${height}")
        }

        // Выбираем подходящий диапазон FPS
        val fpsRanges = cameraCharacteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: emptyArray()
        val selectedFpsRange = fpsRanges
            .filter { it.upper >= fps }
            .minByOrNull { it.upper - fps }
            ?: fpsRanges.maxByOrNull { it.upper }
            ?: Range(15, 30)

        Log.i(TAG, "Запуск стрима: ${width}x${height}, FPS: $selectedFpsRange (запрошено: $fps)")
        actualStreamSize = Size(width, height)

        // Создаём ImageReader для стрима
        streamImageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, buffers).apply {
            setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    lastStreamFrame.set(bytes)
                    streamCallback?.invoke(bytes)
                }
            }, imageProcessHandler)
        }

        val camera = cameraDevice ?: return false
        val sessionLatch = CountDownLatch(1)
        val sessionSuccess = AtomicBoolean(false)

        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(streamImageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        synchronized(streamOperationLock) {
                            if (captureSession != null && captureSession !== session) {
                                Log.w(TAG, "Сессия заменена, закрываем старую")
                                session.close()
                                sessionLatch.countDown()
                                return
                            }

                            captureSession = session
                            val reader = streamImageReader

                            if (reader == null) {
                                Log.w(TAG, "ImageReader закрыт до настройки сессии")
                                session.close()
                                captureSession = null
                                sessionLatch.countDown()
                                return
                            }

                            // Создаём repeating request для стрима
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(reader.surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

                                // Постоянный автофокус для видео
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

                                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedFpsRange)
                                set(CaptureRequest.JPEG_QUALITY, quality.toByte())
                            }.build()

                            session.setRepeatingRequest(request, null, backgroundHandler)
                            isStreaming.set(true)
                            sessionSuccess.set(true)

                            Log.i(TAG, "Стрим: ${width}x${height}@${fps}fps Q$quality, фокус: CONTINUOUS")
                        }
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Сессия закрыта во время конфигурации: ${e.message}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка в onConfigured: ${e.message}", e)
                    } finally {
                        sessionLatch.countDown()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Ошибка конфигурации сессии для ${width}x${height}")
                    sessionLatch.countDown()
                }
            }, backgroundHandler)

            sessionLatch.await(CameraConfig.CAMERA_SESSION_TIMEOUT_MS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка создания сессии: ${e.message}", e)
            streamImageReader?.close()
            streamImageReader = null
            forceCloseCamera()
            return false
        }

        // Если сессия не создалась
        if (!sessionSuccess.get()) {
            Log.e(TAG, "Не удалось создать capture session")
            streamImageReader?.close()
            streamImageReader = null
            forceCloseCamera()
            return false
        }

        return isStreaming.get()
    }

    /**
     * Останавливает стрим
     */
    fun stopStream() {
        if (!isStreaming.get()) return

        runCatching { captureSession?.stopRepeating() }
        captureSession?.close()
        captureSession = null

        streamImageReader?.close()
        streamImageReader = null

        lastStreamFrame.set(null)
        streamCallback = null

        isStreaming.set(false)
        actualStreamSize = null

        closeCamera()
        Log.i(TAG, "Стрим остановлен")
    }

    /** Проверяет, активен ли стрим */
    fun isStreamActive(): Boolean = isStreaming.get()

    /** Возвращает последний кадр стрима */
    fun getLastFrame(): ByteArray? = lastStreamFrame.get()

    /** Устанавливает callback для получения кадров */
    fun setStreamCallback(callback: ((ByteArray) -> Unit)?) {
        streamCallback = callback
    }

    /**
     * Перезапускает стрим с новыми настройками
     */
    fun restartStreamWithNewSettings(): Boolean {
        if (!isStreaming.get()) return true

        if (!streamOperationInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Операция со стримом уже выполняется, пропускаем перезапуск")
            return true
        }

        try {
            val savedCallback = streamCallback
            Log.i(TAG, "Перезапуск стрима с новыми настройками")

            synchronized(streamOperationLock) {
                runCatching { captureSession?.stopRepeating() }
                runCatching { captureSession?.close() }
                captureSession = null

                runCatching { streamImageReader?.close() }
                streamImageReader = null

                isStreaming.set(false)
            }

            Thread.sleep(CameraConfig.STREAM_RESTART_DELAY_MS)
            streamOperationInProgress.set(false)
            return startStream(savedCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка перезапуска стрима: ${e.message}", e)
            streamOperationInProgress.set(false)
            return false
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ФОТО
    // ══════════════════════════════════════════════════════════════════

    // Защита от параллельных операций фото
    private val photoInProgress = AtomicBoolean(false)

    /**
     * Делает быстрое фото (текущий кадр стрима)
     */
    fun captureQuickPhoto(): ByteArray? {
        return lastStreamFrame.get()?.clone()
    }

    /**
     * Делает фото в полном разрешении
     *
     * Если стрим активен - пересоздает сессию (закрывает stream session, создает photo session,
     * делает фото, закрывает photo session, создает stream session заново).
     * Клиенты продолжат видеть последний кадр во время съемки (~2-3 сек).
     * Если стрим не активен - создаёт отдельную сессию для фото.
     *
     * @return JPEG данные фото, null если захват уже выполняется или ошибка
     */
    fun capturePhoto(): ByteArray? {
        // Защита от параллельных захватов
        if (!photoInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Фото уже захватывается, запрос отклонен")
            return null
        }

        return try {
            if (isStreaming.get()) {
                capturePhotoWithActiveStream()
            } else {
                capturePhotoWithoutStream()
            }
        } finally {
            photoInProgress.set(false)
        }
    }

    /**
     * Захват full resolution фото при активном стриме
     * Пересоздает сессию: pause → закрыть сессию → создать с photo surface → фото → закрыть → создать с stream surface → resume
     */
    private fun capturePhotoWithActiveStream(): ByteArray? {
        Log.i(TAG, "Full photo при активном стриме: recreate session подход")

        val camera = cameraDevice ?: run {
            Log.e(TAG, "CameraDevice = null")
            return null
        }

        val oldSession = captureSession
        val savedCallback = streamCallback

        return try {
            // 1. PAUSE: Останавливаем и закрываем текущую сессию
            Log.d(TAG, "Останавливаем стрим сессию для фото")
            runCatching { oldSession?.stopRepeating() }
            runCatching { oldSession?.close() }
            captureSession = null
            Thread.sleep(100) // Даем камере завершить

            // 2. PHOTO SESSION: Создаем сессию с photo surface
            val photoReader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 2)
            val photoResult = AtomicReference<ByteArray?>(null)
            val photoSessionLatch = CountDownLatch(1)
            val captureLatch = CountDownLatch(1)
            var photoSession: CameraCaptureSession? = null

            photoReader.setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    photoResult.set(bytes)
                }
                captureLatch.countDown()
            }, backgroundHandler)

            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(photoReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    photoSession = session
                    photoSessionLatch.countDown()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Ошибка конфигурации photo session")
                    photoSessionLatch.countDown()
                }
            }, backgroundHandler)

            // Ждем создания photo session
            if (!photoSessionLatch.await(CameraConfig.CAMERA_SESSION_TIMEOUT_MS, TimeUnit.SECONDS) || photoSession == null) {
                Log.e(TAG, "Таймаут создания photo session")
                photoReader.close()
                return restartStreamAfterPhotoError(savedCallback)
            }

            // 3. CAPTURE: Делаем фото
            Log.d(TAG, "Захват full resolution фото")
            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(photoReader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.JPEG_QUALITY, CameraConfig.PHOTO_QUALITY.toByte())
            }.build()

            photoSession!!.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    Log.e(TAG, "Ошибка захвата фото: ${f.reason}")
                    captureLatch.countDown()
                }
            }, backgroundHandler)

            // Ждем завершения фото
            captureLatch.await(CameraConfig.PHOTO_CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val result = photoResult.get()

            // 4. CLEANUP: Закрываем photo session и reader
            runCatching { photoSession?.close() }
            runCatching { photoReader.close() }
            Thread.sleep(100)

            // 5. RESUME: Пересоздаем stream session
            Log.d(TAG, "Пересоздаем stream session")
            val streamReader = streamImageReader ?: run {
                Log.e(TAG, "StreamImageReader = null при resume")
                return result
            }

            val streamSessionLatch = CountDownLatch(1)
            val streamSessionSuccess = AtomicBoolean(false)

            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(streamReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        captureSession = session

                        val resumeRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(streamReader.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(CameraConfig.targetFps, CameraConfig.targetFps))
                            set(CaptureRequest.JPEG_QUALITY, CameraConfig.jpegQuality.toByte())
                        }.build()

                        session.setRepeatingRequest(resumeRequest, null, backgroundHandler)
                        streamCallback = savedCallback
                        streamSessionSuccess.set(true)
                        Log.i(TAG, "Стрим возобновлен после фото")
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка при resume stream: ${e.message}")
                    } finally {
                        streamSessionLatch.countDown()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Ошибка конфигурации stream session при resume")
                    streamSessionLatch.countDown()
                }
            }, backgroundHandler)

            streamSessionLatch.await(CameraConfig.CAMERA_SESSION_TIMEOUT_MS, TimeUnit.SECONDS)

            if (result != null) {
                Log.i(TAG, "Full photo успешно: ${result.size} байт (${photoSize.width}x${photoSize.height})")
            } else {
                Log.w(TAG, "Фото не получено")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка recreate session photo: ${e.message}", e)
            restartStreamAfterPhotoError(savedCallback)
        }
    }

    /**
     * Восстанавливает стрим после ошибки при фото
     */
    private fun restartStreamAfterPhotoError(savedCallback: ((ByteArray) -> Unit)?): ByteArray? {
        try {
            Log.w(TAG, "Попытка восстановить стрим после ошибки фото")
            val streamReader = streamImageReader ?: return null
            val camera = cameraDevice ?: return null

            val streamSessionLatch = CountDownLatch(1)

            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(streamReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        captureSession = session

                        val resumeRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(streamReader.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(CameraConfig.targetFps, CameraConfig.targetFps))
                            set(CaptureRequest.JPEG_QUALITY, CameraConfig.jpegQuality.toByte())
                        }.build()

                        session.setRepeatingRequest(resumeRequest, null, backgroundHandler)
                        streamCallback = savedCallback
                        Log.i(TAG, "Стрим восстановлен после ошибки")
                    } catch (e: Exception) {
                        Log.e(TAG, "Не удалось восстановить стрим: ${e.message}")
                    } finally {
                        streamSessionLatch.countDown()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Ошибка конфигурации при восстановлении")
                    streamSessionLatch.countDown()
                }
            }, backgroundHandler)

            streamSessionLatch.await(CameraConfig.CAMERA_SESSION_TIMEOUT_MS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Критическая ошибка восстановления стрима: ${e.message}")
        }

        return null
    }

    /**
     * Захват фото когда стрим не активен (создание отдельной сессии)
     */
    private fun capturePhotoWithoutStream(): ByteArray? {
        if (!isCameraOpen.get() && !openCamera()) {
            Log.e(TAG, "Не удалось открыть камеру для фото")
            return null
        }

        val camera = cameraDevice ?: run {
            Log.e(TAG, "CameraDevice = null")
            return null
        }

        return try {
            val reader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 2)
            val photoResult = AtomicReference<ByteArray?>(null)
            val sessionLatch = CountDownLatch(1)
            val captureLatch = CountDownLatch(1)

            reader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    photoResult.set(bytes)
                }
                captureLatch.countDown()
            }, backgroundHandler)

            var photoSession: CameraCaptureSession? = null

            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    photoSession = session
                    sessionLatch.countDown()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Ошибка конфигурации сессии для фото")
                    sessionLatch.countDown()
                    captureLatch.countDown()
                }
            }, backgroundHandler)

            if (!sessionLatch.await(CameraConfig.CAMERA_SESSION_TIMEOUT_MS, TimeUnit.SECONDS) || photoSession == null) {
                Log.e(TAG, "Таймаут создания сессии для фото")
                runCatching { reader.close() }
                closeCamera()
                return null
            }

            // Запрос на фото
            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.JPEG_QUALITY, CameraConfig.PHOTO_QUALITY.toByte())
            }.build()

            photoSession!!.capture(captureRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    Log.e(TAG, "Ошибка захвата фото: ${f.reason}")
                    captureLatch.countDown()
                }
            }, backgroundHandler)

            if (!captureLatch.await(CameraConfig.PHOTO_CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Таймаут захвата фото")
            }

            runCatching { photoSession?.close() }
            runCatching { reader.close() }
            closeCamera()

            val result = photoResult.get()
            if (result != null) {
                Log.i(TAG, "Фото захвачено (стрим неактивен): ${result.size} байт (${photoSize.width}x${photoSize.height})")
            } else {
                Log.w(TAG, "Фото не получено (стрим неактивен)")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка фото без стрима: ${e.message}", e)
            closeCamera()
            null
        }
    }


    // ══════════════════════════════════════════════════════════════════
    // СТАТУС
    // ══════════════════════════════════════════════════════════════════

    /**
     * Возвращает текущий статус камеры
     */
    fun getStatus(): CameraStatus {
        val streamRes = actualStreamSize?.let { "${it.width}x${it.height}" }
            ?: "${CameraConfig.streamWidth}x${CameraConfig.streamHeight}"

        return CameraStatus(
            isOpen = isCameraOpen.get(),
            isStreaming = isStreaming.get(),
            streamResolution = streamRes,
            photoResolution = "${photoSize.width}x${photoSize.height}",
            targetFps = CameraConfig.targetFps,
            jpegQuality = CameraConfig.jpegQuality
        )
    }
}

/**
 * Статус камеры
 */
data class CameraStatus(
    val isOpen: Boolean,
    val isStreaming: Boolean,
    val streamResolution: String,
    val photoResolution: String,
    val targetFps: Int,
    val jpegQuality: Int
)
