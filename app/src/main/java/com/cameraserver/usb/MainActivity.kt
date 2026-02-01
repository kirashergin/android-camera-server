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

/**
 * Главный экран приложения
 *
 * Обеспечивает:
 * - Запуск/остановку сервиса камеры
 * - Отображение статуса сервера
 * - Запрос необходимых разрешений
 * - Настройку оптимизации батареи
 */
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
            Log.i(TAG, "Подключён к сервису")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            isBound = false
            updateUI()
            Log.i(TAG, "Отключён от сервиса")
        }
    }

    @SuppressLint("BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Держать экран включенным
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

        checkBatteryOptimization()

        if (checkPermissions()) {
            startCameraService()
        } else {
            requestPermissions()
        }
    }

    /**
     * Проверяет и запрашивает исключение из оптимизации батареи
     */
    @SuppressLint("BatteryLife")
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Оптимизация батареи")
                    .setMessage(
                        "Для стабильной работы необходимо отключить оптимизацию батареи.\n\n" +
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
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
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
                Toast.makeText(this, "Требуется разрешение камеры", Toast.LENGTH_LONG).show()
                statusText.text = "Разрешение камеры отклонено"
            }
        }
    }

    /**
     * Запускает сервис камеры
     */
    private fun startCameraService() {
        val intent = Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        updateUI()
    }

    /**
     * Останавливает сервис камеры
     */
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

    /**
     * Обновляет UI на основе текущего состояния
     */
    private fun updateUI() {
        runOnUiThread {
            val isRunning = cameraService?.isServerRunning() == true
            val port = CameraConfig.SERVER_PORT
            val configSummary = CameraConfig.getConfigSummary()
            val batteryOptimized = if (isBatteryOptimizationDisabled()) "OFF" else "ON"

            val doStatus = DeviceOwnerManager.getStatus(this)
            val deviceOwner = if (doStatus.isDeviceOwner) "YES" else "NO"
            val fgsBackground = if (doStatus.canStartFgsFromBackground) "YES" else "NO"

            if (isRunning) {
                statusText.text = """
                    ══════ СТАТУС СЕРВЕРА ══════
                    Статус: РАБОТАЕТ
                    Порт: $port
                    Стрим: $configSummary

                    ══════ DEVICE OWNER ══════
                    Device Owner: $deviceOwner
                    FGS из фона: $fgsBackground

                    ══════ НАДЁЖНОСТЬ ══════
                    Оптимизация батареи: $batteryOptimized

                    ══════ API ENDPOINTS ══════
                    GET  /status | /stream/config
                    POST /stream/config
                    POST /stream/start | stop
                    GET  /stream/mjpeg
                    POST /photo | /photo/quick

                    ══════ ПОДКЛЮЧЕНИЕ USB ══════
                    adb forward tcp:$port tcp:$port
                    http://localhost:$port
                """.trimIndent()

                startButton.isEnabled = false
                stopButton.isEnabled = true
            } else {
                statusText.text = """
                    ══════ СТАТУС СЕРВЕРА ══════
                    Статус: ОСТАНОВЛЕН
                    Конфиг: $configSummary

                    ══════ DEVICE OWNER ══════
                    Device Owner: $deviceOwner
                    FGS из фона: $fgsBackground

                    ══════ НАДЁЖНОСТЬ ══════
                    Оптимизация батареи: $batteryOptimized

                    ═══════════════════════════
                    Нажмите START для запуска
                """.trimIndent()

                startButton.isEnabled = true
                stopButton.isEnabled = false
            }
        }
    }

    override fun onStart() {
        super.onStart()
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
