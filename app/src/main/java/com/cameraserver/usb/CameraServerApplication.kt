package com.cameraserver.usb

import android.app.Application
import android.util.Log
import com.cameraserver.usb.reliability.ServiceGuard

class CameraServerApplication : Application() {
    companion object {
        private const val TAG = "CameraServerApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Application starting")
        ServiceGuard.init(this)
    }
}
