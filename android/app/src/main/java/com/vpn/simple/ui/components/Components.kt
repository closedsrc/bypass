package com.vpn.simple.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import com.vpn.simple.VpnPhase
import com.vpn.simple.VpnStatus
import com.vpn.simple.ui.theme.LocalSpacing
import com.vpn.simple.ui.theme.LocalStatusColors

/**
 * The product's one control. Its colour, icon and wording all move together with the
 * real tunnel phase, and it reports that phase to accessibility services as a switch —
 * so the state is never carried by colour alone and never disagrees with the label.
 */
@Composable
fun ConnectionControl(
    status: VpnStatus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val statusColors = LocalStatusColors.current

    val on = status.phase == VpnPhase.CONNECTED
    val target = when (status.phase) {
        VpnPhase.CONNECTED, VpnPhase.CONNECTING -> false
        else -> true
    }

    val container = when (status.phase) {
        VpnPhase.CONNECTED -> statusColors.connectedContainer
        VpnPhase.CONNECTING, VpnPhase.DISCONNECTING -> statusColors.connectingContainer
        VpnPhase.ERROR -> MaterialTheme.colorScheme.errorContainer
        VpnPhase.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when (status.phase) {
        VpnPhase.CONNECTED -> statusColors.onConnectedContainer
        VpnPhase.CONNECTING, VpnPhase.DISCONNECTING -> statusColors.onConnectingContainer
        VpnPhase.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        VpnPhase.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val accent = when (status.phase) {
        VpnPhase.CONNECTED -> statusColors.connected
        VpnPhase.CONNECTING, VpnPhase.DISCONNECTING -> statusColors.connecting
        VpnPhase.ERROR -> MaterialTheme.colorScheme.error
        VpnPhase.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // A slow breath while work is in flight, so a 60-second setup never looks frozen.
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    val animatedContainer by animateColorAsState(container, label = "container")

    val label = when (status.phase) {
        VpnPhase.CONNECTED -> "Connected"
        VpnPhase.CONNECTING -> "Connecting…"
        VpnPhase.DISCONNECTING -> "Disconnecting…"
        VpnPhase.ERROR -> "Stopped"
        VpnPhase.DISCONNECTED -> if (status.hasProfile) "Not connected" else "No profile yet"
    }
    val hint = when (status.phase) {
        VpnPhase.CONNECTED -> "Tap to disconnect"
        VpnPhase.CONNECTING -> "Setting up the tunnel"
        VpnPhase.DISCONNECTING -> "Releasing the tunnel"
        VpnPhase.ERROR -> "Tap to try again"
        VpnPhase.DISCONNECTED ->
            if (status.hasProfile) "Tap to connect" else "Tap to import a profile"
    }
    val icon: ImageVector = when (status.phase) {
        VpnPhase.CONNECTED -> Icons.Rounded.Shield
        VpnPhase.ERROR -> Icons.Rounded.ErrorOutline
        else -> Icons.Rounded.PowerSettingsNew
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(animatedContainer)
            // clickable supplies the touch target and ripple; clearAndSetSemantics then
            // replaces the subtree's semantics with one node carrying the name, role, state
            // and click action, so assistive tech announces a single switch with its state
            // rather than a nameless clickable container with an unlabelled child.
            .clickable(
                role = Role.Switch,
                onClick = { if (target) onConnect() else onDisconnect() },
            )
            .clearAndSetSemantics {
                contentDescription = "Bypass connection"
                stateDescription = if (on) "Connected" else label
                role = Role.Switch
                onClick(label = if (target) "Connect" else "Disconnect") {
                    if (target) onConnect() else onDisconnect()
                    true
                }
            }
            .padding(horizontal = spacing.xl, vertical = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (status.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    color = accent,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier
                        .size(56.dp)
                        .then(
                            if (status.phase == VpnPhase.CONNECTED) Modifier else Modifier,
                        ),
                )
            }
        }
        Spacer(Modifier.height(spacing.lg))
        Text(
            text = label,
            style = MaterialTheme.typography.headlineSmall,
            color = onContainer,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = onContainer.copy(alpha = if (status.isBusy) pulse else 0.75f),
            textAlign = TextAlign.Center,
        )
    }
}

/** A single label/value pair. Values are real measurements, never invented. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp, top = 8.dp),
    )
}

/** Grouped surface. Grouping comes from one container, not a card per row. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column { content() }
    }
}

/** Icon + title + optional detail + chevron, as one tappable row. */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Persistent, readable failure surface — replaces the old 2-second toast. */
@Composable
fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(spacing.lg),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(spacing.md))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(spacing.sm))
        Text(
            text = "Dismiss",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** A quiet success note, used to confirm a profile was imported. */
@Composable
fun SuccessBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val statusColors = LocalStatusColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(statusColors.connectedContainer)
            .padding(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = statusColors.connected,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(spacing.md))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColors.onConnectedContainer,
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Small coloured dot + word; used where a full banner would be too loud. */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    container: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(container)
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}
