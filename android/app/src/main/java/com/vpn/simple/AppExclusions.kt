package com.vpn.simple

import android.content.Context

/**
 * The apps the user has asked to keep out of the tunnel: while Bypass is connected their
 * traffic still uses the device's own connection instead of the profile.
 */
object AppExclusions {
    private const val PREFS = "bypass.exclusions"
    private const val KEY = "packages"

    fun load(context: Context): Set<String> {
        // getStringSet hands back a live reference into the preferences; copy it so a
        // caller cannot accidentally mutate what is stored.
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
        return stored.orEmpty().filter { it.isNotBlank() }.toSet()
    }

    fun save(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY, packages)
            .apply()
    }
}
