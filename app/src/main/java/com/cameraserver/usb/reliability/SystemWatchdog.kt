package com.cameraserver.usb.reliability

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Watchdog для мониторинга здоровья системы
 *
 * Отслеживает и реагирует на:
 * - Зависание стрима (нет кадров >10 сек) → [onStreamStuck]
 * - Зависание сервера (нет ответов >2 мин) → [onServerStuck]
 * - Критическое использование памяти (>85%) → [onMemoryWarning]
 *
 * При 3+ последовательных ошибках вызывает [onCriticalError]
 * для полного перезапуска сервиса.
 *
 * @property context Android Context для системных сервисов
 */
class SystemWatchdog(private val context: Context) {
    
    companion object {
        private const val TAG = "Watchdog"

        private const val CHECK_INTERVAL_MS = 5000L
        private const val STREAM_TIMEOUT_MS = 10000L
        private const val SERVER_TIMEOUT_MS = 30000L

        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val MEMORY_WARNING_THRESHOLD = 0.85
        private const val MEMORY_CRITICAL_THRESHOLD = 0.95
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)

    private val lastFrameTime = AtomicLong(0)
    private val lastServerResponseTime = AtomicLong(0)
    private val consecutiveFailures = AtomicInteger(0)

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
                Log.e(TAG, "Health check error", e)
            }
            
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }
    
    /** Запускает watchdog с указанными callbacks */
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
        Log.i(TAG, "Watchdog started")
    }
    
    /** Останавливает watchdog */
    fun stop() {
        isRunning.set(false)
        handler.removeCallbacks(checkRunnable)
        Log.i(TAG, "Watchdog stopped")
    }
    
    private val isStreamActive = AtomicBoolean(false)

    /** Вызывается при получении нового кадра */
    fun reportFrameReceived() {
        lastFrameTime.set(SystemClock.elapsedRealtime())
        isStreamActive.set(true)
        if (consecutiveFailures.get() > 0) {
            consecutiveFailures.decrementAndGet()
        }
    }

    /** Вызывается при остановке стрима */
    fun reportStreamStopped() {
        isStreamActive.set(false)
    }

    /** Вызывается при успешном HTTP запросе */
    fun reportServerResponse() {
        lastServerResponseTime.set(SystemClock.elapsedRealtime())
    }

    /** Сбрасывает временные метки (при старте/рестарте) */
    fun resetTimestamps() {
        val now = SystemClock.elapsedRealtime()
        lastFrameTime.set(now)
        lastServerResponseTime.set(now)
        consecutiveFailures.set(0)
    }
    
    private fun performHealthCheck() {
        val now = SystemClock.elapsedRealtime()

        checkMemory()

        if (isStreamActive.get()) {
            val frameAge = now - lastFrameTime.get()
            if (frameAge > STREAM_TIMEOUT_MS) {
                Log.w(TAG, "Stream stuck (no frames for ${frameAge}ms)")
                handleFailure("stream_stuck") { onStreamStuck?.invoke() }
            }
        }

        // Проверка сервера (2 мин) - нормально если нет клиентов
        val serverAge = now - lastServerResponseTime.get()
        if (serverAge > SERVER_TIMEOUT_MS * 4) {
            Log.w(TAG, "Server stuck (no response for ${serverAge}ms)")
            handleFailure("server_stuck") { onServerStuck?.invoke() }
        }
    }
    
    private fun checkMemory() {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryUsage = usedMemory.toFloat() / maxMemory
        
        when {
            memoryUsage > MEMORY_CRITICAL_THRESHOLD -> {
                Log.e(TAG, "CRITICAL: Memory usage at ${(memoryUsage * 100).toInt()}%")
                // Принудительный GC
                System.gc()
                onMemoryWarning?.invoke(memoryUsage)
            }
            memoryUsage > MEMORY_WARNING_THRESHOLD -> {
                Log.w(TAG, "WARNING: Memory usage at ${(memoryUsage * 100).toInt()}%")
                onMemoryWarning?.invoke(memoryUsage)
            }
        }
    }
    
    private fun handleFailure(type: String, recoveryAction: () -> Unit) {
        val failures = consecutiveFailures.incrementAndGet()
        Log.w(TAG, "Failure detected: $type (consecutive: $failures)")
        
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            Log.e(TAG, "Too many consecutive failures, triggering critical error handler")
            consecutiveFailures.set(0)
            totalRestarts++
            lastRestartTime = System.currentTimeMillis()
            onCriticalError?.invoke()
        } else {
            // Пытаемся мягко восстановить
            recoveryAction()
        }
    }
    
    /** Возвращает статистику для отладки */
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

data class WatchdogStats(
    val isRunning: Boolean,
    val lastFrameAgeMs: Long,
    val lastServerResponseAgeMs: Long,
    val consecutiveFailures: Int,
    val totalRestarts: Int,
    val lastRestartTime: Long
)
