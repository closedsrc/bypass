package com.vpn.simple.ui

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vpn.simple.ProfileStore
import com.vpn.simple.SimpleVpnService
import com.vpn.simple.VpnPhase
import com.vpn.simple.VpnStateHolder
import com.vpn.simple.VpnStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A short-lived, user-facing confirmation. Distinct from errors, which are persistent. */
data class Notice(val message: String, val isError: Boolean = false)

class BypassViewModel(app: Application) : AndroidViewModel(app) {

    val status: StateFlow<VpnStatus> = VpnStateHolder.status

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val app = getApplication<Application>()
        VpnStateHolder.refreshProfileInfo(app)
        VpnStateHolder.refreshExcludedCount(app)
        // A tunnel that is running but whose phase was lost to a process restart.
        if (SimpleVpnService.running && VpnStateHolder.status.value.phase == VpnPhase.DISCONNECTED) {
            VpnStateHolder.setPhase(VpnPhase.CONNECTED)
        }
    }

    /** Starts the tunnel with the stored profile. Returns false when there is none. */
    fun connect(): Boolean {
        val app = getApplication<Application>()
        val profile = ProfileStore.file(app)
        if (!profile.isFile) {
            _notice.value = Notice("Import a profile first — Bypass needs one to know where to send traffic.")
            return false
        }
        VpnStateHolder.setPhase(VpnPhase.CONNECTING)
        app.startService(
            Intent(app, SimpleVpnService::class.java)
                .putExtra(SimpleVpnService.EXTRA_PROFILE, profile.absolutePath),
        )
        return true
    }

    fun disconnect() {
        val app = getApplication<Application>()
        VpnStateHolder.setPhase(VpnPhase.DISCONNECTING)
        app.startService(
            Intent(app, SimpleVpnService::class.java).setAction(SimpleVpnService.ACTION_DISCONNECT),
        )
    }

    /** Re-reads the tunnel when the activity comes back to the foreground. */
    fun onResume() = refresh()

    fun importProfile(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val name = displayName(uri) ?: "profile.yaml"
            runCatching {
                app.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "The file could not be read." }
                    ProfileStore.install(app, name, input)
                }
            }.onSuccess {
                val info = ProfileStore.info(app)
                VpnStateHolder.refreshProfileInfo(app)
                val removed = info?.removedNodes ?: 0
                _notice.value = Notice(
                    buildString {
                        append("Imported $name")
                        if (info != null) append(" · ${info.usableNodes} usable nodes")
                        if (removed > 0) append(" · $removed US node(s) removed")
                    },
                )
            }.onFailure {
                _notice.value = Notice(it.message ?: "That file could not be imported.", isError = true)
            }
        }
    }

    fun removeProfile() {
        val app = getApplication<Application>()
        if (SimpleVpnService.running) disconnect()
        ProfileStore.remove(app)
        VpnStateHolder.refreshProfileInfo(app)
        _notice.value = Notice("Profile removed.")
    }

    fun setError(message: String) {
        VpnStateHolder.setPhase(VpnPhase.ERROR, message)
    }

    fun dismissError() {
        VpnStateHolder.setPhase(VpnPhase.DISCONNECTED)
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private fun displayName(uri: Uri): String? {
        val app = getApplication<Application>()
        return runCatching {
            app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }
}
