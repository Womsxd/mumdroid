package dev.woms.mumdroid.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.woms.mumdroid.core.model.ChannelPasswordPrompt
import dev.woms.mumdroid.core.model.ChannelPick

/** Administration screens reached from the connection screen's server menu. */
internal enum class AdminPage {
    RegisteredUsers,
    BanList,
}

/**
 * Which transient surface the connection screen currently shows: the selected
 * tab, the administration page, and the dialogs (server info, access tokens,
 * user information, channel password, whisper / shout pickers).
 *
 * The screen itself only renders; every one of these is a small piece of state
 * that the top bar, the channel list and the dialogs all need to read or write,
 * so bundling them keeps the parameter lists from growing with each dialog.
 */
internal class ConnectionScreenState {
    var tab by mutableIntStateOf(0)
    var adminPage by mutableStateOf<AdminPage?>(null)
    var showServerInfo by mutableStateOf(false)
    var showAccessTokens by mutableStateOf(false)
    var infoSession by mutableStateOf<Int?>(null)
    var infoUserName by mutableStateOf("")
    /** Password prompt discovered while joining from this screen. */
    var localPasswordPrompt by mutableStateOf<ChannelPasswordPrompt?>(null)
    var whisperPicker by mutableStateOf(false)
    var shoutPicker by mutableStateOf(false)
    var pendingShoutChannel by mutableStateOf<ChannelPick?>(null)

    fun openUserInformation(session: Int, name: String) {
        infoUserName = name
        infoSession = session
    }

    fun closeUserInformation() {
        infoSession = null
        infoUserName = ""
    }

    fun clearVoiceTargetPickers() {
        whisperPicker = false
        shoutPicker = false
        pendingShoutChannel = null
    }
}

@Composable
internal fun rememberConnectionScreenState(): ConnectionScreenState =
    remember { ConnectionScreenState() }
