package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.iossideloadbridge.app.anisette.AnisetteClient
import com.aistudio.iossideloadbridge.app.anisette.AnisetteHeaders
import com.aistudio.iossideloadbridge.app.anisette.ServerCheckResult
import com.aistudio.iossideloadbridge.app.auth.AppleAuthClient
import com.aistudio.iossideloadbridge.app.auth.AppleAuthSession
import com.aistudio.iossideloadbridge.app.auth.AppleTrustHelper
import com.aistudio.iossideloadbridge.app.auth.AuthSessionStorage
import com.aistudio.iossideloadbridge.app.auth.DryRunInspection
import com.aistudio.iossideloadbridge.app.auth.SignInContext
import com.aistudio.iossideloadbridge.app.auth.SignInState
import com.aistudio.iossideloadbridge.app.provisioning.AppleDeveloperClient
import com.aistudio.iossideloadbridge.app.provisioning.DeveloperTeam
import com.aistudio.iossideloadbridge.app.provisioning.IpaSigner
import com.aistudio.iossideloadbridge.app.provisioning.ProvisionedIdentity
import com.aistudio.iossideloadbridge.app.provisioning.ProvisioningPipelineCard
import com.aistudio.iossideloadbridge.app.provisioning.ProvisioningStorage
import com.aistudio.iossideloadbridge.app.usb.BridgeStatus
import com.aistudio.iossideloadbridge.app.usb.InstallProgress
import com.aistudio.iossideloadbridge.app.usb.UsbBridge
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class PipelineStageState {
    NOT_STARTED,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED
}

enum class NavigationTab(val title: String, val testTag: String) {
    BRIDGE("Bridge", "nav_tab_bridge"),
    INSTALL("Install", "nav_tab_install"),
    DIAGNOSTICS("Diagnostics", "nav_tab_diagnostics"),
    SETTINGS("Settings", "nav_tab_settings")
}

data class SelectedIpaInfo(
    val uri: Uri,
    val fileName: String,
    val fileSizeFormatted: String,
    val appName: String,
    val bundleId: String,
    val version: String
)

class MainActivity : ComponentActivity() {

    private lateinit var usbBridge: UsbBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        usbBridge = UsbBridge(applicationContext)

        setContent {
            MyApplicationTheme {
                val status by usbBridge.status.collectAsState()
                val logs by usbBridge.logs.collectAsState()
                val usbManager = remember { getSystemService(Context.USB_SERVICE) as UsbManager }

                var deviceList by remember { mutableStateOf(emptyList<UsbDevice>()) }

                fun refreshDevices() {
                    deviceList = usbBridge.getDeviceList()
                }

                LaunchedEffect(Unit) {
                    refreshDevices()
                    usbBridge.start()
                }

                AppScaffold(
                    status = status,
                    logs = logs,
                    devices = deviceList,
                    usbManager = usbManager,
                    usbBridge = usbBridge,
                    onStartDaemon = {
                        usbBridge.start()
                        refreshDevices()
                    },
                    onStopDaemon = {
                        usbBridge.stop()
                        refreshDevices()
                    },
                    onRefreshDevices = {
                        refreshDevices()
                        usbBridge.scanAndConnect()
                    },
                    onRequestPermission = { device ->
                        usbBridge.requestPermission(device)
                    },
                    onClearLogs = {
                        usbBridge.clearLogs()
                    },
                    onRetryLockdown = {
                        usbBridge.queryLockdownDeviceInfo()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                if (::usbBridge.isInitialized) {
                    val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        usbBridge.requestPermission(device)
                    } else {
                        usbBridge.scanAndConnect()
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "Error handling onNewIntent USB attachment: ${t.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::usbBridge.isInitialized) {
            usbBridge.stop()
        }
    }
}

@Composable
fun AppScaffold(
    status: BridgeStatus,
    logs: List<String>,
    devices: List<UsbDevice>,
    usbManager: UsbManager,
    usbBridge: UsbBridge,
    onStartDaemon: () -> Unit,
    onStopDaemon: () -> Unit,
    onRefreshDevices: () -> Unit,
    onRequestPermission: (UsbDevice) -> Unit,
    onClearLogs: () -> Unit,
    onRetryLockdown: () -> Unit
) {
    var currentTab by rememberSaveable { mutableStateOf(NavigationTab.BRIDGE) }

    @Composable
    fun RenderTabContent() {
        when (currentTab) {
            NavigationTab.BRIDGE -> BridgeTabScreen(
                status = status,
                logs = logs,
                devices = devices,
                usbManager = usbManager,
                onStartDaemon = onStartDaemon,
                onStopDaemon = onStopDaemon,
                onRefreshDevices = onRefreshDevices,
                onRequestPermission = onRequestPermission,
                onRetryLockdown = onRetryLockdown,
                onClearLogs = onClearLogs
            )
            NavigationTab.INSTALL -> InstallTabScreen(
                status = status,
                connectedDevices = devices,
                usbBridge = usbBridge
            )
            NavigationTab.DIAGNOSTICS -> DiagnosticsTabScreen(
                status = status,
                logs = logs,
                devices = devices,
                usbBridge = usbBridge,
                onClearLogs = onClearLogs
            )
            NavigationTab.SETTINGS -> SettingsTabScreen(
                status = status,
                logs = logs
            )
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        val isWideScreen = maxWidth >= 600.dp

        if (isWideScreen) {
            // Adaptive wide layout: NavigationRail on side + centered content container
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                NavigationRail(
                    containerColor = DarkSurface,
                    modifier = Modifier.testTag("side_nav_rail")
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    NavigationRailItem(
                        selected = currentTab == NavigationTab.BRIDGE,
                        onClick = { currentTab = NavigationTab.BRIDGE },
                        icon = { Icon(Icons.Default.Cable, contentDescription = "Bridge Tab") },
                        label = { Text("Bridge") },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = CalmBlue,
                            indicatorColor = CalmBlueContainer,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted
                        ),
                        modifier = Modifier.testTag(NavigationTab.BRIDGE.testTag)
                    )
                    NavigationRailItem(
                        selected = currentTab == NavigationTab.INSTALL,
                        onClick = { currentTab = NavigationTab.INSTALL },
                        icon = { Icon(Icons.Default.InstallMobile, contentDescription = "Install Tab") },
                        label = { Text("Install") },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = CalmBlue,
                            indicatorColor = CalmBlueContainer,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted
                        ),
                        modifier = Modifier.testTag(NavigationTab.INSTALL.testTag)
                    )
                    NavigationRailItem(
                        selected = currentTab == NavigationTab.DIAGNOSTICS,
                        onClick = { currentTab = NavigationTab.DIAGNOSTICS },
                        icon = { Icon(Icons.Default.BugReport, contentDescription = "Diagnostics Tab") },
                        label = { Text("Diagnostics") },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = CalmBlue,
                            indicatorColor = CalmBlueContainer,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted
                        ),
                        modifier = Modifier.testTag(NavigationTab.DIAGNOSTICS.testTag)
                    )
                    NavigationRailItem(
                        selected = currentTab == NavigationTab.SETTINGS,
                        onClick = { currentTab = NavigationTab.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings Tab") },
                        label = { Text("Settings") },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = CalmBlue,
                            indicatorColor = CalmBlueContainer,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted
                        ),
                        modifier = Modifier.testTag(NavigationTab.SETTINGS.testTag)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 840.dp)
                    ) {
                        RenderTabContent()
                    }
                }
            }
        } else {
            // Compact mobile layout: Scaffold with bottom NavigationBar
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("main_app_scaffold"),
                containerColor = DarkBackground,
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    NavigationBar(
                        containerColor = DarkSurface,
                        tonalElevation = 2.dp,
                        modifier = Modifier.testTag("bottom_nav_bar")
                    ) {
                        NavigationBarItem(
                            selected = currentTab == NavigationTab.BRIDGE,
                            onClick = { currentTab = NavigationTab.BRIDGE },
                            icon = { Icon(Icons.Default.Cable, contentDescription = "Bridge Tab") },
                            label = { Text("Bridge") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = CalmBlue,
                                indicatorColor = CalmBlueContainer,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted
                            ),
                            modifier = Modifier.testTag(NavigationTab.BRIDGE.testTag)
                        )

                        NavigationBarItem(
                            selected = currentTab == NavigationTab.INSTALL,
                            onClick = { currentTab = NavigationTab.INSTALL },
                            icon = { Icon(Icons.Default.InstallMobile, contentDescription = "Install Tab") },
                            label = { Text("Install") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = CalmBlue,
                                indicatorColor = CalmBlueContainer,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted
                            ),
                            modifier = Modifier.testTag(NavigationTab.INSTALL.testTag)
                        )

                        NavigationBarItem(
                            selected = currentTab == NavigationTab.DIAGNOSTICS,
                            onClick = { currentTab = NavigationTab.DIAGNOSTICS },
                            icon = { Icon(Icons.Default.BugReport, contentDescription = "Diagnostics Tab") },
                            label = { Text("Diagnostics") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = CalmBlue,
                                indicatorColor = CalmBlueContainer,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted
                            ),
                            modifier = Modifier.testTag(NavigationTab.DIAGNOSTICS.testTag)
                        )

                        NavigationBarItem(
                            selected = currentTab == NavigationTab.SETTINGS,
                            onClick = { currentTab = NavigationTab.SETTINGS },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings Tab") },
                            label = { Text("Settings") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = CalmBlue,
                                indicatorColor = CalmBlueContainer,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted
                            ),
                            modifier = Modifier.testTag(NavigationTab.SETTINGS.testTag)
                        )
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(DarkBackground)
                ) {
                    RenderTabContent()
                }
            }
        }
    }
}

/* =========================================================================================
 * TAB 1: BRIDGE SCREEN
 * ========================================================================================= */

@Composable
fun BridgeTabScreen(
    status: BridgeStatus,
    logs: List<String>,
    devices: List<UsbDevice>,
    usbManager: UsbManager,
    onStartDaemon: () -> Unit,
    onStopDaemon: () -> Unit,
    onRefreshDevices: () -> Unit,
    onRequestPermission: (UsbDevice) -> Unit,
    onRetryLockdown: () -> Unit,
    onClearLogs: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "iOS Sideload Bridge",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "usbmuxd & libimobiledevice daemon",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            FilledTonalButton(
                onClick = onRefreshDevices,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = DarkSurfaceVariant,
                    contentColor = CalmBlue
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("bridge_rescan_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Scan",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan", fontSize = 13.sp)
            }
        }

        // 1. Status Hero Card at top
        StatusHeroCard(
            status = status,
            onStartDaemon = onStartDaemon,
            onStopDaemon = onStopDaemon
        )

        // 2. Connected Devices Section
        ConnectedDevicesSection(
            devices = devices,
            usbManager = usbManager,
            status = status,
            onRequestPermission = onRequestPermission,
            onRetryLockdown = onRetryLockdown
        )

        // 3. Technical Log Section (collapsed by default behind expandable row)
        ExpandableTechnicalLogCard(
            logs = logs,
            onClearLogs = onClearLogs
        )
    }
}

@Composable
fun StatusHeroCard(
    status: BridgeStatus,
    onStartDaemon: () -> Unit,
    onStopDaemon: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_hero_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Big USB/plug icon + status text
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkSurfaceVariant)
                            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cable,
                            contentDescription = "USB Daemon Icon",
                            tint = if (status.isRunning) CalmBlue else TextMuted,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Status dot
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (status.isRunning) StatusGreen else StatusRed)
                            )
                            Text(
                                text = if (status.isRunning) "Online" else "Offline",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        Text(
                            text = if (status.isRunning) "Daemon active on abstract socket" else "Daemon stopped",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                // Right: Pill-shaped Start/Stop button
                Button(
                    onClick = {
                        if (status.isRunning) onStopDaemon() else onStartDaemon()
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (status.isRunning) DarkSurfaceVariant else CalmBlue,
                        contentColor = if (status.isRunning) StatusRed else Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("daemon_toggle_button")
                ) {
                    Icon(
                        imageVector = if (status.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (status.isRunning) "Stop" else "Start",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }

            // Abstract socket endpoint shown in small monospace grey text
            Surface(
                color = DarkBackground,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Socket Endpoint",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = "@${status.socketName}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    val context = LocalContext.current
                    Text(
                        text = "Copy",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = CalmBlue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Socket", "@${status.socketName}"))
                                Toast.makeText(context, "Copied @${status.socketName}", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectedDevicesSection(
    devices: List<UsbDevice>,
    usbManager: UsbManager,
    status: BridgeStatus,
    onRequestPermission: (UsbDevice) -> Unit,
    onRetryLockdown: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Connected Devices",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        if (devices.isEmpty()) {
            // Empty state when nothing connected: centered icon + text
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("empty_devices_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(DarkSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneIphone,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Text(
                        text = "Plug in your iPhone to get started",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )

                    Text(
                        text = "Use a USB-C to Lightning or USB-C to C cable. Unlock your iOS device and tap 'Trust' if prompted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            devices.forEach { device ->
                DeviceItemCard(
                    device = device,
                    usbManager = usbManager,
                    status = status,
                    onRequestPermission = onRequestPermission,
                    onRetryLockdown = onRetryLockdown
                )
            }
        }
    }
}

@Composable
fun DeviceItemCard(
    device: UsbDevice,
    usbManager: UsbManager,
    status: BridgeStatus,
    onRequestPermission: (UsbDevice) -> Unit,
    onRetryLockdown: () -> Unit
) {
    val isApple = device.vendorId == UsbBridge.VID_APPLE
    val hasPermission = usbManager.hasPermission(device)
    val isClaimed = status.isInterfaceClaimed &&
            (status.connectedDeviceName == (device.productName ?: device.deviceName))
    val lockdown = if (isClaimed) status.lockdownInfo else null

    // Dot color: green = active/claimed/paired, amber = permission pending or trust needed, red = error
    val dotColor = when {
        lockdown?.isPaired == true -> StatusGreen
        lockdown?.needsTrust == true -> StatusAmber
        isClaimed -> StatusGreen
        !hasPermission -> StatusAmber
        isApple -> StatusGreen
        else -> StatusAmber
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_item_card_${device.deviceId}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isApple) Icons.Default.PhoneIphone else Icons.Default.Usb,
                        contentDescription = "Device Icon",
                        tint = if (isApple) CalmBlue else TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        val displayName = if (!lockdown?.deviceName.isNullOrEmpty()) {
                            lockdown!!.deviceName
                        } else {
                            device.productName ?: device.deviceName
                        }
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isApple) {
                            Surface(
                                color = CalmBlueContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Apple iOS",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "VID: 0x${device.vendorId.toString(16).uppercase().padStart(4, '0')}  PID: 0x${device.productId.toString(16).uppercase().padStart(4, '0')}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )

                    if (lockdown != null) {
                        if (lockdown.productType.isNotEmpty() || lockdown.productVersion.isNotEmpty()) {
                            Text(
                                text = "${lockdown.productType.ifEmpty { "iOS Device" }} • iOS ${lockdown.productVersion.ifEmpty { "Unknown" }}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                        }
                        if (lockdown.udid.isNotEmpty()) {
                            val shortUdid = if (lockdown.udid.length > 16) {
                                "${lockdown.udid.take(8)}...${lockdown.udid.takeLast(6)}"
                            } else lockdown.udid
                            Text(
                                text = "UDID: $shortUdid",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted
                            )
                        }
                        if (lockdown.isPaired) {
                            Text(
                                text = "✓ Paired & trusted via lockdown",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusGreen
                            )
                        } else if (lockdown.errorMessage.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = lockdown.errorMessage,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (lockdown.needsTrust) StatusAmber else StatusRed,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Text(
                                    text = "Retry",
                                    fontSize = 11.sp,
                                    color = CalmBlue,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { onRetryLockdown() }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    } else if (isClaimed) {
                        Text(
                            text = "Claimed usbmux interface (FD: ${status.fd})",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusGreen
                        )
                    }
                }
            }

            if (!hasPermission) {
                FilledTonalButton(
                    onClick = { onRequestPermission(device) },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = CalmBlue,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("grant_usb_permission_button")
                ) {
                    Text("Grant", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Permission granted",
                    tint = StatusGreen,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun ExpandableTechnicalLogCard(
    logs: List<String>,
    onClearLogs: () -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "chevron")
    val listState = rememberLazyListState()
    val latestLog = logs.lastOrNull() ?: "No log events yet"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("technical_log_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Clickable header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = CalmBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Technical Log (${logs.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (expanded && logs.isNotEmpty()) {
                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier.size(28.dp).testTag("clear_log_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = TextSecondary,
                        modifier = Modifier.rotate(chevronRotation)
                    )
                }
            }

            // Preview line when collapsed
            if (!expanded) {
                Text(
                    text = latestLog,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Expanded view
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    if (logs.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    val text = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(logs.joinToString("\n"))
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Technical Logs", text))
                                    Toast.makeText(context, "Copied ${logs.size} log entries to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("copy_logs_button"),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = DarkSurfaceVariant,
                                    contentColor = CalmBlue
                                )
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy Logs", fontSize = 12.sp)
                            }

                            FilledTonalButton(
                                onClick = {
                                    val text = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(logs.joinToString("\n"))
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "iOS Sideload Bridge Diagnostic Logs")
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share Diagnostic Logs"))
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("share_logs_button"),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = DarkSurfaceVariant,
                                    contentColor = CalmBlue
                                )
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share Logs", fontSize = 12.sp)
                            }
                        }
                    }

                    Surface(
                        color = DarkBackground,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    ) {
                        if (logs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No log output recorded yet.",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(logs) { log ->
                                    val textColor = when {
                                        log.contains("granted", true) || log.contains("successfully", true) -> StatusGreen
                                        log.contains("error", true) || log.contains("fail", true) || log.contains("denied", true) -> StatusRed
                                        log.contains("attached", true) || log.contains("Apple", true) -> CalmBlueLight
                                        else -> TextSecondary
                                    }
                                    Text(
                                        text = log,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = textColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* =========================================================================================
 * TAB 2: INSTALL SCREEN
 * ========================================================================================= */

@Composable
fun InstallTabScreen(
    status: BridgeStatus,
    connectedDevices: List<UsbDevice>,
    usbBridge: UsbBridge
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // Apple Account state & storage
    val authStorage = remember { AuthSessionStorage(context) }
    val anisetteClient = remember { AnisetteClient(timeoutSeconds = 5L) }
    val appleAuthClient = remember { AppleAuthClient(timeoutSeconds = 15L, anisetteClient = anisetteClient) }

    val provisioningStorage = remember { ProvisioningStorage(context) }
    val developerClient = remember { AppleDeveloperClient(timeoutSeconds = 25L) }

    val initialSavedSession = remember {
        authStorage.loadSession()?.takeIf {
            it.tokens["com.apple.gs.xcode.auth"]?.isNotBlank() == true || it.pet.isNotBlank()
        }
    }
    var authSession by remember { mutableStateOf<AppleAuthSession?>(initialSavedSession) }
    var isSignedIn by remember { mutableStateOf(initialSavedSession != null) }
    var appleIdText by rememberSaveable { mutableStateOf(initialSavedSession?.appleId ?: "") }
    // Never persist the account password to disk or Android savedInstanceState Bundle
    var passwordText by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var show2faBox by remember { mutableStateOf(false) }
    var twoFactorCode by remember { mutableStateOf(List(6) { "" }) }

    var isSigningIn by remember { mutableStateOf(false) }
    var signInCooldownSeconds by rememberSaveable { mutableIntStateOf(0) }
    var isSubmitting2Fa by remember { mutableStateOf(false) }
    var isResending2Fa by remember { mutableStateOf(false) }
    var authErrorMessage by remember { mutableStateOf<String?>(null) }
    var pending2FaContext by remember { mutableStateOf<SignInContext?>(null) }
    var twoFactorMessage by remember { mutableStateOf<String?>(null) }

    // 60-second cooldown countdown timer after a failed sign-in
    LaunchedEffect(signInCooldownSeconds) {
        if (signInCooldownSeconds > 0) {
            kotlinx.coroutines.delay(1000L)
            signInCooldownSeconds -= 1
        }
    }

    // Safe inspection / dry run state for Auth
    var isDryRunMode by remember { mutableStateOf(false) }
    var dryRunInspection by remember { mutableStateOf<DryRunInspection?>(null) }
    var isInspectingDryRun by remember { mutableStateOf(false) }

    // Provisioning & Teams state
    var provisionedIdentity by remember { mutableStateOf<ProvisionedIdentity?>(provisioningStorage.loadIdentity()) }
    var developerTeams by remember { mutableStateOf<List<DeveloperTeam>>(emptyList()) }
    var selectedTeam by remember { mutableStateOf<DeveloperTeam?>(null) }
    var isFetchingTeams by remember { mutableStateOf(false) }
    var isProvisioning by remember { mutableStateOf(false) }
    var provisioningStageIndex by remember { mutableStateOf(0) }
    var provisioningStageMessage by remember { mutableStateOf<String?>(null) }
    var provisioningError by remember { mutableStateOf<String?>(null) }
    var isProvDryRunMode by remember { mutableStateOf(false) }
    var provDryRunInspection by remember { mutableStateOf<DryRunInspection?>(null) }
    var isInspectingProvDryRun by remember { mutableStateOf(false) }

    // Selected IPA state
    var selectedIpa by remember { mutableStateOf<SelectedIpaInfo?>(null) }

    // Live Installation state
    var installProgress by remember { mutableStateOf(InstallProgress()) }
    var copyProgressText by remember { mutableStateOf<String?>(null) }
    var installJob by remember { mutableStateOf<Job?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = queryFileName(context, uri) ?: "SelectedApp.ipa"
            val fileSize = queryFileSizeFormatted(context, uri)
            val fallbackAppName = fileName.removeSuffix(".ipa")
            val fallbackBundleId = "com.sideload.${fallbackAppName.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "app" }}"

            selectedIpa = SelectedIpaInfo(
                uri = uri,
                fileName = fileName,
                fileSizeFormatted = fileSize,
                appName = fallbackAppName,
                bundleId = fallbackBundleId,
                version = "1.0.0"
            )
            installProgress = InstallProgress()
            copyProgressText = null

            // Asynchronously extract real CFBundleIdentifier and CFBundleDisplayName if available
            coroutineScope.launch {
                val (extractedBundle, extractedName) = extractIpaMetadata(context, uri)
                if (extractedBundle != null || extractedName != null) {
                    selectedIpa = selectedIpa?.copy(
                        bundleId = extractedBundle ?: fallbackBundleId,
                        appName = extractedName ?: fallbackAppName
                    )
                }
            }
        }
    }

    fun isSessionRejectedOrExpired(err: Throwable): Boolean {
        if (err is com.aistudio.iossideloadbridge.app.provisioning.AppleDeveloperException) {
            if (err.resultCode == 1100 || err.resultCode == 1102 || err.resultCode == -22411) return true
        }
        val msg = err.message ?: ""
        return msg.contains("session has expired", ignoreCase = true) ||
            msg.contains("session expired", ignoreCase = true) ||
            msg.contains("HTTP 401", ignoreCase = true) ||
            msg.contains("HTTP 403", ignoreCase = true) ||
            msg.contains("X-Apple-GS-Token) is missing", ignoreCase = true) ||
            msg.contains("token is missing", ignoreCase = true) ||
            msg.contains("rejected", ignoreCase = true) ||
            msg.contains("unauthorized", ignoreCase = true)
    }

    fun handleRejectedOrExpiredSession(reason: String) {
        com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.logAuthTrace(
            com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                "Saved session rejected or expired ($reason). Clearing only auth session; no automatic retry."
            )
        )
        // Clear only the rejected/expired auth session (do not clear provisioning identity or pairing data)
        authStorage.clearSession()
        authSession = null
        isSignedIn = false
        developerTeams = emptyList()
        selectedTeam = null
        val clearMsg = "Your saved session was rejected or has expired. Please sign in again."
        authErrorMessage = clearMsg
        provisioningError = clearMsg
    }

    // Verify persisted encrypted session and provisioning identity on mount
    LaunchedEffect(Unit) {
        val saved = authStorage.loadSession()
        if (saved != null) {
            val hasToken = saved.tokens["com.apple.gs.xcode.auth"]?.isNotBlank() == true || saved.pet.isNotBlank()
            if (hasToken) {
                authSession = saved
                isSignedIn = true
                appleIdText = saved.appleId
                com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.logAuthTrace(
                    com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                        "Restored saved session on start: Signed in as ${saved.appleId}"
                    )
                )
            } else {
                handleRejectedOrExpiredSession("missing Xcode auth token in saved session")
            }
        }
        val savedIdentity = provisioningStorage.loadIdentity()
        if (savedIdentity != null) {
            provisionedIdentity = savedIdentity
        }
    }

    fun refreshTeams(session: AppleAuthSession) {
        if (isFetchingTeams) return
        coroutineScope.launch {
            isFetchingTeams = true
            provisioningError = null
            try {
                val hasToken = session.pet.isNotEmpty() || session.tokens["com.apple.gs.xcode.auth"]?.isNotBlank() == true
                if (!hasToken) {
                    handleRejectedOrExpiredSession("session token missing")
                    return@launch
                }
                val aniResult = anisetteClient.fetchAnisetteHeaders()
                val anisette = aniResult.getOrElse {
                    provisioningError = "Failed to obtain Anisette headers: ${it.message}"
                    return@launch
                }
                val teamsResult = developerClient.fetchTeams(session, anisette)
                teamsResult.onSuccess { teams ->
                    developerTeams = teams
                    if (selectedTeam == null || teams.none { it.teamId == selectedTeam?.teamId }) {
                        selectedTeam = teams.firstOrNull()
                    }
                }.onFailure { err ->
                    if (isSessionRejectedOrExpired(err)) {
                        handleRejectedOrExpiredSession(err.message ?: "session rejected by Apple")
                    } else {
                        val msg = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                            err.message ?: "Unknown error"
                        )
                        provisioningError = "Failed to fetch developer teams: $msg"
                    }
                }
            } finally {
                isFetchingTeams = false
            }
        }
    }

    // Single lookup when signed in; no automatic retry loop if rejected
    LaunchedEffect(isSignedIn, authSession?.appleId) {
        val s = authSession
        if (isSignedIn && s != null && developerTeams.isEmpty()) {
            refreshTeams(s)
        }
    }

    fun startProvisioningPipeline() {
        if (isProvisioning) return
        val session = authSession ?: run {
            provisioningError = "Apple ID session is missing. Please sign in above."
            return
        }
        val team = selectedTeam ?: run {
            provisioningError = "Please select a developer team."
            return
        }
        val bundleId = selectedIpa?.bundleId ?: "com.sideload.sampleapp"
        val appName = selectedIpa?.appName ?: "SideloadApp"

        coroutineScope.launch {
            isProvisioning = true
            provisioningError = null
            provisioningStageIndex = 0
            provisioningStageMessage = "Initializing developer session..."
            try {
                val aniResult = anisetteClient.fetchAnisetteHeaders()
                val anisette = aniResult.getOrElse {
                    provisioningError = "Anisette headers error: ${it.message}"
                    return@launch
                }

                val targetUdid = status.lockdownInfo?.udid
                val targetDeviceName = status.lockdownInfo?.deviceName ?: "iOS Device"

                val result = developerClient.executeFullProvisioning(
                    session = session,
                    team = team,
                    bundleId = bundleId,
                    appName = appName,
                    anisette = anisette,
                    deviceUdid = targetUdid,
                    deviceName = targetDeviceName
                ) { stageIdx, stageMsg ->
                    provisioningStageIndex = stageIdx
                    provisioningStageMessage = stageMsg
                }

                result.onSuccess { identity ->
                    provisioningStorage.saveIdentity(identity)
                    provisionedIdentity = identity
                    Toast.makeText(context, "Provisioning profile ready for $bundleId!", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    if (isSessionRejectedOrExpired(err)) {
                        handleRejectedOrExpiredSession(err.message ?: "session rejected during provisioning")
                    } else {
                        provisioningError = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                            err.message ?: "Provisioning failed"
                        )
                    }
                }
            } finally {
                isProvisioning = false
            }
        }
    }

    fun inspectProvDryRun() {
        val session = authSession ?: run {
            provisioningError = "Please sign in to inspect provisioning requests."
            return
        }
        val team = selectedTeam ?: run {
            provisioningError = "Please select a developer team first."
            return
        }
        val bundleId = selectedIpa?.bundleId ?: "com.sideload.sampleapp"

        coroutineScope.launch {
            isInspectingProvDryRun = true
            provisioningError = null
            try {
                val aniResult = anisetteClient.fetchAnisetteHeaders()
                val anisette = aniResult.getOrElse {
                    provisioningError = "Failed to fetch Anisette headers: ${it.message}"
                    return@launch
                }
                val inspection = developerClient.inspectProvisioningRequest(
                    session = session,
                    team = team,
                    bundleId = bundleId,
                    anisette = anisette
                )
                provDryRunInspection = inspection
            } catch (e: Exception) {
                provisioningError = "Failed to build inspection: ${e.message}"
            } finally {
                isInspectingProvDryRun = false
            }
        }
    }

    val isDeviceConnected = status.isInterfaceClaimed && connectedDevices.isNotEmpty()
    val canInstallSigned = isDeviceConnected && isSignedIn

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Tab Header
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text = "IPA Sideload Installer",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Sign and install iOS apps directly onto your device",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        // 1. Apple Account Card
        AppleAccountCard(
            isSignedIn = isSignedIn,
            session = authSession,
            appleIdText = appleIdText,
            onAppleIdChange = {
                appleIdText = it
                authErrorMessage = null
            },
            passwordText = passwordText,
            onPasswordChange = {
                passwordText = it
                authErrorMessage = null
            },
            passwordVisible = passwordVisible,
            onTogglePasswordVisibility = { passwordVisible = !passwordVisible },
            show2faBox = show2faBox,
            twoFactorCode = twoFactorCode,
            onTwoFactorCodeChange = { index, value ->
                if (value.length <= 1) {
                    val updated = twoFactorCode.toMutableList()
                    updated[index] = value
                    twoFactorCode = updated
                }
            },
            isSigningIn = isSigningIn,
            signInCooldownSeconds = signInCooldownSeconds,
            isSubmitting2Fa = isSubmitting2Fa,
            authErrorMessage = authErrorMessage,
            twoFactorMessage = twoFactorMessage,
            dryRunInspection = dryRunInspection,
            isInspectingDryRun = isInspectingDryRun,
            isDryRunMode = isDryRunMode,
            onToggleDryRunMode = {
                isDryRunMode = !isDryRunMode
                dryRunInspection = null
            },
            onInspectDryRun = {
                if (appleIdText.isBlank()) {
                    authErrorMessage = "Please enter an Apple ID email before inspecting request."
                    return@AppleAccountCard
                }
                coroutineScope.launch {
                    isInspectingDryRun = true
                    authErrorMessage = null
                    try {
                        val aniResult = anisetteClient.fetchAnisetteHeaders()
                        val anisette = aniResult.getOrThrow()
                        val inspection = appleAuthClient.inspectSignInRequest(appleIdText.trim(), anisette)
                        dryRunInspection = inspection
                    } catch (e: Exception) {
                        authErrorMessage = "Failed to fetch Anisette headers for inspection: ${e.message}"
                    } finally {
                        isInspectingDryRun = false
                    }
                }
            },
            onSignInClick = {
                // One request per tap: ignore if already signing in or in 60s failure cooldown
                if (isSigningIn || signInCooldownSeconds > 0) {
                    return@AppleAccountCard
                }
                if (appleIdText.isBlank() || passwordText.isBlank()) {
                    authErrorMessage = "Please enter both Apple ID and password."
                    return@AppleAccountCard
                }
                isSigningIn = true
                val submittedAppleId = appleIdText.trim()
                val submittedPassword = passwordText
                passwordText = ""
                com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.logAuthTrace("Sign In button tapped (one request per tap)")
                android.util.Log.i("MainActivity", "Sign In button tapped")
                coroutineScope.launch {
                    authErrorMessage = null
                    show2faBox = false
                    pending2FaContext = null
                    try {
                        val aniResult = anisetteClient.fetchAnisetteHeaders()
                        val anisette = aniResult.getOrElse {
                            signInCooldownSeconds = 60
                            authErrorMessage = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                                "Could not fetch Anisette data: ${it.message}"
                            )
                            return@launch
                        }

                        val result = appleAuthClient.signIn(submittedAppleId, submittedPassword, anisette)
                        result.onSuccess { state ->
                            when (state) {
                                is SignInState.Success -> {
                                    val token = state.session.tokens["com.apple.gs.xcode.auth"] ?: state.session.pet
                                    if (token.isNotBlank()) {
                                        authStorage.saveSession(state.session)
                                        authSession = state.session
                                        isSignedIn = true
                                        signInCooldownSeconds = 0
                                        Toast.makeText(context, "Successfully signed in to Apple ID!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        signInCooldownSeconds = 60
                                        authErrorMessage = "Login finished but Xcode session token (com.apple.gs.xcode.auth) was missing. Please retry."
                                        isSignedIn = false
                                        authSession = null
                                    }
                                }
                                is SignInState.NeedsTwoFactor -> {
                                    com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.logAuthTrace("2FA UI shown")
                                    android.util.Log.i("MainActivity", "2FA UI shown")
                                    pending2FaContext = state.context
                                    twoFactorMessage = state.promptMessage
                                    show2faBox = true
                                    twoFactorCode = List(6) { "" }
                                }
                                is SignInState.Failure -> {
                                    signInCooldownSeconds = 60
                                    authErrorMessage = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                                        state.errorMessage
                                    )
                                }
                            }
                        }.onFailure { err ->
                            signInCooldownSeconds = 60
                            val safeErr = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                                err.message ?: "Unknown error"
                            )
                            authErrorMessage = "Sign in error: $safeErr"
                            usbBridge.addLog(
                                com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                                    "[TLS DIAG] GrandSlam sign-in failed: ${err.javaClass.name}: $safeErr"
                                )
                            )
                            usbBridge.addLog("[TLS DIAG] Host: ${AppleAuthClient.GSA_ENDPOINT}")
                            AppleTrustHelper.lastErrorDiagnostic.forEach { line ->
                                usbBridge.addLog(com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(line))
                            }
                        }
                    } finally {
                        isSigningIn = false
                    }
                }
            },
            onSubmit2FaClick = {
                if (isSubmitting2Fa) {
                    return@AppleAccountCard
                }
                val contextToResume = pending2FaContext
                val code = twoFactorCode.joinToString("").trim()
                if (contextToResume == null) {
                    authErrorMessage = "No active 2FA session context. Please sign in again."
                    return@AppleAccountCard
                }
                if (code.length != 6) {
                    authErrorMessage = "Please enter all 6 digits of the verification code."
                    return@AppleAccountCard
                }
                isSubmitting2Fa = true
                coroutineScope.launch {
                    authErrorMessage = null
                    try {
                        val result = appleAuthClient.submitTwoFactorCode(code, contextToResume)
                        result.onSuccess { state ->
                            when (state) {
                                is SignInState.Success -> {
                                    val token = state.session.tokens["com.apple.gs.xcode.auth"] ?: state.session.pet
                                    if (token.isNotBlank()) {
                                        authStorage.saveSession(state.session)
                                        authSession = state.session
                                        isSignedIn = true
                                        show2faBox = false
                                        pending2FaContext = null
                                        passwordText = ""
                                        signInCooldownSeconds = 0
                                        Toast.makeText(context, "2FA verified! Signed in to Apple ID.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        signInCooldownSeconds = 60
                                        show2faBox = false
                                        pending2FaContext = null
                                        authErrorMessage = "2FA verified but Xcode session token (com.apple.gs.xcode.auth) was missing. Please retry."
                                        isSignedIn = false
                                        authSession = null
                                    }
                                }
                                is SignInState.NeedsTwoFactor -> {
                                    authErrorMessage = "Verification code incorrect or expired. Please retry."
                                }
                                is SignInState.Failure -> {
                                    signInCooldownSeconds = 60
                                    val rawMsg = if (state.errorMessage.startsWith("[")) {
                                        state.errorMessage
                                    } else {
                                        "2FA validation failed: ${state.errorMessage}"
                                    }
                                    val msg = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(rawMsg)
                                    authErrorMessage = msg
                                    usbBridge.addLog(com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets("2FA failure: $msg"))
                                }
                            }
                        }.onFailure { err ->
                            signInCooldownSeconds = 60
                            val netMsg = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                                "[validate request] Network failure: ${err.message}"
                            )
                            authErrorMessage = netMsg
                            usbBridge.addLog(netMsg)
                            usbBridge.addLog(
                                com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                                    "[TLS DIAG] 2FA validation failed: ${err.javaClass.name}: ${err.message}"
                                )
                            )
                            usbBridge.addLog("[TLS DIAG] Host: ${AppleAuthClient.GSA_VALIDATE_ENDPOINT}")
                            AppleTrustHelper.lastErrorDiagnostic.forEach { line ->
                                usbBridge.addLog(com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(line))
                            }
                        }
                    } finally {
                        isSubmitting2Fa = false
                    }
                }
            },
            onResend2FaClick = {
                if (isResending2Fa) return@AppleAccountCard
                val contextToResume = pending2FaContext
                if (contextToResume == null) {
                    authErrorMessage = "No active 2FA session context. Please sign in again."
                    return@AppleAccountCard
                }
                isResending2Fa = true
                coroutineScope.launch {
                    authErrorMessage = null
                    try {
                        val result = appleAuthClient.triggerTwoFactorVerification(contextToResume)
                        result.onSuccess {
                            Toast.makeText(context, "Verification code sent to your trusted devices!", Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            authErrorMessage = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                                "Failed to resend 2FA code: ${err.message}"
                            )
                        }
                    } finally {
                        isResending2Fa = false
                    }
                }
            },
            isResending2Fa = isResending2Fa,
            onSignOutClick = {
                com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.logAuthTrace(
                    "Sign Out tapped: clearing all saved account and provisioning data."
                )
                authStorage.clearSession()
                provisioningStorage.clearIdentity()
                authSession = null
                isSignedIn = false
                appleIdText = ""
                passwordText = ""
                show2faBox = false
                twoFactorCode = List(6) { "" }
                pending2FaContext = null
                twoFactorMessage = null
                dryRunInspection = null
                authErrorMessage = null
                provisionedIdentity = null
                developerTeams = emptyList()
                selectedTeam = null
                provDryRunInspection = null
                provisioningError = null
                Toast.makeText(context, "Signed out and cleared all saved account data.", Toast.LENGTH_SHORT).show()
            }
        )

        // 2. IPA File Card
        IpaFileCard(
            selectedIpa = selectedIpa,
            onSelectIpa = { filePickerLauncher.launch("*/*") }
        )

        // 3. Certificate & Provisioning Profile Card
        ProvisioningPipelineCard(
            isSignedIn = isSignedIn,
            session = authSession,
            targetBundleId = selectedIpa?.bundleId ?: "com.sideload.sampleapp",
            targetAppName = selectedIpa?.appName ?: "SideloadApp",
            teams = developerTeams,
            selectedTeam = selectedTeam,
            onSelectTeam = { selectedTeam = it },
            onFetchTeams = {
                authSession?.let { refreshTeams(it) }
            },
            isFetchingTeams = isFetchingTeams,
            identity = provisionedIdentity,
            isProvisioning = isProvisioning,
            provisioningStageIndex = provisioningStageIndex,
            provisioningStageMessage = provisioningStageMessage,
            provisioningError = provisioningError,
            onStartProvisioning = { startProvisioningPipeline() },
            onClearProvisioning = {
                provisioningStorage.clearIdentity()
                provisionedIdentity = null
            },
            dryRunInspection = provDryRunInspection,
            isInspectingDryRun = isInspectingProvDryRun,
            isDryRunMode = isProvDryRunMode,
            onToggleDryRunMode = {
                isProvDryRunMode = !isProvDryRunMode
                provDryRunInspection = null
            },
            onInspectDryRun = { inspectProvDryRun() }
        )

        // 4. Install Actions Section (Standard sign-in button + Test Pre-signed IPA button)
        InstallActionButtonSection(
            canInstallSigned = canInstallSigned,
            isDeviceConnected = isDeviceConnected,
            isSignedIn = isSignedIn,
            hasIpa = selectedIpa != null,
            isInstalling = installProgress.isInstalling,
            onInstallSignedClick = {
                val ipaUri = selectedIpa?.uri
                val identity = provisionedIdentity
                if (ipaUri == null) {
                    Toast.makeText(context, "Please select an IPA file first.", Toast.LENGTH_SHORT).show()
                } else if (identity == null) {
                    Toast.makeText(
                        context,
                        "Please request a development certificate & provisioning profile first using the card above.",
                        Toast.LENGTH_LONG
                    ).show()
                } else if (!installProgress.isInstalling) {
                    installJob = coroutineScope.launch {
                        var tempUnsignedFile: File? = null
                        var signedIpaFile: File? = null
                        try {
                            copyProgressText = "Copying IPA to temporary cache..."
                            installProgress = InstallProgress(
                                isInstalling = true,
                                stage = "PREPARING",
                                percent = 0
                            )
                            tempUnsignedFile = copyUriToCache(context, ipaUri) { copied, total ->
                                val pct = if (total > 0) ((copied * 100) / total).toInt() else 0
                                val mbCopied = copied / (1024 * 1024)
                                val mbTotal = total / (1024 * 1024)
                                copyProgressText = "Caching IPA: $pct% ($mbCopied MB / $mbTotal MB)"
                            }
                            copyProgressText = null

                            // Code-sign IPA with native Mach-O signer
                            installProgress = InstallProgress(
                                isInstalling = true,
                                stage = "SIGNING",
                                percent = 10,
                                plainErrorMessage = "Signing Mach-O binaries, frameworks & embedding provisioning profile..."
                            )

                            val signResult = withContext(Dispatchers.Default) {
                                IpaSigner.signIpa(
                                    context = context,
                                    ipaPath = tempUnsignedFile.absolutePath,
                                    certificatePem = identity.certificatePem,
                                    privateKeyPem = identity.privateKeyPem,
                                    provisioningProfileData = identity.profileBytes,
                                    bundleId = selectedIpa?.bundleId,
                                    appName = selectedIpa?.appName
                                )
                            }

                            if (signResult.isFailure) {
                                val err = signResult.exceptionOrNull()?.message ?: "Unknown signing error"
                                installProgress = InstallProgress(
                                    isInstalling = false,
                                    stage = "ERROR",
                                    rawErrorName = "CodeSigningFailed",
                                    plainErrorMessage = "Code-signing failed: $err"
                                )
                                return@launch
                            }

                            val signedPath = signResult.getOrThrow()
                            signedIpaFile = File(signedPath)

                            installProgress = InstallProgress(
                                isInstalling = true,
                                stage = "SIGNED",
                                percent = 25,
                                plainErrorMessage = "IPA successfully signed! Transferring to device via AFC..."
                            )

                            // Hand off correctly signed IPA directly to AFC install pipeline
                            usbBridge.installIpa(signedIpaFile) { p ->
                                installProgress = p
                            }
                        } catch (e: Exception) {
                            installProgress = InstallProgress(
                                isInstalling = false,
                                stage = "ERROR",
                                rawErrorName = "Exception",
                                plainErrorMessage = "Failed to sign or install IPA: ${e.message}"
                            )
                        } finally {
                            copyProgressText = null
                            try { tempUnsignedFile?.delete() } catch (_: Exception) {}
                        }
                    }
                }
            },
            onInstallPreSignedClick = {
                val ipaUri = selectedIpa?.uri
                if (ipaUri != null && !installProgress.isInstalling) {
                    installJob = coroutineScope.launch {
                        var tempFile: File? = null
                        try {
                            copyProgressText = "Copying IPA to temporary cache..."
                            installProgress = InstallProgress(isInstalling = true, stage = "PREPARING", percent = 0)
                            tempFile = copyUriToCache(context, ipaUri) { copied, total ->
                                val pct = if (total > 0) ((copied * 100) / total).toInt() else 0
                                val mbCopied = copied / (1024 * 1024)
                                val mbTotal = total / (1024 * 1024)
                                copyProgressText = "Caching IPA: $pct% ($mbCopied MB / $mbTotal MB)"
                            }
                            copyProgressText = null
                            usbBridge.installIpa(tempFile) { p ->
                                installProgress = p
                            }
                        } catch (e: Exception) {
                            installProgress = InstallProgress(
                                isInstalling = false,
                                stage = "ERROR",
                                rawErrorName = "Exception",
                                plainErrorMessage = "Failed to copy or process IPA: ${e.message}"
                            )
                        } finally {
                            copyProgressText = null
                            try { tempFile?.delete() } catch (_: Exception) {}
                        }
                    }
                }
            },
            onCancelInstallClick = {
                usbBridge.cancelInstall()
                installJob?.cancel()
                installProgress = InstallProgress(
                    isInstalling = false,
                    stage = "CANCELLED",
                    plainErrorMessage = "Installation was cancelled by user."
                )
            }
        )

        // 4. Progress Pipeline (Live AFC Stage)
        InstallProgressPipelineCard(
            installProgress = installProgress,
            copyProgressText = copyProgressText,
            provisionedIdentity = provisionedIdentity,
            isProvisioning = isProvisioning
        )

        // 5. Common Error States Reference Guide
        ErrorStatesGuideCard()

        // 6. Diagnostic Technical Log (with Copy/Share buttons)
        val logs by usbBridge.logs.collectAsState()
        ExpandableTechnicalLogCard(
            logs = logs,
            onClearLogs = { usbBridge.clearLogs() }
        )
    }
}

private suspend fun extractIpaMetadata(context: Context, uri: Uri): Pair<String?, String?> = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            java.util.zip.ZipInputStream(stream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.startsWith("Payload/") && name.endsWith(".app/Info.plist") && !name.contains("__MACOSX")) {
                        val bytes = zis.readBytes()
                        val text = String(bytes, Charsets.UTF_8)
                        var bundleId: String? = null
                        var appName: String? = null
                        if (text.contains("CFBundleIdentifier")) {
                            val idRegex = Regex("<key>CFBundleIdentifier</key>\\s*<string>([^<]+)</string>")
                            bundleId = idRegex.find(text)?.groupValues?.get(1)?.trim()
                            val nameRegex = Regex("<key>CFBundleDisplayName</key>\\s*<string>([^<]+)</string>")
                            appName = nameRegex.find(text)?.groupValues?.get(1)?.trim()
                                ?: Regex("<key>CFBundleName</key>\\s*<string>([^<]+)</string>").find(text)?.groupValues?.get(1)?.trim()
                        }
                        if (bundleId == null && bytes.size > 8 && bytes[0] == 'b'.code.toByte() && bytes[1] == 'p'.code.toByte()) {
                            val ascii = bytes.map { if (it in 32..126) it.toInt().toChar() else '.' }.joinToString("")
                            val idMatch = Regex("CFBundleIdentifier[.]+([a-zA-Z0-9.\\-_]{4,60})").find(ascii)
                            bundleId = idMatch?.groupValues?.get(1)?.trim('.')
                        }
                        return@withContext Pair(bundleId, appName)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    } catch (_: Exception) {}
    Pair(null, null)
}

private suspend fun copyUriToCache(
    context: Context,
    uri: Uri,
    onProgress: (Long, Long) -> Unit
): File = withContext(Dispatchers.IO) {
    val tempFile = File(context.cacheDir, "staging_install_${System.currentTimeMillis()}.ipa")
    val totalSize = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (_: Exception) { -1L }

    context.contentResolver.openInputStream(uri)?.use { input ->
        tempFile.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                copied += read
                onProgress(copied, totalSize)
            }
            output.flush()
        }
    } ?: throw IOException("Could not open stream for content URI: $uri")
    tempFile
}

@Composable
fun AppleAccountCard(
    isSignedIn: Boolean,
    session: AppleAuthSession?,
    appleIdText: String,
    onAppleIdChange: (String) -> Unit,
    passwordText: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisibility: () -> Unit,
    show2faBox: Boolean,
    twoFactorCode: List<String>,
    onTwoFactorCodeChange: (Int, String) -> Unit,
    isSigningIn: Boolean,
    signInCooldownSeconds: Int = 0,
    isSubmitting2Fa: Boolean,
    authErrorMessage: String?,
    twoFactorMessage: String?,
    dryRunInspection: DryRunInspection?,
    isInspectingDryRun: Boolean,
    isDryRunMode: Boolean,
    onToggleDryRunMode: () -> Unit,
    onInspectDryRun: () -> Unit,
    onSignInClick: () -> Unit,
    onSubmit2FaClick: () -> Unit,
    onResend2FaClick: () -> Unit = {},
    isResending2Fa: Boolean = false,
    onSignOutClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("apple_account_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = CalmBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Apple Developer Account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                val token = session?.tokens?.get("com.apple.gs.xcode.auth") ?: session?.pet ?: ""
                val hasXcodeToken = token.isNotBlank()
                val (badgeText, badgeColor) = when {
                    isSignedIn && hasXcodeToken -> "Authenticated" to StatusGreen
                    isSignedIn && !hasXcodeToken -> "Token Missing" to StatusRed
                    isSigningIn -> "Connecting..." to CalmBlue
                    show2faBox -> "2FA Required" to StatusAmber
                    isDryRunMode -> "Safe Mode" to CalmBlueLight
                    else -> "GSA / SRP-6a" to CalmBlue
                }

                Surface(
                    color = DarkSurfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = badgeColor,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (isSignedIn) {
                // ==========================================
                // Signed In View (Real Active Session)
                // ==========================================
                Surface(
                    color = DarkBackground,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusGreen.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val maskedEmail = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(
                                session?.appleId?.ifBlank { appleIdText } ?: appleIdText
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Active Session",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                                Text(
                                    text = "Signed in as $maskedEmail",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }

                            TextButton(
                                onClick = onSignOutClick,
                                colors = ButtonDefaults.textButtonColors(contentColor = StatusRed),
                                modifier = Modifier.testTag("apple_signout_button")
                            ) {
                                Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sign Out")
                            }
                        }

                        if (session != null) {
                            Divider(color = DarkBorder, thickness = 0.5.dp)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (session.dsid.isNotEmpty()) {
                                    val shortDsid = if (session.dsid.length > 4) "${session.dsid.take(2)}***${session.dsid.takeLast(2)}" else "***"
                                    Text(
                                        text = "DSID: $shortDsid",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextSecondary
                                    )
                                }
                                if (session.adsid.isNotEmpty()) {
                                    val shortAdsid = if (session.adsid.length > 16) "${session.adsid.take(8)}...${session.adsid.takeLast(6)}" else session.adsid
                                    Text(
                                        text = "ADSID: $shortAdsid",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextSecondary
                                    )
                                }
                                val hasToken = session.tokens.containsKey("com.apple.gs.xcode.auth") || session.pet.isNotEmpty()
                                val tokenStatus = if (hasToken) "Present (com.apple.gs.xcode.auth)" else "Not present (tap Sign Out & re-login)"
                                Text(
                                    text = "Xcode Auth Token: $tokenStatus",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (hasToken) StatusGreen else StatusRed
                                )
                                Text(
                                    text = "Tokens cached: ${session.tokens.size} (${session.tokens.keys.joinToString(", ").ifEmpty { "None" }})",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            } else {
                // ==========================================
                // Signed Out View (Input & Authentication Flow)
                // ==========================================

                // Error message banner
                if (authErrorMessage != null) {
                    Surface(
                        color = StatusRed.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StatusRed.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusRed, modifier = Modifier.size(18.dp))
                            Text(
                                text = authErrorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusRed
                            )
                        }
                    }
                }

                // Apple ID text field
                OutlinedTextField(
                    value = appleIdText,
                    onValueChange = onAppleIdChange,
                    label = { Text("Apple ID (Email)") },
                    placeholder = { Text("user@example.com") },
                    singleLine = true,
                    enabled = !isSigningIn && !isSubmitting2Fa,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CalmBlue,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = CalmBlue,
                        unfocusedLabelColor = TextMuted,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = DarkBackground,
                        unfocusedContainerColor = DarkBackground
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("apple_id_input")
                )

                // Password text field
                OutlinedTextField(
                    value = passwordText,
                    onValueChange = onPasswordChange,
                    label = { Text("Apple ID Password") },
                    placeholder = { Text("••••••••") },
                    singleLine = true,
                    enabled = !isSigningIn && !isSubmitting2Fa,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onTogglePasswordVisibility) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                tint = TextMuted
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CalmBlue,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = CalmBlue,
                        unfocusedLabelColor = TextMuted,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = DarkBackground,
                        unfocusedContainerColor = DarkBackground
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("apple_password_input")
                )

                // Safe Inspection Mode Switch Row
                Surface(
                    color = DarkBackground,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isDryRunMode) CalmBlue else DarkBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleDryRunMode() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Safe Inspection Mode (Dry Run)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDryRunMode) CalmBlueLight else TextPrimary
                            )
                            Text(
                                text = "Inspects the exact SRP A2k payload without sending credentials to Apple",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                        Switch(
                            checked = isDryRunMode,
                            onCheckedChange = { onToggleDryRunMode() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = CalmBlue,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = DarkSurfaceVariant
                            )
                        )
                    }
                }

                // Dry Run Inspection Viewer Panel
                if (isDryRunMode) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onInspectDryRun,
                            enabled = !isInspectingDryRun,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CalmBlueContainer,
                                contentColor = CalmBlueLight
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("inspect_dry_run_button")
                        ) {
                            if (isInspectingDryRun) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CalmBlueLight, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Building inspection payload...")
                            } else {
                                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Inspect Prepared GSA Request")
                            }
                        }

                        dryRunInspection?.let { inspection ->
                            Surface(
                                color = DarkBackground,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, CalmBlue.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dry_run_inspection_card")
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Prepared GSA SRP Step 1 Request:",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = CalmBlueLight
                                    )
                                    Text(
                                        text = "POST ${inspection.endpoint}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = TextPrimary
                                    )
                                    Divider(color = DarkBorder, thickness = 0.5.dp)

                                    Text(
                                        text = "HTTP Request Headers:",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                    inspection.httpHeaders.forEach { (k, v) ->
                                        Text(
                                            text = "$k: $v",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Divider(color = DarkBorder, thickness = 0.5.dp)
                                    Text(
                                        text = "XML Plist Payload:",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = inspection.xmlPayload,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // 2FA Code Entry Section
                AnimatedVisibility(visible = show2faBox) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = StatusAmber.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StatusAmber.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = twoFactorMessage ?: "Enter 6-Digit Verification Code (2FA) sent to your trusted Apple devices:",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusAmber,
                                modifier = Modifier.padding(10.dp)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            for (i in 0 until 6) {
                                OutlinedTextField(
                                    value = twoFactorCode[i],
                                    onValueChange = { onTwoFactorCodeChange(i, it) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    textStyle = LocalTextStyle.current.copy(
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = TextPrimary
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = CalmBlue,
                                        unfocusedBorderColor = DarkBorder,
                                        focusedContainerColor = DarkBackground,
                                        unfocusedContainerColor = DarkBackground
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .width(44.dp)
                                        .height(52.dp)
                                        .testTag("2fa_box_$i")
                                )
                            }
                        }

                        Button(
                            onClick = onSubmit2FaClick,
                            enabled = !isSubmitting2Fa && twoFactorCode.all { it.isNotEmpty() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CalmBlue,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("submit_2fa_button")
                        ) {
                            if (isSubmitting2Fa) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Verifying Code...")
                            } else {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Submit Verification Code")
                            }
                        }

                        // Resend Code Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = onResend2FaClick,
                                enabled = !isResending2Fa && !isSubmitting2Fa,
                                modifier = Modifier.testTag("resend_2fa_button")
                            ) {
                                if (isResending2Fa) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = CalmBlueLight)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Requesting new code...", fontSize = 13.sp, color = CalmBlueLight)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = CalmBlueLight)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Resend code to trusted devices", fontSize = 13.sp, color = CalmBlueLight)
                                }
                            }
                        }
                    }
                }

                // Normal Sign In Button (when not in 2FA mode)
                if (!show2faBox && !isDryRunMode) {
                    Button(
                        onClick = onSignInClick,
                        enabled = !isSigningIn && signInCooldownSeconds <= 0 && appleIdText.isNotBlank() && passwordText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CalmBlue,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("apple_signin_button")
                    ) {
                        if (isSigningIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Authenticating with Apple...")
                        } else if (signInCooldownSeconds > 0) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign In disabled (${signInCooldownSeconds}s)", fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign In to Apple ID (GSA)", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IpaFileCard(
    selectedIpa: SelectedIpaInfo?,
    onSelectIpa: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ipa_file_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        tint = CalmBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "IPA Package",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                FilledTonalButton(
                    onClick = onSelectIpa,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = DarkSurfaceVariant,
                        contentColor = CalmBlue
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("select_ipa_button")
                ) {
                    Text(if (selectedIpa == null) "Select IPA" else "Change", fontSize = 13.sp)
                }
            }

            if (selectedIpa == null) {
                Surface(
                    color = DarkBackground,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "No IPA file selected",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )
                        Text(
                            text = "Tap 'Select IPA' to choose an iOS app archive (.ipa)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }
            } else {
                Surface(
                    color = DarkBackground,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(CalmBlueContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.InstallMobile,
                                contentDescription = "App Icon Placeholder",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedIpa.appName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Bundle: ${selectedIpa.bundleId}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted
                            )
                            Text(
                                text = "Version: ${selectedIpa.version} • Size: ${selectedIpa.fileSizeFormatted}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InstallActionButtonSection(
    canInstallSigned: Boolean,
    isDeviceConnected: Boolean,
    isSignedIn: Boolean,
    hasIpa: Boolean,
    isInstalling: Boolean,
    onInstallSignedClick: () -> Unit,
    onInstallPreSignedClick: () -> Unit,
    onCancelInstallClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isInstalling) {
            Button(
                onClick = onCancelInstallClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusRed,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("cancel_install_button")
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cancel Installation", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        } else {
            // 1. Direct Pre-Signed IPA Test Button (no Apple sign-in required)
            Button(
                onClick = onInstallPreSignedClick,
                enabled = isDeviceConnected && hasIpa,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CalmBlue,
                    contentColor = Color.White,
                    disabledContainerColor = DarkSurfaceVariant,
                    disabledContentColor = TextMuted
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("install_presigned_ipa_button")
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Install pre-signed IPA (test)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }

            // 2. Standard signing route (honest: requires Apple ID & cert generation)
            OutlinedButton(
                onClick = onInstallSignedClick,
                enabled = canInstallSigned && hasIpa,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = TextPrimary,
                    disabledContentColor = TextMuted
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("install_to_iphone_button")
            ) {
                Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign & Install to iPhone", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }

        // Helper explanation text
        val reasonText = when {
            !isDeviceConnected -> "Connect and pair your iPhone on the Bridge tab first."
            !hasIpa -> "Select an IPA file above to install."
            isInstalling -> "Installing onto iOS device... Do not unplug USB cable."
            else -> "Ready to install. Tap 'Sign & Install to iPhone' to sign with developer certificate and install over AFC."
        }

        Text(
            text = reasonText,
            style = MaterialTheme.typography.labelSmall,
            color = if (isDeviceConnected && hasIpa) CalmBlueLight else TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun InstallProgressPipelineCard(
    installProgress: InstallProgress,
    copyProgressText: String?,
    provisionedIdentity: ProvisionedIdentity? = null,
    isProvisioning: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("install_progress_pipeline_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Installation Pipeline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                val (badgeText, badgeColor) = when {
                    installProgress.isComplete -> "Completed" to StatusGreen
                    installProgress.plainErrorMessage != null -> "Failed" to StatusRed
                    installProgress.isInstalling -> "In Progress" to CalmBlue
                    else -> "Idle" to TextMuted
                }

                Surface(
                    color = DarkSurfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Stage 1: Certificate & Provisioning
                val stage1State = when {
                    isProvisioning -> PipelineStageState.IN_PROGRESS
                    provisionedIdentity != null -> PipelineStageState.SUCCEEDED
                    else -> PipelineStageState.NOT_STARTED
                }
                val stage1Tag = when (stage1State) {
                    PipelineStageState.IN_PROGRESS -> "IN_PROGRESS: Requesting CSR / Apple API..."
                    PipelineStageState.SUCCEEDED -> "SUCCEEDED: ${provisionedIdentity?.certificateName?.ifBlank { "Cert Loaded" }}"
                    PipelineStageState.FAILED -> "FAILED: Certificate error"
                    PipelineStageState.NOT_STARTED -> "NOT_STARTED: Pending"
                }
                PipelineStageRow(
                    index = 1,
                    title = "Signing Certificate & Profile",
                    statusTag = stage1Tag,
                    state = stage1State
                )

                // Stage 2: Code Signing
                val stage2State = when {
                    installProgress.stage == "SIGNING" -> PipelineStageState.IN_PROGRESS
                    installProgress.stage == "ERROR" && installProgress.rawErrorName == "CodeSigningFailed" -> PipelineStageState.FAILED
                    installProgress.percent >= 25 || installProgress.stage == "SIGNED" ||
                            installProgress.stage == "TRANSFERRING" || installProgress.stage == "INSTALLING" || installProgress.isComplete -> PipelineStageState.SUCCEEDED
                    else -> PipelineStageState.NOT_STARTED
                }
                val stage2Tag = when (stage2State) {
                    PipelineStageState.IN_PROGRESS -> "IN_PROGRESS: Signing Mach-O binaries & profile..."
                    PipelineStageState.SUCCEEDED -> "SUCCEEDED: Signed via zsign native"
                    PipelineStageState.FAILED -> "FAILED: ${installProgress.plainErrorMessage ?: "Signing error"}"
                    PipelineStageState.NOT_STARTED -> if (provisionedIdentity != null) "NOT_STARTED: Ready to sign" else "NOT_STARTED: Pending"
                }
                PipelineStageRow(
                    index = 2,
                    title = "Signing App & Provisioning",
                    statusTag = stage2Tag,
                    state = stage2State
                )

                // Stage 3: Live AFC & instproxy stage
                val stage3State = when {
                    installProgress.isComplete -> PipelineStageState.SUCCEEDED
                    installProgress.stage == "ERROR" && installProgress.rawErrorName != "CodeSigningFailed" -> PipelineStageState.FAILED
                    installProgress.isInstalling && installProgress.stage != "SIGNING" -> PipelineStageState.IN_PROGRESS
                    else -> PipelineStageState.NOT_STARTED
                }
                val stage3Tag = when (stage3State) {
                    PipelineStageState.SUCCEEDED -> "SUCCEEDED: Installed on iOS device"
                    PipelineStageState.IN_PROGRESS -> "IN_PROGRESS: ${installProgress.stage} (${installProgress.percent}%)"
                    PipelineStageState.FAILED -> "FAILED: ${installProgress.plainErrorMessage ?: "AFC Error"}"
                    PipelineStageState.NOT_STARTED -> "NOT_STARTED: Pending"
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (installProgress.isInstalling) DarkSurfaceVariant.copy(alpha = 0.5f) else Color.Transparent)
                        .padding(if (installProgress.isInstalling) 8.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PipelineStageRow(
                        index = 3,
                        title = "Installing via AFC protocol",
                        statusTag = stage3Tag,
                        state = stage3State
                    )

                    if (copyProgressText != null) {
                        Text(
                            text = copyProgressText,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = CalmBlueLight,
                            modifier = Modifier.padding(start = 32.dp)
                        )
                    }

                    if (installProgress.isInstalling) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Stage: ${installProgress.stage}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "${installProgress.percent}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = CalmBlue
                                )
                            }
                            LinearProgressIndicator(
                                progress = { installProgress.percent.coerceIn(0, 100) / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = CalmBlue,
                                trackColor = DarkSurfaceVariant
                            )
                            if (installProgress.totalBytes > 0L) {
                                Text(
                                    text = "${installProgress.bytesTransferred / 1024} KB / ${installProgress.totalBytes / 1024} KB transferred",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextMuted
                                )
                            }
                        }
                    }

                    if (installProgress.isComplete) {
                        Surface(
                            color = StatusGreen.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StatusGreen.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp)
                        ) {
                            Text(
                                text = "✓ App installed successfully on device!",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = StatusGreen,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    if (installProgress.plainErrorMessage != null) {
                        Surface(
                            color = StatusRed.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StatusRed.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Installation Failed",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusRed
                                )
                                Text(
                                    text = installProgress.plainErrorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                                if (!installProgress.rawErrorName.isNullOrEmpty()) {
                                    Text(
                                        text = "Raw iOS code: ${installProgress.rawErrorName} ${if (!installProgress.rawErrorDesc.isNullOrEmpty()) "(${installProgress.rawErrorDesc})" else ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }
                }

                // Stage 4: iOS Launch
                val stage4State = when {
                    installProgress.isComplete -> PipelineStageState.SUCCEEDED
                    else -> PipelineStageState.NOT_STARTED
                }
                val stage4Tag = when (stage4State) {
                    PipelineStageState.SUCCEEDED -> "SUCCEEDED: Ready to open on iOS home screen"
                    else -> "NOT_STARTED: Manual launch policy"
                }
                PipelineStageRow(
                    index = 4,
                    title = "Launch on iOS device",
                    statusTag = stage4Tag,
                    state = stage4State
                )
            }
        }
    }
}

@Composable
private fun PipelineStageRow(
    index: Int,
    title: String,
    statusTag: String,
    state: PipelineStageState
) {
    val isDone = state == PipelineStageState.SUCCEEDED
    val isActive = state == PipelineStageState.IN_PROGRESS
    val isFailed = state == PipelineStageState.FAILED
    val stateColor = when (state) {
        PipelineStageState.SUCCEEDED -> StatusGreen
        PipelineStageState.IN_PROGRESS -> CalmBlue
        PipelineStageState.FAILED -> StatusRed
        PipelineStageState.NOT_STARTED -> TextMuted
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isDone) StatusGreen else if (isFailed) StatusRed else if (isActive) CalmBlue else DarkSurfaceVariant)
                    .border(1.dp, if (isActive) CalmBlue else if (isFailed) StatusRed else DarkBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isDone) "✓" else if (isFailed) "!" else "$index",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDone || isActive || isFailed) Color.White else TextMuted
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive || isDone) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive || isDone) TextPrimary else TextSecondary
            )
        }

        Text(
            text = statusTag,
            fontSize = 10.sp,
            color = stateColor
        )
    }
}

@Composable
fun ErrorStatesGuideCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("error_states_guide_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Common iOS Installation Notes",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ErrorGuideRow(
                    title = "Signing Failure:",
                    detail = "Ensure Apple ID credentials and Anisette data are fresh. Certificates expire after 7 days on free accounts."
                )
                ErrorGuideRow(
                    title = "Free Apple ID 3-App Limit:",
                    detail = "Free Apple accounts can only have 3 active sideloaded apps at a time. Delete an existing app if this limit is reached."
                )
                ErrorGuideRow(
                    title = "AFC Transfer Failure:",
                    detail = "Usually occurs if the device is locked, untrusted, or disconnected during upload. Re-unlock and verify USB trust."
                )
            }
        }
    }
}

@Composable
fun ErrorGuideRow(title: String, detail: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = CalmBlueLight
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            fontSize = 12.sp
        )
    }
}

/* =========================================================================================
 * TAB 3: DIAGNOSTICS SCREEN
 * ========================================================================================= */

@Composable
fun DiagnosticsTabScreen(
    status: BridgeStatus,
    logs: List<String>,
    devices: List<UsbDevice>,
    usbBridge: UsbBridge,
    onClearLogs: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }

    var lastCrash by remember { mutableStateOf(com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.getLastCrash(context)) }
    var isRunningSelfTests by remember { mutableStateOf(false) }

    fun buildDiagnosticReport(): String {
        val sb = StringBuilder()
        sb.append("=== iOS Sideload Bridge Diagnostic Report ===\n")
        sb.append("Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n")
        sb.append("App Version: ${com.example.BuildConfig.VERSION_NAME}\n")
        sb.append("Package: ${context.packageName}\n")
        sb.append("Build Stamp: ${com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.BUILD_ID}\n")
        sb.append("Git Commit: ${com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.GIT_COMMIT}\n")
        sb.append("OS: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n")

        sb.append("--- USB Subsystem ---\n")
        sb.append("Daemon Running: ${status.isRunning} (Socket: @${status.socketName})\n")
        sb.append("Connected Devices Total: ${devices.size}\n")
        devices.forEachIndexed { i, dev ->
            val isApple = dev.vendorId == 0x05ac
            val prod = try { dev.productName } catch (_: SecurityException) { null } catch (_: Throwable) { null }
            val devName = prod ?: try { dev.deviceName } catch (_: Throwable) { null } ?: "USB device"
            sb.append("Device #$i: $devName (VID=0x${Integer.toHexString(dev.vendorId)}, PID=0x${Integer.toHexString(dev.productId)}, Apple=$isApple, configs=${dev.configurationCount})\n")
        }
        sb.append("Active Claimed Device: ${status.connectedDeviceName ?: "None"} (FD: ${status.fd}, Claimed: ${status.isInterfaceClaimed})\n")
        status.lockdownInfo?.let { lock ->
            sb.append("Lockdown: Device='${lock.deviceName}' (${lock.productType}, iOS ${lock.productVersion}), UDID=${lock.udid}, Paired=${lock.isPaired}, TrustRequired=${lock.needsTrust}\n")
        }
        sb.append("\n--- Authentication & Token Step Trace ---\n")
        val authTraceList = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.getAuthTrace()
        if (authTraceList.isEmpty()) {
            sb.append("No authentication step events recorded.\n")
        } else {
            authTraceList.forEach { line -> sb.append("$line\n") }
        }
        sb.append("\n--- TLS & Network Subsystem ---\n")
        sb.append("TrustManager: AppleTrustHelper (Composite System + Apple Root CA / Server Auth CA)\n")
        sb.append("Host Validation: gsa.apple.com strict certificate check\n")
        if (AppleTrustHelper.lastErrorDiagnostic.isNotEmpty()) {
            sb.append("Last TLS Error Diagnostics:\n")
            AppleTrustHelper.lastErrorDiagnostic.forEach { line -> sb.append("  $line\n") }
        } else {
            sb.append("No active TLS errors recorded.\n")
        }

        sb.append("\n--- Native Logcat Dump ---\n")
        val nativeLogs = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.readNativeLogcat(60)
        nativeLogs.forEach { nl -> sb.append("$nl\n") }

        sb.append("\n--- Complete Log Stream (${logs.size} entries) ---\n")
        logs.forEach { log -> sb.append("$log\n") }
        return com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(sb.toString())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Last Crash Alert Card
        lastCrash?.let { crashText ->
            Card(
                modifier = Modifier.fillMaxWidth().testTag("last_crash_card"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                border = androidx.compose.foundation.BorderStroke(1.dp, StatusRed)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Last Crash Recorded", color = StatusRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        TextButton(onClick = {
                            com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.clearLastCrash(context)
                            lastCrash = null
                        }) {
                            Text("Dismiss", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                    Text(crashText.take(400) + if (crashText.length > 400) "..." else "", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextPrimary)
                }
            }
        }

        // 1. Header Card
        Card(
            modifier = Modifier.fillMaxWidth().testTag("diagnostics_header_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("System Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Hardware, TLS & Pipeline Logs", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    Surface(color = DarkSurfaceVariant, shape = RoundedCornerShape(6.dp)) {
                        Text("Build: ${com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.BUILD_ID}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = CalmBlueLight, modifier = Modifier.padding(6.dp, 2.dp))
                    }
                }

                HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                Text("Package: ${context.packageName}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextSecondary)
                Text("Version: ${com.example.BuildConfig.VERSION_NAME} • Built: ${com.example.BuildConfig.BUILD_TIMESTAMP}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextSecondary)
                Text("Commit: ${com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.GIT_COMMIT} • Build ID: ${com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.BUILD_ID}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextSecondary)

                // Self-tests and USB Scan Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isRunningSelfTests = true
                                try {
                                    com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.runSelfTests(context) { line ->
                                        usbBridge.addLog(line)
                                    }
                                } finally {
                                    isRunningSelfTests = false
                                }
                            }
                        },
                        enabled = !isRunningSelfTests,
                        modifier = Modifier.weight(1f).testTag("run_self_tests_btn"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CalmBlueContainer, contentColor = CalmBlueLight)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isRunningSelfTests) "Testing..." else "Run self-tests", fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.scanUsbReadOnly(context, usbManager) { line ->
                                usbBridge.addLog(line)
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("usb_scan_readonly_btn"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = TextPrimary)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("USB scan (read-only)", fontSize = 11.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val text = buildDiagnosticReport()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostic Report", text))
                            Toast.makeText(context, "Copied full diagnostic report (${logs.size} logs)", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f).testTag("diag_copy_all_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = DarkSurfaceVariant, contentColor = CalmBlue)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy All", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    FilledTonalButton(
                        onClick = {
                            val text = buildDiagnosticReport()
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "iOS Sideload Bridge Diagnostic Report")
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Diagnostic Report"))
                        },
                        modifier = Modifier.weight(1f).testTag("diag_share_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = DarkSurfaceVariant, contentColor = CalmBlue)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 2. Authentication & Token Diagnostics Trace Card
        var authTraceList by remember { mutableStateOf(com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.getAuthTrace()) }

        Card(
            modifier = Modifier.fillMaxWidth().testTag("auth_trace_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Auth & Token Step Trace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("${authTraceList.size} step event(s) recorded", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            authTraceList = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.getAuthTrace()
                        }) {
                            Text("Refresh", fontSize = 11.sp, color = CalmBlue)
                        }
                        TextButton(onClick = {
                            com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.clearAuthTrace()
                            authTraceList = emptyList()
                        }) {
                            Text("Clear", fontSize = 11.sp, color = StatusRed)
                        }
                    }
                }

                HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                if (authTraceList.isEmpty()) {
                    Text("No authentication steps recorded yet. Sign in with Apple ID to generate a trace.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                } else {
                    Surface(
                        color = DarkBackground,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            authTraceList.forEach { entry ->
                                Text(
                                    text = entry,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = if (entry.contains("fail", ignoreCase = true) || entry.contains("error", ignoreCase = true) || entry.contains("mismatch", ignoreCase = true)) StatusRed else CalmBlueLight
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Technical Log Card
        ExpandableTechnicalLogCard(
            logs = logs,
            onClearLogs = onClearLogs
        )
    }
}

/* =========================================================================================
 * TAB 4: SETTINGS SCREEN
 * ========================================================================================= */

@Composable
fun SettingsTabScreen(
    status: BridgeStatus,
    logs: List<String>
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    var showClearDataDialog by remember { mutableStateOf(false) }
    var verboseLoggingEnabled by remember { mutableStateOf(false) }

    val anisetteClient = remember { AnisetteClient(timeoutSeconds = 5L) }
    val serverList = AnisetteClient.OFFICIAL_SERVERS
    var selectedServer by remember { mutableStateOf(serverList[0]) }

    var serverStatuses by remember { mutableStateOf<Map<String, ServerCheckResult>>(emptyMap()) }
    var isCheckingServers by remember { mutableStateOf(false) }

    var isTestingFetch by remember { mutableStateOf(false) }
    var testFetchResult by remember { mutableStateOf<Result<AnisetteHeaders>?>(null) }
    var testFetchDurationMs by remember { mutableStateOf(0L) }

    // Auto-check the first server on initial view to provide immediate feedback
    LaunchedEffect(Unit) {
        if (serverStatuses.isEmpty()) {
            val result = anisetteClient.checkServer(selectedServer)
            serverStatuses = mapOf(selectedServer to result)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Tab Header
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Anisette endpoints, storage, and bridge diagnostics",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        // 1. Anisette Server Section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("anisette_settings_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = null,
                            tint = CalmBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Anisette Servers",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    val anyReachable = serverStatuses.values.any { it.isReachable }
                    val allFailed = serverStatuses.isNotEmpty() && serverStatuses.values.all { !it.isReachable }

                    val (statusLabel, statusColor) = when {
                        isCheckingServers -> "Checking..." to CalmBlue
                        anyReachable -> "Reachable" to StatusGreen
                        allFailed -> "Unreachable" to StatusRed
                        else -> "Standby" to StatusAmber
                    }

                    Surface(
                        color = DarkSurfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = statusLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = "Anisette generates hardware attestation headers (X-Apple-I-MD, etc.) required for Apple GSA authentication without a physical Mac.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                // Server List with Real Reachability Status
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    serverList.forEach { serverUrl ->
                        val check = serverStatuses[serverUrl]
                        val isSelected = selectedServer == serverUrl

                        Surface(
                            color = if (isSelected) CalmBlueContainer.copy(alpha = 0.35f) else DarkBackground,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) CalmBlue else DarkBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedServer = serverUrl }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = serverUrl,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 12.sp,
                                            color = TextPrimary
                                        )
                                        if (serverUrl == serverList.first()) {
                                            Text(
                                                text = "Default",
                                                fontSize = 9.sp,
                                                color = CalmBlueLight,
                                                modifier = Modifier
                                                    .background(CalmBlueContainer.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }

                                    // Real status text per server
                                    if (check != null) {
                                        if (check.isReachable) {
                                            val verText = check.implementationVersion ?: "HTTP 200 OK"
                                            Text(
                                                text = "● Reachable (${check.responseTimeMs} ms) • $verText",
                                                fontSize = 11.sp,
                                                color = StatusGreen
                                            )
                                        } else {
                                            Text(
                                                text = "● Unreachable (${check.errorMessage ?: "Failed"})",
                                                fontSize = 11.sp,
                                                color = StatusRed
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "Not checked yet",
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    }
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = CalmBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Action Buttons: Check Status & Test Anisette Fetch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (!isCheckingServers) {
                                coroutineScope.launch {
                                    isCheckingServers = true
                                    try {
                                        val results = anisetteClient.checkAllServers(serverList)
                                        serverStatuses = results.associateBy { it.serverUrl }
                                    } finally {
                                        isCheckingServers = false
                                    }
                                }
                            }
                        },
                        enabled = !isCheckingServers,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("check_anisette_servers_button")
                    ) {
                        if (isCheckingServers) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = CalmBlue
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Checking...", fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Check Servers", fontSize = 12.sp)
                        }
                    }

                    Button(
                        onClick = {
                            if (!isTestingFetch) {
                                coroutineScope.launch {
                                    isTestingFetch = true
                                    testFetchResult = null
                                    val start = System.currentTimeMillis()
                                    try {
                                        val result = anisetteClient.fetchAnisetteHeaders(preferredServer = selectedServer)
                                        testFetchDurationMs = System.currentTimeMillis() - start
                                        testFetchResult = result
                                        // Update status of the winning server
                                        result.onSuccess { h ->
                                            serverStatuses = serverStatuses + (h.serverUsed to ServerCheckResult(
                                                serverUrl = h.serverUsed,
                                                isReachable = true,
                                                statusCode = 200,
                                                responseTimeMs = testFetchDurationMs,
                                                implementationVersion = h.implementationVersion,
                                                headers = h
                                            ))
                                        }
                                    } finally {
                                        isTestingFetch = false
                                    }
                                }
                            }
                        },
                        enabled = !isTestingFetch,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CalmBlue,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("test_anisette_fetch_button")
                    ) {
                        if (isTestingFetch) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Fetching...", fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Test Anisette Fetch", fontSize = 12.sp)
                        }
                    }
                }

                // Raw Test Fetch Result Display
                testFetchResult?.let { result ->
                    Surface(
                        color = DarkBackground,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (result.isSuccess) StatusGreen.copy(alpha = 0.5f) else StatusRed.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .testTag("anisette_test_result_panel")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            result.onSuccess { headers ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "✓ Anisette Headers Retrieved",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = StatusGreen
                                    )
                                    Text(
                                        text = "${testFetchDurationMs} ms",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                                Text(
                                    text = "Server: ${headers.serverUsed} (${headers.implementationVersion ?: "anisette-v3-server"})",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = CalmBlueLight
                                )
                                Divider(color = DarkBorder, thickness = 0.5.dp)

                                val items = listOf(
                                    "X-Apple-I-MD" to headers.md,
                                    "X-Apple-I-MD-M" to headers.mdM,
                                    "X-Apple-I-MD-RINFO" to headers.mdRinfo,
                                    "X-Apple-I-MD-LU" to headers.mdLu,
                                    "X-Apple-I-Client-Time" to headers.clientTime,
                                    "X-Apple-I-TimeZone" to headers.timeZone,
                                    "X-Apple-Locale" to headers.locale,
                                    "X-Apple-I-SRL-NO" to headers.srlNo,
                                    "X-Mme-Device-Id" to headers.deviceId,
                                    "X-MMe-Client-Info" to headers.clientInfo
                                )

                                items.forEach { (key, value) ->
                                    Column(modifier = Modifier.padding(vertical = 1.dp)) {
                                        Text(
                                            text = key,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextSecondary
                                        )
                                        Text(
                                            text = value.ifEmpty { "(empty)" },
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = TextPrimary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            result.onFailure { error ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "✕ Anisette Fetch Failed",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = StatusRed
                                    )
                                    Text(
                                        text = "${testFetchDurationMs} ms",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                                Text(
                                    text = error.message ?: "Unknown network error",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = StatusRed
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Storage Section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("storage_settings_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = CalmBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Storage & Pairing Records",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Surface(
                    color = DarkBackground,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Internal Storage Directory",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = status.storageDir.ifEmpty { context.filesDir.absolutePath },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                OutlinedButton(
                    onClick = { showClearDataDialog = true },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("clear_data_button")
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear Pairing Data")
                }
            }
        }

        // 3. Diagnostics Section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("diagnostics_settings_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = CalmBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Verbose Log Output",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Capture detailed usbmuxd & libusb packet events",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }

                    Switch(
                        checked = verboseLoggingEnabled,
                        onCheckedChange = { verboseLoggingEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = CalmBlue,
                            uncheckedTrackColor = DarkSurfaceVariant
                        ),
                        modifier = Modifier.testTag("verbose_log_switch")
                    )
                }

                FilledTonalButton(
                    onClick = {
                        val exportText = com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics.redactSecrets(logs.joinToString("\n"))
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Bridge Logs", exportText))
                        Toast.makeText(context, "Copied ${logs.size} log entries to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = DarkSurfaceVariant,
                        contentColor = CalmBlue
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("export_logs_button")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export Logs to Clipboard")
                }
            }
        }

        // 4. Open-Source Attributions & Version
        Surface(
            color = DarkSurface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "iOS Sideload Bridge v1.0.0",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Powered by open-source libimobiledevice, usbmuxd, libplist, libusb, and OpenSSL.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    // Confirmation dialog for clearing pairing data
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            containerColor = DarkSurface,
            title = {
                Text("Clear Pairing Data?", color = TextPrimary)
            },
            text = {
                Text(
                    "This will delete saved lockdownd pairing records in the internal storage directory. You may need to reconnect your iPhone and tap 'Trust' again.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val dir = File(status.storageDir.ifEmpty { context.filesDir.absolutePath })
                        if (dir.exists()) {
                            dir.listFiles()?.forEach { file ->
                                if (file.isFile && (file.name.endsWith(".plist") || file.name.contains("pair"))) {
                                    file.delete()
                                }
                            }
                        }
                        showClearDataDialog = false
                        Toast.makeText(context, "Pairing records cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed)
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearDataDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

/* =========================================================================================
 * UTILITY HELPERS
 * ========================================================================================= */

private fun queryFileName(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    return cursor.getString(index)
                }
            }
        }
    }
    return uri.path?.let { File(it).name }
}

private fun queryFileSizeFormatted(context: Context, uri: Uri): String {
    var sizeBytes: Long = 0
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index != -1) {
                    sizeBytes = cursor.getLong(index)
                }
            }
        }
    }
    return when {
        sizeBytes <= 0 -> "Unknown"
        sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", sizeBytes.toDouble() / (1024 * 1024))
    }
}
