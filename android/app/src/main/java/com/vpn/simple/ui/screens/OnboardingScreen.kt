package com.vpn.simple.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vpn.simple.ui.theme.LocalSpacing

/**
 * First run. The old build dropped the user straight into the system file picker with no
 * explanation and then into Android's VPN consent dialog, which is the first thing a new
 * user ever read. This explains the profile and the permission first, in that order.
 */
@Composable
fun OnboardingScreen(onImport: () -> Unit) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(spacing.xxl))
        Text(
            text = "Bypass",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(spacing.sm))
        Text(
            text = "One switch. Your own proxies. No US exit nodes.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(spacing.xxl))

        Step(
            icon = Icons.Rounded.FileOpen,
            title = "Bring a Clash profile",
            body = "Import the .yaml your provider gives you. Bypass keeps it in this app's " +
                "private storage — it is never uploaded anywhere.",
        )
        Spacer(Modifier.height(spacing.xl))
        Step(
            icon = Icons.Rounded.Shield,
            title = "Bypass filters it",
            body = "US exit nodes are removed and the rest are load-balanced behind one " +
                "switch. Nodes that stop answering are dropped before they are used, so a " +
                "request does not sit waiting on a dead one. If every node is American, " +
                "Bypass refuses to start rather than send your traffic through one.",
        )
        Spacer(Modifier.height(spacing.xl))
        Step(
            icon = Icons.Rounded.Lock,
            title = "Android will ask to trust the VPN",
            body = "The system shows a connection request the first time. Bypass needs it to " +
                "route your traffic — that is what a VPN is. You can revoke it any time in " +
                "Android Settings.",
        )

        Spacer(Modifier.height(spacing.xxl))
        Button(
            onClick = onImport,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Import a profile")
        }
        Spacer(Modifier.height(spacing.huge))
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, body: String) {
    val spacing = LocalSpacing.current
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(spacing.md))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
