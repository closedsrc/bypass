package com.vpn.simple

import android.content.Context
import java.io.File

/**
 * The imported profile, on disk and summarised. One place decides what "a profile is
 * installed" means, so the UI can always offer to replace or remove it — the previous
 * build had no way to change a profile at all once one existed.
 */
object ProfileStore {

    private const val FILE_NAME = "profile.yaml"
    private const val META = "bypass.profile"
    private const val KEY_NAME = "name"
    private const val KEY_IMPORTED_AT = "importedAt"

    data class Info(
        val name: String,
        val importedAt: Long,
        val bytes: Long,
        val usableNodes: Int,
        val removedNodes: Int,
    )

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * Reads the stored profile and reports what the filter did with it, so the UI can
     * show the user that US nodes were removed and how many nodes remain.
     */
    fun info(context: Context): Info? {
        val f = file(context)
        if (!f.isFile) return null
        val prefs = context.getSharedPreferences(META, Context.MODE_PRIVATE)
        val summary = runCatching {
            val result = ProfileFilter.apply(f.readText())
            result.kept.size to result.removed.size
        }.getOrElse { 0 to 0 }
        return Info(
            name = prefs.getString(KEY_NAME, null) ?: f.name,
            importedAt = prefs.getLong(KEY_IMPORTED_AT, f.lastModified()),
            bytes = f.length(),
            usableNodes = summary.first,
            removedNodes = summary.second,
        )
    }

    /** Copies the picked document into private storage and records where it came from. */
    fun install(context: Context, sourceName: String, bytes: java.io.InputStream) {
        file(context).outputStream().use { out -> bytes.copyTo(out) }
        context.getSharedPreferences(META, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, sourceName)
            .putLong(KEY_IMPORTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun remove(context: Context) {
        file(context).delete()
        context.getSharedPreferences(META, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
