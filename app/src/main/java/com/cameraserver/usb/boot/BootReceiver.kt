package com.cameraserver.usb.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.cameraserver.usb.CameraService
import com.cameraserver.usb.admin.DeviceOwnerManager
import com.cameraserver.usb.reliability.LogReporter

/**
 * Автозапуск сервиса при загрузке устройства
 *
 * - Device Owner: запускает FGS напрямую
 * - Без Device Owner на Android 14+: запускает MainActivity
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received: $action")
        LogReporter.info(TAG, "Boot event: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                startCameraService(context)
            }
        }
    }

    private fun startCameraService(context: Context) {
        val isDeviceOwner = DeviceOwnerManager.isDeviceOwner(context)
        val canStartFromBackground = DeviceOwnerManager.canStartForegroundServiceFromBackground(context)

        LogReporter.info(TAG, "Starting CameraService (DeviceOwner: $isDeviceOwner, API: ${Build.VERSION.SDK_INT})")

        if (canStartFromBackground) {
            startServiceDirectly(context)
        } else {
            LogReporter.warn(TAG, "Cannot start FGS from background, launching Activity")
            launchMainActivity(context)
        }
    }

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
            LogReporter.info(TAG, "CameraService start requested directly")
        } catch (e: Exception) {
            LogReporter.error(TAG, "Failed to start service directly, trying Activity", e)
            launchMainActivity(context)
        }
    }

    private fun launchMainActivity(context: Context) {
        try {
            val activityIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            activityIntent?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(activityIntent)
            LogReporter.info(TAG, "MainActivity launched for service start")
        } catch (e: Exception) {
            LogReporter.error(TAG, "Failed to launch Activity", e)
        }
    }
}
