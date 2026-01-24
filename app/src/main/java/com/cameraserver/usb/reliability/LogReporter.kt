package com.cameraserver.usb.reliability

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Отправка логов и алертов на ПК для мониторинга
 *
 * Логи буферизуются и отправляются пачками каждые 10 секунд.
 * При недоступности сервера логи сохраняются в буфере (до 100 записей).
 *
 * ## Уровни логов
 * - [info] - информационные сообщения
 * - [warn] - предупреждения
 * - [error] - ошибки
 * - [critical] - критические ошибки (отправляются немедленно)
 *
 * ## Настройка
 * По умолчанию отправляет на http://127.0.0.1:8011/device-logs
 * Изменить через [setReportUrl]
 */
object LogReporter {

    private const val TAG = "LogReporter"

    @Volatile
    var reportUrl: String = "http://127.0.0.1:8011/device-logs"
        private set

    private val logBuffer = ConcurrentLinkedQueue<LogEntry>()
    private const val MAX_BUFFER_SIZE = 100
    private const val SEND_INTERVAL_MS = 10_000L

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val isRunning = AtomicBoolean(false)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var deviceId: String = "unknown"
    private var deviceModel: String = "unknown"

    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
        val stackTrace: String? = null
    )

    /** Инициализирует репортер. Вызывается в Application.onCreate */
    fun init(context: Context, pcUrl: String? = null) {
        deviceId = Build.SERIAL ?: Build.ID ?: "unknown"
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"

        pcUrl?.let { reportUrl = it }

        if (isRunning.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(
                { sendBufferedLogs() },
                SEND_INTERVAL_MS,
                SEND_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
            Log.i(TAG, "LogReporter initialized. URL: $reportUrl")
        }
    }

    /** Устанавливает URL для отправки логов */
    fun setReportUrl(url: String) {
        reportUrl = url
        Log.i(TAG, "Report URL changed to: $url")
    }

    fun info(tag: String, message: String) {
        Log.i(tag, message)
        addToBuffer("INFO", tag, message)
    }

    fun warn(tag: String, message: String) {
        Log.w(tag, message)
        addToBuffer("WARN", tag, message)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        addToBuffer("ERROR", tag, message, throwable?.stackTraceToString())
    }

    /** Критическая ошибка - отправляется немедленно */
    fun critical(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, "CRITICAL: $message", throwable)
        addToBuffer("CRITICAL", tag, message, throwable?.stackTraceToString())
        executor.submit { sendBufferedLogs() }
    }

    /** Отправляет алерт о попытке восстановления */
    fun reportRecoveryAttempt(attempt: Int, strategy: String, success: Boolean) {
        val status = if (success) "SUCCESS" else "FAILED"
        val message = "Recovery attempt #$attempt using $strategy: $status"

        if (success) {
            info(TAG, message)
        } else {
            warn(TAG, message)
        }
    }

    /** Отправляет heartbeat для мониторинга */
    fun sendHeartbeat(status: String, details: Map<String, Any> = emptyMap()) {
        executor.submit {
            try {
                val json = JSONObject().apply {
                    put("type", "heartbeat")
                    put("deviceId", deviceId)
                    put("deviceModel", deviceModel)
                    put("timestamp", System.currentTimeMillis())
                    put("status", status)
                    put("details", JSONObject(details))
                }
                sendToPC(json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send heartbeat", e)
            }
        }
    }

    private fun addToBuffer(level: String, tag: String, message: String, stackTrace: String? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            stackTrace = stackTrace
        )

        logBuffer.add(entry)

        while (logBuffer.size > MAX_BUFFER_SIZE) {
            logBuffer.poll()
        }
    }

    private fun sendBufferedLogs() {
        if (logBuffer.isEmpty()) return

        val logsToSend = mutableListOf<LogEntry>()
        while (logBuffer.isNotEmpty() && logsToSend.size < 50) {
            logBuffer.poll()?.let { logsToSend.add(it) }
        }

        if (logsToSend.isEmpty()) return

        try {
            val json = JSONObject().apply {
                put("type", "logs")
                put("deviceId", deviceId)
                put("deviceModel", deviceModel)
                put("logs", JSONArray().apply {
                    logsToSend.forEach { entry ->
                        put(JSONObject().apply {
                            put("timestamp", dateFormat.format(Date(entry.timestamp)))
                            put("level", entry.level)
                            put("tag", entry.tag)
                            put("message", entry.message)
                            entry.stackTrace?.let { put("stackTrace", it) }
                        })
                    }
                })
            }

            val success = sendToPC(json.toString())
            if (!success) {
                logsToSend.forEach { logBuffer.add(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send logs", e)
            logsToSend.forEach { logBuffer.add(it) }
        }
    }

    private fun sendToPC(jsonData: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(reportUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonData)
                writer.flush()
            }

            val responseCode = connection.responseCode
            responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
