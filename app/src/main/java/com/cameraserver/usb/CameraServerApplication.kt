package com.cameraserver.usb

import android.app.Application
import android.util.Log
import com.cameraserver.usb.config.CameraConfig
import com.cameraserver.usb.reliability.ServiceGuard

/**
 * Application entry point
 *
 * Initializes:
 * - CameraConfig: detects device tier for adaptive performance
 * - ServiceGuard: ensures 24/7 service operation
 *
 * Note: Does NOT start the service directly - MainActivity handles that
 * because Android 14+ requires foreground context for camera FGS.
 */
class CameraServerApplication : Application() {

    companion object {
        private const val TAG = "CameraServerApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Application starting...")

        // Initialize device-adaptive configuration
        CameraConfig.initialize(this)
        Log.i(TAG, "CameraConfig initialized: ${CameraConfig.deviceTier}")

        // Initialize service reliability guard
        ServiceGuard.init(this)
        Log.i(TAG, "ServiceGuard initialized")
    }
}
