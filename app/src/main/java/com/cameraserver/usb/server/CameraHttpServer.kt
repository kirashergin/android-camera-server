package com.cameraserver.usb.server

import android.content.Context
import android.util.Log
import com.cameraserver.usb.admin.DeviceOwnerManager
import com.cameraserver.usb.camera.CameraController
import com.cameraserver.usb.config.CameraConfig
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
 * HTTP сервер для управления камерой
 *
 * Предоставляет REST API и веб-интерфейс для:
 * - Управления MJPEG стримом
 * - Захвата фото
 * - Настройки камеры (разрешение, FPS, качество)
 * - Управления фокусом
 *
 * Использует NanoHTTPD для обработки HTTP запросов.
 */
class CameraHttpServer(
    private val context: Context,
    private val port: Int,
    private val cameraController: CameraController,
    private val watchdog: SystemWatchdog? = null
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "CameraHttpServer"
    }

    // Активные MJPEG соединения
    private val activeStreams = ConcurrentHashMap<String, AtomicBoolean>()
    private val streamClientCount = AtomicInteger(0)
    private val clientFrameQueues = ConcurrentHashMap<String, ArrayBlockingQueue<ByteArray>>()
    private val streamLock = Object()

    // ══════════════════════════════════════════════════════════════════
    // МАРШРУТИЗАЦИЯ
    // ══════════════════════════════════════════════════════════════════

    override fun serve(session: IHTTPSession): Response {
        watchdog?.reportServerResponse()

        // Обработка CORS preflight запросов
        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                addHeader("Access-Control-Allow-Headers", "Content-Type")
                addHeader("Access-Control-Max-Age", "86400")
            }
        }

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
                session.uri == "/device-owner" -> handleDeviceOwnerStatus()
                session.uri == "/device-owner/reboot" && session.method == Method.POST -> handleDeviceReboot()
                else -> errorResponse("Не найдено: ${session.uri}", Response.Status.NOT_FOUND)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки ${session.method} ${session.uri}: ${e.message}", e)
            errorResponse("${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ВЕБ-ИНТЕРФЕЙС
    // ══════════════════════════════════════════════════════════════════

    /**
     * Генерирует favicon (1x1 прозрачный PNG)
     */
    private fun handleFavicon(): Response {
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

        return newFixedLengthResponse(
            Response.Status.OK,
            "image/png",
            ByteArrayInputStream(favicon),
            favicon.size.toLong()
        ).apply {
            addHeader("Cache-Control", "max-age=86400")
        }
    }

    /**
     * Генерирует HTML веб-интерфейс
     */
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
        .video-box img{width:100%;display:block;transform:scaleX(-1)}
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
        .photo-preview img{max-width:100%;border-radius:6px;margin-top:12px;transform:scaleX(-1)}
        .toast{position:fixed;bottom:20px;right:20px;padding:10px 20px;border-radius:6px;background:#4ecca3;color:#1a1a2e;font-weight:500;opacity:0;transition:opacity 0.3s}
        .toast.show{opacity:1}.toast.error{background:#e94560;color:#fff}
        .config-row{display:grid;grid-template-columns:1fr 1fr;gap:8px}
    </style>
</head>
<body>
    <h1>Camera Server</h1>
    <div class="grid">
        <div>
            <div class="card">
                <h2>Live Stream</h2>
                <div class="video-box">
                    <div id="ph" class="placeholder">Connecting to camera server...</div>
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
        </div>
    </div>
    <div class="toast" id="toast"></div>
<script>
let statusTimer;
let connectionReady = false;

// API с retry логикой для надежности через ADB
const api = async (url, method='GET', body=null, retries=3) => {
    const opts = {
        method,
        timeout: 10000 // 10 сек таймаут для ADB
    };
    if(body){
        opts.headers={'Content-Type':'application/json'};
        opts.body=JSON.stringify(body);
    }

    for(let i = 0; i < retries; i++) {
        try {
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), opts.timeout);

            const r = await fetch(url, {...opts, signal: controller.signal});
            clearTimeout(timeoutId);

            if(!r.ok) {
                // Обработка 429 для фото
                if(r.status === 429) {
                    const data = await r.json();
                    throw new Error(data.message || 'Too many requests');
                }
                throw new Error(`HTTP ${'$'}{r.status}`);
            }

            connectionReady = true;
            return r.headers.get('content-type')?.includes('json') ? r.json() : r.blob();
        } catch(e) {
            if(i === retries - 1) throw e;
            // Экспоненциальный backoff: 500ms, 1s, 2s
            await new Promise(resolve => setTimeout(resolve, 500 * Math.pow(2, i)));
        }
    }
};

const toast = (msg, err=false) => {
    const t = document.getElementById('toast');
    t.textContent = msg;
    t.className = 'toast show' + (err ? ' error' : '');
    setTimeout(() => t.className = 'toast', 2000);
};
const updateStatus = async () => {
    try {
        const s = await api('/status', 'GET', null, 2); // 2 retry для статуса

        // Обновление UI статуса
        document.getElementById('sCam').textContent = s.camera.isOpen ? 'Open' : 'Closed';
        document.getElementById('sCam').className = s.camera.isOpen ? 'on' : 'off';
        document.getElementById('sStr').textContent = s.camera.isStreaming ? 'Active' : 'Stopped';
        document.getElementById('sStr').className = s.camera.isStreaming ? 'on' : 'off';
        document.getElementById('sRes').textContent = s.camera.streamResolution;
        document.getElementById('sFps').textContent = s.camera.targetFps;
        document.getElementById('sQ').textContent = s.camera.jpegQuality + '%';
        document.getElementById('sPhoto').textContent = s.camera.photoResolution;

        const vid = document.getElementById('vid');
        const ph = document.getElementById('ph');

        if(s.camera.isStreaming && vid.style.display === 'none') {
            vid.src = '/stream/mjpeg?' + Date.now();
            vid.style.display = 'block';
            ph.style.display = 'none';
            document.getElementById('btnStart').disabled = true;
            document.getElementById('btnStop').disabled = false;
        } else if(!s.camera.isStreaming && vid.style.display !== 'none') {
            vid.style.display = 'none';
            ph.style.display = 'flex';
            document.getElementById('btnStart').disabled = false;
            document.getElementById('btnStop').disabled = true;
        }

        // Убираем индикатор загрузки при первом успешном соединении
        if(!connectionReady) {
            connectionReady = true;
            ph.textContent = s.camera.isStreaming ? '' : 'Stream stopped';
        }
    } catch(e) {
        console.error('Status error:', e);
        // Показываем статус подключения
        const ph = document.getElementById('ph');
        if(!connectionReady) {
            ph.textContent = 'Connecting...';
        }
    }
};
const startStream = async () => {
    try {
        toast('Starting stream...');
        await api('/stream/start', 'POST');
        // Небольшая задержка перед загрузкой MJPEG
        await new Promise(r => setTimeout(r, 300));
        document.getElementById('vid').src = '/stream/mjpeg?' + Date.now();
        document.getElementById('vid').style.display = 'block';
        document.getElementById('ph').style.display = 'none';
        document.getElementById('btnStart').disabled = true;
        document.getElementById('btnStop').disabled = false;
        toast('Stream started');
    } catch(e) {
        toast('Failed to start: ' + (e.message || 'unknown'), true);
        console.error('Stream start error:', e);
    }
};
const stopStream = async () => {
    try {
        await api('/stream/stop', 'POST');
        document.getElementById('vid').style.display = 'none';
        document.getElementById('ph').style.display = 'flex';
        document.getElementById('ph').textContent = 'Stream stopped';
        document.getElementById('btnStart').disabled = false;
        document.getElementById('btnStop').disabled = true;
        toast('Stream stopped');
    } catch(e) {
        toast('Failed to stop: ' + (e.message || 'unknown'), true);
    }
};
const applyConfig = async () => {
    const [w,h] = document.getElementById('res').value.split('x').map(Number);
    const fps = parseInt(document.getElementById('fps').value);
    const quality = parseInt(document.getElementById('quality').value);
    try {
        await api('/stream/config', 'POST', {width:w, height:h, fps, quality});
        toast('Settings applied');
        setTimeout(updateStatus, 500);
    } catch(e) {
        toast('Failed to apply: ' + (e.message || 'unknown'), true);
    }
};
const takePhoto = async (type) => {
    try {
        const blob = await api(type === 'quick' ? '/photo/quick' : '/photo', 'POST', null, 1);
        const url = URL.createObjectURL(blob);
        document.getElementById('photoPreview').innerHTML = '<img src="' + url + '">';
        const a = document.createElement('a'); a.href = url; a.download = 'photo_' + Date.now() + '.jpg'; a.click();
        toast('Photo: ' + (blob.size/1024).toFixed(0) + ' KB');
    } catch(e) {
        const msg = e.message || 'Failed';
        toast(msg.includes('progress') ? 'Photo in progress, wait...' : 'Failed: ' + msg, true);
    }
};
const loadConfig = async () => {
    try {
        const c = await api('/stream/config', 'GET', null, 2);
        document.getElementById('res').value = c.width + 'x' + c.height;
        document.getElementById('fps').value = c.fps;
        document.getElementById('quality').value = c.quality;
        document.getElementById('qval').textContent = c.quality;
    } catch(e) {
        console.error('Config load error:', e);
    }
};

// Инициализация с задержкой для стабильности ADB
const init = async () => {
    document.getElementById('ph').textContent = 'Connecting...';

    // Даем серверу время на инициализацию
    await new Promise(r => setTimeout(r, 500));

    // Загружаем конфиг и статус параллельно
    await Promise.all([loadConfig(), updateStatus()]);

    // Запускаем периодическое обновление статуса
    statusTimer = setInterval(updateStatus, 2000);
};

init();
</script>
</body>
</html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    // ══════════════════════════════════════════════════════════════════
    // API - СТАТУС
    // ══════════════════════════════════════════════════════════════════

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
                })
                put("server", JSONObject().put("port", port).put("clients", streamClientCount.get()))
            })
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения статуса: ${e.message}", e)
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

    // ══════════════════════════════════════════════════════════════════
    // API - КОНФИГУРАЦИЯ
    // ══════════════════════════════════════════════════════════════════

    private fun handleGetConfig() = jsonResponse(JSONObject().apply {
        put("width", CameraConfig.streamWidth)
        put("height", CameraConfig.streamHeight)
        put("fps", CameraConfig.targetFps)
        put("quality", CameraConfig.jpegQuality)
    })

    private fun handleSetConfig(session: IHTTPSession): Response {
        val body = try {
            parseBody(session)
        } catch (e: IllegalArgumentException) {
            return errorResponse(e.message ?: "Некорректный запрос")
        }

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

    // ══════════════════════════════════════════════════════════════════
    // API - СТРИМ
    // ══════════════════════════════════════════════════════════════════

    private fun handleStreamStart(): Response {
        var success = cameraController.startStream()

        if (!success) {
            Thread.sleep(CameraConfig.STREAM_START_RETRY_DELAY_MS)
            success = cameraController.startStream()
        }

        return if (success) {
            jsonResponse(JSONObject().put("success", true).put("mjpegUrl", "/stream/mjpeg"))
        } else {
            errorResponse("Не удалось запустить стрим", Response.Status.INTERNAL_ERROR)
        }
    }

    private fun handleStreamStop(): Response {
        cameraController.stopStream()
        watchdog?.reportStreamStopped()
        return jsonResponse(JSONObject().put("success", true))
    }

    private fun handleSingleFrame(): Response {
        if (!cameraController.isStreamActive()) {
            return errorResponse("Стрим не активен")
        }

        val frame = cameraController.getLastFrame()
            ?: return errorResponse("Нет кадра")

        return newFixedLengthResponse(
            Response.Status.OK,
            "image/jpeg",
            ByteArrayInputStream(frame),
            frame.size.toLong()
        ).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    /**
     * Распределяет кадры по очередям клиентов
     */
    private val frameDistributor: (ByteArray) -> Unit = { frame ->
        clientFrameQueues.forEach { (_, q) ->
            if (!q.offer(frame)) {
                q.poll()
                q.offer(frame)
            }
        }
    }

    /**
     * Обрабатывает MJPEG стрим
     */
    private fun handleMjpegStream(session: IHTTPSession): Response {
        val clientId = "${session.remoteIpAddress}:${System.currentTimeMillis()}"
        Log.d(TAG, "MJPEG запрос от $clientId")

        val isActive = AtomicBoolean(true)
        activeStreams[clientId] = isActive
        val frameQueue = ArrayBlockingQueue<ByteArray>(CameraConfig.CLIENT_FRAME_QUEUE_SIZE)
        clientFrameQueues[clientId] = frameQueue
        streamClientCount.incrementAndGet()

        synchronized(streamLock) {
            if (!cameraController.isStreamActive()) {
                if (!cameraController.startStream(frameDistributor)) {
                    Log.e(TAG, "Не удалось запустить стрим для $clientId")
                    cleanup(clientId)
                    return errorResponse("Не удалось запустить стрим")
                }
            } else {
                cameraController.setStreamCallback(frameDistributor)
            }
        }

        val pis = PipedInputStream(CameraConfig.MJPEG_PIPE_BUFFER_SIZE)
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
                        if (consecutiveTimeouts > CameraConfig.MJPEG_MAX_CONSECUTIVE_TIMEOUTS) {
                            Log.w(TAG, "MJPEG $clientId: слишком много таймаутов, закрываем")
                            break
                        }
                        continue
                    }

                    consecutiveTimeouts = 0
                    watchdog?.reportFrameReceived()

                    val header = "--${CameraConfig.MJPEG_BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                    pos.write(header.toByteArray())
                    pos.write(frame)
                    pos.write("\r\n".toByteArray())
                    pos.flush()
                    framesWritten++
                }
            } catch (e: java.io.IOException) {
                Log.d(TAG, "MJPEG $clientId: клиент отключился после $framesWritten кадров")
            } catch (e: Exception) {
                Log.e(TAG, "MJPEG $clientId: ошибка после $framesWritten кадров: ${e.message}")
            } finally {
                Log.d(TAG, "MJPEG $clientId: закрытие стрима, записано $framesWritten кадров")
                isActive.set(false)
                cleanup(clientId)
                runCatching { pos.close() }
                runCatching { pis.close() }
            }
        }, "MJPEG-$clientId").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=${CameraConfig.MJPEG_BOUNDARY}",
            pis
        ).apply {
            addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            addHeader("Pragma", "no-cache")
            addHeader("Expires", "0")
            addHeader("Connection", "keep-alive")
        }
    }

    /**
     * Очищает ресурсы клиента
     */
    private fun cleanup(clientId: String) {
        clientFrameQueues.remove(clientId)
        activeStreams.remove(clientId)
        val remaining = streamClientCount.decrementAndGet()
        Log.d(TAG, "Очистка $clientId, осталось клиентов: $remaining")

        synchronized(streamLock) {
            if (remaining <= 0 && activeStreams.isEmpty()) {
                Log.d(TAG, "Нет клиентов, стрим остаётся активным для переподключения")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // API - ФОТО
    // ══════════════════════════════════════════════════════════════════

    private fun handlePhoto(): Response {
        return try {
            val data = cameraController.capturePhoto()

            if (data == null) {
                Log.w(TAG, "Фото вернуло null (захват уже выполняется)")
                return newFixedLengthResponse(
                    Response.Status.TOO_MANY_REQUESTS,
                    "application/json",
                    JSONObject().apply {
                        put("success", false)
                        put("error", "Photo capture already in progress")
                        put("message", "Снимок уже делается, дождитесь завершения")
                    }.toString()
                ).apply {
                    addHeader("Retry-After", "2")
                    addHeader("Access-Control-Allow-Origin", "*")
                }
            }

            newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                ByteArrayInputStream(data),
                data.size.toLong()
            ).apply {
                addHeader("Content-Disposition", "attachment; filename=\"photo_${System.currentTimeMillis()}.jpg\"")
                addHeader("Access-Control-Allow-Origin", "*")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка захвата фото: ${e.message}", e)
            errorResponse("Ошибка захвата фото: ${e.message}", Response.Status.INTERNAL_ERROR)
        }
    }

    private fun handleQuickPhoto(): Response {
        return try {
            val data = cameraController.captureQuickPhoto()

            if (data == null) {
                return errorResponse("Нет доступного кадра", Response.Status.SERVICE_UNAVAILABLE)
            }

            newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                ByteArrayInputStream(data),
                data.size.toLong()
            ).apply {
                addHeader("Content-Disposition", "attachment; filename=\"quick_${System.currentTimeMillis()}.jpg\"")
                addHeader("Access-Control-Allow-Origin", "*")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка быстрого фото: ${e.message}", e)
            errorResponse("Ошибка быстрого фото: ${e.message}", Response.Status.INTERNAL_ERROR)
        }
    }


    // ══════════════════════════════════════════════════════════════════
    // API - DEVICE OWNER
    // ══════════════════════════════════════════════════════════════════

    private fun handleDeviceOwnerStatus(): Response {
        val s = DeviceOwnerManager.getStatus(context)
        return jsonResponse(JSONObject().apply {
            put("isDeviceOwner", s.isDeviceOwner)
            put("isDeviceAdmin", s.isDeviceAdmin)
            put("canStartFgsFromBackground", s.canStartFgsFromBackground)
        })
    }

    private fun handleDeviceReboot(): Response {
        if (!DeviceOwnerManager.isDeviceOwner(context)) {
            return errorResponse("Не Device Owner", Response.Status.FORBIDDEN)
        }

        Thread {
            Thread.sleep(CameraConfig.DEVICE_REBOOT_DELAY_MS)
            DeviceOwnerManager.rebootDevice(context)
        }.start()

        return jsonResponse(JSONObject().put("success", true))
    }

    // ══════════════════════════════════════════════════════════════════
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ══════════════════════════════════════════════════════════════════

    /**
     * Парсит тело запроса (JSON или form-data)
     *
     * @throws IllegalArgumentException если JSON некорректный
     */
    private fun parseBody(session: IHTTPSession): Map<String, String> {
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
        val data = files["postData"] ?: ""

        if (data.isEmpty()) {
            return emptyMap()
        }

        return if (data.trimStart().startsWith("{")) {
            try {
                JSONObject(data).let { j ->
                    j.keys().asSequence().associateWith { j.optString(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Некорректный JSON: ${e.message}")
                throw IllegalArgumentException("Некорректный JSON: ${e.message}")
            }
        } else {
            data.split("&")
                .filter { it.contains("=") }
                .associate {
                    it.split("=", limit = 2).let { p ->
                        p[0] to (p.getOrNull(1) ?: "")
                    }
                }
        }
    }

    private fun jsonResponse(json: JSONObject, status: Response.Status = Response.Status.OK) =
        newFixedLengthResponse(status, "application/json", json.toString()).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }

    private fun errorResponse(msg: String, status: Response.Status = Response.Status.BAD_REQUEST) =
        jsonResponse(JSONObject().put("success", false).put("error", msg), status)

    override fun stop() {
        activeStreams.values.forEach { it.set(false) }
        activeStreams.clear()
        clientFrameQueues.clear()
        super.stop()
    }
}
