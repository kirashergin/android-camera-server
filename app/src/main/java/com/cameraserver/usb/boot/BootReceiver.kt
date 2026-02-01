package com.cameraserver.usb.boot

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.cameraserver.usb.CameraService
import com.cameraserver.usb.admin.DeviceOwnerManager
import com.cameraserver.usb.reliability.LogReporter

/**
 * Автозапуск сервиса при загрузке устройства
 *
 * Поведение:
 * - Device Owner: запускает FGS напрямую
 * - Без Device Owner на Android 14+: запускает MainActivity
 *
 * Обрабатываемые события:
 * - ACTION_BOOT_COMPLETED
 * - ACTION_LOCKED_BOOT_COMPLETED
 * - Vendor-specific quickboot events
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Получено: $action")
        LogReporter.info(TAG, "Событие загрузки: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                startCameraService(context)
            }
        }
    }

    /**
     * Запускает сервис камеры в зависимости от возможностей
     */
    private fun startCameraService(context: Context) {
        // Проверяем разрешение камеры
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            LogReporter.warn(TAG, "Нет разрешения CAMERA, запускаем Activity для запроса")
            launchMainActivity(context)
            return
        }

        val isDeviceOwner = DeviceOwnerManager.isDeviceOwner(context)
        val canStartFromBackground = DeviceOwnerManager.canStartForegroundServiceFromBackground(context)

        LogReporter.info(TAG, "Запуск CameraService (DeviceOwner: $isDeviceOwner, API: ${Build.VERSION.SDK_INT})")

        if (canStartFromBackground) {
            startServiceDirectly(context)
        } else {
            LogReporter.warn(TAG, "Нельзя запустить FGS из фона, запускаем Activity")
            launchMainActivity(context)
        }
    }

    /**
     * Прямой запуск сервиса (для Device Owner или API < 34)
     */
    private fun startServiceDirectly(context: Context) {
        val serviceIntent = Intent(context, CameraService::class.java).apply {
            action = CameraService.ACTION_START
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            LogReporter.info(TAG, "CameraService запущен напрямую")
        } catch (e: Exception) {
            LogReporter.error(TAG, "Ошибка прямого запуска, пробуем Activity", e)
            launchMainActivity(context)
        }
    }

    /**
     * Запуск через MainActivity (для Android 14+ без Device Owner)
     */
    private fun launchMainActivity(context: Context) {
        runCatching {
            val activityIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            activityIntent?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(activityIntent)
            LogReporter.info(TAG, "MainActivity запущен для старта сервиса")
        }.onFailure {
            LogReporter.error(TAG, "Ошибка запуска Activity", it)
        }
    }
}
