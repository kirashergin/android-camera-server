package com.cameraserver.usb

import android.app.Application
import android.util.Log
import com.cameraserver.usb.reliability.ServiceGuard

/**
 * Точка входа приложения
 *
 * Инициализирует ServiceGuard для защиты сервиса от остановки.
 */
class CameraServerApplication : Application() {

    companion object {
        private const val TAG = "CameraServerApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Приложение запускается")
        ServiceGuard.init(this)
    }
}
