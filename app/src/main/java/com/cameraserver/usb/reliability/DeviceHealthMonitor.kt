package com.cameraserver.usb.reliability

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.cameraserver.usb.config.CameraConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Мониторинг состояния устройства
 *
 * Отслеживает температуру батареи, уровень заряда и температуру CPU.
 * При критических значениях вызывает callbacks для реакции приложения.
 *
 * Пороги температуры задаются в CameraConfig:
 * - TEMP_WARNING (40°C) - предупреждение
 * - TEMP_HIGH (45°C) - высокая температура
 * - TEMP_CRITICAL (50°C) - критическая температура
 */
class DeviceHealthMonitor(private val context: Context) {

    companion object {
        private const val TAG = "DeviceHealth"
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
        NORMAL, WARNING, HIGH, CRITICAL
    }

    /**
     * Запускает мониторинг температуры и батареи
     */
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

        Log.i(TAG, "Мониторинг здоровья устройства запущен")
    }

    /**
     * Останавливает мониторинг
     */
    fun stopMonitoring() {
        if (!isMonitoring.get()) return

        batteryReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
        }
        batteryReceiver = null
        isMonitoring.set(false)

        Log.i(TAG, "Мониторинг здоровья устройства остановлен")
    }

    private fun processBatteryIntent(intent: Intent) {
        // Температура батареи
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
            temp >= CameraConfig.TEMP_CRITICAL -> TemperatureLevel.CRITICAL
            temp >= CameraConfig.TEMP_HIGH -> TemperatureLevel.HIGH
            temp >= CameraConfig.TEMP_WARNING -> TemperatureLevel.WARNING
            else -> TemperatureLevel.NORMAL
        }

        if (level != TemperatureLevel.NORMAL) {
            Log.w(TAG, "Температура ${level.name}: ${temp / 10.0}°C")
            onTemperatureWarning?.invoke(temp, level)
        }
    }

    private fun checkBattery(level: Int) {
        if (!isCharging) {
            when {
                level <= CameraConfig.BATTERY_CRITICAL -> {
                    Log.e(TAG, "КРИТИЧЕСКИЙ уровень батареи: $level%")
                    onBatteryLow?.invoke(level)
                }
                level <= CameraConfig.BATTERY_LOW -> {
                    Log.w(TAG, "Низкий уровень батареи: $level%")
                    onBatteryLow?.invoke(level)
                }
            }
        }
    }

    /**
     * Читает температуру CPU из системных файлов
     */
    private fun readCpuTemperature(): Float {
        val paths = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp"
        )

        for (path in paths) {
            runCatching {
                val file = File(path)
                if (file.exists()) {
                    val temp = file.readText().trim().toFloatOrNull() ?: return@runCatching
                    return if (temp > 1000) temp / 1000 else temp
                }
            }
        }
        return 0f
    }

    /**
     * Возвращает текущий статус здоровья устройства
     */
    fun getHealthStatus(): DeviceHealthStatus {
        return DeviceHealthStatus(
            batteryLevel = currentBatteryLevel,
            batteryTemperature = currentTemperature / 10f,
            cpuTemperature = cpuTemperature,
            isCharging = isCharging,
            temperatureLevel = when {
                currentTemperature >= CameraConfig.TEMP_CRITICAL -> TemperatureLevel.CRITICAL
                currentTemperature >= CameraConfig.TEMP_HIGH -> TemperatureLevel.HIGH
                currentTemperature >= CameraConfig.TEMP_WARNING -> TemperatureLevel.WARNING
                else -> TemperatureLevel.NORMAL
            }
        )
    }

    /**
     * Рекомендуемое снижение качества на основе состояния устройства
     *
     * @return коэффициент снижения (0.0-QUALITY_REDUCTION_MAX)
     */
    fun getRecommendedQualityReduction(): Float {
        val tempReduction = when {
            currentTemperature >= CameraConfig.TEMP_CRITICAL -> CameraConfig.QUALITY_REDUCTION_TEMP_CRITICAL
            currentTemperature >= CameraConfig.TEMP_HIGH -> CameraConfig.QUALITY_REDUCTION_TEMP_HIGH
            currentTemperature >= CameraConfig.TEMP_WARNING -> CameraConfig.QUALITY_REDUCTION_TEMP_WARNING
            else -> 0f
        }

        val batteryReduction = if (!isCharging && currentBatteryLevel < CameraConfig.BATTERY_LOW) {
            CameraConfig.QUALITY_REDUCTION_BATTERY_LOW
        } else {
            0f
        }

        return (tempReduction + batteryReduction).coerceAtMost(CameraConfig.QUALITY_REDUCTION_MAX)
    }
}

/**
 * Статус здоровья устройства
 */
data class DeviceHealthStatus(
    val batteryLevel: Int,
    val batteryTemperature: Float,
    val cpuTemperature: Float,
    val isCharging: Boolean,
    val temperatureLevel: DeviceHealthMonitor.TemperatureLevel
)
