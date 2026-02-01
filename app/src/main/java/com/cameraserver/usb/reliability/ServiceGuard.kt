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
import com.cameraserver.usb.config.CameraConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * Защита сервиса от остановки (бесконечные попытки восстановления)
 *
 * Обеспечивает работу сервиса 24/7 через:
 * - Обработку crash с автоматическим перезапуском
 * - Периодическую проверку состояния
 * - Эскалирующие стратегии восстановления
 *
 * Стратегии восстановления (от мягкой к агрессивной):
 * - SIMPLE_RESTART - простой перезапуск сервиса
 * - DELAYED_RESTART - перезапуск с задержкой
 * - CLEAR_STATE_RESTART - очистка состояния + перезапуск
 * - FULL_APP_RESTART - полный перезапуск приложения
 *
 * На Android 14+ сразу используется FULL_APP_RESTART, т.к. FGS с типом camera
 * не может быть запущен из фонового контекста.
 */
object ServiceGuard {

    private const val TAG = "ServiceGuard"
    private const val ALARM_REQUEST_CODE = 12345

    private val consecutiveFailures = AtomicInteger(0)
    private val totalRecoveryAttempts = AtomicInteger(0)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    /**
     * Инициализирует защиту сервиса
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        setupCrashHandler(context)
        scheduleServiceCheck(context)
        requestBatteryOptimizationExemption(context)

        LogReporter.init(context)
        LogReporter.info(TAG, "ServiceGuard инициализирован (интервал проверки: ${CameraConfig.SERVICE_CHECK_INTERVAL_MS}мс)")
    }

    /**
     * Устанавливает обработчик crash с отправкой логов и планированием recovery
     */
    private fun setupCrashHandler(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogReporter.critical(TAG, "CRASH в потоке ${thread.name}: ${throwable.message}", throwable)
            scheduleRecoveryChain(context)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Запускает цепочку восстановления с эскалирующей стратегией
     */
    fun scheduleRecoveryChain(context: Context) {
        val attempt = totalRecoveryAttempts.incrementAndGet()
        val failures = consecutiveFailures.incrementAndGet()

        val strategy = if (Build.VERSION.SDK_INT >= 34) {
            RecoveryStrategy.FULL_APP_RESTART
        } else {
            when {
                failures <= CameraConfig.RECOVERY_SIMPLE_RESTART_THRESHOLD -> RecoveryStrategy.SIMPLE_RESTART
                failures <= CameraConfig.RECOVERY_DELAYED_RESTART_THRESHOLD -> RecoveryStrategy.DELAYED_RESTART
                failures <= CameraConfig.RECOVERY_CLEAR_STATE_THRESHOLD -> RecoveryStrategy.CLEAR_STATE_RESTART
                else -> RecoveryStrategy.FULL_APP_RESTART
            }
        }

        val delay = if (strategy == RecoveryStrategy.FULL_APP_RESTART) {
            CameraConfig.RECOVERY_FULL_RESTART_DELAY_MS
        } else {
            val delayIndex = (failures - 1).coerceIn(0, CameraConfig.RECOVERY_RETRY_DELAYS.lastIndex)
            CameraConfig.RECOVERY_RETRY_DELAYS[delayIndex]
        }

        LogReporter.warn(TAG, "Восстановление #$attempt (подряд: $failures) " +
                "стратегия: ${strategy.name}, задержка: ${delay}мс (API ${Build.VERSION.SDK_INT})")

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

    /**
     * Выполняет восстановление указанной стратегией
     */
    fun executeRecovery(context: Context, strategy: RecoveryStrategy, attempt: Int): Boolean {
        LogReporter.info(TAG, "Выполнение восстановления #$attempt: ${strategy.name}")

        return try {
            when (strategy) {
                RecoveryStrategy.SIMPLE_RESTART -> {
                    startService(context)
                }
                RecoveryStrategy.DELAYED_RESTART -> {
                    Thread.sleep(CameraConfig.SERVICE_RESTART_DELAY_MS)
                    startService(context)
                }
                RecoveryStrategy.CLEAR_STATE_RESTART -> {
                    runCatching {
                        context.stopService(Intent(context, CameraService::class.java))
                        Thread.sleep(CameraConfig.SERVICE_RESTART_DELAY_MS * CameraConfig.RECOVERY_CLEAR_STATE_DELAY_MULTIPLIER)
                    }
                    startService(context)
                }
                RecoveryStrategy.FULL_APP_RESTART -> {
                    restartApp(context)
                    true
                }
            }
        } catch (e: Exception) {
            LogReporter.error(TAG, "Восстановление ${strategy.name} не удалось", e)
            false
        }
    }

    /**
     * Сбрасывает счётчик неудач при успешной работе
     */
    fun reportSuccess() {
        if (consecutiveFailures.get() > 0) {
            LogReporter.info(TAG, "Сервис восстановлен после ${consecutiveFailures.get()} ошибок")
            consecutiveFailures.set(0)
        }
    }

    private fun startService(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            LogReporter.warn(TAG, "API 34+: Запуск сервиса может потребовать Activity контекст")
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
            Thread.sleep(CameraConfig.SERVICE_START_VERIFICATION_DELAY_MS)
            val running = isServiceRunning(context)
            if (running) {
                reportSuccess()
                LogReporter.info(TAG, "Сервис успешно запущен")
            }
            running
        } catch (e: SecurityException) {
            LogReporter.error(TAG, "SecurityException: Нужен foreground контекст для FGS", e)
            restartApp(context)
            true
        } catch (e: Exception) {
            LogReporter.error(TAG, "Ошибка запуска сервиса", e)
            false
        }
    }

    private fun restartApp(context: Context) {
        LogReporter.warn(TAG, "Полный перезапуск приложения...")

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + CameraConfig.SERVICE_RESTART_DELAY_MS,
            pendingIntent
        )

        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * Планирует периодическую проверку сервиса через AlarmManager
     */
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

        val triggerTime = SystemClock.elapsedRealtime() + CameraConfig.SERVICE_CHECK_INTERVAL_MS

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
                CameraConfig.SERVICE_CHECK_INTERVAL_MS,
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
                LogReporter.warn(TAG, "Приложение НЕ исключено из оптимизации батареи!")
            } else {
                LogReporter.info(TAG, "Приложение исключено из оптимизации батареи")
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

    /**
     * Проверяет сервис и запускает recovery если нужно
     */
    fun ensureServiceRunning(context: Context) {
        if (!isServiceRunning(context)) {
            LogReporter.warn(TAG, "Сервис не запущен, запуск восстановления... (API ${Build.VERSION.SDK_INT})")
            scheduleRecoveryChain(context)
        } else {
            reportSuccess()
            LogReporter.sendHeartbeat("running", mapOf(
                "uptime" to SystemClock.elapsedRealtime(),
                "recoveryAttempts" to totalRecoveryAttempts.get()
            ))
        }
    }

    /**
     * Запускает сервис из Activity контекста (для Android 14+)
     */
    fun startServiceFromActivity(context: Context): Boolean {
        LogReporter.info(TAG, "Запуск сервиса из Activity контекста (API ${Build.VERSION.SDK_INT})")
        return startService(context)
    }

    fun getStats(): Map<String, Any> = mapOf(
        "totalRecoveryAttempts" to totalRecoveryAttempts.get(),
        "consecutiveFailures" to consecutiveFailures.get()
    )
}

/**
 * Стратегии восстановления сервиса
 */
enum class RecoveryStrategy {
    SIMPLE_RESTART,
    DELAYED_RESTART,
    CLEAR_STATE_RESTART,
    FULL_APP_RESTART
}

/**
 * Периодическая проверка состояния сервиса
 */
class ServiceCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("ServiceCheckReceiver", "Проверка состояния сервиса...")
        ServiceGuard.ensureServiceRunning(context)
        ServiceGuard.scheduleServiceCheck(context)
    }
}

/**
 * Перезапуск сервиса после crash
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("ServiceRestartReceiver", "Перезапуск сервиса после crash...")
        ServiceGuard.ensureServiceRunning(context)
    }
}

/**
 * Выполняет стратегии восстановления
 */
class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val strategyName = intent.getStringExtra("strategy") ?: "SIMPLE_RESTART"
        val attempt = intent.getIntExtra("attempt", 0)

        val strategy = runCatching { RecoveryStrategy.valueOf(strategyName) }
            .getOrDefault(RecoveryStrategy.SIMPLE_RESTART)

        Log.i("RecoveryReceiver", "Выполнение восстановления #$attempt: $strategyName")

        Thread {
            val success = ServiceGuard.executeRecovery(context, strategy, attempt)

            if (!success) {
                Log.w("RecoveryReceiver", "Восстановление не удалось, планирование следующей попытки...")
                ServiceGuard.scheduleRecoveryChain(context)
            } else {
                LogReporter.info("RecoveryReceiver", "Восстановление успешно!")
            }
        }.start()
    }
}
