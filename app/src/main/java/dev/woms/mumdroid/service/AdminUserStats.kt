package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.net.UserConnectionInfo
import dev.woms.mumdroid.core.proto.UserStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The `UserStats` reply shown in the user-info dialog.
 *
 * A reply only carries connection details the server chose to send, so a
 * partial reply is merged over the previous snapshot for the same session
 * ([UserConnectionInfo.fromProto]); a stale snapshot from another session is
 * never merged and must be cleared when the user leaves, which is what
 * [clearIfSession] is for.
 */
internal class AdminUserStats {

    private val _userStats = MutableStateFlow<UserConnectionInfo?>(null)
    val userStats: StateFlow<UserConnectionInfo?> = _userStats

    fun onStats(stats: UserStats, userName: String): UserConnectionInfo {
        val merged = UserConnectionInfo.fromProto(stats, userName, _userStats.value)
        _userStats.value = merged
        return merged
    }

    fun clear() {
        _userStats.value = null
    }

    /** Drops the snapshot only when it belongs to the user that just left. */
    fun clearIfSession(session: Int) {
        if (_userStats.value?.session == session) {
            _userStats.value = null
        }
    }
}
