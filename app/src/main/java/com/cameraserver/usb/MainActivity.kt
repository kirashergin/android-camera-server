package com.cameraserver.usb

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cameraserver.usb.admin.DeviceOwnerManager
import com.cameraserver.usb.config.CameraConfig

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val BATTERY_OPTIMIZATION_REQUEST = 101
    }

    private var cameraService: CameraService? = null
    private var isBound = false

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.LocalBinder
            cameraService = binder.getService()
            isBound = true
            updateUI()
            Log.i(TAG, "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            isBound = false
            updateUI()
            Log.i(TAG, "Service disconnected")
        }
    }

    @SuppressLint("BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Держать экран включенным (для фотобудки)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        startButton.setOnClickListener {
            if (checkPermissions()) {
                startCameraService()
            } else {
                requestPermissions()
            }
        }

        stopButton.setOnClickListener {
            stopCameraService()
        }

        // Проверяем и запрашиваем исключение из оптимизации батареи
        checkBatteryOptimization()

        // Автозапуск при наличии разрешений
        if (checkPermissions()) {
            startCameraService()
        } else {
            requestPermissions()
        }
    }

    @SuppressLint("BatteryLife")
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                // Показываем диалог с объяснением
                AlertDialog.Builder(this)
                    .setTitle("Battery Optimization")
                    .setMessage(
                        "Для стабильной работы в режиме фотобудки необходимо отключить оптимизацию батареи.\n\n" +
                        "Это позволит сервису работать непрерывно в фоне."
                    )
                    .setPositiveButton("Настроить") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST)
                    }
                    .setNegativeButton("Позже", null)
                    .show()
            }
        }
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(packageName)
        }
        return true
    }

    private fun checkPermissions(): Boolean {
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        return cameraPermission == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        
        // Android 13+ требует отдельного разрешения для уведомлений
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val cameraGranted = grantResults.isNotEmpty() && 
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            
            if (cameraGranted) {
                startCameraService()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
                statusText.text = "Camera permission denied"
            }
        }
    }

    private fun startCameraService() {
        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_START
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        // Bind для получения статуса
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        updateUI()
    }

    private fun stopCameraService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        
        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_STOP
        }
        startService(intent)
        
        cameraService = null
        updateUI()
    }

    private fun updateUI() {
        runOnUiThread {
            val isRunning = cameraService?.isServerRunning() == true
            val port = cameraService?.getServerPort() ?: CameraService.SERVER_PORT
            val configSummary = CameraConfig.getConfigSummary()
            val batteryOptimized = if (isBatteryOptimizationDisabled()) "✅ OFF" else "⚠️ ON"

            // Device Owner статус
            val doStatus = DeviceOwnerManager.getStatus(this)
            val deviceOwner = if (doStatus.isDeviceOwner) "✅ YES" else "❌ NO"
            val fgsBackground = if (doStatus.canStartFgsFromBackground) "✅ YES" else "⚠️ NO"

            if (isRunning) {
                statusText.text = """
                    ══════ SERVER STATUS ══════
                    Status: ✅ RUNNING
                    Port: $port
                    Stream: $configSummary

                    ══════ DEVICE OWNER ══════
                    Device Owner: $deviceOwner
                    FGS from Background: $fgsBackground

                    ══════ RELIABILITY ══════
                    Battery Optimization: $batteryOptimized

                    ══════ API ENDPOINTS ══════
                    GET  /status | /stream/config
                    POST /stream/config
                    POST /stream/start | stop
                    GET  /stream/mjpeg
                    POST /photo | /photo/quick

                    ══════ CONNECT VIA USB ══════
                    adb forward tcp:$port tcp:$port
                    http://localhost:$port
                """.trimIndent()

                startButton.isEnabled = false
                stopButton.isEnabled = true
            } else {
                statusText.text = """
                    ══════ SERVER STATUS ══════
                    Status: ⏹️ STOPPED
                    Config: $configSummary

                    ══════ DEVICE OWNER ══════
                    Device Owner: $deviceOwner
                    FGS from Background: $fgsBackground

                    ══════ RELIABILITY ══════
                    Battery Optimization: $batteryOptimized

                    ═══════════════════════════
                    Press START to begin
                """.trimIndent()

                startButton.isEnabled = true
                stopButton.isEnabled = false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Привязываемся к сервису если он работает
        val intent = Intent(this, CameraService::class.java)
        bindService(intent, serviceConnection, 0)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
