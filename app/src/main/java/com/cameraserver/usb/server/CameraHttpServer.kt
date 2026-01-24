package com.cameraserver.usb.server

import android.content.Context
import android.util.Log
import com.cameraserver.usb.admin.DeviceOwnerManager
import com.cameraserver.usb.camera.CameraController
import com.cameraserver.usb.config.CameraConfig
import com.cameraserver.usb.config.FocusMode
import com.cameraserver.usb.config.StreamQuality
import com.cameraserver.usb.reliability.SystemWatchdog
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP server for photo booth camera control
 *
 * Built on NanoHTTPD, runs inside a foreground service.
 *
 * ## API Endpoints
 *
 * ### Status
 * - `GET  /health` - Health check for monitoring
 * - `GET  /status` - Full camera and server status
 *
 * ### Streaming
 * - `POST /stream/start` - Start MJPEG stream
 * - `POST /stream/stop` - Stop stream
 * - `GET  /stream/mjpeg` - MJPEG stream (multipart/x-mixed-replace)
 * - `GET  /stream/frame` - Single frame (for debugging)
 * - `GET  /stream/quality` - Current quality settings
 * - `POST /stream/quality` - Change quality at runtime
 *
 * ### Photo
 * - `POST /photo` - Full resolution photo (~1-2 sec)
 * - `POST /photo/quick` - Instant photo from stream
 * - `POST /photo/quick?highres=true` - Quick photo at max resolution without stopping stream
 *
 * ### Focus
 * - `GET  /focus/mode` - Current focus mode
 * - `POST /focus/mode` - Set focus mode (AUTO/CONTINUOUS/MANUAL/FIXED/MACRO)
 * - `POST /focus/distance` - Distance for MANUAL mode
 * - `POST /focus/point` - Focus on point
 * - `POST /focus/auto` - Trigger single autofocus
 * - `GET  /focus/modes` - List of supported modes
 *
 * ### Device Owner (for technicians)
 * - `GET  /device-owner` - Device Owner status
 * - `POST /device-owner/reboot` - Reboot device
 */
class CameraHttpServer(
    private val context: Context,
    private val port: Int,
    private val cameraController: CameraController,
    private val watchdog: SystemWatchdog? = null
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "CameraHttpServer"
        private const val MJPEG_BOUNDARY = "frame"
        private const val STREAM_RETRY_DELAY_MS = 2500L
    }

    private val activeStreams = ConcurrentHashMap<String, AtomicBoolean>()
    private val streamClientCount = AtomicInteger(0)
    private val clientFrameQueues = ConcurrentHashMap<String, ArrayBlockingQueue<ByteArray>>()
    private val streamLock = Object()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // Report to watchdog that server is alive
        watchdog?.reportServerResponse()

        return try {
            when {
                // Health check
                uri == "/health" && method == Method.GET -> handleHealth()

                // Status
                uri == "/status" && method == Method.GET -> handleStatus()

                // Stream control
                uri == "/stream/start" && method == Method.POST -> handleStreamStart()
                uri == "/stream/stop" && method == Method.POST -> handleStreamStop()

                // Video streaming
                uri == "/stream/mjpeg" && method == Method.GET -> handleMjpegStream(session)
                uri == "/stream/frame" && method == Method.GET -> handleSingleFrame()

                // Photo capture
                uri == "/photo" && method == Method.POST -> handlePhoto()
                uri == "/photo/quick" && method == Method.POST -> handleQuickPhoto(session)

                // Quality settings
                uri == "/stream/quality" && method == Method.GET -> handleGetQuality()
                uri == "/stream/quality" && method == Method.POST -> handleSetQuality(session)

                // Focus control
                uri == "/focus/mode" && method == Method.POST -> handleSetFocusMode(session)
                uri == "/focus/mode" && method == Method.GET -> handleGetFocusMode()
                uri == "/focus/distance" && method == Method.POST -> handleSetFocusDistance(session)
                uri == "/focus/point" && method == Method.POST -> handleFocusOnPoint(session)
                uri == "/focus/auto" && method == Method.POST -> handleTriggerAutoFocus()
                uri == "/focus/modes" && method == Method.GET -> handleGetSupportedModes()

                // Device Owner (for technicians)
                uri == "/device-owner" && method == Method.GET -> handleDeviceOwnerStatus()
                uri == "/device-owner/reboot" && method == Method.POST -> handleDeviceReboot()

                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not Found: $uri"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $method $uri: ${e.message}")
            createErrorResponse(e.message ?: "Unknown error")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // STATUS ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════

    private fun handleHealth(): Response {
        return createJsonResponse(JSONObject().apply {
            put("status", "ok")
            put("timestamp", System.currentTimeMillis())
        })
    }

    private fun handleStatus(): Response {
        val status = cameraController.getStatus()
        return createJsonResponse(JSONObject().apply {
            put("camera", JSONObject().apply {
                put("isOpen", status.isOpen)
                put("isStreaming", status.isStreaming)
                put("streamResolution", status.streamResolution)
                put("photoResolution", status.photoResolution)
                put("targetFps", status.targetFps)
                put("quality", status.quality)
                put("focusMode", status.focusMode)
                put("quickPhotoHighResAvailable", status.quickPhotoHighResAvailable)
                status.quickPhotoHighResolution?.let { put("quickPhotoHighResolution", it) }
            })
            put("device", JSONObject().apply {
                put("tier", status.deviceTier)
                put("ram", CameraConfig.totalRamMb)
                put("cpuCores", CameraConfig.cpuCores)
            })
            put("server", JSONObject().apply {
                put("port", port)
                put("activeClients", streamClientCount.get())
            })
            put("timestamp", System.currentTimeMillis())
        })
    }

    // ═══════════════════════════════════════════════════════════════════
    // STREAM CONTROL
    // ═══════════════════════════════════════════════════════════════════

    private fun handleStreamStart(): Response {
        var success = cameraController.startStream()

        // Retry if first attempt failed (dual output fallback)
        if (!success) {
            Log.i(TAG, "Stream start failed, retrying after camera recovery...")
            Thread.sleep(STREAM_RETRY_DELAY_MS)
            success = cameraController.startStream()
        }

        return if (success) {
            createJsonResponse(JSONObject().apply {
                put("success", true)
                put("message", "Stream started")
                put("mjpegUrl", "/stream/mjpeg")
                put("frameUrl", "/stream/frame")
            })
        } else {
            createErrorResponse("Failed to start stream", Response.Status.INTERNAL_ERROR)
        }
    }

    private fun handleStreamStop(): Response {
        cameraController.stopStream()
        watchdog?.reportStreamStopped()
        return createJsonResponse(JSONObject().apply {
            put("success", true)
            put("message", "Stream stopped")
        })
    }

    private fun handleSingleFrame(): Response {
        if (!cameraController.isStreamActive()) {
            return createErrorResponse("Stream not active. Call POST /stream/start first.", Response.Status.BAD_REQUEST)
        }

        val frame = cameraController.getLastFrame()
        return if (frame != null) {
            newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                ByteArrayInputStream(frame),
                frame.size.toLong()
            ).apply {
                addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                addHeader("Pragma", "no-cache")
                addHeader("Expires", "0")
            }
        } else {
            createErrorResponse("No frame available", Response.Status.SERVICE_UNAVAILABLE)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MJPEG STREAMING
    // ═══════════════════════════════════════════════════════════════════

    /** Distribute frames to all connected clients (push model) */
    private val frameDistributor: (ByteArray) -> Unit = { frame ->
        clientFrameQueues.forEach { (_, queue) ->
            if (!queue.offer(frame)) {
                queue.poll()
                queue.offer(frame)
            }
        }
    }

    private fun handleMjpegStream(session: IHTTPSession): Response {
        val clientId = "${session.remoteIpAddress}:${System.currentTimeMillis()}"
        val isActive = AtomicBoolean(true)
        activeStreams[clientId] = isActive

        val frameQueue = ArrayBlockingQueue<ByteArray>(3)
        clientFrameQueues[clientId] = frameQueue

        val clientNum = streamClientCount.incrementAndGet()
        Log.i(TAG, "MJPEG client connected: $clientId (total: $clientNum)")

        synchronized(streamLock) {
            if (!cameraController.isStreamActive()) {
                if (!cameraController.startStream(frameDistributor)) {
                    clientFrameQueues.remove(clientId)
                    activeStreams.remove(clientId)
                    streamClientCount.decrementAndGet()
                    return createErrorResponse("Failed to start stream", Response.Status.INTERNAL_ERROR)
                }
            } else {
                cameraController.setStreamCallback(frameDistributor)
            }
        }

        val bufferSize = if (CameraConfig.streamWidth >= 1920) 524288 else 262144
        val pipedInput = PipedInputStream(bufferSize)
        val pipedOutput = PipedOutputStream(pipedInput)

        Thread({
            try {
                val frameTimeoutMs = 1000L / CameraConfig.targetFps * 3

                while (isActive.get()) {
                    val frame = frameQueue.poll(frameTimeoutMs, TimeUnit.MILLISECONDS)

                    if (frame != null) {
                        try {
                            watchdog?.reportFrameReceived()

                            val header = "--$MJPEG_BOUNDARY\r\n" +
                                    "Content-Type: image/jpeg\r\n" +
                                    "Content-Length: ${frame.size}\r\n" +
                                    "X-Timestamp: ${System.currentTimeMillis()}\r\n\r\n"

                            pipedOutput.write(header.toByteArray())
                            pipedOutput.write(frame)
                            pipedOutput.write("\r\n".toByteArray())
                            pipedOutput.flush()
                        } catch (e: java.io.IOException) {
                            break
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // Thread interrupted, exit gracefully
            } catch (e: Exception) {
                Log.w(TAG, "MJPEG stream ended: ${e.message}")
            } finally {
                isActive.set(false)
                clientFrameQueues.remove(clientId)
                runCatching { pipedOutput.close() }
                activeStreams.remove(clientId)

                synchronized(streamLock) {
                    val remaining = streamClientCount.decrementAndGet()
                    Log.i(TAG, "MJPEG client disconnected: $clientId (remaining: $remaining)")

                    if (remaining == 0 && activeStreams.isEmpty()) {
                        Log.i(TAG, "No clients remaining, stopping stream")
                        cameraController.stopStream()
                        watchdog?.reportStreamStopped()
                    }
                }
            }
        }, "MJPEG-$clientId").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY",
            pipedInput
        ).apply {
            addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            addHeader("Pragma", "no-cache")
            addHeader("Connection", "keep-alive")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PHOTO CAPTURE
    // ═══════════════════════════════════════════════════════════════════

    private fun handlePhoto(): Response {
        val photoData = cameraController.capturePhoto()
        return if (photoData != null) {
            Log.i(TAG, "Full resolution photo: ${photoData.size} bytes")
            newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                ByteArrayInputStream(photoData),
                photoData.size.toLong()
            ).apply {
                addHeader("Content-Disposition", "attachment; filename=\"photo_${System.currentTimeMillis()}.jpg\"")
                addHeader("Cache-Control", "no-cache")
            }
        } else {
            createErrorResponse("Failed to capture photo", Response.Status.INTERNAL_ERROR)
        }
    }

    private fun handleQuickPhoto(session: IHTTPSession): Response {
        val highRes = session.parameters["highres"]?.firstOrNull()?.toBoolean() == true
                || parseBody(session)["highres"]?.toBoolean() == true

        val photoData = cameraController.captureQuickPhoto(highRes = highRes)
        return if (photoData != null) {
            val resolution = cameraController.getQuickPhotoResolution(highRes)
            val modeStr = if (highRes) "high-res" else "stream"
            newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                ByteArrayInputStream(photoData),
                photoData.size.toLong()
            ).apply {
                addHeader("Content-Disposition", "attachment; filename=\"quick_${System.currentTimeMillis()}.jpg\"")
                addHeader("X-Resolution", resolution)
                addHeader("X-Mode", modeStr)
                addHeader("Cache-Control", "no-cache")
            }
        } else {
            createErrorResponse("No stream frame available. Call POST /stream/start first.", Response.Status.BAD_REQUEST)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // QUALITY SETTINGS
    // ═══════════════════════════════════════════════════════════════════

    private fun handleGetQuality(): Response {
        val presets = CameraConfig.presets.map { (quality, preset) ->
            JSONObject().apply {
                put("name", quality.name)
                put("width", preset.width)
                put("height", preset.height)
                put("fps", preset.fps)
                put("description", preset.description)
            }
        }

        val current = CameraConfig.current
        return createJsonResponse(JSONObject().apply {
            put("current", JSONObject().apply {
                put("quality", CameraConfig.currentQuality.name)
                put("width", current.width)
                put("height", current.height)
                put("fps", current.fps)
                put("description", current.description)
            })
            put("deviceTier", CameraConfig.deviceTier.name)
            put("presets", JSONArray(presets))
        })
    }

    private fun handleSetQuality(session: IHTTPSession): Response {
        val body = parseBody(session)

        // Option 1: Preset
        val qualityStr = body["quality"]?.uppercase()
        if (qualityStr != null) {
            val quality = try {
                StreamQuality.valueOf(qualityStr)
            } catch (e: Exception) {
                return createErrorResponse("Invalid quality: $qualityStr. Valid: ${StreamQuality.entries.joinToString()}")
            }

            CameraConfig.setQuality(quality)
            val success = cameraController.restartStreamWithNewSettings()

            return createJsonResponse(JSONObject().apply {
                put("success", success)
                put("quality", quality.name)
                put("width", CameraConfig.streamWidth)
                put("height", CameraConfig.streamHeight)
                put("fps", CameraConfig.targetFps)
            })
        }

        // Option 2: Custom parameters
        val width = body["width"]?.toIntOrNull()
        val height = body["height"]?.toIntOrNull()
        val fps = body["fps"]?.toIntOrNull()

        if (width != null && height != null && fps != null) {
            if (width < 320 || width > 3840 || height < 240 || height > 2160) {
                return createErrorResponse("Invalid resolution. Range: 320x240 - 3840x2160")
            }
            if (fps < 5 || fps > 60) {
                return createErrorResponse("Invalid FPS. Range: 5-60")
            }

            val jpegQuality = body["jpegQuality"]?.toIntOrNull() ?: 75
            CameraConfig.setCustom(width, height, fps, jpegQuality.coerceIn(50, 100))
            val success = cameraController.restartStreamWithNewSettings()

            return createJsonResponse(JSONObject().apply {
                put("success", success)
                put("quality", "CUSTOM")
                put("width", width)
                put("height", height)
                put("fps", fps)
            })
        }

        return createErrorResponse("Provide 'quality' (LOW/MEDIUM/HIGH) or 'width', 'height', 'fps'")
    }

    // ═══════════════════════════════════════════════════════════════════
    // FOCUS CONTROL
    // ═══════════════════════════════════════════════════════════════════

    private fun handleSetFocusMode(session: IHTTPSession): Response {
        val body = parseBody(session)
        val modeStr = body["mode"]?.uppercase()
            ?: return createErrorResponse("Missing 'mode' parameter")

        val mode = try {
            FocusMode.valueOf(modeStr)
        } catch (e: Exception) {
            return createErrorResponse("Invalid mode: $modeStr. Valid: ${FocusMode.entries.joinToString()}")
        }

        val success = cameraController.setFocusMode(mode)
        return createJsonResponse(JSONObject().apply {
            put("success", success)
            put("focusMode", mode.name)
        })
    }

    private fun handleGetFocusMode(): Response {
        val status = cameraController.getStatus()
        return createJsonResponse(JSONObject().apply {
            put("focusMode", status.focusMode)
            put("manualFocusDistance", status.manualFocusDistance)
            put("supportsManualFocus", status.supportsManualFocus)
        })
    }

    private fun handleSetFocusDistance(session: IHTTPSession): Response {
        val body = parseBody(session)
        val distanceStr = body["distance"]
            ?: return createErrorResponse("Missing 'distance' parameter")

        val distance = distanceStr.toFloatOrNull()
            ?: return createErrorResponse("Invalid distance value: $distanceStr")

        if (distance < 0f || distance > 1f) {
            return createErrorResponse("Distance must be 0.0-1.0 (0=infinity, 1=macro)")
        }

        val success = cameraController.setManualFocusDistance(distance)
        return createJsonResponse(JSONObject().apply {
            put("success", success)
            put("distance", distance)
            put("note", "Set focus mode to MANUAL to use this distance")
        })
    }

    private fun handleFocusOnPoint(session: IHTTPSession): Response {
        val body = parseBody(session)

        val x = body["x"]?.toFloatOrNull()
            ?: return createErrorResponse("Missing or invalid 'x' parameter")
        val y = body["y"]?.toFloatOrNull()
            ?: return createErrorResponse("Missing or invalid 'y' parameter")

        if (x < 0f || x > 1f || y < 0f || y > 1f) {
            return createErrorResponse("x and y must be 0.0-1.0")
        }

        val success = cameraController.focusOnPoint(x, y)
        return createJsonResponse(JSONObject().apply {
            put("success", success)
            put("x", x)
            put("y", y)
        })
    }

    private fun handleTriggerAutoFocus(): Response {
        val success = cameraController.triggerAutoFocus()
        return createJsonResponse(JSONObject().apply {
            put("success", success)
            put("message", if (success) "Autofocus triggered" else "Failed to trigger autofocus")
        })
    }

    private fun handleGetSupportedModes(): Response {
        val modes = cameraController.getSupportedFocusModes()
        return createJsonResponse(JSONObject().apply {
            put("modes", JSONArray(modes.map { it.name }))
            put("current", cameraController.getStatus().focusMode)
        })
    }

    // ═══════════════════════════════════════════════════════════════════
    // DEVICE OWNER
    // ═══════════════════════════════════════════════════════════════════

    private fun handleDeviceOwnerStatus(): Response {
        val status = DeviceOwnerManager.getStatus(context)
        return createJsonResponse(JSONObject().apply {
            put("isDeviceOwner", status.isDeviceOwner)
            put("isDeviceAdmin", status.isDeviceAdmin)
            put("isKeyguardDisabled", status.isKeyguardDisabled)
            put("canStartFgsFromBackground", status.canStartFgsFromBackground)
            if (!status.isDeviceOwner) {
                put("setupInstructions", DeviceOwnerManager.getSetupInstructions(context))
            }
        })
    }

    private fun handleDeviceReboot(): Response {
        if (!DeviceOwnerManager.isDeviceOwner(context)) {
            return createErrorResponse("Not a Device Owner. Cannot reboot.", Response.Status.FORBIDDEN)
        }

        Thread {
            Thread.sleep(1000)
            DeviceOwnerManager.rebootDevice(context)
        }.start()

        return createJsonResponse(JSONObject().apply {
            put("success", true)
            put("message", "Device will reboot in 1 second")
        })
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun parseBody(session: IHTTPSession): Map<String, String> {
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }

        val postData = files["postData"] ?: session.queryParameterString ?: ""

        return try {
            if (postData.startsWith("{")) {
                val json = JSONObject(postData)
                json.keys().asSequence().associateWith { json.optString(it) }
            } else {
                postData.split("&")
                    .filter { it.contains("=") }
                    .associate {
                        val parts = it.split("=", limit = 2)
                        parts[0] to (parts.getOrNull(1) ?: "")
                    }
            }
        } catch (e: Exception) {
            session.parameters.mapValues { it.value.firstOrNull() ?: "" }
        }
    }

    private fun createJsonResponse(json: JSONObject, status: Response.Status = Response.Status.OK): Response {
        return newFixedLengthResponse(
            status,
            "application/json",
            json.toString()
        ).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    private fun createErrorResponse(message: String, status: Response.Status = Response.Status.BAD_REQUEST): Response {
        return createJsonResponse(JSONObject().apply {
            put("success", false)
            put("error", message)
        }, status)
    }

    override fun stop() {
        activeStreams.values.forEach { it.set(false) }
        activeStreams.clear()
        clientFrameQueues.clear()
        super.stop()
        Log.i(TAG, "Server stopped")
    }
}
