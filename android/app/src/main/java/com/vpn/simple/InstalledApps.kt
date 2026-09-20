package com.vpn.simple

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * The apps a user can actually launch and recognise. The previous build listed every
 * installed package — system libraries, raw package identifiers and all — which made the
 * screen impossible to use. Only activities that respond to a launcher intent are shown.
 */
data class AppEntry(
    val label: String,
    val pkg: String,
    val isSystem: Boolean,
    val icon: Drawable?,
)

object InstalledApps {

    fun load(context: Context, includeSystem: Boolean): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>(resolved.size)
        for (info in resolved) {
            val appInfo = info.activityInfo?.applicationInfo ?: continue
            val pkg = appInfo.packageName ?: continue
            if (pkg == context.packageName) continue // excluding ourselves would carry nothing
            if (!seen.add(pkg)) continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !includeSystem) continue
            val label = appInfo.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
            out += AppEntry(label, pkg, isSystem, runCatching { appInfo.loadIcon(pm) }.getOrNull())
        }
        return out.sortedBy { it.label.lowercase() }
    }
}
