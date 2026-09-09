package dev.woms.mumdroid.core.model

/** A registered (database) user reported by the server. */
data class RegisteredUser(
    val userId: Int = 0,
    val name: String = "",
    val lastSeen: String = "",
    val lastChannel: Int = 0,
)
