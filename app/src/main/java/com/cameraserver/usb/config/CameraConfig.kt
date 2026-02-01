package com.cameraserver.usb.config

import android.util.Log

/**
 * Режим фокусировки камеры
 *
 * CONTINUOUS - постоянный автофокус, всегда активен
 */
enum class FocusMode {
    CONTINUOUS
}

/**
 * Центральная конфигурация приложения
 *
 * Все настраиваемые параметры собраны здесь для упрощения поддержки.
 * Runtime-параметры (разрешение, FPS, качество) можно менять через API.
 */
object CameraConfig {
    private const val TAG = "CameraConfig"

    // ══════════════════════════════════════════════════════════════════
    // КАМЕРА - базовые настройки
    // ══════════════════════════════════════════════════════════════════

    /** Использовать переднюю камеру (false = задняя) */
    const val USE_FRONT_CAMERA = false

    /** Режим фокуса по умолчанию */
    val DEFAULT_FOCUS_MODE = FocusMode.CONTINUOUS

    /** Качество JPEG для фото в полном разрешении (0-100) */
    const val PHOTO_QUALITY = 95

    // ══════════════════════════════════════════════════════════════════
    // HTTP СЕРВЕР
    // ══════════════════════════════════════════════════════════════════

    /** Порт HTTP сервера */
    const val SERVER_PORT = 8080

    /** Граница MJPEG кадров */
    const val MJPEG_BOUNDARY = "frame"

    /** Размер очереди кадров на клиента */
    const val CLIENT_FRAME_QUEUE_SIZE = 5

    /** Размер буфера PipedInputStream для MJPEG (512KB) */
    const val MJPEG_PIPE_BUFFER_SIZE = 524288

    /** Максимум таймаутов подряд перед закрытием соединения */
    const val MJPEG_MAX_CONSECUTIVE_TIMEOUTS = 10

    // ══════════════════════════════════════════════════════════════════
    // СТРИМ - runtime настройки (изменяемые через API)
    // ══════════════════════════════════════════════════════════════════

    @Volatile var streamWidth: Int = 1280
        private set
    @Volatile var streamHeight: Int = 720
        private set
    @Volatile var targetFps: Int = 30
        private set
    @Volatile var jpegQuality: Int = 80
        private set

    // Границы значений
    private const val MIN_WIDTH = 320
    private const val MAX_WIDTH = 3840
    private const val MIN_HEIGHT = 240
    private const val MAX_HEIGHT = 2160
    private const val MIN_FPS = 5
    private const val MAX_FPS = 60
    private const val MIN_QUALITY = 30
    private const val MAX_QUALITY = 100

    /** Количество буферов ImageReader в зависимости от FPS */
    val bufferCount: Int get() = when {
        targetFps >= 60 -> 8
        targetFps >= 30 -> 5
        else -> 4
    }

    /** Интервал между кадрами в мс */
    val frameIntervalMs: Long get() = 1000L / targetFps

    /**
     * Динамический таймаут стрима на основе FPS
     * Вычисляется как: frameInterval * WATCHDOG_STREAM_MISSED_FRAMES
     * Например, для 30 FPS: 33ms * 300 = 10 секунд
     */
    val watchdogStreamTimeoutMs: Long
        get() = (frameIntervalMs * WATCHDOG_STREAM_MISSED_FRAMES).coerceAtLeast(WATCHDOG_STREAM_TIMEOUT_MIN_MS)

    // ══════════════════════════════════════════════════════════════════
    // WATCHDOG - мониторинг здоровья
    // ══════════════════════════════════════════════════════════════════

    /** Интервал проверки здоровья (мс) */
    const val WATCHDOG_CHECK_INTERVAL_MS = 5000L

    /** Минимальный таймаут стрима (мс) */
    const val WATCHDOG_STREAM_TIMEOUT_MIN_MS = 5000L

    /** Количество пропущенных кадров до срабатывания watchdog */
    const val WATCHDOG_STREAM_MISSED_FRAMES = 300

    /** Таймаут сервера - нет ответов дольше этого времени = проблема (мс) */
    const val WATCHDOG_SERVER_TIMEOUT_MS = 30000L

    /** Множитель для таймаута сервера при критической проверке */
    const val WATCHDOG_SERVER_TIMEOUT_MULTIPLIER = 4

    /** Максимум последовательных ошибок перед критическим действием */
    const val WATCHDOG_MAX_CONSECUTIVE_FAILURES = 3

    /** Порог предупреждения о памяти (0.0-1.0) */
    const val WATCHDOG_MEMORY_WARNING_THRESHOLD = 0.85f

    /** Критический порог памяти (0.0-1.0) */
    const val WATCHDOG_MEMORY_CRITICAL_THRESHOLD = 0.95f

    // ══════════════════════════════════════════════════════════════════
    // SERVICE GUARD - защита сервиса
    // ══════════════════════════════════════════════════════════════════

    /** Интервал проверки сервиса через AlarmManager (мс) */
    const val SERVICE_CHECK_INTERVAL_MS = 30000L

    /** Задержки между попытками восстановления (мс) */
    val RECOVERY_RETRY_DELAYS = longArrayOf(
        2_000,    // 2 сек
        5_000,    // 5 сек
        10_000,   // 10 сек
        30_000,   // 30 сек
        60_000,   // 1 мин
        120_000,  // 2 мин
        300_000   // 5 мин (максимум)
    )

    /** Множитель задержки для стратегии CLEAR_STATE_RESTART */
    const val RECOVERY_CLEAR_STATE_DELAY_MULTIPLIER = 2

    /** Пороги для выбора стратегии восстановления (по количеству последовательных ошибок) */
    const val RECOVERY_SIMPLE_RESTART_THRESHOLD = 3
    const val RECOVERY_DELAYED_RESTART_THRESHOLD = 6
    const val RECOVERY_CLEAR_STATE_THRESHOLD = 10

    /** Задержка для стратегии FULL_APP_RESTART (мс) */
    const val RECOVERY_FULL_RESTART_DELAY_MS = 1000L

    /** Время ожидания после запуска сервиса для проверки его работы (мс) */
    const val SERVICE_START_VERIFICATION_DELAY_MS = 3000L

    /** Задержка перед ребутом устройства (мс) */
    const val DEVICE_REBOOT_DELAY_MS = 1000L

    // ══════════════════════════════════════════════════════════════════
    // CAMERA RECOVERY - восстановление камеры
    // ══════════════════════════════════════════════════════════════════

    /** Задержки между попытками восстановления камеры (мс) */
    val CAMERA_RECOVERY_DELAYS_MS = listOf(500L, 1000L, 2000L, 5000L)

    /** Максимум попыток восстановления камеры */
    const val CAMERA_RECOVERY_MAX_RETRIES = 4

    /** Cooldown между восстановлениями камеры (мс) */
    const val CAMERA_RECOVERY_COOLDOWN_MS = 30000L

    /** Задержка после release камеры перед initialize (мс) */
    const val CAMERA_FULL_RESET_RELEASE_DELAY_MS = 1000L

    /** Задержка после initialize камеры (мс) */
    const val CAMERA_FULL_RESET_INIT_DELAY_MS = 500L

    // ══════════════════════════════════════════════════════════════════
    // DEVICE HEALTH - мониторинг устройства
    // ══════════════════════════════════════════════════════════════════

    /** Температура предупреждения (в десятых градуса, 400 = 40.0°C) */
    const val TEMP_WARNING = 400

    /** Высокая температура (в десятых градуса, 450 = 45.0°C) */
    const val TEMP_HIGH = 450

    /** Критическая температура (в десятых градуса, 500 = 50.0°C) */
    const val TEMP_CRITICAL = 500

    /** Низкий уровень батареи (%) */
    const val BATTERY_LOW = 15

    /** Критический уровень батареи (%) */
    const val BATTERY_CRITICAL = 5

    // ══════════════════════════════════════════════════════════════════
    // АДАПТИВНОЕ СНИЖЕНИЕ КАЧЕСТВА - при перегреве/низком заряде
    // ══════════════════════════════════════════════════════════════════

    /** Снижение качества при критической температуре (0.0-1.0) */
    const val QUALITY_REDUCTION_TEMP_CRITICAL = 0.5f

    /** Снижение качества при высокой температуре (0.0-1.0) */
    const val QUALITY_REDUCTION_TEMP_HIGH = 0.25f

    /** Снижение качества при предупреждении о температуре (0.0-1.0) */
    const val QUALITY_REDUCTION_TEMP_WARNING = 0.1f

    /** Снижение качества при низком заряде без зарядки (0.0-1.0) */
    const val QUALITY_REDUCTION_BATTERY_LOW = 0.25f

    /** Максимальное общее снижение качества (0.0-1.0) */
    const val QUALITY_REDUCTION_MAX = 0.75f

    // ══════════════════════════════════════════════════════════════════
    // LOG REPORTER - отправка логов
    // ══════════════════════════════════════════════════════════════════

    /** URL по умолчанию для отправки логов */
    const val LOG_REPORT_URL = "http://127.0.0.1:8011/device-logs"

    /** Максимум записей в буфере логов */
    const val LOG_MAX_BUFFER_SIZE = 100

    /** Интервал отправки логов (мс) */
    const val LOG_SEND_INTERVAL_MS = 10000L

    /** Максимум логов в одном запросе */
    const val LOG_BATCH_SIZE = 50

    /** Таймаут HTTP соединения для отправки логов (мс) */
    const val LOG_HTTP_CONNECT_TIMEOUT_MS = 5000

    /** Таймаут чтения HTTP для отправки логов (мс) */
    const val LOG_HTTP_READ_TIMEOUT_MS = 5000

    // ══════════════════════════════════════════════════════════════════
    // CAMERA SERVICE - таймауты сервиса
    // ══════════════════════════════════════════════════════════════════

    /** Таймаут Wake Lock (мс) */
    const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L

    /** Интервал обновления Wake Lock - за минуту до истечения (мс) */
    const val WAKELOCK_RENEW_INTERVAL_MS = WAKELOCK_TIMEOUT_MS - 60000L

    /** Задержка перед перезапуском HTTP сервера (мс) */
    const val HTTP_SERVER_RESTART_DELAY_MS = 500L

    /** Задержка перед перезапуском сервиса (мс) */
    const val SERVICE_RESTART_DELAY_MS = 1000L

    /** Задержка повторной попытки старта стрима (мс) */
    const val STREAM_START_RETRY_DELAY_MS = 2500L

    // ══════════════════════════════════════════════════════════════════
    // CAMERA CONTROLLER - таймауты камеры
    // ══════════════════════════════════════════════════════════════════

    /** Таймаут открытия камеры (мс) */
    const val CAMERA_OPEN_TIMEOUT_MS = 3000L

    /** Таймаут создания сессии (мс) */
    const val CAMERA_SESSION_TIMEOUT_MS = 3000L

    /** Таймаут захвата фото (мс) */
    const val PHOTO_CAPTURE_TIMEOUT_MS = 5000L

    /** Таймаут pre-capture для фото (мс) */
    const val PHOTO_PRECAPTURE_TIMEOUT_MS = 1500L

    /** Задержка после pre-capture (мс) */
    const val PHOTO_PRECAPTURE_DELAY_MS = 200L

    /** Задержка перед перезапуском стрима (мс) */
    const val STREAM_RESTART_DELAY_MS = 200L

    // ══════════════════════════════════════════════════════════════════
    // МЕТОДЫ
    // ══════════════════════════════════════════════════════════════════

    /**
     * Устанавливает настройки стрима
     * Значения автоматически ограничиваются допустимыми диапазонами
     */
    fun setStreamConfig(width: Int, height: Int, fps: Int, quality: Int) {
        streamWidth = width.coerceIn(MIN_WIDTH, MAX_WIDTH)
        streamHeight = height.coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        targetFps = fps.coerceIn(MIN_FPS, MAX_FPS)
        jpegQuality = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
        Log.i(TAG, "Конфиг: ${streamWidth}x${streamHeight} @ ${targetFps}fps, JPEG $jpegQuality%")
    }

    /** Возвращает краткую сводку текущей конфигурации */
    fun getConfigSummary(): String = "${streamWidth}x${streamHeight} @ ${targetFps}fps, Q$jpegQuality"
}
