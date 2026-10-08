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
 * Reliable HTTP/1.1 Keep-Alive client for Divoom Pixoo 64 devices.
 *
 * Key guarantees:
 * 1. Native HTTP/1.1 & chunked-response compatibility with Divoom ESP32 (`HttpURLConnection`).
 * 2. Per-device `Mutex` serialization so a device never receives overlapping commands while busy.
 * 3. Per-device Circuit Breaker (`BACKOFF_DURATION_MS = 15_000L`) so an unreachable secondary Pixoo
 *    NEVER stalls or fails frame delivery to a healthy primary Pixoo.
 * 4. Exact per-device Wi-Fi round-trip time (`RTT ms`) measurement.
 */
object PixooHttpClient {

    private const val BACKOFF_FAILURE_THRESHOLD = 2
    private const val BACKOFF_DURATION_MS = 15_000L

    private data class DeviceHealthState(
        var consecutiveFailures: Int = 0,
        var backoffUntilEpochMs: Long = 0L,
        var needsReinitOnRecovery: Boolean = false,
        var lastRttMs: Long = 0L
    )

    private val deviceLocks = ConcurrentHashMap<String, Mutex>()
    private val deviceHealth = ConcurrentHashMap<String, DeviceHealthState>()

    init {
        System.setProperty("http.keepAlive", "true")
        System.setProperty("http.maxConnections", "16")
    }

    private fun keyFor(ip: String, port: Int): String = "${ip.trim()}:$port"

    private fun lockForDevice(ip: String, port: Int): Mutex {
        return deviceLocks.computeIfAbsent(keyFor(ip, port)) { Mutex() }
    }

    private fun healthForDevice(ip: String, port: Int): DeviceHealthState {
        return deviceHealth.computeIfAbsent(keyFor(ip, port)) { DeviceHealthState() }
    }

    fun getLastRttMs(ip: String, port: Int = 80): Long {
        return healthForDevice(ip, port).lastRttMs
    }

    fun isDeviceInBackoff(ip: String, port: Int = 80): Boolean {
        val state = healthForDevice(ip, port)
        return System.currentTimeMillis() < state.backoffUntilEpochMs
    }

    fun resetCircuitBreaker(ip: String, port: Int = 80) {
        val state = healthForDevice(ip, port)
        state.consecutiveFailures = 0
        state.backoffUntilEpochMs = 0L
        state.needsReinitOnRecovery = false
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
        connectTimeoutMs: Int = 1500,
        readTimeoutMs: Int = 2500
    ): String? {
        val cleanIp = ip.trim()
        if (cleanIp.isEmpty()) return null
        return lockForDevice(cleanIp, port).withLock {
            withContext(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
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
                        os.flush()
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = stream?.use { s ->
                        BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).readText()
                    }
                    val rtt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    healthForDevice(cleanIp, port).lastRttMs = rtt
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
    suspend fun checkDevice(ip: String, port: Int = 80, timeoutMs: Int = 1500): Boolean {
        val response = postJson(
            ip = ip,
            port = port,
            jsonPayload = """{"Command":"Channel/GetIndex"}""",
            connectTimeoutMs = timeoutMs,
            readTimeoutMs = timeoutMs
        ) ?: return false

        val ok = isPixooSuccessResponse(response)
        if (ok) {
            resetCircuitBreaker(ip, port)
        }
        return ok
    }

    /**
     * Ensures the screen is ON (`OnOff: 1`) and switches to Custom Channel (`SelectIndex: 3`) if needed.
     */
    suspend fun initDisplay(ip: String, port: Int = 80) {
        resetCircuitBreaker(ip, port)
        postJson(
            ip = ip,
            port = port,
            jsonPayload = """{"Command":"Channel/OnOffScreen","OnOff":1}""",
            connectTimeoutMs = 2000,
            readTimeoutMs = 2500
        )

        val getIdxResp = postJson(
            ip = ip,
            port = port,
            jsonPayload = """{"Command":"Channel/GetIndex"}""",
            connectTimeoutMs = 1500,
            readTimeoutMs = 1500
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
                connectTimeoutMs = 2000,
                readTimeoutMs = 2500
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
            connectTimeoutMs = 2000,
            readTimeoutMs = 2500
        )
        return isPixooSuccessResponse(resp)
    }

    /**
     * Sends a frame via `Draw/SendHttpGif` (`PicNum`, `PicWidth: 64`, `PicOffset`, `PicSpeed`).
     * Integrates per-device circuit breaker so an unreachable device skips immediately without stalling
     * healthy devices.
     */
    suspend fun sendHttpGifFrame(
        ip: String,
        port: Int = 80,
        picNum: Int = 1,
        picOffset: Int = 0,
        picId: Int,
        picSpeedMs: Int = 1000,
        base64Data: String,
        timeoutMs: Int = 1800,
        maxRetries: Int = 1
    ): Boolean {
        val cleanIp = ip.trim()
        if (cleanIp.isEmpty()) return false

        val health = healthForDevice(cleanIp, port)
        val now = System.currentTimeMillis()
        if (now < health.backoffUntilEpochMs) {
            return false
        }

        // If recovering from a backoff window, ensure channel 3 & reset PicID first
        if (health.needsReinitOnRecovery) {
            health.needsReinitOnRecovery = false
            val reachable = checkDevice(cleanIp, port, timeoutMs = 800)
            if (!reachable) {
                health.backoffUntilEpochMs = now + BACKOFF_DURATION_MS
                return false
            }
            resetHttpGifId(cleanIp, port)
        }

        val payload = """{"Command":"Draw/SendHttpGif","PicNum":$picNum,"PicWidth":64,"PicOffset":$picOffset,"PicID":$picId,"PicSpeed":$picSpeedMs,"PicData":"$base64Data"}"""

        for (attempt in 0..maxRetries) {
            val resp = postJson(
                ip = cleanIp,
                port = port,
                jsonPayload = payload,
                connectTimeoutMs = 1000,
                readTimeoutMs = timeoutMs
            )
            if (isPixooSuccessResponse(resp)) {
                health.consecutiveFailures = 0
                health.backoffUntilEpochMs = 0L
                return true
            }
            if (attempt < maxRetries) {
                delay(40L)
            }
        }

        health.consecutiveFailures += 1
        if (health.consecutiveFailures >= BACKOFF_FAILURE_THRESHOLD) {
            health.backoffUntilEpochMs = System.currentTimeMillis() + BACKOFF_DURATION_MS
            health.needsReinitOnRecovery = true
        }
        return false
    }

    /**
     * Dispatches an operation concurrently to the primary device and (if configured) secondary device.
     * Returns `true` as long as AT LEAST ONE active device succeeded (`r1 || r2`), so an offline
     * secondary device NEVER blocks or aborts playback on the primary device!
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
            r1 || r2
        }
    }
}
