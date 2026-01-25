package com.cameraserver.usb.server

import android.content.Context
import android.util.Log
import com.cameraserver.usb.admin.DeviceOwnerManager
import com.cameraserver.usb.camera.CameraController
import com.cameraserver.usb.config.CameraConfig
import com.cameraserver.usb.config.FocusMode
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

class CameraHttpServer(
    private val context: Context,
    private val port: Int,
    private val cameraController: CameraController,
    private val watchdog: SystemWatchdog? = null
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "CameraHttpServer"
        private const val MJPEG_BOUNDARY = "frame"
    }

    private val activeStreams = ConcurrentHashMap<String, AtomicBoolean>()
    private val streamClientCount = AtomicInteger(0)
    private val clientFrameQueues = ConcurrentHashMap<String, ArrayBlockingQueue<ByteArray>>()
    private val streamLock = Object()

    override fun serve(session: IHTTPSession): Response {
        watchdog?.reportServerResponse()
        return try {
            when {
                session.uri == "/" || session.uri == "/settings" -> handleWebUI()
                session.uri == "/favicon.ico" -> handleFavicon()
                session.uri == "/health" -> handleHealth()
                session.uri == "/status" -> handleStatus()
                session.uri == "/stream/start" && session.method == Method.POST -> handleStreamStart()
                session.uri == "/stream/stop" && session.method == Method.POST -> handleStreamStop()
                session.uri == "/stream/mjpeg" -> handleMjpegStream(session)
                session.uri == "/stream/frame" -> handleSingleFrame()
                session.uri == "/stream/config" && session.method == Method.GET -> handleGetConfig()
                session.uri == "/stream/config" && session.method == Method.POST -> handleSetConfig(session)
                session.uri == "/photo" && session.method == Method.POST -> handlePhoto()
                session.uri == "/photo/quick" && session.method == Method.POST -> handleQuickPhoto()
                session.uri == "/focus/mode" && session.method == Method.POST -> handleSetFocusMode(session)
                session.uri == "/focus/mode" -> handleGetFocusMode()
                session.uri == "/focus/auto" && session.method == Method.POST -> handleTriggerAutoFocus()
                session.uri == "/focus/modes" -> handleGetSupportedModes()
                session.uri == "/device-owner" -> handleDeviceOwnerStatus()
                session.uri == "/device-owner/reboot" && session.method == Method.POST -> handleDeviceReboot()
                else -> errorResponse("Not Found: ${session.uri}", Response.Status.NOT_FOUND)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ${session.uri}: ${e.message}", e)
            errorResponse(e.message ?: "Error")
        }
    }

    private fun handleFavicon(): Response {
        // 1x1 transparent PNG
        val favicon = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78.toByte(), 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
            0x42, 0x60, 0x82.toByte()
        )
        return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(favicon), favicon.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=86400")
        }
    }

    private fun handleWebUI(): Response {
        val html = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Camera Server</title>
    <style>
        *{box-sizing:border-box;margin:0;padding:0}
        body{font-family:system-ui,sans-serif;background:#1a1a2e;color:#eee;padding:16px}
        h1{text-align:center;color:#4ecca3;margin-bottom:16px;font-size:1.5em}
        .grid{display:grid;grid-template-columns:1fr 320px;gap:16px;max-width:1100px;margin:0 auto}
        @media(max-width:800px){.grid{grid-template-columns:1fr}}
        .card{background:#16213e;border-radius:10px;padding:16px;margin-bottom:16px}
        .card h2{color:#4ecca3;font-size:1em;margin-bottom:12px;padding-bottom:8px;border-bottom:1px solid #4ecca3}
        .video-box{background:#000;border-radius:8px;overflow:hidden;position:relative}
        .video-box img{width:100%;display:block}
        .placeholder{aspect-ratio:16/9;display:flex;align-items:center;justify-content:center;color:#666;font-size:14px}
        .row{display:flex;gap:8px;margin-bottom:12px;flex-wrap:wrap}
        .row:last-child{margin-bottom:0}
        label{display:block;color:#888;font-size:12px;margin-bottom:4px}
        select,input{width:100%;padding:8px;border-radius:6px;border:none;background:#2a2a4a;color:#eee;font-size:14px}
        .btn{flex:1;padding:10px;border:none;border-radius:6px;font-weight:600;cursor:pointer;font-size:13px;min-width:70px}
        .btn:disabled{opacity:0.5;cursor:not-allowed}
        .btn-green{background:#4ecca3;color:#1a1a2e}
        .btn-red{background:#e94560;color:#fff}
        .btn-blue{background:#3282b8;color:#fff}
        .btn-gray{background:#2a2a4a;color:#eee}
        .status-row{display:flex;justify-content:space-between;padding:6px 0;border-bottom:1px solid #2a2a4a;font-size:13px}
        .status-row:last-child{border:none}
        .on{color:#4ecca3}.off{color:#e94560}
        .photo-preview img{max-width:100%;border-radius:6px;margin-top:12px}
        .toast{position:fixed;bottom:20px;right:20px;padding:10px 20px;border-radius:6px;background:#4ecca3;color:#1a1a2e;font-weight:500;opacity:0;transition:opacity 0.3s}
        .toast.show{opacity:1}.toast.error{background:#e94560;color:#fff}
        .config-row{display:grid;grid-template-columns:1fr 1fr;gap:8px}
        .config-row.three{grid-template-columns:1fr 1fr 1fr}
    </style>
</head>
<body>
    <h1>Camera Server</h1>
    <div class="grid">
        <div>
            <div class="card">
                <h2>Live Stream</h2>
                <div class="video-box">
                    <div id="ph" class="placeholder">Stream stopped</div>
                    <img id="vid" style="display:none">
                </div>
                <div class="row" style="margin-top:12px">
                    <button class="btn btn-green" id="btnStart" onclick="startStream()">Start</button>
                    <button class="btn btn-red" id="btnStop" onclick="stopStream()" disabled>Stop</button>
                </div>
            </div>
            <div class="card">
                <h2>Stream Settings</h2>
                <div class="config-row" style="margin-bottom:8px">
                    <div><label>Resolution</label>
                        <select id="res" onchange="applyConfig()">
                            <option value="640x480">640x480</option>
                            <option value="1280x720" selected>1280x720</option>
                            <option value="1920x1080">1920x1080</option>
                        </select>
                    </div>
                    <div><label>FPS</label>
                        <select id="fps" onchange="applyConfig()">
                            <option value="15">15</option>
                            <option value="24">24</option>
                            <option value="30" selected>30</option>
                            <option value="60">60</option>
                        </select>
                    </div>
                </div>
                <div><label>JPEG Quality: <span id="qval">80</span>%</label>
                    <input type="range" id="quality" min="30" max="100" value="80" onchange="applyConfig();document.getElementById('qval').textContent=this.value">
                </div>
            </div>
            <div class="card">
                <h2>Photo</h2>
                <div class="row">
                    <button class="btn btn-blue" onclick="takePhoto('quick')">Quick Photo</button>
                    <button class="btn btn-gray" onclick="takePhoto('full')">Full Resolution</button>
                </div>
                <div class="photo-preview" id="photoPreview"></div>
            </div>
        </div>
        <div>
            <div class="card">
                <h2>Status</h2>
                <div class="status-row"><span>Camera</span><span id="sCam" class="off">-</span></div>
                <div class="status-row"><span>Stream</span><span id="sStr" class="off">-</span></div>
                <div class="status-row"><span>Resolution</span><span id="sRes">-</span></div>
                <div class="status-row"><span>FPS</span><span id="sFps">-</span></div>
                <div class="status-row"><span>JPEG Quality</span><span id="sQ">-</span></div>
                <div class="status-row"><span>Photo Resolution</span><span id="sPhoto">-</span></div>
            </div>
            <div class="card">
                <h2>Focus</h2>
                <select id="focusMode" onchange="setFocus(this.value)" style="margin-bottom:8px">
                    <option value="CONTINUOUS">Continuous</option>
                    <option value="AUTO">Auto</option>
                    <option value="MANUAL">Manual</option>
                    <option value="FIXED">Fixed</option>
                    <option value="MACRO">Macro</option>
                </select>
                <button class="btn btn-gray" onclick="triggerFocus()" style="width:100%">Trigger Focus</button>
            </div>
        </div>
    </div>
    <div class="toast" id="toast"></div>
<script>
let statusTimer, reconnectTimer;
const api = (url, method='GET', body=null) => {
    const opts = {method};
    if(body){opts.headers={'Content-Type':'application/json'};opts.body=JSON.stringify(body);}
    return fetch(url, opts).then(r => r.headers.get('content-type')?.includes('json') ? r.json() : r.blob());
};
const toast = (msg, err=false) => {
    const t = document.getElementById('toast');
    t.textContent = msg;
    t.className = 'toast show' + (err ? ' error' : '');
    setTimeout(() => t.className = 'toast', 2000);
};
const updateStatus = async () => {
    try {
        const s = await api('/status');
        document.getElementById('sCam').textContent = s.camera.isOpen ? 'Open' : 'Closed';
        document.getElementById('sCam').className = s.camera.isOpen ? 'on' : 'off';
        document.getElementById('sStr').textContent = s.camera.isStreaming ? 'Active' : 'Stopped';
        document.getElementById('sStr').className = s.camera.isStreaming ? 'on' : 'off';
        document.getElementById('sRes').textContent = s.camera.streamResolution;
        document.getElementById('sFps').textContent = s.camera.targetFps;
        document.getElementById('sQ').textContent = s.camera.jpegQuality + '%';
        document.getElementById('sPhoto').textContent = s.camera.photoResolution;

        // Auto-reconnect stream if it's active but video is hidden
        const vid = document.getElementById('vid');
        if(s.camera.isStreaming && vid.style.display === 'none') {
            vid.src = '/stream/mjpeg?' + Date.now();
            vid.style.display = 'block';
            document.getElementById('ph').style.display = 'none';
            document.getElementById('btnStart').disabled = true;
            document.getElementById('btnStop').disabled = false;
        } else if(!s.camera.isStreaming && vid.style.display !== 'none') {
            vid.style.display = 'none';
            document.getElementById('ph').style.display = 'flex';
            document.getElementById('btnStart').disabled = false;
            document.getElementById('btnStop').disabled = true;
        }
    } catch(e) { console.log('Status error'); }
};
const startStream = async () => {
    try {
        await api('/stream/start', 'POST');
        document.getElementById('vid').src = '/stream/mjpeg?' + Date.now();
        document.getElementById('vid').style.display = 'block';
        document.getElementById('ph').style.display = 'none';
        document.getElementById('btnStart').disabled = true;
        document.getElementById('btnStop').disabled = false;
        toast('Stream started');
    } catch(e) { toast('Failed', true); }
};
const stopStream = async () => {
    try {
        await api('/stream/stop', 'POST');
        document.getElementById('vid').style.display = 'none';
        document.getElementById('ph').style.display = 'flex';
        document.getElementById('btnStart').disabled = false;
        document.getElementById('btnStop').disabled = true;
        toast('Stream stopped');
    } catch(e) { toast('Failed', true); }
};
const applyConfig = async () => {
    const [w,h] = document.getElementById('res').value.split('x').map(Number);
    const fps = parseInt(document.getElementById('fps').value);
    const quality = parseInt(document.getElementById('quality').value);
    try {
        await api('/stream/config', 'POST', {width:w, height:h, fps, quality});
        toast('Settings applied');
        updateStatus();
    } catch(e) { toast('Failed', true); }
};
const setFocus = async (mode) => {
    try { await api('/focus/mode', 'POST', {mode}); toast('Focus: ' + mode); } catch(e) { toast('Failed', true); }
};
const triggerFocus = async () => {
    try { await api('/focus/auto', 'POST'); toast('Focus triggered'); } catch(e) { toast('Failed', true); }
};
const takePhoto = async (type) => {
    try {
        const blob = await api(type === 'quick' ? '/photo/quick' : '/photo', 'POST');
        const url = URL.createObjectURL(blob);
        document.getElementById('photoPreview').innerHTML = '<img src="' + url + '">';
        const a = document.createElement('a'); a.href = url; a.download = 'photo_' + Date.now() + '.jpg'; a.click();
        toast('Photo: ' + (blob.size/1024).toFixed(0) + ' KB');
    } catch(e) { toast('Failed', true); }
};
const loadConfig = async () => {
    try {
        const c = await api('/stream/config');
        document.getElementById('res').value = c.width + 'x' + c.height;
        document.getElementById('fps').value = c.fps;
        document.getElementById('quality').value = c.quality;
        document.getElementById('qval').textContent = c.quality;
    } catch(e) {}
};
loadConfig();
updateStatus();
statusTimer = setInterval(updateStatus, 2000);
</script>
</body>
</html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun handleHealth() = jsonResponse(JSONObject().put("status", "ok"))

    private fun handleStatus(): Response {
        return try {
            val s = cameraController.getStatus()
            jsonResponse(JSONObject().apply {
                put("camera", JSONObject().apply {
                    put("isOpen", s.isOpen)
                    put("isStreaming", s.isStreaming)
                    put("streamResolution", s.streamResolution)
                    put("photoResolution", s.photoResolution)
                    put("targetFps", s.targetFps)
                    put("jpegQuality", s.jpegQuality)
                    put("focusMode", s.focusMode)
                })
                put("server", JSONObject().put("port", port).put("clients", streamClientCount.get()))
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error getting status: ${e.message}", e)
            jsonResponse(JSONObject().apply {
                put("camera", JSONObject().apply {
                    put("isOpen", false)
                    put("isStreaming", false)
                    put("error", e.message)
                })
                put("server", JSONObject().put("port", port).put("clients", 0))
            })
        }
    }

    private fun handleGetConfig() = jsonResponse(JSONObject().apply {
        put("width", CameraConfig.streamWidth)
        put("height", CameraConfig.streamHeight)
        put("fps", CameraConfig.targetFps)
        put("quality", CameraConfig.jpegQuality)
    })

    private fun handleSetConfig(session: IHTTPSession): Response {
        val body = parseBody(session)
        val width = body["width"]?.toIntOrNull() ?: CameraConfig.streamWidth
        val height = body["height"]?.toIntOrNull() ?: CameraConfig.streamHeight
        val fps = body["fps"]?.toIntOrNull() ?: CameraConfig.targetFps
        val quality = body["quality"]?.toIntOrNull() ?: CameraConfig.jpegQuality

        CameraConfig.setStreamConfig(width, height, fps, quality)
        cameraController.restartStreamWithNewSettings()

        return jsonResponse(JSONObject().apply {
            put("success", true)
            put("width", CameraConfig.streamWidth)
            put("height", CameraConfig.streamHeight)
            put("fps", CameraConfig.targetFps)
            put("quality", CameraConfig.jpegQuality)
        })
    }

    private fun handleStreamStart(): Response {
        var success = cameraController.startStream()
        if (!success) { Thread.sleep(2500); success = cameraController.startStream() }
        return if (success) jsonResponse(JSONObject().put("success", true).put("mjpegUrl", "/stream/mjpeg"))
        else errorResponse("Failed to start stream", Response.Status.INTERNAL_ERROR)
    }

    private fun handleStreamStop(): Response {
        cameraController.stopStream()
        watchdog?.reportStreamStopped()
        return jsonResponse(JSONObject().put("success", true))
    }

    private fun handleSingleFrame(): Response {
        if (!cameraController.isStreamActive()) return errorResponse("Stream not active")
        val frame = cameraController.getLastFrame() ?: return errorResponse("No frame")
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(frame), frame.size.toLong())
    }

    private val frameDistributor: (ByteArray) -> Unit = { frame ->
        clientFrameQueues.forEach { (_, q) -> if (!q.offer(frame)) { q.poll(); q.offer(frame) } }
    }

    private fun handleMjpegStream(session: IHTTPSession): Response {
        val clientId = "${session.remoteIpAddress}:${System.currentTimeMillis()}"
        Log.d(TAG, "MJPEG stream request from $clientId")

        val isActive = AtomicBoolean(true)
        activeStreams[clientId] = isActive
        val frameQueue = ArrayBlockingQueue<ByteArray>(5)
        clientFrameQueues[clientId] = frameQueue
        streamClientCount.incrementAndGet()

        synchronized(streamLock) {
            if (!cameraController.isStreamActive()) {
                if (!cameraController.startStream(frameDistributor)) {
                    Log.e(TAG, "Failed to start stream for $clientId")
                    cleanup(clientId)
                    return errorResponse("Failed to start stream")
                }
            } else {
                cameraController.setStreamCallback(frameDistributor)
            }
        }

        val pis = PipedInputStream(524288)
        val pos = PipedOutputStream(pis)

        Thread({
            var framesWritten = 0L
            try {
                val baseTimeout = 1000L / CameraConfig.targetFps * 5
                var consecutiveTimeouts = 0

                while (isActive.get()) {
                    val frame = frameQueue.poll(baseTimeout, TimeUnit.MILLISECONDS)

                    if (frame == null) {
                        consecutiveTimeouts++
                        if (consecutiveTimeouts > 10) {
                            Log.w(TAG, "MJPEG $clientId: Too many timeouts, closing")
                            break
                        }
                        continue
                    }

                    consecutiveTimeouts = 0
                    watchdog?.reportFrameReceived()

                    val header = "--$MJPEG_BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                    pos.write(header.toByteArray())
                    pos.write(frame)
                    pos.write("\r\n".toByteArray())
                    pos.flush()
                    framesWritten++
                }
            } catch (e: java.io.IOException) {
                Log.d(TAG, "MJPEG $clientId: Client disconnected after $framesWritten frames")
            } catch (e: Exception) {
                Log.e(TAG, "MJPEG $clientId: Error after $framesWritten frames: ${e.message}")
            } finally {
                Log.d(TAG, "MJPEG $clientId: Closing stream, wrote $framesWritten frames")
                isActive.set(false)
                cleanup(clientId)
                runCatching { pos.close() }
                runCatching { pis.close() }
            }
        }, "MJPEG-$clientId").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY", pis).apply {
            addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            addHeader("Pragma", "no-cache")
            addHeader("Expires", "0")
            addHeader("Connection", "keep-alive")
        }
    }

    private fun cleanup(clientId: String) {
        clientFrameQueues.remove(clientId)
        activeStreams.remove(clientId)
        val remaining = streamClientCount.decrementAndGet()
        Log.d(TAG, "Cleanup $clientId, remaining clients: $remaining")

        synchronized(streamLock) {
            if (remaining <= 0 && activeStreams.isEmpty()) {
                Log.d(TAG, "No more clients, keeping stream active for reconnection")
                // Don't stop stream immediately - allow time for reconnection
            }
        }
    }

    private fun handlePhoto(): Response {
        return try {
            val data = cameraController.capturePhoto()
            if (data == null) {
                Log.w(TAG, "Photo capture returned null (possibly in progress)")
                return errorResponse("Photo capture failed or already in progress", Response.Status.SERVICE_UNAVAILABLE)
            }
            newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(data), data.size.toLong()).apply {
                addHeader("Content-Disposition", "attachment; filename=\"photo_${System.currentTimeMillis()}.jpg\"")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Photo capture error: ${e.message}", e)
            errorResponse("Photo capture error: ${e.message}", Response.Status.INTERNAL_ERROR)
        }
    }

    private fun handleQuickPhoto(): Response {
        return try {
            val data = cameraController.captureQuickPhoto()
            if (data == null) {
                return errorResponse("No frame available", Response.Status.SERVICE_UNAVAILABLE)
            }
            newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(data), data.size.toLong()).apply {
                addHeader("Content-Disposition", "attachment; filename=\"quick_${System.currentTimeMillis()}.jpg\"")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Quick photo error: ${e.message}", e)
            errorResponse("Quick photo error: ${e.message}", Response.Status.INTERNAL_ERROR)
        }
    }

    private fun handleSetFocusMode(session: IHTTPSession): Response {
        val mode = parseBody(session)["mode"]?.uppercase() ?: return errorResponse("Missing mode")
        val focusMode = runCatching { FocusMode.valueOf(mode) }.getOrNull() ?: return errorResponse("Invalid mode")
        cameraController.setFocusMode(focusMode)
        return jsonResponse(JSONObject().put("success", true).put("mode", mode))
    }

    private fun handleGetFocusMode() = jsonResponse(JSONObject().put("mode", cameraController.getStatus().focusMode))

    private fun handleTriggerAutoFocus(): Response {
        val success = cameraController.triggerAutoFocus()
        return jsonResponse(JSONObject().put("success", success))
    }

    private fun handleGetSupportedModes(): Response {
        val modes = cameraController.getSupportedFocusModes()
        return jsonResponse(JSONObject().put("modes", JSONArray(modes.map { it.name })))
    }

    private fun handleDeviceOwnerStatus(): Response {
        val s = DeviceOwnerManager.getStatus(context)
        return jsonResponse(JSONObject().apply {
            put("isDeviceOwner", s.isDeviceOwner)
            put("isDeviceAdmin", s.isDeviceAdmin)
            put("canStartFgsFromBackground", s.canStartFgsFromBackground)
        })
    }

    private fun handleDeviceReboot(): Response {
        if (!DeviceOwnerManager.isDeviceOwner(context)) return errorResponse("Not device owner", Response.Status.FORBIDDEN)
        Thread { Thread.sleep(1000); DeviceOwnerManager.rebootDevice(context) }.start()
        return jsonResponse(JSONObject().put("success", true))
    }

    private fun parseBody(session: IHTTPSession): Map<String, String> {
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
        val data = files["postData"] ?: ""
        return runCatching {
            if (data.startsWith("{")) JSONObject(data).let { j -> j.keys().asSequence().associateWith { j.optString(it) } }
            else data.split("&").filter { it.contains("=") }.associate { it.split("=", limit = 2).let { p -> p[0] to (p.getOrNull(1) ?: "") } }
        }.getOrDefault(emptyMap())
    }

    private fun jsonResponse(json: JSONObject, status: Response.Status = Response.Status.OK) =
        newFixedLengthResponse(status, "application/json", json.toString()).apply { addHeader("Access-Control-Allow-Origin", "*") }

    private fun errorResponse(msg: String, status: Response.Status = Response.Status.BAD_REQUEST) =
        jsonResponse(JSONObject().put("success", false).put("error", msg), status)

    override fun stop() {
        activeStreams.values.forEach { it.set(false) }
        activeStreams.clear()
        clientFrameQueues.clear()
        super.stop()
    }
}
