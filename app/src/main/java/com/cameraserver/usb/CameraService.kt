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
 * Foreground Service для работы камеры в режиме фотобудки
 *
 * Это основной компонент приложения, обеспечивающий:
 * - Работу HTTP сервера для управления камерой
 * - Удержание Wake Lock для предотвращения засыпания
 * - Мониторинг здоровья системы (температура, память)
 * - Автоматическое восстановление при сбоях
 *
 * ## Компоненты надёжности
 * - [SystemWatchdog] - отслеживает зависания стрима и сервера
 * - [CameraRecoveryManager] - мягкое восстановление камеры
 * - [DeviceHealthMonitor] - мониторинг температуры и батареи
 *
 * ## Жизненный цикл
 * Сервис запускается через [ACTION_START] и работает до явной остановки
 * через [ACTION_STOP] или убийства системой (в этом случае перезапустится).
 *
 * @see ServiceGuard для защиты от остановки
 */
class CameraService : Service() {

    companion object {
        private const val TAG = "CameraService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "camera_service_channel"
        val SERVER_PORT = CameraConfig.SERVER_PORT
        
        const val ACTION_START = "com.cameraserver.usb.START"
        const val ACTION_STOP = "com.cameraserver.usb.STOP"
    }

    private var cameraController: CameraController? = null
    private var httpServer: CameraHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Компоненты надёжности
    private var watchdog: SystemWatchdog? = null
    private var recoveryManager: CameraRecoveryManager? = null
    private var healthMonitor: DeviceHealthMonitor? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundService()
            }
        }
        return START_STICKY // Автоперезапуск при убийстве системой
    }

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

        Log.i(TAG, "Foreground service started on port $SERVER_PORT")
    }

    private fun initializeCamera() {
        if (cameraController == null) {
            cameraController = CameraController(applicationContext)
            cameraController?.initialize()
        }
    }

    private fun startHttpServer() {
        if (httpServer == null && cameraController != null) {
            httpServer = CameraHttpServer(this, SERVER_PORT, cameraController!!, watchdog)
            try {
                httpServer?.start()
                Log.i(TAG, "HTTP server started on port $SERVER_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HTTP server", e)
            }
        }
    }
    
    private fun initializeWatchdog() {
        if (watchdog != null) return

        watchdog = SystemWatchdog(applicationContext)
        // Запуск watchdog отложим до initializeReliabilitySystems
    }

    private fun initializeReliabilitySystems() {
        val camera = cameraController ?: return

        recoveryManager = CameraRecoveryManager(camera).apply {
            setCallbacks(
                onStarted = { Log.i(TAG, "Recovery started") },
                onSuccess = {
                    Log.i(TAG, "Recovery successful")
                    watchdog?.resetTimestamps()
                },
                onFailed = {
                    Log.e(TAG, "Recovery failed, will restart service")
                    restartService()
                }
            )
        }

        healthMonitor = DeviceHealthMonitor(applicationContext).apply {
            startMonitoring(
                onTemperatureWarning = { temp, level ->
                    Log.w(TAG, "Temperature: ${temp/10.0}°C ($level)")
                    if (level == DeviceHealthMonitor.TemperatureLevel.CRITICAL) {
                        Log.e(TAG, "Critical temperature!")
                    }
                },
                onBatteryLow = { level ->
                    Log.w(TAG, "Battery low: $level%")
                }
            )
        }

        watchdog?.start(
            onStreamStuck = {
                Log.w(TAG, "Watchdog: Stream stuck, attempting recovery")
                recoveryManager?.recoverStream()
            },
            onServerStuck = {
                Log.w(TAG, "Watchdog: Server stuck, restarting")
                restartHttpServer()
            },
            onCriticalError = {
                Log.e(TAG, "Watchdog: Critical error, full restart")
                restartService()
            },
            onMemoryWarning = { usage ->
                Log.w(TAG, "Memory usage: ${(usage * 100).toInt()}%")
            }
        )

        Log.i(TAG, "Reliability systems initialized")
    }
    
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun restartHttpServer() {
        Thread {
            try {
                val oldServer = httpServer
                httpServer = null
                oldServer?.stop()
                Thread.sleep(500)
                mainHandler.post {
                    if (httpServer == null) {
                        startHttpServer()
                    }
                    watchdog?.resetTimestamps()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart HTTP server", e)
            }
        }.start()
    }
    
    private fun restartService() {
        Log.w(TAG, "Restarting service...")

        Thread {
            try {
                stopReliabilitySystems()
                httpServer?.stop()
                httpServer = null
                cameraController?.release()
                cameraController = null

                Thread.sleep(1000)

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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service", e)
            }
        }.start()
    }
    
    private fun stopReliabilitySystems() {
        watchdog?.stop()
        watchdog = null
        
        recoveryManager?.shutdown()
        recoveryManager = null
        
        healthMonitor?.stopMonitoring()
        healthMonitor = null
    }

    private val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L // 10 минут
    
    private val wakeLockRenewRunnable = object : Runnable {
        override fun run() {
            renewWakeLock()
            mainHandler.postDelayed(this, WAKELOCK_TIMEOUT_MS - 60000) // Обновляем за минуту до истечения
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
                it.acquire(WAKELOCK_TIMEOUT_MS)
                Log.i(TAG, "Wake lock acquired with timeout")
            }
        }
        
        // Запускаем периодическое обновление
        mainHandler.removeCallbacks(wakeLockRenewRunnable)
        mainHandler.postDelayed(wakeLockRenewRunnable, WAKELOCK_TIMEOUT_MS - 60000)
    }
    
    private fun renewWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
            it.acquire(WAKELOCK_TIMEOUT_MS)
            Log.d(TAG, "Wake lock renewed")
        }
    }

    private fun releaseWakeLock() {
        mainHandler.removeCallbacks(wakeLockRenewRunnable)
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Camera Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Camera server is running"
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
            setContentText("${CameraConfig.CURRENT_QUALITY.name}: ${CameraConfig.current.resolution} @ ${CameraConfig.targetFps}fps")
            setSmallIcon(android.R.drawable.ic_menu_camera)
            setContentIntent(openPendingIntent)
            setOngoing(true)
            addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
        }.build()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        
        stopReliabilitySystems()
        
        httpServer?.stop()
        httpServer = null
        
        cameraController?.release()
        cameraController = null
        
        releaseWakeLock()
        
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    // ═══════════════════════════════════════════════════════════
    // PUBLIC API (для MainActivity и других компонентов)
    // ═══════════════════════════════════════════════════════════

    /** Возвращает контроллер камеры для прямого доступа */
    fun getCameraController(): CameraController? = cameraController

    /** Проверяет, запущен ли HTTP сервер */
    fun isServerRunning(): Boolean = httpServer?.isAlive == true

    /** Возвращает порт сервера */
    fun getServerPort(): Int = SERVER_PORT

    /** Возвращает статус здоровья устройства */
    fun getHealthStatus() = healthMonitor?.getHealthStatus()

    /** Возвращает статистику watchdog */
    fun getWatchdogStats() = watchdog?.getStats()
}
