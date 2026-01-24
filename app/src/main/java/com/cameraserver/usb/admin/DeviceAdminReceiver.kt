package com.cameraserver.usb.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cameraserver.usb.reliability.LogReporter

/**
 * Device Admin Receiver для получения прав администратора устройства
 *
 * Возможности Device Owner (без Kiosk Mode):
 * - Автозапуск без разблокировки экрана
 * - Запуск FGS из фона на Android 14+
 * - Программное отключение оптимизации батареи
 * - Управление экраном (держать включенным при зарядке)
 * - Автообновление приложения без подтверждения
 *
 * Установка Device Owner:
 * 1. Сброс устройства до заводских настроек
 * 2. НЕ добавлять Google аккаунт при настройке
 * 3. Включить Developer Options и USB debugging
 * 4. adb install app-debug.apk
 * 5. adb shell dpm set-device-owner com.cameraserver.usb/.admin.CameraDeviceAdminReceiver
 */
class CameraDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DeviceAdmin"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, CameraDeviceAdminReceiver::class.java)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled")
        LogReporter.info(TAG, "Device Admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device Admin disabled")
        LogReporter.warn(TAG, "Device Admin disabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Profile provisioning complete")

        // Применяем настройки Device Owner
        DeviceOwnerManager.applyDeviceOwnerSettings(context)
    }
}
