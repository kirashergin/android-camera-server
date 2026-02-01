package com.cameraserver.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.cameraserver.usb.camera.CameraController
import com.cameraserver.usb.config.CameraConfig
import com.cameraserver.usb.reliability.CameraRecoveryManager
import com.cameraserver.usb.reliability.DeviceHealthMonitor
import com.cameraserver.usb.reliability.SystemWatchdog
import com.cameraserver.usb.server.CameraHttpServer

/**
 * Foreground Service для работы камеры
 *
 * Основной компонент приложения, обеспечивающий:
 * - HTTP сервер для управления камерой
 * - Wake Lock для предотвращения засыпания
 * - Мониторинг здоровья системы
 * - Автоматическое восстановление при сбоях
 *
 * Компоненты надёжности:
 * - SystemWatchdog - отслеживает зависания
 * - CameraRecoveryManager - мягкое восстановление камеры
 * - DeviceHealthMonitor - мониторинг температуры и батареи
 */
class CameraService : Service() {

    companion object {
        private const val TAG = "CameraService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "camera_service_channel"

        const val ACTION_START = "com.cameraserver.usb.START"
        const val ACTION_STOP = "com.cameraserver.usb.STOP"
    }

    private var cameraController: CameraController? = null
    private var httpServer: CameraHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var watchdog: SystemWatchdog? = null
    private var recoveryManager: CameraRecoveryManager? = null
    private var healthMonitor: DeviceHealthMonitor? = null

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Сервис создан")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startForegroundService()
        }
        return START_STICKY
    }

    /**
     * Запускает foreground сервис со всеми компонентами
     */
    private fun startForegroundService() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()
        initializeCamera()
        initializeWatchdog()
        startHttpServer()
        initializeReliabilitySystems()

        Log.i(TAG, "Сервис запущен на порту ${CameraConfig.SERVER_PORT}")
    }

    /**
     * Инициализирует контроллер камеры
     */
    private fun initializeCamera() {
        if (cameraController == null) {
            cameraController = CameraController(applicationContext)
            cameraController?.initialize()
        }
    }

    /**
     * Запускает HTTP сервер
     */
    private fun startHttpServer() {
        if (httpServer == null && cameraController != null) {
            httpServer = CameraHttpServer(this, CameraConfig.SERVER_PORT, cameraController!!, watchdog)
            runCatching {
                httpServer?.start()
                Log.i(TAG, "HTTP сервер запущен на порту ${CameraConfig.SERVER_PORT}")
            }.onFailure {
                Log.e(TAG, "Ошибка запуска HTTP сервера", it)
            }
        }
    }

    /**
     * Инициализирует watchdog
     */
    private fun initializeWatchdog() {
        if (watchdog != null) return
        watchdog = SystemWatchdog(applicationContext)
    }

    /**
     * Инициализирует системы надёжности
     */
    private fun initializeReliabilitySystems() {
        val camera = cameraController ?: return

        recoveryManager = CameraRecoveryManager(camera).apply {
            setCallbacks(
                onStarted = { Log.i(TAG, "Восстановление запущено") },
                onSuccess = {
                    Log.i(TAG, "Восстановление успешно")
                    watchdog?.resetTimestamps()
                },
                onFailed = {
                    Log.e(TAG, "Восстановление не удалось, перезапуск сервиса")
                    restartService()
                }
            )
        }

        healthMonitor = DeviceHealthMonitor(applicationContext).apply {
            startMonitoring(
                onTemperatureWarning = { temp, level ->
                    Log.w(TAG, "Температура: ${temp / 10.0}°C ($level)")
                    if (level == DeviceHealthMonitor.TemperatureLevel.CRITICAL) {
                        Log.e(TAG, "Критическая температура!")
                    }
                },
                onBatteryLow = { level ->
                    Log.w(TAG, "Низкий заряд: $level%")
                }
            )
        }

        watchdog?.start(
            onStreamStuck = {
                Log.w(TAG, "Watchdog: Стрим завис, восстановление")
                recoveryManager?.recoverStream()
            },
            onServerStuck = {
                Log.w(TAG, "Watchdog: Сервер завис, перезапуск")
                restartHttpServer()
            },
            onCriticalError = {
                Log.e(TAG, "Watchdog: Критическая ошибка, полный перезапуск")
                restartService()
            },
            onMemoryWarning = { usage ->
                Log.w(TAG, "Память: ${(usage * 100).toInt()}%")
            }
        )

        Log.i(TAG, "Системы надёжности инициализированы")
    }

    /**
     * Перезапускает HTTP сервер
     */
    private fun restartHttpServer() {
        Thread {
            runCatching {
                val oldServer = httpServer
                httpServer = null
                oldServer?.stop()
                Thread.sleep(CameraConfig.HTTP_SERVER_RESTART_DELAY_MS)
                mainHandler.post {
                    if (httpServer == null) {
                        startHttpServer()
                    }
                    watchdog?.resetTimestamps()
                }
            }.onFailure {
                Log.e(TAG, "Ошибка перезапуска HTTP сервера", it)
            }
        }.start()
    }

    /**
     * Перезапускает весь сервис
     */
    private fun restartService() {
        Log.w(TAG, "Перезапуск сервиса...")

        Thread {
            runCatching {
                stopReliabilitySystems()
                httpServer?.stop()
                httpServer = null
                cameraController?.release()
                cameraController = null

                Thread.sleep(CameraConfig.SERVICE_RESTART_DELAY_MS)

                mainHandler.post {
                    val restartIntent = Intent(applicationContext, CameraService::class.java).apply {
                        action = ACTION_START
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(restartIntent)
                    } else {
                        startService(restartIntent)
                    }
                }
            }.onFailure {
                Log.e(TAG, "Ошибка перезапуска сервиса", it)
            }
        }.start()
    }

    /**
     * Останавливает системы надёжности
     */
    private fun stopReliabilitySystems() {
        watchdog?.stop()
        watchdog = null

        recoveryManager?.shutdown()
        recoveryManager = null

        healthMonitor?.stopMonitoring()
        healthMonitor = null
    }

    // ══════════════════════════════════════════════════════════════════
    // WAKE LOCK
    // ══════════════════════════════════════════════════════════════════

    private val wakeLockRenewRunnable = object : Runnable {
        override fun run() {
            renewWakeLock()
            mainHandler.postDelayed(this, CameraConfig.WAKELOCK_RENEW_INTERVAL_MS)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CameraServer::WakeLock"
            )
        }

        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(CameraConfig.WAKELOCK_TIMEOUT_MS)
                Log.i(TAG, "Wake lock получен")
            }
        }

        mainHandler.removeCallbacks(wakeLockRenewRunnable)
        mainHandler.postDelayed(wakeLockRenewRunnable, CameraConfig.WAKELOCK_RENEW_INTERVAL_MS)
    }

    private fun renewWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
            it.acquire(CameraConfig.WAKELOCK_TIMEOUT_MS)
            Log.d(TAG, "Wake lock обновлён")
        }
    }

    private fun releaseWakeLock() {
        mainHandler.removeCallbacks(wakeLockRenewRunnable)
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Wake lock освобождён")
            }
        }
        wakeLock = null
    }

    // ══════════════════════════════════════════════════════════════════
    // УВЕДОМЛЕНИЯ
    // ══════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Camera Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Сервер камеры работает"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, CameraService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setContentTitle("Camera Server")
            setContentText(CameraConfig.getConfigSummary())
            setSmallIcon(android.R.drawable.ic_menu_camera)
            setContentIntent(openPendingIntent)
            setOngoing(true)
            addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
        }.build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.i(TAG, "Сервис завершается")

        stopReliabilitySystems()

        httpServer?.stop()
        httpServer = null

        cameraController?.release()
        cameraController = null

        releaseWakeLock()

        super.onDestroy()
        Log.i(TAG, "Сервис завершён")
    }

    // ══════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ══════════════════════════════════════════════════════════════════

    fun getCameraController(): CameraController? = cameraController
    fun isServerRunning(): Boolean = httpServer?.isAlive == true
    fun getServerPort(): Int = CameraConfig.SERVER_PORT
    fun getHealthStatus() = healthMonitor?.getHealthStatus()
    fun getWatchdogStats() = watchdog?.getStats()
}
