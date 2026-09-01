package dev.woms.mumdroid.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.woms.mumdroid.R
import dev.woms.mumdroid.core.model.ChannelAclPassword
import dev.woms.mumdroid.core.model.ChannelPasswordPrompt
import dev.woms.mumdroid.core.model.CertificatePrompt
import dev.woms.mumdroid.core.model.ServerConnectionInfo
import dev.woms.mumdroid.core.model.ServerRemoval
import dev.woms.mumdroid.core.model.UserCertificate
import dev.woms.mumdroid.core.model.UserConnectionInfo
import dev.woms.mumdroid.core.model.VoiceOutputTarget
import dev.woms.mumdroid.core.net.BanEntry
import dev.woms.mumdroid.core.net.RegisteredUser
import dev.woms.mumdroid.data.UserCertificateStore
import dev.woms.mumdroid.data.WrongPasswordException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the user client certificate state (active certificate, installed list,
 * generation/import/export errors) independently of the rest of [MainViewModel].
 */
internal class UserCertificateController(
    private val application: Application,
    private val scope: CoroutineScope,
) {
    private val store = UserCertificateStore(application)

    private val _userCertificate = MutableStateFlow(UserCertificate.NONE)
    val userCertificate: StateFlow<UserCertificate> = _userCertificate.asStateFlow()

    private val _userCertificates = MutableStateFlow<List<UserCertificate>>(emptyList())
    val userCertificates: StateFlow<List<UserCertificate>> = _userCertificates.asStateFlow()

    /** Last user-certificate generation failure message (null when none). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    init {
        _userCertificate.value = store.load()
        _userCertificates.value = store.loadAll()
    }

    fun generate(username: String) {
        scope.launch {
            try {
                store.generate(username)
                _userCertificate.value = store.load()
                _userCertificates.value = store.loadAll()
                _error.value = null
            } catch (e: Exception) {
                // Surface the failure instead of silently swallowing it: the
                // user pressed "generate" and deserves to know it did not work.
                _error.value = e.message
                    ?: application.getString(R.string.generate_certificate_failed)
            }
        }
    }

    /** Deletes the user client certificate with the given fingerprint. */
    fun delete(fingerprint: String) {
        scope.launch {
            store.delete(fingerprint)
            _userCertificate.value = store.load()
            _userCertificates.value = store.loadAll()
        }
    }

    /** Selects the user certificate with the given fingerprint as the active one. */
    fun select(fingerprint: String) {
        scope.launch {
            store.select(fingerprint)
            _userCertificate.value = store.load()
            _userCertificates.value = store.loadAll()
        }
    }

    /**
     * Imports a user certificate from a PKCS#12 (.p12/.pfx) file. On success the
     * loaded certificate replaces the current one.
     *
     * @param p12Bytes the raw bytes of the selected .p12/.pfx file.
     * @param password the password protecting the file.
     * @param onNeedPassword invoked when the file cannot be opened with the given
     *   (empty) password, indicating the user should be prompted for it.
     * @param onError invoked with a human-readable message on failure.
     */
    fun import(
        p12Bytes: ByteArray,
        password: CharArray,
        onNeedPassword: () -> Unit = {},
        onError: (String) -> Unit,
    ) {
        scope.launch {
            try {
                store.import(p12Bytes, password)
                _userCertificate.value = store.load()
                _userCertificates.value = store.loadAll()
            } catch (e: WrongPasswordException) {
                onNeedPassword()
            } catch (e: Exception) {
                onError(e.message ?: application.getString(R.string.import_cert_failed))
            }
        }
    }

    /**
     * Exports the user certificate (and private key) with the given fingerprint
     * as a PKCS#12 file.
     *
     * @param fingerprint the SHA-256 fingerprint of the certificate to export.
     * @param out the destination stream (e.g. from the SAF file picker).
     * @param password the password to protect the exported file with.
     * @param onError invoked with a human-readable message on failure.
     */
    fun export(fingerprint: String, out: java.io.OutputStream, password: CharArray, onError: (String) -> Unit) {
        scope.launch {
            try {
                store.exportTo(fingerprint, out, password)
            } catch (e: Exception) {
                onError(e.message ?: application.getString(R.string.export_cert_failed))
            }
        }
    }
}

/** Low-frequency session snapshot for the UI.
 *
 *  Audio-callback meters (VAD level, local PTT/VAD talking flag) are
 *  deliberately omitted: they change every capture frame and must not
 *  rebuild this object. Roster talking icons travel with [ConnectionState.users].
 */
data class ConnectionState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val status: String = "",
    val serverName: String = "",
    val channels: List<dev.woms.mumdroid.core.model.Channel> = emptyList(),
    val users: List<dev.woms.mumdroid.core.model.User> = emptyList(),
    val selfMuted: Boolean = false,
    val selfDeafened: Boolean = false,
    val chatMessages: List<dev.woms.mumdroid.core.model.ChatMessage> = emptyList(),
    /** Seconds until the next automatic reconnect after a drop; 0 if none. */
    val reconnectCountdown: Int = 0,
    /** True from an unexpected drop until we are back or retries are exhausted. */
    val reconnecting: Boolean = false,
    /** Live snapshot of the connected server (empty while disconnected). */
    val serverInfo: ServerConnectionInfo = ServerConnectionInfo(),
    val userInfo: UserConnectionInfo? = null,
    val channelPasswordPrompt: ChannelPasswordPrompt? = null,
    /** Certificate-mismatch prompt while the TLS handshake waits for the user. */
    val certificatePrompt: CertificatePrompt? = null,
    /** Local user's access-token bag for this server (desktop `tokens`). */
    val accessTokens: List<String> = emptyList(),
    val registeredUsers: List<RegisteredUser>? = null,
    val banList: List<BanEntry>? = null,
    val userListRefreshing: Boolean = false,
    val banListRefreshing: Boolean = false,
    /** Incremented when channel ACL bits arrive; menus re-check mute/move. */
    val permissionEpoch: Int = 0,
    /** Set when the server kicked/banned/removed us; not a network drop. */
    val serverRemoval: ServerRemoval? = null,
    /** Device currently playing incoming voice; null while disconnected. */
    val outputTarget: VoiceOutputTarget? = null,
    /** Channel ids the local user is listening to without joining. */
    val listeningChannels: Set<Int> = emptySet(),
    /** Password from the last ACL query, used by the channel-edit dialog. */
    val channelAclPassword: ChannelAclPassword? = null,
    /** Saved favorite this session was started from (0 if unknown). */
    val favoriteId: Long = 0,
) {
    /**
     * `host:port` of the live session, used to mark the matching home-list
     * card when the favorite id is unknown. Null when no connect is in flight.
     */
    val activeServerKey: String?
        get() {
            if (!connected && !connecting && !reconnecting) return null
            val host = serverInfo.host.trim()
            if (host.isEmpty() || serverInfo.port <= 0) return null
            return "${host.lowercase()}:${serverInfo.port}"
        }

    /** Favorite row to highlight; null when idle. */
    val activeServerId: Long?
        get() {
            if (!connected && !connecting && !reconnecting) return null
            return favoriteId.takeIf { it > 0L }
        }
}

/** Extension to start a foreground service across API levels. */
fun Context.startForegroundServiceCompat(intent: Intent) {
    // Guard against the "Service Intent must be explicit" crash: Android throws
    // IllegalArgumentException when a service is started with an implicit intent.
    // Resolve it to an explicit component before starting, if possible.
    val explicit = if (intent.component != null) {
        intent
    } else {
        val resolved = packageManager.resolveService(intent, 0)?.serviceInfo ?: return
        Intent(intent).apply {
            component = android.content.ComponentName(resolved.packageName, resolved.name)
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(explicit)
    } else {
        startService(explicit)
    }
}
