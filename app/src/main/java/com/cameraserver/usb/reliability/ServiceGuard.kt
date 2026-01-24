package com.cameraserver.usb.reliability

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.cameraserver.usb.CameraService
import java.util.concurrent.atomic.AtomicInteger

/**
 * Защита сервиса от остановки (бесконечные попытки восстановления)
 *
 * Обеспечивает работу сервиса 24/7 через:
 * - Обработку crash с автоматическим перезапуском
 * - Периодическую проверку состояния (каждые 30 сек)
 * - Эскалирующие стратегии восстановления
 *
 * ## Стратегии восстановления (от мягкой к агрессивной)
 * 1. [RecoveryStrategy.SIMPLE_RESTART] - простой перезапуск сервиса
 * 2. [RecoveryStrategy.DELAYED_RESTART] - перезапуск с задержкой
 * 3. [RecoveryStrategy.CLEAR_STATE_RESTART] - очистка состояния + перезапуск
 * 4. [RecoveryStrategy.FULL_APP_RESTART] - полный перезапуск приложения
 *
 * ## Особенности Android 14+ (API 34)
 * На Android 14+ сразу используется FULL_APP_RESTART, т.к. FGS с типом camera
 * не может быть запущен из фонового контекста (BroadcastReceiver).
 *
 * @see RecoveryReceiver для обработки recovery
 * @see ServiceCheckReceiver для периодических проверок
 */
object ServiceGuard {

    private const val TAG = "ServiceGuard"
    private const val CHECK_INTERVAL_MS = 30_000L
    private const val ALARM_REQUEST_CODE = 12345

    private val consecutiveFailures = AtomicInteger(0)
    private val totalRecoveryAttempts = AtomicInteger(0)

    /** Интервалы между попытками (exponential backoff) */
    private val RETRY_DELAYS = longArrayOf(
        2_000,    // 2 сек
        5_000,    // 5 сек
        10_000,   // 10 сек
        30_000,   // 30 сек
        60_000,   // 1 мин
        120_000,  // 2 мин
        300_000   // 5 мин (максимум, потом повторяется)
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    /** Инициализирует защиту сервиса. Вызывается в Application.onCreate */
    fun init(context: Context) {
        appContext = context.applicationContext
        setupCrashHandler(context)
        scheduleServiceCheck(context)
        requestBatteryOptimizationExemption(context)

        // Инициализация LogReporter
        LogReporter.init(context)

        LogReporter.info(TAG, "ServiceGuard initialized with infinite retry")
    }

    /** Устанавливает обработчик crash с отправкой логов и планированием recovery */
    private fun setupCrashHandler(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogReporter.critical(TAG, "CRASH in thread ${thread.name}: ${throwable.message}", throwable)

            // Планируем бесконечные попытки восстановления
            scheduleRecoveryChain(context)

            // Вызываем стандартный обработчик
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Запускает цепочку восстановления с эскалирующей стратегией */
    fun scheduleRecoveryChain(context: Context) {
        val attempt = totalRecoveryAttempts.incrementAndGet()
        val failures = consecutiveFailures.incrementAndGet()

        // Android 14+: нужен foreground контекст для FGS camera
        val strategy = if (Build.VERSION.SDK_INT >= 34) {
            RecoveryStrategy.FULL_APP_RESTART
        } else {
            // На более старых версиях используем escalating strategy
            when {
                failures <= 3 -> RecoveryStrategy.SIMPLE_RESTART
                failures <= 6 -> RecoveryStrategy.DELAYED_RESTART
                failures <= 10 -> RecoveryStrategy.CLEAR_STATE_RESTART
                else -> RecoveryStrategy.FULL_APP_RESTART
            }
        }

        val delay = if (strategy == RecoveryStrategy.FULL_APP_RESTART) {
            1_000L
        } else {
            val delayIndex = (failures - 1).coerceIn(0, RETRY_DELAYS.size - 1)
            RETRY_DELAYS[delayIndex]
        }

        LogReporter.warn(TAG, "Recovery attempt #$attempt (consecutive: $failures) " +
                "using ${strategy.name}, delay: ${delay}ms (API ${Build.VERSION.SDK_INT})")

        scheduleRecovery(context, delay, strategy)
    }

    private fun scheduleRecovery(context: Context, delayMs: Long, strategy: RecoveryStrategy) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, RecoveryReceiver::class.java).apply {
            putExtra("strategy", strategy.name)
            putExtra("attempt", totalRecoveryAttempts.get())
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE + 100 + strategy.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = SystemClock.elapsedRealtime() + delayMs

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    /** Выполняет восстановление указанной стратегией */
    fun executeRecovery(context: Context, strategy: RecoveryStrategy, attempt: Int): Boolean {
        LogReporter.info(TAG, "Executing recovery #$attempt: ${strategy.name}")

        return try {
            when (strategy) {
                RecoveryStrategy.SIMPLE_RESTART -> {
                    startService(context)
                }
                RecoveryStrategy.DELAYED_RESTART -> {
                    Thread.sleep(1000)
                    startService(context)
                }
                RecoveryStrategy.CLEAR_STATE_RESTART -> {
                    try {
                        context.stopService(Intent(context, CameraService::class.java))
                        Thread.sleep(2000)
                    } catch (_: Exception) { }
                    startService(context)
                }
                RecoveryStrategy.FULL_APP_RESTART -> {
                    restartApp(context)
                    true
                }
            }
        } catch (e: Exception) {
            LogReporter.error(TAG, "Recovery ${strategy.name} failed", e)
            false
        }
    }

    /** Сбрасывает счётчик неудач при успешной работе */
    fun reportSuccess() {
        if (consecutiveFailures.get() > 0) {
            LogReporter.info(TAG, "Service recovered successfully after ${consecutiveFailures.get()} failures")
            consecutiveFailures.set(0)
        }
    }

    private fun startService(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            LogReporter.warn(TAG, "API 34+: Service start may need Activity context")
        }

        val intent = Intent(context, CameraService::class.java).apply {
            action = CameraService.ACTION_START
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            // Проверяем через 3 секунды
            Thread.sleep(3000)
            val running = isServiceRunning(context)
            if (running) {
                reportSuccess()
                LogReporter.info(TAG, "Service started successfully")
            }
            running
        } catch (e: SecurityException) {
            LogReporter.error(TAG, "SecurityException: Need foreground context for FGS", e)
            restartApp(context)
            true
        } catch (e: Exception) {
            LogReporter.error(TAG, "Failed to start service", e)
            false
        }
    }

    private fun restartApp(context: Context) {
        LogReporter.warn(TAG, "Performing full app restart...")

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )

        // Завершаем процесс
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Планирует периодическую проверку сервиса через AlarmManager */
    fun scheduleServiceCheck(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ServiceCheckReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)

        val triggerTime = SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } else {
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                CHECK_INTERVAL_MS,
                pendingIntent
            )
        }
    }

    fun scheduleRestart(context: Context, delayMs: Long) {
        scheduleRecovery(context, delayMs, RecoveryStrategy.SIMPLE_RESTART)
    }

    fun requestBatteryOptimizationExemption(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = context.packageName

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                LogReporter.warn(TAG, "App is NOT exempt from battery optimization!")
            } else {
                LogReporter.info(TAG, "App is exempt from battery optimization")
            }
        }
    }

    fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (CameraService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    /** Проверяет сервис и запускает recovery если нужно */
    fun ensureServiceRunning(context: Context) {
        if (!isServiceRunning(context)) {
            LogReporter.warn(TAG, "Service not running, attempting recovery... (API ${Build.VERSION.SDK_INT})")
            scheduleRecoveryChain(context)
        } else {
            reportSuccess()
            // Отправляем heartbeat
            LogReporter.sendHeartbeat("running", mapOf(
                "uptime" to SystemClock.elapsedRealtime(),
                "recoveryAttempts" to totalRecoveryAttempts.get()
            ))
        }
    }

    /** Запускает сервис из Activity контекста (для Android 14+) */
    fun startServiceFromActivity(context: Context): Boolean {
        LogReporter.info(TAG, "Starting service from Activity context (API ${Build.VERSION.SDK_INT})")
        return startService(context)
    }

    fun getStats(): Map<String, Any> = mapOf(
        "totalRecoveryAttempts" to totalRecoveryAttempts.get(),
        "consecutiveFailures" to consecutiveFailures.get()
    )
}

enum class RecoveryStrategy {
    SIMPLE_RESTART,
    DELAYED_RESTART,
    CLEAR_STATE_RESTART,
    FULL_APP_RESTART
}

/** Периодическая проверка сервиса (каждые 30 сек) */
class ServiceCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("ServiceCheckReceiver", "Checking service status...")
        ServiceGuard.ensureServiceRunning(context)
        ServiceGuard.scheduleServiceCheck(context)
    }
}

/** Перезапуск сервиса после crash */
class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("ServiceRestartReceiver", "Restarting service after crash...")
        ServiceGuard.ensureServiceRunning(context)
    }
}

/** Выполняет стратегии восстановления */
class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val strategyName = intent.getStringExtra("strategy") ?: "SIMPLE_RESTART"
        val attempt = intent.getIntExtra("attempt", 0)

        val strategy = try {
            RecoveryStrategy.valueOf(strategyName)
        } catch (e: Exception) {
            RecoveryStrategy.SIMPLE_RESTART
        }

        Log.i("RecoveryReceiver", "Executing recovery #$attempt: $strategyName")

        // Выполняем в отдельном потоке чтобы не блокировать BroadcastReceiver
        Thread {
            val success = ServiceGuard.executeRecovery(context, strategy, attempt)

            if (!success) {
                // Если не удалось - планируем следующую попытку
                Log.w("RecoveryReceiver", "Recovery failed, scheduling next attempt...")
                ServiceGuard.scheduleRecoveryChain(context)
            } else {
                LogReporter.info("RecoveryReceiver", "Recovery successful!")
            }
        }.start()
    }
}
