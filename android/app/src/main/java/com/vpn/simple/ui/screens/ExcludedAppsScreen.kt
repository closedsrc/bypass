package com.vpn.simple.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vpn.simple.AppEntry
import com.vpn.simple.ui.components.EmptyState
import com.vpn.simple.ui.components.SectionCard
import com.vpn.simple.ui.theme.LocalSpacing

@Composable
fun ExcludedAppsScreen(
    apps: List<AppEntry>,
    loading: Boolean,
    excluded: Set<String>,
    includeSystem: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onIncludeSystemChange: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
) {
    val spacing = LocalSpacing.current

    val filtered = remember(apps, query, excluded) {
        val q = query.trim().lowercase()
        apps.filter { entry ->
            q.isEmpty() || entry.label.lowercase().contains(q) || entry.pkg.lowercase().contains(q)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.lg),
    ) {
        Spacer(Modifier.height(spacing.md))
        Text(
            text = "Selected apps use your normal connection while Bypass is on — useful for " +
                "banking apps and anything that breaks behind a proxy.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(spacing.lg))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        )

        Spacer(Modifier.height(spacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = includeSystem,
                onClick = { onIncludeSystemChange(!includeSystem) },
                label = { Text("Show system apps") },
            )
            Spacer(Modifier.width(spacing.md))
            Text(
                text = if (excluded.isEmpty()) {
                    "Nothing excluded"
                } else {
                    "${excluded.size} excluded"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(spacing.md))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                EmptyState(
                    icon = Icons.Rounded.Search,
                    title = if (apps.isEmpty()) "No apps found" else "No matches",
                    body = if (apps.isEmpty()) {
                        "No launchable apps are visible on this device."
                    } else {
                        "Nothing matches \"$query\"."
                    },
                )
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    SectionCard {
                        filtered.forEachIndexed { index, entry ->
                            AppRow(
                                entry = entry,
                                checked = entry.pkg in excluded,
                                onToggle = { onToggle(entry.pkg) },
                            )
                            if (index != filtered.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 60.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(spacing.xxl)) }
            }
        }
    }
}

@Composable
private fun AppRow(
    entry: AppEntry,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = entry.icon
        if (icon != null) {
            val bitmap = remember(entry.pkg) { runCatching { icon.toBitmap(96, 96).asImageBitmap() }.getOrNull() }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(9.dp)),
                )
            } else {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            Spacer(Modifier.width(spacing.md))
        } else {
            Spacer(Modifier.width(spacing.xs))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.isSystem) "System app" else entry.pkg,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(spacing.sm))
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}
