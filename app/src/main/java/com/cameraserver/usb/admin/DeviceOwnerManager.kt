package com.cameraserver.usb.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.cameraserver.usb.reliability.LogReporter

/**
 * Менеджер возможностей Device Owner
 *
 * Device Owner обеспечивает надёжную работу фотобудки:
 * - Запуск FGS из фона даже на Android 14+
 * - Отключение экрана блокировки
 * - Удержание экрана включенным при зарядке
 * - Перезагрузка устройства через API
 *
 * ## Установка Device Owner
 * ```
 * adb shell dpm set-device-owner com.cameraserver.usb/.admin.CameraDeviceAdminReceiver
 * ```
 *
 * ## Проверка
 * ```
 * adb shell dpm list-owners
 * ```
 *
 * @see CameraDeviceAdminReceiver
 */
object DeviceOwnerManager {

    private const val TAG = "DeviceOwnerManager"

    /** Проверяет, является ли приложение Device Owner */
    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    /** Проверяет, является ли приложение Device Admin */
    fun isDeviceAdmin(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = CameraDeviceAdminReceiver.getComponentName(context)
        return dpm.isAdminActive(componentName)
    }

    /** Возвращает статус Device Owner для API и UI */
    fun getStatus(context: Context): DeviceOwnerStatus {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = CameraDeviceAdminReceiver.getComponentName(context)

        return DeviceOwnerStatus(
            isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName),
            isDeviceAdmin = dpm.isAdminActive(componentName),
            // На API 28+ можно проверить статус keyguard
            isKeyguardDisabled = dpm.isDeviceOwnerApp(context.packageName), // Если Device Owner, keyguard обычно отключен
            canStartFgsFromBackground = canStartForegroundServiceFromBackground(context)
        )
    }

    /** Проверяет возможность запуска FGS из фона (Device Owner может всегда) */
    fun canStartForegroundServiceFromBackground(context: Context): Boolean {
        if (isDeviceOwner(context)) return true
        if (Build.VERSION.SDK_INT < 34) return true
        return false
    }

    /** Применяет настройки Device Owner (keyguard, stay on, etc.) */
    fun applyDeviceOwnerSettings(context: Context) {
        if (!isDeviceOwner(context)) {
            Log.w(TAG, "Not a Device Owner, cannot apply settings")
            return
        }

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = CameraDeviceAdminReceiver.getComponentName(context)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setKeyguardDisabled(componentName, true)
                LogReporter.info(TAG, "Keyguard disabled")
            }

            dpm.setGlobalSetting(
                componentName,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                (BatteryManager.BATTERY_PLUGGED_AC or
                        BatteryManager.BATTERY_PLUGGED_USB or
                        BatteryManager.BATTERY_PLUGGED_WIRELESS).toString()
            )
            LogReporter.info(TAG, "Stay on while plugged in enabled")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.setSecureSetting(
                    componentName,
                    Settings.Secure.INSTALL_NON_MARKET_APPS,
                    "1"
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setSystemSetting(
                    componentName,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    "60000" // 1 минута
                )
            }

            LogReporter.info(TAG, "Device Owner settings applied successfully")

        } catch (e: Exception) {
            LogReporter.error(TAG, "Failed to apply Device Owner settings", e)
        }
    }

    /** Включает экран блокировки обратно */
    fun enableKeyguard(context: Context) {
        if (!isDeviceOwner(context)) return

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = CameraDeviceAdminReceiver.getComponentName(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                dpm.setKeyguardDisabled(componentName, false)
                LogReporter.info(TAG, "Keyguard enabled")
            } catch (e: Exception) {
                LogReporter.error(TAG, "Failed to enable keyguard", e)
            }
        }
    }

    /** Устанавливает приложение как лаунчер (не реализовано - не нужно для headless) */
    fun setAsDefaultLauncher(context: Context) {
        if (!isDeviceOwner(context)) return
        Log.i(TAG, "setAsDefaultLauncher: Not implemented")
    }

    /** Перезагружает устройство (только Device Owner) */
    fun rebootDevice(context: Context) {
        if (!isDeviceOwner(context)) {
            LogReporter.warn(TAG, "Cannot reboot: not Device Owner")
            return
        }

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                LogReporter.warn(TAG, "Initiating device reboot...")
                dpm.reboot(CameraDeviceAdminReceiver.getComponentName(context))
            } catch (e: Exception) {
                LogReporter.error(TAG, "Failed to reboot device", e)
            }
        }
    }

    /** Удаляет Device Owner (для сброса потребуется factory reset) */
    fun clearDeviceOwner(context: Context) {
        if (!isDeviceOwner(context)) return

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        try {
            enableKeyguard(context)
            dpm.clearDeviceOwnerApp(context.packageName)
            LogReporter.warn(TAG, "Device Owner cleared")
        } catch (e: Exception) {
            LogReporter.error(TAG, "Failed to clear Device Owner", e)
        }
    }

    /** Возвращает инструкции по установке Device Owner */
    fun getSetupInstructions(context: Context): String {
        val packageName = context.packageName
        return """
            |=== Device Owner Setup ===
            |
            |1. Factory reset the device
            |2. Skip Google account during setup
            |3. Enable Developer Options & USB debugging
            |4. Install the app:
            |   adb install app-debug.apk
            |
            |5. Set as Device Owner:
            |   adb shell dpm set-device-owner $packageName/.admin.CameraDeviceAdminReceiver
            |
            |6. Verify:
            |   adb shell dpm list-owners
            |
            |To remove Device Owner later:
            |   Use app settings or:
            |   adb shell dpm remove-active-admin $packageName/.admin.CameraDeviceAdminReceiver
        """.trimMargin()
    }
}

/** Статус Device Owner для API и UI */
data class DeviceOwnerStatus(
    val isDeviceOwner: Boolean,
    val isDeviceAdmin: Boolean,
    val isKeyguardDisabled: Boolean,
    val canStartFgsFromBackground: Boolean
) {
    fun toMap(): Map<String, Any> = mapOf(
        "isDeviceOwner" to isDeviceOwner,
        "isDeviceAdmin" to isDeviceAdmin,
        "isKeyguardDisabled" to isKeyguardDisabled,
        "canStartFgsFromBackground" to canStartFgsFromBackground
    )

    override fun toString(): String {
        return """
            Device Owner: ${if (isDeviceOwner) "YES" else "NO"}
            Device Admin: ${if (isDeviceAdmin) "YES" else "NO"}
            Keyguard Disabled: ${if (isKeyguardDisabled) "YES" else "NO"}
            FGS from Background: ${if (canStartFgsFromBackground) "YES" else "NO"}
        """.trimIndent()
    }
}
