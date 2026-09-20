package com.vpn.simple.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vpn.simple.BuildConfig
import com.vpn.simple.ProfileStore
import com.vpn.simple.VpnStatus
import com.vpn.simple.ui.components.SectionCard
import com.vpn.simple.ui.components.SectionHeader
import com.vpn.simple.ui.components.SettingsRow
import com.vpn.simple.ui.theme.LocalSpacing
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    status: VpnStatus,
    profile: ProfileStore.Info?,
    onImport: () -> Unit,
    onRemove: () -> Unit,
    onOpenExcluded: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var confirmRemove by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg),
    ) {
        Spacer(Modifier.height(spacing.lg))
        Spacer(Modifier.height(spacing.xs))

        SectionHeader("Profile")
        SectionCard {
            if (profile == null) {
                SettingsRow(
                    icon = Icons.Rounded.FileOpen,
                    title = "No profile imported",
                    detail = "Import a Clash-compatible .yaml to get started",
                    onClick = onImport,
                )
            } else {
                SettingsRow(
                    icon = Icons.Rounded.FileOpen,
                    title = profile.name,
                    detail = buildString {
                        append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(profile.importedAt)))
                        append(" · ")
                        append("${profile.bytes / 1024} KB")
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    icon = Icons.Rounded.Shield,
                    title = "Nodes in use",
                    detail = buildString {
                        append("${profile.usableNodes} usable")
                        if (profile.removedNodes > 0) {
                            append(" · ${profile.removedNodes} US node(s) removed")
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    icon = Icons.Rounded.FileOpen,
                    title = "Change profile",
                    detail = "Import a different file",
                    onClick = onImport,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    icon = Icons.Rounded.DeleteOutline,
                    title = "Remove profile",
                    detail = "Disconnects and deletes the stored file",
                    onClick = { confirmRemove = true },
                )
            }
        }

        Spacer(Modifier.height(spacing.xl))
        SectionHeader("Split tunnelling")
        SectionCard {
            SettingsRow(
                icon = Icons.Rounded.Apps,
                title = "Apps that skip the tunnel",
                detail = when (status.excludedCount) {
                    0 -> "Every app uses the tunnel"
                    1 -> "1 app uses your normal connection"
                    else -> "${status.excludedCount} apps use your normal connection"
                },
                onClick = onOpenExcluded,
            )
        }

        Spacer(Modifier.height(spacing.xl))
        SectionHeader("About")
        SectionCard {
            SettingsRow(
                icon = Icons.Rounded.Info,
                title = "Bypass",
                detail = "Version ${BuildConfig.VERSION_NAME}",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                icon = Icons.Rounded.Lock,
                title = "Privacy",
                detail = "Your profile never leaves this device",
            )
        }

        Spacer(Modifier.height(spacing.lg))
        Text(
            text = "Traffic is routed through the nodes in your own profile. Bypass strips US " +
                "exit nodes before the tunnel starts and refuses to connect if none remain. " +
                "Nodes are health-checked, so dead ones are not dialled.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(spacing.huge))
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove profile?") },
            text = { Text("The stored profile is deleted and the tunnel disconnects. You can import it again at any time.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            },
        )
    }
}
