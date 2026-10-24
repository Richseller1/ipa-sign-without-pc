package com.aistudio.iossideloadbridge.app.provisioning

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.iossideloadbridge.app.auth.AppleAuthSession
import com.aistudio.iossideloadbridge.app.auth.DryRunInspection
import com.example.ui.theme.*

@Composable
fun ProvisioningPipelineCard(
    isSignedIn: Boolean,
    session: AppleAuthSession?,
    targetBundleId: String,
    targetAppName: String,
    teams: List<DeveloperTeam>,
    selectedTeam: DeveloperTeam?,
    onSelectTeam: (DeveloperTeam) -> Unit,
    onFetchTeams: () -> Unit,
    isFetchingTeams: Boolean,
    identity: ProvisionedIdentity?,
    isProvisioning: Boolean,
    provisioningStageIndex: Int,
    provisioningStageMessage: String?,
    provisioningError: String?,
    onStartProvisioning: () -> Unit,
    onClearProvisioning: () -> Unit,
    dryRunInspection: DryRunInspection?,
    isInspectingDryRun: Boolean,
    isDryRunMode: Boolean,
    onToggleDryRunMode: () -> Unit,
    onInspectDryRun: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("provisioning_pipeline_card"),
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
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = null,
                        tint = CalmBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Certificate & Profile",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                val (badgeText, badgeColor) = when {
                    !isSignedIn -> "Sign In Required" to TextMuted
                    identity != null -> "Profile Ready" to StatusGreen
                    isProvisioning -> "Provisioning..." to CalmBlue
                    selectedTeam == null -> "Select Team" to StatusAmber
                    else -> "Ready to Provision" to CalmBlueLight
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

            if (!isSignedIn) {
                Text(
                    text = "Sign in to your Apple ID above to request a developer certificate and provisioning profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            } else {
                // Free Account Notice & Caveats
            Surface(
                color = StatusAmber.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, StatusAmber.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(16.dp))
                        Text(
                            text = "Free Apple Developer Account Caveats",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = StatusAmber
                        )
                    }
                    Text(
                        text = "• Provisioning profiles expire after 7 days (apps require refreshing).\n" +
                               "• Limited to 3 active App IDs at a time per account.\n" +
                               "• Each profile generation consumes 1 App ID slot.",
                        fontSize = 11.sp,
                        color = TextPrimary.copy(alpha = 0.9f),
                        lineHeight = 16.sp
                    )
                }
            }

            // Team Selection Section
            Surface(
                color = DarkBackground,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Developer Team",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = selectedTeam?.name ?: if (teams.isEmpty()) "No team selected" else "Select a team",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            if (selectedTeam != null) {
                                Text(
                                    text = "Team ID: ${selectedTeam.teamId} • Type: ${selectedTeam.type}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextSecondary
                                )
                            }
                        }

                        IconButton(
                            onClick = onFetchTeams,
                            enabled = !isFetchingTeams && !isProvisioning,
                            modifier = Modifier.testTag("refresh_teams_button")
                        ) {
                            if (isFetchingTeams) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CalmBlue, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Fetch Teams", tint = CalmBlue)
                            }
                        }
                    }

                    // Team picker dropdown/buttons if multiple teams exist
                    if (teams.size > 1) {
                        Text(
                            text = "Switch Team:",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            teams.forEach { team ->
                                val isSelected = team.teamId == selectedTeam?.teamId
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSelectTeam(team) },
                                    label = { Text(team.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CalmBlueContainer,
                                        selectedLabelColor = CalmBlueLight
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Target Bundle ID info
            Surface(
                color = DarkBackground,
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Target App Bundle ID",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = targetBundleId,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = CalmBlueLight
                        )
                        Text(
                            text = "App: $targetAppName",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Error banner if any
            if (provisioningError != null) {
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
                            text = provisioningError,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusRed
                        )
                    }
                }
            }

            // If already provisioned: show details and status
            if (identity != null) {
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
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Active Provisioned Profile",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = StatusGreen
                            )
                            TextButton(
                                onClick = onClearProvisioning,
                                colors = ButtonDefaults.textButtonColors(contentColor = StatusRed),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Re-generate", fontSize = 11.sp)
                            }
                        }

                        Divider(color = DarkBorder, thickness = 0.5.dp)

                        Text(
                            text = "Certificate: ${identity.certificateName}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        if (identity.serialNumber.isNotEmpty()) {
                            Text(
                                text = "Serial: ${identity.serialNumber}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextMuted
                            )
                        }
                        Text(
                            text = "Profile: ${identity.profileName}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        Text(
                            text = "Expires: ${identity.profileExpiration}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (identity.isFreeAccount) StatusAmber else StatusGreen
                        )
                        Text(
                            text = "Size: ${identity.profileBytes.size} bytes (Encrypted in Android KeyStore)",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted
                        )
                    }
                }
            }

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
                            text = "Inspect the exact CSR & provisioning plist request without contacting Apple",
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

            // Dry Run Inspection Panel
            if (isDryRunMode) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onInspectDryRun,
                        enabled = !isInspectingDryRun && selectedTeam != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CalmBlueContainer,
                            contentColor = CalmBlueLight
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("inspect_provisioning_button")
                    ) {
                        if (isInspectingDryRun) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CalmBlueLight, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Building inspection payload...")
                        } else {
                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Inspect Prepared Provisioning Request")
                        }
                    }

                    dryRunInspection?.let { inspection ->
                        Surface(
                            color = DarkBackground,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CalmBlue.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("provisioning_dry_run_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Prepared Apple Developer Request:",
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

            // Provisioning Stages Pipeline (when in progress)
            if (isProvisioning) {
                Surface(
                    color = DarkBackground,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CalmBlue.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Provisioning Pipeline in Progress",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = CalmBlue
                        )

                        val stages = listOf(
                            "Generate 2048-bit RSA key pair & CSR",
                            "Request Development Certificate (CSR)",
                            "Register App ID: $targetBundleId",
                            "Download Team Provisioning Profile",
                            "Store in hardware-backed KeyStore"
                        )

                        stages.forEachIndexed { idx, stageTitle ->
                            val stageNum = idx + 1
                            val isDone = provisioningStageIndex > stageNum
                            val isCurrent = provisioningStageIndex == stageNum

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                when {
                                    isDone -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(16.dp))
                                    isCurrent -> CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CalmBlue, strokeWidth = 2.dp)
                                    else -> Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                }

                                Text(
                                    text = stageTitle,
                                    fontSize = 12.sp,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    color = when {
                                        isDone -> TextPrimary
                                        isCurrent -> CalmBlueLight
                                        else -> TextMuted
                                    }
                                )
                            }
                        }

                        if (provisioningStageMessage != null) {
                            Text(
                                text = provisioningStageMessage,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // Action Button: Request Certificate & Provisioning Profile
            if (!isDryRunMode && identity == null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = onStartProvisioning,
                        enabled = !isProvisioning && selectedTeam != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CalmBlue,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("request_provisioning_button")
                    ) {
                        if (isProvisioning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Provisioning...")
                        } else {
                            Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Request Certificate & Profile", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (selectedTeam == null && !isProvisioning) {
                        Text(
                            text = "Please select or fetch a developer team above to enable provisioning.",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusAmber,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
}
