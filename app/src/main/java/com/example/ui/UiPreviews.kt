package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.iossideloadbridge.app.diagnostics.AppDiagnostics
import com.example.ui.theme.*

/**
 * Preview annotations covering all device sizes, orientations, and font scales requested:
 * - Small phone: 360 x 640 dp
 * - Galaxy A14: 411 x 900 dp
 * - Large phone: 480 x 960 dp
 * - Tablet portrait: 800 x 1280 dp
 * - Tablet landscape: 1280 x 800 dp
 * - Android TV / Large screen: 960 x 540 dp
 * - High font scale: 1.3x and 2.0x
 */

@Preview(name = "1. Small Phone (360x640 dp)", widthDp = 360, heightDp = 640, showBackground = true)
@Preview(name = "2. Galaxy A14 (411x900 dp)", widthDp = 411, heightDp = 900, showBackground = true)
@Preview(name = "3. Large Phone (480x960 dp)", widthDp = 480, heightDp = 960, showBackground = true)
@Preview(name = "4. Tablet Portrait (800x1280 dp)", widthDp = 800, heightDp = 1280, showBackground = true)
@Preview(name = "5. Tablet Landscape (1280x800 dp)", widthDp = 1280, heightDp = 800, showBackground = true)
@Preview(name = "6. Android TV (960x540 dp)", widthDp = 960, heightDp = 540, showBackground = true)
@Preview(name = "7. Galaxy A14 - Font Scale 1.3x", widthDp = 411, heightDp = 900, fontScale = 1.3f, showBackground = true)
@Preview(name = "8. Galaxy A14 - Font Scale 2.0x", widthDp = 411, heightDp = 900, fontScale = 2.0f, showBackground = true)
@Composable
fun SideloadBridgeAppAdaptivePreview() {
    MyApplicationTheme {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
        ) {
            val isWideScreen = maxWidth >= 600.dp

            if (isWideScreen) {
                // Wide screen layout (Tablet / Desktop / TV): side navigation rail + centered content
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    NavigationRail(
                        containerColor = DarkSurface,
                        modifier = Modifier.testTag("preview_nav_rail")
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        NavigationRailItem(
                            selected = false,
                            onClick = {},
                            icon = { Icon(Icons.Default.Cable, contentDescription = "Bridge") },
                            label = { Text("Bridge") }
                        )
                        NavigationRailItem(
                            selected = true,
                            onClick = {},
                            icon = { Icon(Icons.Default.InstallMobile, contentDescription = "Install") },
                            label = { Text("Install") }
                        )
                        NavigationRailItem(
                            selected = false,
                            onClick = {},
                            icon = { Icon(Icons.Default.BugReport, contentDescription = "Diagnostics") },
                            label = { Text("Diagnostics") }
                        )
                        NavigationRailItem(
                            selected = false,
                            onClick = {},
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                            label = { Text("Settings") }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 840.dp)
                        ) {
                            PreviewInstallContent()
                        }
                    }
                }
            } else {
                // Compact screen layout (Phone / Galaxy A14): Scaffold with bottom navigation bar
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = DarkBackground,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        NavigationBar(
                            containerColor = DarkSurface,
                            tonalElevation = 2.dp
                        ) {
                            NavigationBarItem(
                                selected = false,
                                onClick = {},
                                icon = { Icon(Icons.Default.Cable, contentDescription = "Bridge") },
                                label = { Text("Bridge") }
                            )
                            NavigationBarItem(
                                selected = true,
                                onClick = {},
                                icon = { Icon(Icons.Default.InstallMobile, contentDescription = "Install") },
                                label = { Text("Install") }
                            )
                            NavigationBarItem(
                                selected = false,
                                onClick = {},
                                icon = { Icon(Icons.Default.BugReport, contentDescription = "Diagnostics") },
                                label = { Text("Diagnostics") }
                            )
                            NavigationBarItem(
                                selected = false,
                                onClick = {},
                                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                label = { Text("Settings") }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        PreviewInstallContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewInstallContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // App header with build ID stamp
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    text = "Build: ${AppDiagnostics.BUILD_ID}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CalmBlueLight,
                    fontFamily = FontFamily.Monospace
                )
            }
            Surface(
                color = StatusGreen.copy(alpha = 0.2f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = StatusGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "iPad Connected",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StatusGreen
                    )
                }
            }
        }

        // Card 1: Apple Account & Token Status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Apple Developer Account",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Status: Authenticated (PET & Xcode Token Active)",
                        fontSize = 13.sp,
                        color = StatusGreen
                    )
                    Surface(
                        color = CalmBlueContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "Token Present",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontSize = 11.sp,
                            color = CalmBlueLight,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Team selection button & info with flexible wrapping
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {},
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("Select Team: Personal (ABC12345)")
                    }
                    Button(
                        onClick = {},
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CalmBlue)
                    ) {
                        Text("Refresh Teams")
                    }
                }
            }
        }

        // Card 2: Provisioning & Signing Pipeline
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Provisioning Pipeline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                val stages = listOf(
                    "1. GSA Authentication & Xcode Token" to "SUCCEEDED",
                    "2. Register Device UDID with Apple" to "READY",
                    "3. Request Development Certificate & Profile" to "READY",
                    "4. zsign Engine Sign & AFC Install" to "READY"
                )

                stages.forEach { (title, tag) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            color = if (tag == "SUCCEEDED") StatusGreen.copy(alpha = 0.2f) else DarkBackground,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = tag,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (tag == "SUCCEEDED") StatusGreen else CalmBlueLight,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CalmBlue)
                ) {
                    Text(
                        text = "Sign & Install IPA to iPad",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Card 3: Diagnostic Step Trace Log
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Authentication & Pipeline Trace",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        onClick = {},
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy Logs",
                            tint = CalmBlueLight,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkBackground, RoundedCornerShape(8.dp))
                        .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val sampleLogs = listOf(
                            "[12:00:01] init ok: HTTP 200, protocol=s2k_fo, salt=16B",
                            "[12:00:02] complete ok: HTTP 200",
                            "[12:00:02] M2 ok: server proof verified",
                            "[12:00:02] spd present: true (len=544B), decrypted ok",
                            "[12:00:03] adsid present: true, GsIdmsToken present: true, sk present: true (32B)",
                            "[12:00:03] apptokens HTTP status: 200 OK",
                            "[12:00:04] et present: true (len=368B), AES-GCM decrypted ok",
                            "[12:00:04] xcode token present: true (len=44B)",
                            "[12:00:05] teams fetch result: ok (1 team found)"
                        )
                        sampleLogs.forEach { log ->
                            Text(
                                text = log,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (log.contains("ok") || log.contains("true")) StatusGreen else TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}
