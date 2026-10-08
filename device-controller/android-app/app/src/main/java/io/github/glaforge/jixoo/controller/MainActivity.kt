package io.github.glaforge.jixoo.controller

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.glaforge.jixoo.controller.model.ImageSourceType
import io.github.glaforge.jixoo.controller.model.SlideshowConfig
import io.github.glaforge.jixoo.controller.ui.PixooLedMatrixPreview
import io.github.glaforge.jixoo.controller.ui.SlideshowUiState
import io.github.glaforge.jixoo.controller.ui.SlideshowViewModel
import kotlin.math.roundToInt

private val PixooDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00262C),
    primaryContainer = Color(0xFF004E59),
    onPrimaryContainer = Color(0xFFB8F5FF),
    secondary = Color(0xFFFF4081),
    onSecondary = Color(0xFF3E0017),
    tertiary = Color(0xFF69F0AE),
    background = Color(0xFF0B0E14),
    surface = Color(0xFF131822),
    surfaceVariant = Color(0xFF1C2331),
    onSurface = Color(0xFFE6EDF3),
    onSurfaceVariant = Color(0xFF9DA7B3)
)

class MainActivity : ComponentActivity() {

    private val viewModel: SlideshowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAdbIntent(intent)
        setContent {
            MaterialTheme(colorScheme = PixooDarkColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val uiState by viewModel.uiState.collectAsState()
                    PixooControllerScreen(
                        uiState = uiState,
                        viewModel = viewModel,
                        activity = this
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAdbIntent(intent)
    }

    private fun handleAdbIntent(intent: Intent?) {
        if (intent == null) return
        val gcsAccount = intent.getStringExtra("gcs_account")
        val gcsToken = intent.getStringExtra("gcs_token")
        val primaryIp = intent.getStringExtra("primary_ip")
        val secondaryIp = intent.getStringExtra("secondary_ip")
        val gcsBucket = intent.getStringExtra("gcs_bucket")

        var updated = false
        viewModel.updateConfig { current ->
            var next = current
            if (!gcsAccount.isNullOrBlank()) {
                next = next.copy(gcsAccount = gcsAccount)
                updated = true
            }
            if (!gcsToken.isNullOrBlank()) {
                next = next.copy(gcsBearerToken = gcsToken)
                updated = true
            }
            if (!primaryIp.isNullOrBlank()) {
                next = next.copy(primaryIp = primaryIp)
                updated = true
            }
            if (!secondaryIp.isNullOrBlank()) {
                next = next.copy(secondaryIpEnabled = true, secondaryIp = secondaryIp)
                updated = true
            }
            if (!gcsBucket.isNullOrBlank()) {
                next = next.copy(gcsBucketUrl = gcsBucket)
                updated = true
            }
            next
        }

        if (updated) {
            viewModel.appendLog("Applied configuration injected via ADB intent.")
            viewModel.resolveGcsAccountNow(this)
            if (intent.getBooleanExtra("auto_sync", false)) {
                viewModel.refreshCacheManual(this)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PixooControllerScreen(
    uiState: SlideshowUiState,
    viewModel: SlideshowViewModel,
    activity: Activity
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // All configuration & option sections are collapsed by default for a clean, focused UI
    var expandDevices by rememberSaveable { mutableStateOf(false) }
    var expandSource by rememberSaveable { mutableStateOf(false) }
    var expandTiming by rememberSaveable { mutableStateOf(false) }
    var expandAuth by rememberSaveable { mutableStateOf(false) }
    var expandLogs by rememberSaveable { mutableStateOf(false) }

    var showBearerTokenField by remember { mutableStateOf(false) }
    var showBearerTokenPlain by remember { mutableStateOf(false) }

    val anyExpanded = expandDevices || expandSource || expandTiming || expandAuth || expandLogs

    // SAF Directory Picker for Local Image Source (-s / --source <local_dir>)
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.updateConfig {
                it.copy(
                    sourceType = ImageSourceType.LOCAL_DIRECTORY,
                    localDirectoryUri = uri.toString()
                )
            }
            viewModel.appendLog("Selected local image directory: $uri")
        }
    }

    // Android Account Picker for GCS Account (-a / --account <email>)
    val accountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!accountName.isNullOrBlank()) {
                viewModel.updateConfig { it.copy(gcsAccount = accountName) }
                viewModel.refreshDeviceAccounts()
                viewModel.resolveGcsAccountNow(activity)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (uiState.isRunning) Color(0xFF00E676) else Color(0xFF6E7681)
                                )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Pixoo 64 Slideshow",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = if (uiState.isRunning) {
                                    "Streaming • ${uiState.currentAnimationStatus}"
                                } else {
                                    "Ready • ${uiState.cachedFileCount} cached files"
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val target = !anyExpanded
                            expandDevices = target
                            expandSource = target
                            expandTiming = target
                            expandAuth = target
                            expandLogs = target
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (anyExpanded) "Collapse All" else "Expand All", fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // =================================================================
            // PRIMARY HERO CARD: LIVE 64x64 LED PREVIEW & TRANSPORT CONTROLS
            // =================================================================
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PixooLedMatrixPreview(
                            pixels64 = uiState.previewPixels64,
                            modifier = Modifier.size(144.dp)
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = uiState.currentSlideFilename,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (uiState.totalSlidesCount > 0) {
                                Text(
                                    text = "Slide ${uiState.currentSlideIndex} of ${uiState.totalSlidesCount}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = uiState.currentAnimationStatus,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val primarySummary = uiState.config.primaryIp.ifBlank { "Auto-discover" }
                            val secondarySummary = uiState.config.effectiveSecondaryIp?.let { " + $it" } ?: ""
                            Text(
                                text = "Target: $primarySummary$secondarySummary",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.toggleSlideshow(activity) },
                            modifier = Modifier.weight(1.5f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.isRunning) {
                                    Color(0xFFFF5252)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                contentColor = if (uiState.isRunning) {
                                    Color.White
                                } else {
                                    MaterialTheme.colorScheme.onPrimary
                                }
                            )
                        ) {
                            Icon(
                                imageVector = if (uiState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.isRunning) "Stop" else "Start Slideshow",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = { viewModel.skipToNextSlide() },
                            enabled = uiState.isRunning,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Next")
                        }

                        OutlinedButton(
                            onClick = { viewModel.initDisplaysManual() }
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = "Init Display Channel 3")
                        }
                    }
                }
            }

            // =================================================================
            // COLLAPSIBLE OPTION 1: TARGET PIXOO DEVICES
            // =================================================================
            val deviceSummary = buildString {
                append(uiState.config.primaryIp.ifBlank { "Auto (${SlideshowConfig.DEFAULT_FALLBACK_IP})" })
                append(":${uiState.config.port}")
                uiState.config.effectiveSecondaryIp?.let { append(" & $it (Sync)") }
            }
            CollapsibleOptionCard(
                icon = Icons.Default.Devices,
                title = "Target Pixoo Devices",
                summary = deviceSummary,
                expanded = expandDevices,
                onToggle = { expandDevices = !expandDevices }
            ) {
                Text(
                    text = "Tip: Tap \"Discover & Replace IP\" anytime you change Wi-Fi networks — it ignores any old IP below, scans your current Wi-Fi, and applies the live Pixoo IP automatically.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = uiState.config.primaryIp,
                        onValueChange = { value ->
                            viewModel.updateConfig { it.copy(primaryIp = value) }
                        },
                        label = { Text("Primary Pixoo IP") },
                        placeholder = { Text("Leave blank or tap Discover") },
                        trailingIcon = {
                            if (uiState.config.primaryIp.isNotEmpty()) {
                                TextButton(
                                    onClick = { viewModel.updateConfig { it.copy(primaryIp = "") } }
                                ) {
                                    Text("✕", fontWeight = FontWeight.Bold)
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = uiState.config.port.toString(),
                        onValueChange = { value ->
                            val parsed = value.filter { it.isDigit() }.toIntOrNull() ?: 80
                            viewModel.updateConfig { it.copy(port = parsed.coerceIn(1, 65535)) }
                        },
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(88.dp)
                    )
                }

                if (uiState.primaryReachable != null || uiState.secondaryReachable != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        uiState.primaryReachable?.let { ok ->
                            StatusBadge(
                                label = "Primary: ${if (ok) "ONLINE" else "UNREACHABLE"}",
                                isOk = ok
                            )
                        }
                        uiState.secondaryReachable?.let { ok ->
                            StatusBadge(
                                label = "Device 2: ${if (ok) "ONLINE" else "UNREACHABLE"}",
                                isOk = ok
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Second Pixoo Sync (--device2)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Stream in sync to a 2nd display (auto-isolated if offline)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = uiState.config.secondaryIpEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.updateConfig { it.copy(secondaryIpEnabled = enabled) }
                        }
                    )
                }

                AnimatedVisibility(visible = uiState.config.secondaryIpEnabled) {
                    OutlinedTextField(
                        value = uiState.config.secondaryIp,
                        onValueChange = { value ->
                            viewModel.updateConfig { it.copy(secondaryIp = value.trim()) }
                        },
                        label = { Text("Second Pixoo IP") },
                        placeholder = { Text("e.g. 192.168.1.50") },
                        trailingIcon = {
                            if (uiState.config.secondaryIp.isNotEmpty()) {
                                TextButton(
                                    onClick = { viewModel.updateConfig { it.copy(secondaryIp = "") } }
                                ) {
                                    Text("✕", fontWeight = FontWeight.Bold)
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.discoverDevices() },
                        enabled = !uiState.isDiscovering,
                        modifier = Modifier.weight(1.3f)
                    ) {
                        if (uiState.isDiscovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (uiState.isDiscovering) "Scanning Wi-Fi..." else "Discover & Replace IP")
                    }

                    OutlinedButton(
                        onClick = { viewModel.checkDevicesConnectivity() },
                        enabled = !uiState.isCheckingDevices,
                        modifier = Modifier.weight(0.9f)
                    ) {
                        if (uiState.isCheckingDevices) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Check Ping")
                    }
                }

                AnimatedVisibility(visible = uiState.discoveredDevices.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Discovered on Local Network:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        uiState.discoveredDevices.forEach { dev ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${dev.ipAddress} (${dev.deviceName})",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp
                                    )
                                    if (dev.macAddress.isNotBlank()) {
                                        Text(
                                            text = "MAC: ${dev.macAddress}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    AssistChip(
                                        onClick = {
                                            viewModel.updateConfig { it.copy(primaryIp = dev.ipAddress) }
                                        },
                                        label = { Text("Primary", fontSize = 11.sp) }
                                    )
                                    AssistChip(
                                        onClick = {
                                            viewModel.updateConfig {
                                                it.copy(
                                                    secondaryIpEnabled = true,
                                                    secondaryIp = dev.ipAddress
                                                )
                                            }
                                        },
                                        label = { Text("Device 2", fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // COLLAPSIBLE OPTION 2: IMAGE & ANIMATION SOURCE (-s / --source)
            // =================================================================
            val sourceSummary = if (uiState.config.sourceType == ImageSourceType.GCS_BUCKET) {
                "${uiState.config.gcsBucketUrl} (${uiState.cachedFileCount} cached)"
            } else {
                uiState.config.localDirectoryUri.ifBlank { "Local directory (not selected)" }
            }
            CollapsibleOptionCard(
                icon = Icons.Default.CloudDownload,
                title = "Image Source (-s / --source)",
                summary = sourceSummary,
                expanded = expandSource,
                onToggle = { expandSource = !expandSource }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.config.sourceType == ImageSourceType.GCS_BUCKET,
                        onClick = {
                            viewModel.updateConfig { it.copy(sourceType = ImageSourceType.GCS_BUCKET) }
                        },
                        label = { Text("GCS Bucket (gs://)") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = uiState.config.sourceType == ImageSourceType.LOCAL_DIRECTORY,
                        onClick = {
                            viewModel.updateConfig { it.copy(sourceType = ImageSourceType.LOCAL_DIRECTORY) }
                        },
                        label = { Text("Local Directory") },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (uiState.config.sourceType == ImageSourceType.GCS_BUCKET) {
                    val presetOptions = listOf(
                        "Best visuals (gs://conference-pics/gravidots/visuals-best)" to SlideshowConfig.BUCKET_BEST,
                        "All approved visuals (conference-pics/gravidots/visuals)" to SlideshowConfig.BUCKET_ALL,
                        "Backup visuals (conference-pics/gravidots/visuals-backup)" to SlideshowConfig.BUCKET_BACKUP,
                        "Custom URL..." to null
                    )

                    val savedUrl = uiState.config.gcsBucketUrl
                    val initialIsCustom = presetOptions.none { it.second == savedUrl }

                    var dropdownExpanded by remember { mutableStateOf(false) }
                    var isCustomMode by remember(savedUrl) { mutableStateOf(initialIsCustom) }
                    var selectedPresetUrl by remember(savedUrl) {
                        mutableStateOf(if (initialIsCustom) SlideshowConfig.BUCKET_ALL else savedUrl)
                    }
                    var customBucketInput by remember(savedUrl) {
                        mutableStateOf(if (initialIsCustom) savedUrl else SlideshowConfig.BUCKET_ALL)
                    }

                    val activeDropdownLabel = if (isCustomMode) {
                        "Custom URL..."
                    } else {
                        presetOptions.firstOrNull { it.second == selectedPresetUrl }?.first
                            ?: presetOptions[1].first
                    }

                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = !dropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = activeDropdownLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("GCS Visuals Bucket") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                            },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            singleLine = true,
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            presetOptions.forEach { (label, presetUrl) ->
                                DropdownMenuItem(
                                    text = { Text(label, fontSize = 13.sp) },
                                    onClick = {
                                        dropdownExpanded = false
                                        if (presetUrl != null) {
                                            isCustomMode = false
                                            selectedPresetUrl = presetUrl
                                        } else {
                                            isCustomMode = true
                                            if (customBucketInput.isBlank() || presetOptions.any { it.second == customBucketInput }) {
                                                customBucketInput = SlideshowConfig.BUCKET_ALL
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    if (isCustomMode) {
                        OutlinedTextField(
                            value = customBucketInput,
                            onValueChange = { value ->
                                customBucketInput = value
                            },
                            label = { Text("Custom GCS Bucket URL") },
                            placeholder = { Text(SlideshowConfig.BUCKET_ALL) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    val effectiveBucketToSave = if (isCustomMode) customBucketInput else selectedPresetUrl

                    Button(
                        onClick = {
                            viewModel.saveBucketAndRefreshCache(effectiveBucketToSave, activity)
                        },
                        enabled = !uiState.isRefreshingCache,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (uiState.isRefreshingCache) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (uiState.isRefreshingCache) "Syncing Bucket..." else "Save & Refresh Cache")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cached: ${uiState.cachedFileCount} files • Sync: ${uiState.lastCacheRefreshTime}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = uiState.config.gcsBucketUrl.removePrefix("gs://"),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = uiState.config.localDirectoryUri,
                        onValueChange = { value ->
                            viewModel.updateConfig { it.copy(localDirectoryUri = value) }
                        },
                        label = { Text("Local Directory URI / Path") },
                        placeholder = { Text("Pick a folder or enter path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedButton(
                        onClick = { directoryPickerLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Choose Local Image Folder")
                    }
                }
            }

            // =================================================================
            // COLLAPSIBLE OPTION 3: TIMING & CACHE REFRESH (-i, -r)
            // =================================================================
            val timingSummary = "Slide: ${uiState.config.slideIntervalSeconds}s • Cache Refresh: ${uiState.config.refreshIntervalRaw} (${uiState.config.refreshIntervalSeconds}s)"
            CollapsibleOptionCard(
                icon = Icons.Default.Timer,
                title = "Timing & Cache Refresh (-i, -r)",
                summary = timingSummary,
                expanded = expandTiming,
                onToggle = { expandTiming = !expandTiming }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Slide Interval (-i / --interval): ${uiState.config.slideIntervalSeconds}s",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "GIFs play full cycle(s) for ≥ ${uiState.config.slideIntervalSeconds}s",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = uiState.config.slideIntervalSeconds.toString(),
                        onValueChange = { v ->
                            val sec = v.filter { it.isDigit() }.toIntOrNull() ?: 3
                            viewModel.updateConfig { it.copy(slideIntervalSeconds = sec.coerceIn(1, 3600)) }
                        },
                        label = { Text("Sec") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(84.dp)
                    )
                }

                Slider(
                    value = uiState.config.slideIntervalSeconds.coerceIn(1, 60).toFloat(),
                    onValueChange = { v ->
                        viewModel.updateConfig { it.copy(slideIntervalSeconds = v.roundToInt().coerceAtLeast(1)) }
                    },
                    valueRange = 1f..60f
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 10, 15, 30, 60).forEach { preset ->
                        FilterChip(
                            selected = uiState.config.slideIntervalSeconds == preset,
                            onClick = {
                                viewModel.updateConfig { it.copy(slideIntervalSeconds = preset) }
                            },
                            label = { Text("${preset}s") }
                        )
                    }
                }

                if (uiState.config.sourceType == ImageSourceType.GCS_BUCKET) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = uiState.config.refreshIntervalRaw,
                            onValueChange = { value ->
                                viewModel.updateConfig { it.copy(refreshIntervalRaw = value) }
                            },
                            label = { Text("GCS Cache Refresh (-r / --refresh)") },
                            placeholder = { Text("e.g. 1m, 60s, 90s") },
                            supportingText = {
                                Text("Parsed duration: ${uiState.config.refreshIntervalSeconds} seconds")
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedButton(
                            onClick = { viewModel.refreshCacheManual(activity) },
                            enabled = !uiState.isRefreshingCache
                        ) {
                            if (uiState.isRefreshingCache) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sync")
                        }
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("30s", "1m", "2m", "5m", "10m").forEach { preset ->
                            FilterChip(
                                selected = uiState.config.refreshIntervalRaw.equals(preset, ignoreCase = true),
                                onClick = {
                                    viewModel.updateConfig { it.copy(refreshIntervalRaw = preset) }
                                },
                                label = { Text(preset) }
                            )
                        }
                    }
                }
            }

            // =================================================================
            // COLLAPSIBLE OPTION 4: GOOGLE CLOUD AUTH (-a / --account)
            // =================================================================
            if (uiState.config.sourceType == ImageSourceType.GCS_BUCKET) {
                val authSummary = when {
                    uiState.resolvedGcsAccountLabel.isNotBlank() -> uiState.resolvedGcsAccountLabel
                    uiState.config.gcsAccount.isNotBlank() -> uiState.config.gcsAccount
                    else -> "Auto-resolve (public / ADC / device account)"
                }
                CollapsibleOptionCard(
                    icon = Icons.Default.AccountCircle,
                    title = "Google Cloud Auth (-a / --account)",
                    summary = authSummary,
                    expanded = expandAuth,
                    onToggle = { expandAuth = !expandAuth }
                ) {
                    OutlinedTextField(
                        value = uiState.config.gcsAccount,
                        onValueChange = { value ->
                            viewModel.updateConfig { it.copy(gcsAccount = value) }
                        },
                        label = { Text("Google Cloud Account Email (-a / --account)") },
                        placeholder = { Text("Auto-detect (public, ADC, or device account)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (uiState.resolvedGcsAccountLabel.isNotBlank()) {
                        StatusBadge(
                            label = "Active GCS Auth: ${uiState.resolvedGcsAccountLabel}",
                            isOk = !uiState.resolvedGcsAccountLabel.contains("Failed", ignoreCase = true)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val intent = AccountManager.newChooseAccountIntent(
                                    null,
                                    null,
                                    arrayOf("com.google"),
                                    null,
                                    null,
                                    null,
                                    null
                                )
                                accountPickerLauncher.launch(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pick Account")
                        }

                        OutlinedButton(
                            onClick = { viewModel.resolveGcsAccountNow(activity) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Resolve Access")
                        }
                    }

                    TextButton(
                        onClick = { showBearerTokenField = !showBearerTokenField }
                    ) {
                        Text(
                            text = if (showBearerTokenField) {
                                "Hide OAuth2 Bearer Token Override"
                            } else {
                                "Advanced: OAuth2 Bearer Token Override"
                            },
                            fontSize = 12.sp
                        )
                    }

                    AnimatedVisibility(visible = showBearerTokenField) {
                        OutlinedTextField(
                            value = uiState.config.gcsBearerToken,
                            onValueChange = { value ->
                                viewModel.updateConfig { it.copy(gcsBearerToken = value) }
                            },
                            label = { Text("OAuth2 Access Token (Optional)") },
                            placeholder = { Text("ya29.a0...") },
                            visualTransformation = if (showBearerTokenPlain) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                TextButton(onClick = { showBearerTokenPlain = !showBearerTokenPlain }) {
                                    Text(if (showBearerTokenPlain) "Hide" else "Show", fontSize = 11.sp)
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // =================================================================
            // COLLAPSIBLE OPTION 5: STREAMER CONSOLE LOG
            // =================================================================
            val lastLogSummary = uiState.logs.lastOrNull() ?: "No log entries yet"
            CollapsibleOptionCard(
                icon = Icons.Default.Terminal,
                title = "Streamer Console Log (${uiState.logs.size})",
                summary = lastLogSummary,
                expanded = expandLogs,
                onToggle = { expandLogs = !expandLogs }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { viewModel.clearLogs() }) {
                        Text("Clear Logs", fontSize = 12.sp)
                    }
                }

                val listState = rememberLazyListState()
                LaunchedEffect(uiState.logs.size) {
                    if (uiState.logs.isNotEmpty()) {
                        listState.animateScrollToItem(uiState.logs.lastIndex)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF07090D))
                        .padding(10.dp)
                ) {
                    if (uiState.logs.isEmpty()) {
                        Text(
                            text = "Ready. Tap 'Start Slideshow' above.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFF6E7681)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            items(uiState.logs) { line ->
                                Text(
                                    text = line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = when {
                                        line.contains("Error:") -> Color(0xFFFF5252)
                                        line.contains("Warning:") -> Color(0xFFFFD740)
                                        line.contains("Successfully") || line.contains("Found Pixoo") -> Color(0xFF69F0AE)
                                        else -> Color(0xFFD0D7DE)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleOptionCard(
    icon: ImageVector,
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = summary,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    content()
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, isOk: Boolean) {
    val bgColor = if (isOk) Color(0xFF103321) else Color(0xFF3B1219)
    val textColor = if (isOk) Color(0xFF69F0AE) else Color(0xFFFF5252)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}
