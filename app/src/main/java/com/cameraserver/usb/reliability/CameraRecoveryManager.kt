package com.cameraserver.usb.reliability

import android.util.Log
import com.cameraserver.usb.camera.CameraController
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Менеджер мягкого восстановления камеры
 *
 * Пытается восстановить работу камеры без перезапуска сервиса:
 * - [recoverStream] - перезапуск стрима (до 4 попыток с backoff)
 * - [fullReset] - полный сброс камеры (release + initialize)
 *
 * Cooldown между восстановлениями: 30 секунд.
 */
class CameraRecoveryManager(
    private val cameraController: CameraController
) {
    companion object {
        private const val TAG = "CameraRecovery"
        private val RETRY_DELAYS_MS = listOf(500L, 1000L, 2000L, 5000L)
        private const val MAX_RETRIES = 4
        private const val RECOVERY_COOLDOWN_MS = 30_000L
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRecovering = AtomicBoolean(false)
    private val recoveryAttempts = AtomicInteger(0)
    private var lastRecoveryTime = 0L
    
    private var onRecoveryStarted: (() -> Unit)? = null
    private var onRecoverySuccess: (() -> Unit)? = null
    private var onRecoveryFailed: (() -> Unit)? = null
    
    fun setCallbacks(
        onStarted: () -> Unit = {},
        onSuccess: () -> Unit = {},
        onFailed: () -> Unit = {}
    ) {
        onRecoveryStarted = onStarted
        onRecoverySuccess = onSuccess
        onRecoveryFailed = onFailed
    }
    
    /** Пытается восстановить стрим (до MAX_RETRIES попыток) */
    fun recoverStream(): Boolean {
        if (!canStartRecovery()) {
            Log.w(TAG, "Recovery skipped (cooldown or already recovering)")
            return false
        }
        
        isRecovering.set(true)
        onRecoveryStarted?.invoke()
        
        scope.launch {
            var success = false
            
            for (attempt in 0 until MAX_RETRIES) {
                val delay = RETRY_DELAYS_MS.getOrElse(attempt) { RETRY_DELAYS_MS.last() }
                Log.i(TAG, "Recovery attempt ${attempt + 1}/$MAX_RETRIES (delay: ${delay}ms)")
                
                try {
                    cameraController.stopStream()
                    delay(delay)

                    if (cameraController.startStream()) {
                        Log.i(TAG, "Stream recovered on attempt ${attempt + 1}")
                        success = true
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Recovery attempt ${attempt + 1} failed", e)
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
    
    /** Полный сброс камеры (release + initialize) */
    fun fullReset(): Boolean {
        if (isRecovering.get()) {
            Log.w(TAG, "Full reset skipped (recovery in progress)")
            return false
        }
        
        Log.w(TAG, "Performing full camera reset")
        isRecovering.set(true)
        
        scope.launch {
            try {
                cameraController.release()
                delay(1000)

                cameraController.initialize()
                delay(500)
                
                Log.i(TAG, "Full reset completed")
                onRecoverySuccess?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Full reset failed", e)
                onRecoveryFailed?.invoke()
            } finally {
                isRecovering.set(false)
                lastRecoveryTime = System.currentTimeMillis()
            }
        }
        
        return true
    }
    
    private fun canStartRecovery(): Boolean {
        // Уже идёт восстановление
        if (isRecovering.get()) return false
        
        // Cooldown между восстановлениями
        val timeSinceLastRecovery = System.currentTimeMillis() - lastRecoveryTime
        if (timeSinceLastRecovery < RECOVERY_COOLDOWN_MS) return false
        
        return true
    }
    
    fun isRecoveryInProgress(): Boolean = isRecovering.get()
    
    fun getRecoveryAttempts(): Int = recoveryAttempts.get()
    
    fun shutdown() {
        scope.cancel()
    }
}
