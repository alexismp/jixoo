package io.github.glaforge.jixoo.controller.ui

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.glaforge.jixoo.controller.discovery.DiscoveredPixooDevice
import io.github.glaforge.jixoo.controller.discovery.PixooDiscovery
import io.github.glaforge.jixoo.controller.gcs.GcsCacheManager
import io.github.glaforge.jixoo.controller.image.ImageFrameProcessor
import io.github.glaforge.jixoo.controller.image.ProcessedMedia
import io.github.glaforge.jixoo.controller.model.ImageSourceType
import io.github.glaforge.jixoo.controller.model.SlideshowConfig
import io.github.glaforge.jixoo.controller.model.SlideshowPreferences
import io.github.glaforge.jixoo.controller.network.PixooHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private data class SlideCandidate(
    val id: String,
    val filename: String,
    val readBytes: suspend () -> ByteArray?
)

data class SlideshowUiState(
    val config: SlideshowConfig = SlideshowConfig(),
    val isRunning: Boolean = false,
    val isDiscovering: Boolean = false,
    val isRefreshingCache: Boolean = false,
    val isCheckingDevices: Boolean = false,
    val primaryReachable: Boolean? = null,
    val secondaryReachable: Boolean? = null,
    val discoveredDevices: List<DiscoveredPixooDevice> = emptyList(),
    val deviceGoogleAccounts: List<String> = emptyList(),
    val resolvedGcsAccountLabel: String = "",
    val cachedFileCount: Int = 0,
    val lastCacheRefreshTime: String = "Never",
    val currentSlideFilename: String = "Idle",
    val currentSlideIndex: Int = 0,
    val totalSlidesCount: Int = 0,
    val currentAnimationStatus: String = "Ready to stream",
    val activeTargetSummary: String = "",
    val previewPixels64: IntArray? = null,
    val logs: List<String> = emptyList()
)

class SlideshowViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = SlideshowPreferences(application)
    private val _uiState = MutableStateFlow(
        SlideshowUiState(
            config = prefs.load(),
            deviceGoogleAccounts = GcsCacheManager.getDeviceGoogleAccounts(application),
            cachedFileCount = GcsCacheManager.listValidCachedFiles(
                GcsCacheManager.getCacheDir(application)
            ).size
        )
    )
    val uiState: StateFlow<SlideshowUiState> = _uiState.asStateFlow()

    private var slideshowJob: Job? = null
    private var skipSignal = CompletableDeferred<Unit>()
    private var picIdCounter: Int = 1

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun appendLog(message: String) {
        val ts = timeFormat.format(Date())
        val line = if (message.startsWith("=") || message.startsWith("-")) {
            message
        } else {
            "[$ts] $message"
        }
        _uiState.update { state ->
            val updated = (state.logs + line).takeLast(250)
            state.copy(logs = updated)
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun updateConfig(transform: (SlideshowConfig) -> SlideshowConfig) {
        _uiState.update { state ->
            val rawUpdated = transform(state.config)
            // Support comma-separated IPs (e.g. "192.168.1.10,192.168.1.11") just like simple-control.sh
            val (splitPrimary, splitSecondary) = SlideshowConfig.splitCommaSeparatedIps(rawUpdated.primaryIp)
            val normalized = if (splitSecondary != null) {
                rawUpdated.copy(
                    primaryIp = splitPrimary,
                    secondaryIpEnabled = true,
                    secondaryIp = splitSecondary
                )
            } else {
                rawUpdated
            }
            prefs.save(normalized)
            state.copy(config = normalized)
        }
    }

    fun refreshDeviceAccounts() {
        val accounts = GcsCacheManager.getDeviceGoogleAccounts(getApplication())
        _uiState.update { it.copy(deviceGoogleAccounts = accounts) }
    }

    fun discoverDevices() {
        if (_uiState.value.isDiscovering) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscovering = true) }
            val port = _uiState.value.config.port
            appendLog("Searching local network for Pixoo 64 via UDP/ARP/HTTP probe...")
            val found = PixooDiscovery.discoverDevices(port = port, timeoutMs = 3000)
            if (found.isNotEmpty()) {
                appendLog("Found ${found.size} Pixoo device(s): ${found.joinToString { "${it.ipAddress} (${it.deviceName})" }}")
                _uiState.update { state ->
                    val updatedConfig = if (state.config.primaryIp.isBlank()) {
                        state.config.copy(primaryIp = found.first().ipAddress).also { prefs.save(it) }
                    } else {
                        state.config
                    }
                    state.copy(
                        isDiscovering = false,
                        discoveredDevices = found,
                        config = updatedConfig
                    )
                }
            } else {
                appendLog("No Pixoo devices discovered on local subnet.")
                _uiState.update { it.copy(isDiscovering = false, discoveredDevices = emptyList()) }
            }
        }
    }

    fun checkDevicesConnectivity() {
        if (_uiState.value.isCheckingDevices) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingDevices = true, primaryReachable = null, secondaryReachable = null) }
            val cfg = _uiState.value.config
            val primaryIp = cfg.primaryIp.ifBlank { SlideshowConfig.DEFAULT_FALLBACK_IP }
            val secondaryIp = cfg.effectiveSecondaryIp

            val p1 = async { PixooHttpClient.checkDevice(primaryIp, cfg.port) }
            val p2 = secondaryIp?.let { ip2 -> async { PixooHttpClient.checkDevice(ip2, cfg.port) } }

            val r1 = p1.await()
            val r2 = p2?.await()

            appendLog(
                "Connectivity check: Primary ($primaryIp:${cfg.port}) = ${if (r1) "ONLINE" else "UNREACHABLE"}" +
                    (if (secondaryIp != null) ", Secondary ($secondaryIp:${cfg.port}) = ${if (r2 == true) "ONLINE" else "UNREACHABLE"}" else "")
            )

            _uiState.update {
                it.copy(
                    isCheckingDevices = false,
                    primaryReachable = r1,
                    secondaryReachable = r2
                )
            }
        }
    }

    fun initDisplaysManual() {
        viewModelScope.launch {
            val cfg = _uiState.value.config
            val primaryIp = cfg.primaryIp.ifBlank { SlideshowConfig.DEFAULT_FALLBACK_IP }
            val secondaryIp = cfg.effectiveSecondaryIp
            appendLog("Initializing Pixoo display channel(s) (Screen ON + Channel 3)...")
            coroutineScope {
                launch { PixooHttpClient.initDisplay(primaryIp, cfg.port) }
                if (!secondaryIp.isNullOrBlank()) {
                    launch { PixooHttpClient.initDisplay(secondaryIp, cfg.port) }
                }
            }
            appendLog("Initialized display channel(s).")
        }
    }

    fun resolveGcsAccountNow(activity: Activity?) {
        viewModelScope.launch {
            val cfg = _uiState.value.config
            appendLog("Resolving GCS access for ${cfg.gcsBucketUrl}...")
            val resolved = GcsCacheManager.resolveGcsAccount(
                context = getApplication(),
                gcsUrl = cfg.gcsBucketUrl,
                preferredAccount = cfg.gcsAccount,
                manualBearerToken = cfg.gcsBearerToken,
                activity = activity
            )
            if (resolved != null) {
                appendLog("Resolved GCS access via: ${resolved.accountLabel}")
                _uiState.update { it.copy(resolvedGcsAccountLabel = resolved.accountLabel) }
            } else {
                appendLog("Error: Could not access ${cfg.gcsBucketUrl} (403/401 or invalid bucket). Select an authorized Google account or provide a Bearer token.")
                _uiState.update { it.copy(resolvedGcsAccountLabel = "Unauthorized / Failed") }
            }
        }
    }

    fun refreshCacheManual(activity: Activity?) {
        if (_uiState.value.isRefreshingCache) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingCache = true) }
            val cfg = _uiState.value.config
            val resolved = GcsCacheManager.resolveGcsAccount(
                context = getApplication(),
                gcsUrl = cfg.gcsBucketUrl,
                preferredAccount = cfg.gcsAccount,
                manualBearerToken = cfg.gcsBearerToken,
                activity = activity
            )
            appendLog("Refreshing cache from ${cfg.gcsBucketUrl}...")
            val files = GcsCacheManager.refreshCache(
                context = getApplication(),
                gcsUrl = cfg.gcsBucketUrl,
                bearerToken = resolved?.bearerToken,
                onLog = ::appendLog,
                onProgress = { status ->
                    _uiState.update { it.copy(currentAnimationStatus = status) }
                }
            )
            val nowStr = timeFormat.format(Date())
            appendLog("Cache refresh complete (${files.size} valid image/GIF files).")
            _uiState.update {
                it.copy(
                    isRefreshingCache = false,
                    currentAnimationStatus = "Cache ready (${files.size} files)",
                    cachedFileCount = files.size,
                    lastCacheRefreshTime = nowStr,
                    resolvedGcsAccountLabel = resolved?.accountLabel ?: it.resolvedGcsAccountLabel
                )
            }
        }
    }

    fun skipToNextSlide() {
        val current = skipSignal
        if (!current.isCompleted) {
            current.complete(Unit)
        }
    }

    fun toggleSlideshow(activity: Activity?) {
        if (_uiState.value.isRunning) {
            stopSlideshow()
        } else {
            startSlideshow(activity)
        }
    }

    fun stopSlideshow() {
        slideshowJob?.cancel()
        slideshowJob = null
        skipSignal.complete(Unit)
        appendLog("Slideshow stopped.")
        _uiState.update {
            it.copy(
                isRunning = false,
                currentAnimationStatus = "Stopped"
            )
        }
    }

    fun startSlideshow(activity: Activity?) {
        if (_uiState.value.isRunning) return
        slideshowJob = viewModelScope.launch(Dispatchers.Default) {
            _uiState.update {
                it.copy(
                    isRunning = true,
                    currentAnimationStatus = "Starting slideshow..."
                )
            }

            try {
                runSlideshowLoop(activity)
            } catch (_: CancellationException) {
                // Normal cancellation
            } catch (e: Exception) {
                appendLog("Error: ${e.message ?: "Unexpected failure"}")
            } finally {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        currentAnimationStatus = "Stopped"
                    )
                }
            }
        }
    }

    private suspend fun runSlideshowLoop(activity: Activity?) {
        val appContext = getApplication<Application>()
        var cfg = _uiState.value.config
        val port = cfg.port

        // 1. Determine and verify primary target IP (lines 210-227 of simple-control.sh)
        var primaryIp = cfg.primaryIp.trim()
        if (primaryIp.isEmpty()) {
            appendLog("Searching local network for Pixoo 64...")
            val found = PixooDiscovery.discoverDevices(port = port, timeoutMs = 3000)
            if (found.isNotEmpty()) {
                primaryIp = found.first().ipAddress
                appendLog("Found Pixoo at $primaryIp!")
                updateConfig { it.copy(primaryIp = primaryIp) }
            } else {
                primaryIp = SlideshowConfig.DEFAULT_FALLBACK_IP
                appendLog("Warning: No Pixoo device auto-discovered. Falling back to default IP $primaryIp.")
            }
        }

        val primaryOk = PixooHttpClient.checkDevice(primaryIp, port)
        if (!primaryOk) {
            appendLog("Warning: Primary Pixoo at $primaryIp:$port is not responding.")
            val found = PixooDiscovery.discoverDevices(port = port, timeoutMs = 3000)
            if (found.isNotEmpty() && PixooHttpClient.checkDevice(found.first().ipAddress, port)) {
                primaryIp = found.first().ipAddress
                appendLog("Reconnected to Pixoo at $primaryIp!")
                updateConfig { it.copy(primaryIp = primaryIp) }
            } else {
                appendLog("Error: Could not connect to Pixoo 64 at $primaryIp:$port. Please verify device is powered on and connected to local Wi-Fi.")
                _uiState.update { it.copy(primaryReachable = false) }
                return
            }
        }
        _uiState.update { it.copy(primaryReachable = true) }

        // 2. Verify secondary device IP if configured (lines 229-236 of simple-control.sh)
        val secondaryIp = cfg.effectiveSecondaryIp
        if (!secondaryIp.isNullOrBlank()) {
            val secOk = PixooHttpClient.checkDevice(secondaryIp, port)
            _uiState.update { it.copy(secondaryReachable = secOk) }
            if (secOk) {
                appendLog("Connected to Secondary Pixoo at $secondaryIp!")
            } else {
                appendLog("Warning: Secondary Pixoo at $secondaryIp:$port is not responding (will still attempt sync).")
            }
        }

        // 3. If using GCS, resolve authorized account (lines 296-310 of simple-control.sh)
        var activeBearerToken: String? = null
        var resolvedAccountLabel = ""
        if (cfg.sourceType == ImageSourceType.GCS_BUCKET) {
            val resolved = GcsCacheManager.resolveGcsAccount(
                context = appContext,
                gcsUrl = cfg.gcsBucketUrl,
                preferredAccount = cfg.gcsAccount,
                manualBearerToken = cfg.gcsBearerToken,
                activity = activity
            )
            if (resolved != null) {
                activeBearerToken = resolved.bearerToken
                resolvedAccountLabel = resolved.accountLabel
                _uiState.update { it.copy(resolvedGcsAccountLabel = resolvedAccountLabel) }
            } else {
                appendLog("Error: Cannot access GCS bucket ${cfg.gcsBucketUrl} with any authenticated Google account.")
                appendLog("Please select an authorized account or provide a Bearer token.")
                return
            }
        }

        // 4. Check initial cache status (cache is persisted to avoid re-downloading files)
        val initialCached = GcsCacheManager.listValidCachedFiles(GcsCacheManager.getCacheDir(appContext))
        _uiState.update { it.copy(cachedFileCount = initialCached.size) }

        // 5. Startup summary banner
        val targetSummary = if (!secondaryIp.isNullOrBlank()) {
            "http://$primaryIp:$port & http://$secondaryIp:$port (Synchronized)"
        } else {
            "http://$primaryIp:$port"
        }
        _uiState.update { it.copy(activeTargetSummary = targetSummary) }

        appendLog("==============================================")
        appendLog(" Starting Pixoo 64 Slideshow")
        appendLog(" Target Device : $targetSummary")
        val sourceLabel = if (cfg.sourceType == ImageSourceType.GCS_BUCKET) cfg.gcsBucketUrl else cfg.localDirectoryUri
        appendLog(" Image Source  : $sourceLabel")
        if (cfg.sourceType == ImageSourceType.GCS_BUCKET) {
            if (resolvedAccountLabel.isNotEmpty()) {
                appendLog(" GCS Account   : $resolvedAccountLabel")
            }
            appendLog(" Cache Refresh : Every ${cfg.refreshIntervalSeconds}s")
        }
        appendLog(" Slide Interval: ${cfg.slideIntervalSeconds}s")
        appendLog(" Cache Status  : Persistent (${initialCached.size} local files)")
        appendLog("==============================================")

        // 6. Initial cache population
        var lastCacheRefreshEpochSec = 0L
        if (cfg.sourceType == ImageSourceType.GCS_BUCKET) {
            _uiState.update { it.copy(isRefreshingCache = true, currentAnimationStatus = "Checking GCS cache...") }
            appendLog("Checking cache for ${cfg.gcsBucketUrl}...")
            val cached = GcsCacheManager.refreshCache(
                context = appContext,
                gcsUrl = cfg.gcsBucketUrl,
                bearerToken = activeBearerToken,
                onLog = ::appendLog,
                onProgress = { status ->
                    _uiState.update { it.copy(currentAnimationStatus = status) }
                }
            )
            lastCacheRefreshEpochSec = System.currentTimeMillis() / 1000L
            val nowStr = timeFormat.format(Date())
            _uiState.update {
                it.copy(
                    isRefreshingCache = false,
                    cachedFileCount = cached.size,
                    lastCacheRefreshTime = nowStr
                )
            }
        }

        // 7. Ensure display is ON and set to Custom Channel 3
        appendLog("Initializing Pixoo display channel(s)...")
        coroutineScope {
            launch { PixooHttpClient.initDisplay(primaryIp, port) }
            if (!secondaryIp.isNullOrBlank()) {
                launch { PixooHttpClient.initDisplay(secondaryIp, port) }
            }
        }

        var lastSelectedId = ""

        // 8. Main Slideshow Loop
        while (kotlin.coroutines.coroutineContext.isActive) {
            cfg = _uiState.value.config
            val currentSecondaryIp = cfg.effectiveSecondaryIp
            val currentPort = cfg.port
            val slideIntervalSec = cfg.slideIntervalSeconds.coerceAtLeast(1)
            val refreshIntervalSec = cfg.refreshIntervalSeconds.coerceAtLeast(5)

            // Check if GCS cache refresh is due
            if (cfg.sourceType == ImageSourceType.GCS_BUCKET) {
                val nowSec = System.currentTimeMillis() / 1000L
                if (nowSec - lastCacheRefreshEpochSec >= refreshIntervalSec) {
                    _uiState.update { it.copy(isRefreshingCache = true, currentAnimationStatus = "Refreshing GCS cache...") }
                    appendLog("Refreshing cache from ${cfg.gcsBucketUrl}...")
                    val cached = GcsCacheManager.refreshCache(
                        context = appContext,
                        gcsUrl = cfg.gcsBucketUrl,
                        bearerToken = activeBearerToken,
                        onLog = ::appendLog,
                        onProgress = { status ->
                            _uiState.update { it.copy(currentAnimationStatus = status) }
                        }
                    )
                    lastCacheRefreshEpochSec = System.currentTimeMillis() / 1000L
                    val nowStr = timeFormat.format(Date())
                    _uiState.update {
                        it.copy(
                            isRefreshingCache = false,
                            cachedFileCount = cached.size,
                            lastCacheRefreshTime = nowStr
                        )
                    }
                }
            }

            // Gather valid image files (line 388)
            val candidates = loadSlideCandidates(appContext, cfg)
            val totalFiles = candidates.size
            _uiState.update { it.copy(totalSlidesCount = totalFiles) }

            if (totalFiles == 0) {
                appendLog("No image files found. Waiting ${slideIntervalSec}s...")
                _uiState.update { it.copy(currentAnimationStatus = "No images found. Waiting ${slideIntervalSec}s...") }
                waitWithSkip(slideIntervalSec * 1000L)
                continue
            }

            // Select one file at random (avoid immediate repeat if > 1 image, lines 398-406)
            var randIdx = Random.nextInt(totalFiles)
            var selected = candidates[randIdx]
            if (totalFiles > 1) {
                while (selected.id == lastSelectedId) {
                    randIdx = Random.nextInt(totalFiles)
                    selected = candidates[randIdx]
                }
            }
            lastSelectedId = selected.id

            appendLog("----------------------------------------------")
            appendLog("Slide: ${selected.filename} (${randIdx + 1} of $totalFiles)")
            _uiState.update {
                it.copy(
                    currentSlideFilename = selected.filename,
                    currentSlideIndex = randIdx + 1,
                    totalSlidesCount = totalFiles
                )
            }

            val rawBytes = withContext(Dispatchers.IO) { selected.readBytes() }
            if (rawBytes == null || rawBytes.isEmpty()) {
                appendLog("Warning: Failed to read ${selected.filename}. Skipping.")
                waitWithSkip(1000L)
                continue
            }

            val processed = withContext(Dispatchers.Default) {
                ImageFrameProcessor.processImageBytes(selected.filename, rawBytes)
            }

            if (processed == null) {
                appendLog("Warning: Failed to decode/process ${selected.filename}. Skipping.")
                waitWithSkip(slideIntervalSec * 1000L)
                continue
            }

            skipSignal = CompletableDeferred()

            // Ensure LED screen finished processing any earlier commands before sending a new image/animation
            while (kotlin.coroutines.coroutineContext.isActive && !skipSignal.isCompleted) {
                val ready = PixooHttpClient.dispatchLockstep(primaryIp, currentSecondaryIp, currentPort) { ip, p ->
                    PixooHttpClient.checkDevice(ip, p, timeoutMs = 2500)
                }
                if (ready) break
                appendLog("Waiting for Pixoo LED screen to finish processing previous command...")
                delay(500L)
            }

            when (processed) {
                is ProcessedMedia.AnimatedGif -> {
                    val rawFrames = processed.frames
                    val maxCapacity = 40 // Safe ESP32 SRAM buffer limit for autonomous hardware playback

                    // Downsample if raw frames exceed hardware buffer capacity
                    val (baseFrames, frameSpeedMultiplier) = if (rawFrames.size > maxCapacity) {
                        val step = ceil(rawFrames.size.toDouble() / maxCapacity).toInt()
                        appendLog("GIF has ${rawFrames.size} frames; downsampling by factor of $step to fit hardware buffer ($maxCapacity frames).")
                        rawFrames.filterIndexed { idx, _ -> idx % step == 0 } to step
                    } else {
                        rawFrames to 1
                    }

                    // Pixoo hardware loops buffered multi-frame animations autonomously!
                    // Upload ONLY 1 single cycle (baseFrames) rather than duplicating cycles over Wi-Fi.
                    val singleCycleDur = baseFrames.sumOf { it.delaySeconds * frameSpeedMultiplier }
                    val targetCycles = if (singleCycleDur > 0.0) {
                        max(1, ceil(slideIntervalSec.toDouble() / singleCycleDur).toInt())
                    } else 1
                    val totalPlayDur = singleCycleDur * targetCycles

                    val totalFrames = baseFrames.size
                    picIdCounter = (picIdCounter % 65535) + 1
                    val animationPicId = picIdCounter

                    val targetDesc = if (!currentSecondaryIp.isNullOrBlank()) {
                        "both devices ($primaryIp & $currentSecondaryIp)"
                    } else {
                        "device ($primaryIp)"
                    }

                    appendLog(
                        String.format(
                            Locale.US,
                            "Uploading %d frame(s) to %s hardware buffer (autonomous playback for %d cycle(s), ~%.1fs)...",
                            totalFrames,
                            targetDesc,
                            targetCycles,
                            totalPlayDur
                        )
                    )

                    _uiState.update {
                        it.copy(
                            previewPixels64 = null, // Do not render on the phone
                            currentAnimationStatus = "Uploading $totalFrames frames to hardware (PicID $animationPicId)..."
                        )
                    }

                    // Reset hardware buffer and give ESP32 40ms settlement time before frame 0
                    PixooHttpClient.dispatchLockstep(primaryIp, currentSecondaryIp, currentPort) { ip, p ->
                        PixooHttpClient.resetHttpGifId(ip, p)
                    }
                    delay(40L)

                    var allUploaded = true
                    for ((offset, f) in baseFrames.withIndex()) {
                        if (skipSignal.isCompleted || !kotlin.coroutines.coroutineContext.isActive) {
                            allUploaded = false
                            break
                        }

                        val speedMs = (f.delaySeconds * frameSpeedMultiplier * 1000.0).toInt().coerceIn(20, 5000)
                        val ok = PixooHttpClient.dispatchLockstep(primaryIp, currentSecondaryIp, currentPort) { ip, p ->
                            PixooHttpClient.sendHttpGifFrame(
                                ip = ip,
                                port = p,
                                picNum = totalFrames,
                                picOffset = offset,
                                picId = animationPicId,
                                picSpeedMs = speedMs,
                                base64Data = f.base64Data,
                                timeoutMs = 5000
                            )
                        }
                        if (!ok) {
                            appendLog("Warning: Hardware rejected or timed out on frame $offset/$totalFrames. Aborting sequence to avoid stuck 'Loading...'.")
                            allUploaded = false
                            break
                        }

                        // Pacing between frame uploads so ESP32 never drops frames
                        delay(35L)
                    }

                    if (allUploaded) {
                        appendLog("Hardware buffered all $totalFrames frames. Playing autonomously on LED screen for ~${String.format(Locale.US, "%.1f", totalPlayDur)}s...")
                        _uiState.update {
                            it.copy(
                                previewPixels64 = null,
                                currentAnimationStatus = "Autonomous hardware playback (~${String.format(Locale.US, "%.1f", totalPlayDur)}s)"
                            )
                        }

                        // Wait for full autonomous hardware playback duration before sending any new slide
                        val waitTimeMs = max(slideIntervalSec * 1000L, (totalPlayDur * 1000.0).toLong())
                        waitWithSkip(waitTimeMs)
                    } else {
                        // Reset incomplete buffer immediately so device never stays on "Loading..."
                        PixooHttpClient.dispatchLockstep(primaryIp, currentSecondaryIp, currentPort) { ip, p ->
                            PixooHttpClient.resetHttpGifId(ip, p)
                        }
                        delay(100L)
                        appendLog("Animation upload incomplete or skipped; hardware buffer cleared.")
                    }

                    _uiState.update {
                        it.copy(currentAnimationStatus = "Animation finished")
                    }
                    delay(200L)
                }

                is ProcessedMedia.StaticImage -> {
                    _uiState.update {
                        it.copy(
                            previewPixels64 = null, // Do not render on the phone
                            currentAnimationStatus = "Displaying static slide autonomously (${slideIntervalSec}s)"
                        )
                    }

                    // Reset hardware buffer state machine and let ESP32 settle
                    PixooHttpClient.dispatchLockstep(
                        primaryIp = primaryIp,
                        secondaryIp = currentSecondaryIp,
                        port = currentPort
                    ) { ip, p ->
                        PixooHttpClient.resetHttpGifId(ip, p)
                    }
                    delay(40L)

                    picIdCounter = (picIdCounter % 65535) + 1
                    val currentPicId = picIdCounter

                    // Dispatch static frame to device(s) and await hardware confirmation
                    val ok = PixooHttpClient.dispatchLockstep(
                        primaryIp = primaryIp,
                        secondaryIp = currentSecondaryIp,
                        port = currentPort
                    ) { ip, p ->
                        PixooHttpClient.sendHttpGifFrame(
                            ip = ip,
                            port = p,
                            picNum = 1,
                            picOffset = 0,
                            picId = currentPicId,
                            picSpeedMs = slideIntervalSec * 1000,
                            base64Data = processed.base64Data,
                            timeoutMs = 5000
                        )
                    }

                    if (ok) {
                        if (!currentSecondaryIp.isNullOrBlank()) {
                            appendLog("Displayed static slide on both Pixoo screens autonomously.")
                        } else {
                            appendLog("Displayed static slide on Pixoo screen autonomously.")
                        }
                        appendLog("Holding autonomous display for ${slideIntervalSec}s...")
                        waitWithSkip(slideIntervalSec * 1000L)
                    } else {
                        appendLog("Warning: Static slide upload timed out while device was busy. Waiting before next slide...")
                        delay(500L)
                    }
                }
            }
        }
    }

    private suspend fun waitWithSkip(durationMs: Long) {
        skipSignal = CompletableDeferred()
        withTimeoutOrNull(durationMs) {
            skipSignal.await()
        }
    }

    private suspend fun loadSlideCandidates(
        context: Application,
        config: SlideshowConfig
    ): List<SlideCandidate> = withContext(Dispatchers.IO) {
        when (config.sourceType) {
            ImageSourceType.GCS_BUCKET -> {
                val files = GcsCacheManager.listValidCachedFiles(GcsCacheManager.getCacheDir(context))
                files.map { file ->
                    SlideCandidate(
                        id = file.absolutePath,
                        filename = file.name,
                        readBytes = { runCatching { file.readBytes() }.getOrNull() }
                    )
                }
            }

            ImageSourceType.LOCAL_DIRECTORY -> {
                val uriStr = config.localDirectoryUri.trim()
                if (uriStr.isEmpty()) return@withContext emptyList()
                if (uriStr.startsWith("content://")) {
                    val treeUri = Uri.parse(uriStr)
                    val docDir = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
                    docDir.listFiles()
                        .filter { it.isFile && it.name != null && ImageFrameProcessor.isValidImageFilename(it.name!!) }
                        .sortedBy { it.name }
                        .map { docFile ->
                            val name = docFile.name ?: "image"
                            SlideCandidate(
                                id = docFile.uri.toString(),
                                filename = name,
                                readBytes = {
                                    runCatching {
                                        context.contentResolver.openInputStream(docFile.uri)?.use { it.readBytes() }
                                    }.getOrNull()
                                }
                            )
                        }
                } else {
                    val dir = File(uriStr)
                    val files = dir.listFiles()
                        ?.filter { it.isFile && ImageFrameProcessor.isValidImageFilename(it.name) }
                        ?.sortedBy { it.name }
                        ?: emptyList()
                    files.map { file ->
                        SlideCandidate(
                            id = file.absolutePath,
                            filename = file.name,
                            readBytes = { runCatching { file.readBytes() }.getOrNull() }
                        )
                    }
                }
            }
        }
    }
}
