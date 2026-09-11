package dev.woms.mumdroid.service

import dev.woms.mumdroid.core.model.BanEntry
import dev.woms.mumdroid.core.model.RegisteredUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The registered-user list and the ban list, plus the optimistic local edits
 * the UI applies while the TCP round-trip is in flight.
 *
 * The refresh flags are tri-state on purpose: the list is either never fetched
 * (`null` + not refreshing), being fetched (`null` + refreshing) or present
 * (non-null). [beginUserListRequest] / [beginBanListRequest] encode the rule
 * that a disconnected session must not leave a spinner running, and that a
 * "clear" refresh drops the stale list *before* asking, so the user never
 * sees the previous server's rows.
 */
internal class AdminListState {

    private val _registeredUsers = MutableStateFlow<List<RegisteredUser>?>(null)
    val registeredUsers: StateFlow<List<RegisteredUser>?> = _registeredUsers

    private val _userListRefreshing = MutableStateFlow(false)
    val userListRefreshing: StateFlow<Boolean> = _userListRefreshing

    private val _banList = MutableStateFlow<List<BanEntry>?>(null)
    val banList: StateFlow<List<BanEntry>?> = _banList

    private val _banListRefreshing = MutableStateFlow(false)
    val banListRefreshing: StateFlow<Boolean> = _banListRefreshing

    /** @return false when the request must be skipped (not connected). */
    fun beginUserListRequest(clear: Boolean, connected: Boolean): Boolean {
        if (!connected) {
            _userListRefreshing.value = false
            return false
        }
        if (clear) {
            _registeredUsers.value = null
            _userListRefreshing.value = false
        } else {
            _userListRefreshing.value = true
        }
        return true
    }

    fun onUserList(users: List<RegisteredUser>) {
        _registeredUsers.value = users.sortedBy { it.name.lowercase() }
        _userListRefreshing.value = false
    }

    /** Renames locally; null when the name is blank or already taken. */
    fun renameRegisteredUser(userId: Int, newName: String): RegisteredUser? {
        val name = newName.trim()
        if (name.isEmpty()) return null
        val current = _registeredUsers.value ?: return null
        if (current.any { it.userId != userId && it.name == name }) return null
        _registeredUsers.value = current.map { if (it.userId == userId) it.copy(name = name) else it }
        return RegisteredUser(userId, name)
    }

    /** Removes locally (server-side unregister = id 0 in the echoed list). */
    fun unregisterUser(userId: Int): RegisteredUser? {
        if (userId == 0) return null
        val current = _registeredUsers.value ?: return null
        _registeredUsers.value = current.filter { it.userId != userId }
        return RegisteredUser(userId)
    }

    /** @return false when the request must be skipped (not connected). */
    fun beginBanListRequest(clear: Boolean, connected: Boolean): Boolean {
        if (!connected) {
            _banListRefreshing.value = false
            return false
        }
        if (clear) {
            _banList.value = null
            _banListRefreshing.value = false
        } else {
            _banListRefreshing.value = true
        }
        return true
    }

    fun setBanList(bans: List<BanEntry>) {
        _banList.value = bans
    }

    /** Marks a pending ban-list reply as consumed. */
    fun endBanListRequest() {
        _banListRefreshing.value = false
    }

    /** The pre-kick list, used to diff what the server appended. */
    fun banSnapshot(): List<BanEntry>? = _banList.value

    fun clear() {
        _registeredUsers.value = null
        _banList.value = null
        _userListRefreshing.value = false
        _banListRefreshing.value = false
    }
}
