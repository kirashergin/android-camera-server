package com.cameraserver.usb.reliability

import android.util.Log
import com.cameraserver.usb.camera.CameraController
import com.cameraserver.usb.config.CameraConfig
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Менеджер мягкого восстановления камеры
 *
 * Пытается восстановить работу камеры без перезапуска сервиса:
 * - recoverStream() - перезапуск стрима с экспоненциальным backoff
 * - fullReset() - полный сброс камеры (release + initialize)
 *
 * Cooldown между восстановлениями предотвращает слишком частые попытки.
 */
class CameraRecoveryManager(
    private val cameraController: CameraController
) {
    companion object {
        private const val TAG = "CameraRecovery"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRecovering = AtomicBoolean(false)
    private val recoveryAttempts = AtomicInteger(0)
    private var lastRecoveryTime = 0L

    private var onRecoveryStarted: (() -> Unit)? = null
    private var onRecoverySuccess: (() -> Unit)? = null
    private var onRecoveryFailed: (() -> Unit)? = null

    /**
     * Устанавливает callbacks для отслеживания процесса восстановления
     */
    fun setCallbacks(
        onStarted: () -> Unit = {},
        onSuccess: () -> Unit = {},
        onFailed: () -> Unit = {}
    ) {
        onRecoveryStarted = onStarted
        onRecoverySuccess = onSuccess
        onRecoveryFailed = onFailed
    }

    /**
     * Пытается восстановить стрим с экспоненциальным backoff
     *
     * @return true если восстановление запущено, false если уже в процессе или cooldown
     */
    fun recoverStream(): Boolean {
        if (!canStartRecovery()) {
            Log.w(TAG, "Восстановление пропущено (cooldown или уже выполняется)")
            return false
        }

        isRecovering.set(true)
        onRecoveryStarted?.invoke()

        scope.launch {
            var success = false

            for (attempt in 0 until CameraConfig.CAMERA_RECOVERY_MAX_RETRIES) {
                val delay = CameraConfig.CAMERA_RECOVERY_DELAYS_MS.getOrElse(attempt) {
                    CameraConfig.CAMERA_RECOVERY_DELAYS_MS.last()
                }
                Log.i(TAG, "Попытка восстановления ${attempt + 1}/${CameraConfig.CAMERA_RECOVERY_MAX_RETRIES} (задержка: ${delay}мс)")

                try {
                    cameraController.stopStream()
                    delay(delay)

                    if (cameraController.startStream()) {
                        Log.i(TAG, "Стрим восстановлен на попытке ${attempt + 1}")
                        success = true
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Попытка ${attempt + 1} не удалась: ${e.message}", e)
                }

                delay(delay)
            }

            isRecovering.set(false)
            lastRecoveryTime = System.currentTimeMillis()

            if (success) {
                recoveryAttempts.set(0)
                onRecoverySuccess?.invoke()
            } else {
                recoveryAttempts.incrementAndGet()
                onRecoveryFailed?.invoke()
            }
        }

        return true
    }

    /**
     * Полный сброс камеры (release + initialize)
     *
     * @return true если сброс запущен
     */
    fun fullReset(): Boolean {
        if (isRecovering.get()) {
            Log.w(TAG, "Полный сброс пропущен (восстановление в процессе)")
            return false
        }

        Log.w(TAG, "Запуск полного сброса камеры")
        isRecovering.set(true)

        scope.launch {
            try {
                cameraController.release()
                delay(CameraConfig.CAMERA_FULL_RESET_RELEASE_DELAY_MS)

                cameraController.initialize()
                delay(CameraConfig.CAMERA_FULL_RESET_INIT_DELAY_MS)

                Log.i(TAG, "Полный сброс завершён")
                onRecoverySuccess?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Полный сброс не удался: ${e.message}", e)
                onRecoveryFailed?.invoke()
            } finally {
                isRecovering.set(false)
                lastRecoveryTime = System.currentTimeMillis()
            }
        }

        return true
    }

    private fun canStartRecovery(): Boolean {
        if (isRecovering.get()) return false

        val timeSinceLastRecovery = System.currentTimeMillis() - lastRecoveryTime
        if (timeSinceLastRecovery < CameraConfig.CAMERA_RECOVERY_COOLDOWN_MS) return false

        return true
    }

    fun isRecoveryInProgress(): Boolean = isRecovering.get()

    fun getRecoveryAttempts(): Int = recoveryAttempts.get()

    fun shutdown() {
        scope.cancel()
    }
}
