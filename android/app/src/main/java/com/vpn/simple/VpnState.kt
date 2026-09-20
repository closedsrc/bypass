package com.vpn.simple

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The tunnel's real phase. Every surface reads this; nothing infers it locally. */
enum class VpnPhase { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

data class VpnStats(
    val upBytesPerSec: Long = 0L,
    val downBytesPerSec: Long = 0L,
    val totalUpBytes: Long = 0L,
    val totalDownBytes: Long = 0L,
    val sessionStartedAt: Long = 0L,
)

data class VpnStatus(
    val phase: VpnPhase = VpnPhase.DISCONNECTED,
    val error: String? = null,
    val hasProfile: Boolean = false,
    val profileName: String = "",
    /** Nodes the filter kept, i.e. what the tunnel can actually use. */
    val usableNodes: Int = 0,
    /** Nodes dropped for being US exit nodes. */
    val removedNodes: Int = 0,
    val excludedCount: Int = 0,
    val stats: VpnStats = VpnStats(),
) {
    val isBusy: Boolean get() = phase == VpnPhase.CONNECTING || phase == VpnPhase.DISCONNECTING
}

/**
 * One process-wide holder for tunnel state. The service writes, the UI and the
 * notification read, so the switch, the label, the stats and the shade can never
 * disagree — which is exactly what the previous per-Activity booleans allowed.
 */
object VpnStateHolder {
    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    fun update(transform: (VpnStatus) -> VpnStatus) = _status.update(transform)

    fun setPhase(phase: VpnPhase, error: String? = null) = _status.update {
        it.copy(phase = phase, error = error)
    }

    fun refreshProfileInfo(context: android.content.Context) {
        val info = ProfileStore.info(context)
        _status.update {
            it.copy(
                hasProfile = info != null,
                profileName = info?.name.orEmpty(),
                usableNodes = info?.usableNodes ?: 0,
                removedNodes = info?.removedNodes ?: 0,
            )
        }
    }

    fun refreshExcludedCount(context: android.content.Context) = _status.update {
        it.copy(excludedCount = AppExclusions.load(context).size)
    }
}
