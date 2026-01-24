package com.cameraserver.usb.reliability

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Мониторинг состояния устройства
 *
 * Отслеживает температуру батареи, уровень заряда и температуру CPU.
 * При критических значениях вызывает callbacks для реакции.
 *
 * ## Пороги температуры
 * - 40°C - WARNING (предупреждение)
 * - 45°C - HIGH (высокая)
 * - 50°C - CRITICAL (критическая)
 *
 * ## Пороги батареи
 * - 15% - LOW (низкий)
 * - 5% - CRITICAL (критический)
 */
class DeviceHealthMonitor(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceHealth"

        // Температура в десятых градуса (400 = 40.0°C)
        private const val TEMP_WARNING = 400
        private const val TEMP_HIGH = 450
        private const val TEMP_CRITICAL = 500

        private const val BATTERY_LOW = 15
        private const val BATTERY_CRITICAL = 5
    }
    
    private var batteryReceiver: BroadcastReceiver? = null
    private val isMonitoring = AtomicBoolean(false)
    
    @Volatile var currentTemperature: Int = 0
        private set
    @Volatile var currentBatteryLevel: Int = 100
        private set
    @Volatile var isCharging: Boolean = false
        private set
    @Volatile var cpuTemperature: Float = 0f
        private set

    private var onTemperatureWarning: ((Int, TemperatureLevel) -> Unit)? = null
    private var onBatteryLow: ((Int) -> Unit)? = null
    
    enum class TemperatureLevel {
        NORMAL,
        WARNING,
        HIGH,
        CRITICAL
    }
    
    /** Запускает мониторинг температуры и батареи */
    fun startMonitoring(
        onTemperatureWarning: (Int, TemperatureLevel) -> Unit = { _, _ -> },
        onBatteryLow: (Int) -> Unit = {}
    ) {
        if (isMonitoring.get()) return
        
        this.onTemperatureWarning = onTemperatureWarning
        this.onBatteryLow = onBatteryLow
        
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                processBatteryIntent(intent)
            }
        }
        
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)
        isMonitoring.set(true)
        
        Log.i(TAG, "Device health monitoring started")
    }
    
    /** Останавливает мониторинг */
    fun stopMonitoring() {
        if (!isMonitoring.get()) return
        
        batteryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver", e)
            }
        }
        batteryReceiver = null
        isMonitoring.set(false)
        
        Log.i(TAG, "Device health monitoring stopped")
    }
    
    private fun processBatteryIntent(intent: Intent) {
        // Температура
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        currentTemperature = temp
        checkTemperature(temp)
        
        // Уровень заряда
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        currentBatteryLevel = if (scale > 0) (level * 100 / scale) else level
        checkBattery(currentBatteryLevel)
        
        // Статус зарядки
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                     status == BatteryManager.BATTERY_STATUS_FULL
        
        // CPU температура (опционально)
        cpuTemperature = readCpuTemperature()
    }
    
    private fun checkTemperature(temp: Int) {
        val level = when {
            temp >= TEMP_CRITICAL -> TemperatureLevel.CRITICAL
            temp >= TEMP_HIGH -> TemperatureLevel.HIGH
            temp >= TEMP_WARNING -> TemperatureLevel.WARNING
            else -> TemperatureLevel.NORMAL
        }
        
        if (level != TemperatureLevel.NORMAL) {
            Log.w(TAG, "Temperature ${level.name}: ${temp / 10.0}°C")
            onTemperatureWarning?.invoke(temp, level)
        }
    }
    
    private fun checkBattery(level: Int) {
        if (!isCharging) {
            when {
                level <= BATTERY_CRITICAL -> {
                    Log.e(TAG, "CRITICAL battery level: $level%")
                    onBatteryLow?.invoke(level)
                }
                level <= BATTERY_LOW -> {
                    Log.w(TAG, "Low battery level: $level%")
                    onBatteryLow?.invoke(level)
                }
            }
        }
    }
    
    /** Читает температуру CPU из системных файлов (если доступно) */
    private fun readCpuTemperature(): Float {
        val paths = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp"
        )

        for (path in paths) {
            try {
                val file = File(path)
                if (file.exists()) {
                    val temp = file.readText().trim().toFloatOrNull() ?: continue
                    return if (temp > 1000) temp / 1000 else temp
                }
            } catch (_: Exception) { }
        }
        return 0f
    }

    /** Возвращает текущий статус здоровья устройства */
    fun getHealthStatus(): DeviceHealthStatus {
        return DeviceHealthStatus(
            batteryLevel = currentBatteryLevel,
            batteryTemperature = currentTemperature / 10f,
            cpuTemperature = cpuTemperature,
            isCharging = isCharging,
            temperatureLevel = when {
                currentTemperature >= TEMP_CRITICAL -> TemperatureLevel.CRITICAL
                currentTemperature >= TEMP_HIGH -> TemperatureLevel.HIGH
                currentTemperature >= TEMP_WARNING -> TemperatureLevel.WARNING
                else -> TemperatureLevel.NORMAL
            }
        )
    }
    
    /** Рекомендуемое снижение качества (0.0-0.75) на основе состояния */
    fun getRecommendedQualityReduction(): Float {
        val tempReduction = when {
            currentTemperature >= TEMP_CRITICAL -> 0.5f  // Снизить на 50%
            currentTemperature >= TEMP_HIGH -> 0.25f     // Снизить на 25%
            currentTemperature >= TEMP_WARNING -> 0.1f   // Снизить на 10%
            else -> 0f
        }
        
        val batteryReduction = if (!isCharging && currentBatteryLevel < BATTERY_LOW) 0.25f else 0f
        
        return (tempReduction + batteryReduction).coerceAtMost(0.75f)
    }
}

data class DeviceHealthStatus(
    val batteryLevel: Int,
    val batteryTemperature: Float,
    val cpuTemperature: Float,
    val isCharging: Boolean,
    val temperatureLevel: DeviceHealthMonitor.TemperatureLevel
)
