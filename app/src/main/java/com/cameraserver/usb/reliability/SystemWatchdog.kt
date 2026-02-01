package com.cameraserver.usb.reliability

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.cameraserver.usb.config.CameraConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Watchdog для мониторинга здоровья системы
 *
 * Отслеживает и реагирует на:
 * - Зависание стрима (нет кадров) → onStreamStuck
 * - Зависание сервера (нет ответов) → onServerStuck
 * - Критическое использование памяти → onMemoryWarning
 *
 * При MAX_CONSECUTIVE_FAILURES последовательных ошибках вызывает onCriticalError
 * для полного перезапуска сервиса.
 */
class SystemWatchdog(private val context: Context) {

    companion object {
        private const val TAG = "Watchdog"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)

    private val lastFrameTime = AtomicLong(0)
    private val lastServerResponseTime = AtomicLong(0)
    private val consecutiveFailures = AtomicInteger(0)
    private val isStreamActive = AtomicBoolean(false)

    private var onStreamStuck: (() -> Unit)? = null
    private var onServerStuck: (() -> Unit)? = null
    private var onCriticalError: (() -> Unit)? = null
    private var onMemoryWarning: ((Float) -> Unit)? = null

    private var totalRestarts = 0
    private var lastRestartTime = 0L

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return

            try {
                performHealthCheck()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка проверки здоровья", e)
            }

            handler.postDelayed(this, CameraConfig.WATCHDOG_CHECK_INTERVAL_MS)
        }
    }

    /**
     * Запускает watchdog с указанными callbacks
     */
    fun start(
        onStreamStuck: () -> Unit,
        onServerStuck: () -> Unit,
        onCriticalError: () -> Unit,
        onMemoryWarning: (Float) -> Unit = {}
    ) {
        this.onStreamStuck = onStreamStuck
        this.onServerStuck = onServerStuck
        this.onCriticalError = onCriticalError
        this.onMemoryWarning = onMemoryWarning

        isRunning.set(true)
        resetTimestamps()
        handler.post(checkRunnable)
        Log.i(TAG, "Watchdog запущен")
    }

    /**
     * Останавливает watchdog
     */
    fun stop() {
        isRunning.set(false)
        handler.removeCallbacks(checkRunnable)
        Log.i(TAG, "Watchdog остановлен")
    }

    /**
     * Вызывается при получении нового кадра
     */
    fun reportFrameReceived() {
        lastFrameTime.set(SystemClock.elapsedRealtime())
        isStreamActive.set(true)
        if (consecutiveFailures.get() > 0) {
            consecutiveFailures.decrementAndGet()
        }
    }

    /**
     * Вызывается при остановке стрима
     */
    fun reportStreamStopped() {
        isStreamActive.set(false)
    }

    /**
     * Вызывается при успешном HTTP запросе
     */
    fun reportServerResponse() {
        lastServerResponseTime.set(SystemClock.elapsedRealtime())
    }

    /**
     * Сбрасывает временные метки (при старте/рестарте)
     */
    fun resetTimestamps() {
        val now = SystemClock.elapsedRealtime()
        lastFrameTime.set(now)
        lastServerResponseTime.set(now)
        consecutiveFailures.set(0)
    }

    private fun performHealthCheck() {
        val now = SystemClock.elapsedRealtime()

        checkMemory()

        // Проверка стрима (динамический таймаут на основе FPS)
        if (isStreamActive.get()) {
            val frameAge = now - lastFrameTime.get()
            val timeout = CameraConfig.watchdogStreamTimeoutMs
            if (frameAge > timeout) {
                Log.w(TAG, "Стрим завис (нет кадров ${frameAge}мс, таймаут: ${timeout}мс @ ${CameraConfig.targetFps}fps)")
                handleFailure("stream_stuck") { onStreamStuck?.invoke() }
            }
        }

        // Проверка сервера
        val serverAge = now - lastServerResponseTime.get()
        if (serverAge > CameraConfig.WATCHDOG_SERVER_TIMEOUT_MS * CameraConfig.WATCHDOG_SERVER_TIMEOUT_MULTIPLIER) {
            Log.w(TAG, "Сервер завис (нет ответов ${serverAge}мс)")
            handleFailure("server_stuck") { onServerStuck?.invoke() }
        }
    }

    private fun checkMemory() {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryUsage = usedMemory.toFloat() / maxMemory

        when {
            memoryUsage > CameraConfig.WATCHDOG_MEMORY_CRITICAL_THRESHOLD -> {
                Log.e(TAG, "КРИТИЧНО: Память ${(memoryUsage * 100).toInt()}%")
                System.gc()
                onMemoryWarning?.invoke(memoryUsage)
            }
            memoryUsage > CameraConfig.WATCHDOG_MEMORY_WARNING_THRESHOLD -> {
                Log.w(TAG, "ВНИМАНИЕ: Память ${(memoryUsage * 100).toInt()}%")
                onMemoryWarning?.invoke(memoryUsage)
            }
        }
    }

    private fun handleFailure(type: String, recoveryAction: () -> Unit) {
        val failures = consecutiveFailures.incrementAndGet()
        Log.w(TAG, "Обнаружена ошибка: $type (подряд: $failures)")

        if (failures >= CameraConfig.WATCHDOG_MAX_CONSECUTIVE_FAILURES) {
            Log.e(TAG, "Слишком много ошибок подряд, запуск критического обработчика")
            consecutiveFailures.set(0)
            totalRestarts++
            lastRestartTime = System.currentTimeMillis()
            onCriticalError?.invoke()
        } else {
            recoveryAction()
        }
    }

    /**
     * Возвращает статистику для отладки
     */
    fun getStats(): WatchdogStats {
        val now = SystemClock.elapsedRealtime()
        return WatchdogStats(
            isRunning = isRunning.get(),
            lastFrameAgeMs = now - lastFrameTime.get(),
            lastServerResponseAgeMs = now - lastServerResponseTime.get(),
            consecutiveFailures = consecutiveFailures.get(),
            totalRestarts = totalRestarts,
            lastRestartTime = lastRestartTime
        )
    }
}

/**
 * Статистика watchdog
 */
data class WatchdogStats(
    val isRunning: Boolean,
    val lastFrameAgeMs: Long,
    val lastServerResponseAgeMs: Long,
    val consecutiveFailures: Int,
    val totalRestarts: Int,
    val lastRestartTime: Long
)
