package com.vpn.simple.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vpn.simple.SimpleVpnService
import com.vpn.simple.VpnPhase
import com.vpn.simple.VpnStatus
import com.vpn.simple.ui.components.ConnectionControl
import com.vpn.simple.ui.components.ErrorBanner
import com.vpn.simple.ui.components.SectionCard
import com.vpn.simple.ui.components.SectionHeader
import com.vpn.simple.ui.components.SettingsRow
import com.vpn.simple.ui.components.StatTile
import com.vpn.simple.ui.components.SuccessBanner
import com.vpn.simple.ui.theme.LocalSpacing
import com.vpn.simple.ui.theme.LocalStatusColors

@Composable
fun HomeScreen(
    status: VpnStatus,
    notice: com.vpn.simple.ui.Notice?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onImport: () -> Unit,
    onOpenExcluded: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissError: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val statusColors = LocalStatusColors.current
    var sessionStart by remember { mutableStateOf(0L) }

    // Keep a local copy of the session start so the elapsed time keeps ticking without
    // the service having to publish it every second.
    if (status.stats.sessionStartedAt != 0L && status.stats.sessionStartedAt != sessionStart) {
        sessionStart = status.stats.sessionStartedAt
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg),
    ) {
        Spacer(Modifier.height(spacing.lg))

        Text(
            text = "Bypass",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (status.hasProfile) status.profileName else "One switch. Your own proxies.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(spacing.xl))

        ConnectionControl(
            status = status,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
        )

        if (status.phase == VpnPhase.ERROR && !status.error.isNullOrBlank()) {
            Spacer(Modifier.height(spacing.lg))
            ErrorBanner(message = status.error, onDismiss = onDismissError)
        }

        if (notice != null) {
            Spacer(Modifier.height(spacing.lg))
            if (notice.isError) {
                ErrorBanner(message = notice.message, onDismiss = onDismissNotice)
            } else {
                SuccessBanner(message = notice.message)
            }
        }

        Spacer(Modifier.height(spacing.xl))

        if (!status.hasProfile) {
            NoProfileCard(onImport = onImport)
        } else {
            SectionHeader("This session")
            SectionCard {
                SessionStats(status)
            }
            Spacer(Modifier.height(spacing.xl))
            SectionHeader("Profile")
            SectionCard {
                SettingsRow(
                    icon = Icons.Rounded.FileOpen,
                    title = "Change profile",
                    detail = status.profileName.ifBlank { "Import a different Clash profile" },
                    onClick = onImport,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    icon = Icons.Rounded.Shield,
                    title = "Routing",
                    detail = routingDetail(status),
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
        SettingsRow(
            icon = Icons.Rounded.Shield,
            title = "Settings",
            detail = "Profile, split tunnelling, about",
            onClick = onOpenSettings,
        )
        Spacer(Modifier.height(spacing.huge))
    }
}

@Composable
private fun SessionStats(status: VpnStatus) {
    val spacing = LocalSpacing.current
    val connected = status.phase == VpnPhase.CONNECTED
    Column(Modifier.padding(spacing.lg)) {
        Row(Modifier.fillMaxWidth()) {
            StatTile("Download", if (connected) SimpleVpnService.formatSpeed(status.stats.downBytesPerSec) else "—", Modifier.weight(1f))
            StatTile("Upload", if (connected) SimpleVpnService.formatSpeed(status.stats.upBytesPerSec) else "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(spacing.lg))
        Row(Modifier.fillMaxWidth()) {
            StatTile(
                "Transferred",
                if (connected) {
                    SimpleVpnService.formatBytes(status.stats.totalUpBytes + status.stats.totalDownBytes)
                } else "—",
                Modifier.weight(1f),
            )
            StatTile("Uptime", if (connected) uptimeLabel(status) else "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(spacing.md))
        Text(
            text = "Live from the tunnel. Counters reset on each connect.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun uptimeLabel(status: VpnStatus): String {
    val started = status.stats.sessionStartedAt
    if (started == 0L) return "—"
    // Recompose roughly once a second while this screen is visible.
    val now by androidx.compose.runtime.produceState(started, started) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val seconds = ((now - started) / 1000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun routingDetail(status: VpnStatus): String {
    val parts = mutableListOf<String>()
    if (status.usableNodes > 0) parts += "${status.usableNodes} nodes, load-balanced"
    if (status.removedNodes > 0) parts += "${status.removedNodes} US node(s) removed"
    if (parts.isEmpty()) parts += "Your profile's own rules, kept in order"
    return parts.joinToString(" · ")
}

@Composable
private fun NoProfileCard(onImport: () -> Unit) {
    val spacing = LocalSpacing.current
    SectionCard {
        Column(Modifier.padding(spacing.lg)) {
            Text(
                text = "Add a profile to begin",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = "Bypass needs a Clash-compatible profile (a .yaml file or subscription " +
                    "export). It stays on this device and is never uploaded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.lg))
            androidx.compose.material3.Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Import profile")
            }
        }
    }
}
