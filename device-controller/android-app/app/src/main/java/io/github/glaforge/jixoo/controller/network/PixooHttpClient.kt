package io.github.glaforge.jixoo.controller.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Low-latency HTTP/1.1 Keep-Alive client for Divoom Pixoo 64 devices.
 * Serializes requests per device via Mutex and verifies hardware completion (`error_code == 0`)
 * so the mobile app never sends new images while the LED screen is still processing earlier ones.
 */
object PixooHttpClient {

    private val deviceLocks = ConcurrentHashMap<String, Mutex>()

    init {
        System.setProperty("http.keepAlive", "true")
        System.setProperty("http.maxConnections", "8")
    }

    private fun lockForDevice(ip: String, port: Int): Mutex {
        return deviceLocks.computeIfAbsent("${ip.trim()}:$port") { Mutex() }
    }

    private fun isPixooSuccessResponse(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return try {
            val json = JSONObject(body)
            !json.has("error_code") || json.optInt("error_code", -1) == 0
        } catch (_: Exception) {
            body.contains("\"error_code\":0") || body.contains("\"error_code\": 0") || body.contains("SelectIndex")
        }
    }

    suspend fun postJson(
        ip: String,
        port: Int = 80,
        jsonPayload: String,
        connectTimeoutMs: Int = 3000,
        readTimeoutMs: Int = 5000
    ): String? {
        val cleanIp = ip.trim()
        if (cleanIp.isEmpty()) return null
        return lockForDevice(cleanIp, port).withLock {
            withContext(Dispatchers.IO) {
                try {
                    val bytes = jsonPayload.toByteArray(StandardCharsets.UTF_8)
                    val url = URL("http://$cleanIp:$port/post")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = connectTimeoutMs
                        readTimeout = readTimeoutMs
                        doOutput = true
                        doInput = true
                        useCaches = false
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Connection", "keep-alive")
                        setFixedLengthStreamingMode(bytes.size)
                    }
                    conn.outputStream.use { os ->
                        os.write(bytes)
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream?.use { s ->
                        BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).readText()
                    }
                    if (code in 200..299) body else null
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Verifies device reachability via `{"Command":"Channel/GetIndex"}`.
     */
    suspend fun checkDevice(ip: String, port: Int = 80, timeoutMs: Int = 2000): Boolean {
        val response = postJson(
            ip = ip,
            port = port,
            jsonPayload = """{"Command":"Channel/GetIndex"}""",
            connectTimeoutMs = timeoutMs,
            readTimeoutMs = timeoutMs
        ) ?: return false

        return isPixooSuccessResponse(response)
    }

    /**
     * Ensures the screen is ON (`OnOff: 1`) and switches to Custom Channel (`SelectIndex: 3`) if needed.
     */
    suspend fun initDisplay(ip: String, port: Int = 80) {
        postJson(
            ip = ip,
            port = port,
            jsonPayload = """{"Command":"Channel/OnOffScreen","OnOff":1}""",
            connectTimeoutMs = 3000,
            readTimeoutMs = 3000
        )

        val getIdxResp = postJson(
            ip = ip,
            port = port,
            jsonPayload = """{"Command":"Channel/GetIndex"}""",
            connectTimeoutMs = 2000,
            readTimeoutMs = 2000
        )

        val currentChannel = try {
            if (getIdxResp != null) {
                JSONObject(getIdxResp).optInt("SelectIndex", -1)
            } else -1
        } catch (_: Exception) {
            -1
        }

        if (currentChannel != 3) {
            postJson(
                ip = ip,
                port = port,
                jsonPayload = """{"Command":"Channel/SetIndex","SelectIndex":3}""",
                connectTimeoutMs = 3000,
                readTimeoutMs = 3000
            )
        }
    }

    /**
     * Resets the internal hardware HTTP GIF buffer state machine (`Draw/ResetHttpGifId`).
     */
    suspend fun resetHttpGifId(ip: String, port: Int = 80): Boolean {
        val resp = postJson(
            ip = ip,
            port = port,
            jsonPayload = """{"Command":"Draw/ResetHttpGifId"}""",
            connectTimeoutMs = 3000,
            readTimeoutMs = 5000
        )
        return isPixooSuccessResponse(resp)
    }

    /**
     * Sends a frame via `Draw/SendHttpGif` (`PicNum`, `PicWidth: 64`, `PicOffset`, `PicSpeed`).
     * Waits for hardware acknowledgment (`error_code == 0`) with automatic retry if the ESP32
     * is still busy processing an earlier frame.
     */
    suspend fun sendHttpGifFrame(
        ip: String,
        port: Int = 80,
        picNum: Int = 1,
        picOffset: Int = 0,
        picId: Int,
        picSpeedMs: Int = 1000,
        base64Data: String,
        timeoutMs: Int = 5000,
        maxRetries: Int = 2
    ): Boolean {
        val cleanIp = ip.trim()
        if (cleanIp.isEmpty()) return false
        val payload = """{"Command":"Draw/SendHttpGif","PicNum":$picNum,"PicWidth":64,"PicOffset":$picOffset,"PicID":$picId,"PicSpeed":$picSpeedMs,"PicData":"$base64Data"}"""

        for (attempt in 0..maxRetries) {
            val resp = postJson(
                ip = cleanIp,
                port = port,
                jsonPayload = payload,
                connectTimeoutMs = 3000,
                readTimeoutMs = timeoutMs
            )
            if (isPixooSuccessResponse(resp)) {
                return true
            }
            if (attempt < maxRetries) {
                delay(60L * (attempt + 1))
            }
        }
        return false
    }

    /**
     * Dispatches an operation concurrently to the primary device and (if configured) secondary device
     * in lockstep synchronization.
     */
    suspend fun dispatchLockstep(
        primaryIp: String,
        secondaryIp: String?,
        port: Int,
        operation: suspend (ip: String, port: Int) -> Boolean
    ): Boolean {
        if (secondaryIp.isNullOrBlank()) {
            return operation(primaryIp, port)
        }
        return coroutineScope {
            val primaryDeferred = async { operation(primaryIp, port) }
            val secondaryDeferred = async { operation(secondaryIp, port) }
            val r1 = primaryDeferred.await()
            val r2 = secondaryDeferred.await()
            r1 && r2
        }
    }
}
