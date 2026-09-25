package dev.woms.mumdroid.ui.screen

import androidx.compose.runtime.Immutable
import dev.woms.mumdroid.core.model.Channel
import dev.woms.mumdroid.core.model.ChannelAclPassword
import dev.woms.mumdroid.core.model.ChannelPick
import dev.woms.mumdroid.core.model.User

/**
 * Shared join / roster / channel-admin callbacks for the recursive channel tree.
 *
 * A `data class` marked [Immutable] on purpose: it is rebuilt on every
 * composition of the list, but structural equality (values plus the stable
 * lambda identities Compose memoizes) lets every node compare it as unchanged
 * and skip. A plain class compared by identity would make the whole tree
 * recompose on each roster tick.
 */
@Immutable
internal data class ChannelTreeActions(
    val onJoinChannel: (Channel) -> Unit,
    val onJoinUserChannel: (Int) -> Unit,
    val onMoveUser: (Int, Int) -> Unit,
    val localChannelId: Int,
    val moveChannels: List<ChannelPick>,
    val onSetLocalBlock: (Int, Boolean) -> Unit,
    val onSetLocalIgnore: (Int, Boolean) -> Unit,
    val onSetRemoteMute: (Int, Boolean) -> Unit,
    val onSetRemoteDeafen: (Int, Boolean) -> Unit,
    val onSetPrioritySpeaker: (Int, Boolean) -> Unit,
    val onKickUser: (Int, String) -> Unit,
    val onBanUser: (Int, String, Boolean, Boolean, Int) -> Unit,
    val onRegisterUser: (Int) -> Unit,
    val canAdministerChannel: (Int) -> Boolean,
    val canMuteUser: (User) -> Boolean,
    val canPrioritySpeaker: (User) -> Boolean,
    val canMoveInChannel: (Int) -> Boolean,
    val onQueryChannelPermissions: (Int) -> Unit,
    val canKickUser: () -> Boolean,
    val canBanUser: () -> Boolean,
    val canRegisterUser: (User) -> Boolean,
    val supportsSelectiveBan: () -> Boolean,
    val canTextMessage: (Int) -> Boolean,
    val canListen: (Int) -> Boolean,
    val supportsChannelListen: () -> Boolean,
    val listeningChannels: Set<Int>,
    val onSendChat: (Int, String) -> Unit,
    val onSendPrivateChat: (Int, String) -> Unit,
    val onSetChannelListening: (Int, Boolean) -> Unit,
    val canWriteChannel: (Int) -> Boolean,
    val canAddChannel: (Int) -> Boolean,
    val canMakePermanentChannel: (Int) -> Boolean,
    val canLinkChannel: (Int) -> Boolean,
    val onLinkChannel: (Int) -> Unit,
    val onUnlinkChannel: (Int) -> Unit,
    val onUnlinkAllChannels: () -> Unit,
    val onCreateChannel: (Int, String, String, Int, Boolean, Int, String) -> Unit,
    val onUpdateChannel: (Int, String, String, Int, Int, String) -> Unit,
    val onRemoveChannel: (Int) -> Unit,
    val onRequestChannelDescription: (Int) -> Unit,
    val onRequestChannelAcl: (Int) -> Unit,
    /** Whether this channel's ACL may be edited (Write here or on the root). */
    val canEditAcl: (Int) -> Boolean,
    /** Opens the full-screen ACL editor for this channel. */
    val onOpenChannelAcl: (Int) -> Unit,
    val channelAclPassword: ChannelAclPassword?,
    val permissionEpoch: Int,
    val showUserCount: Boolean,
    val onUserInformation: (Int, String) -> Unit,
    val homeAllLinks: Set<Int>,
    val homeDirectLinks: Set<Int>,
    /** Shout to a channel, with the receiver-set modifiers from the dialog. */
    val onShoutToChannel: (channelId: Int, links: Boolean, children: Boolean, group: String) -> Unit,
    /** Whisper to one user. */
    val onWhisperToUser: (session: Int) -> Unit,
    /** Restore regular speech and revoke the registrations. */
    val onStopVoiceTarget: () -> Unit,
    /** Channel the local user is currently shouting to, if any. */
    val activeShoutChannelId: Int?,
    /** Sessions the local user is currently whispering to. */
    val whisperSessions: Set<Int>,
    /** Whispering to this channel may be offered (`ChanACL::Whisper`). */
    val mayWhisper: (Int) -> Boolean,
    /** Opens the multi-select whisper picker. */
    val onWhisperToUsers: () -> Unit,
    /** Opens the channel picker for a shout target. */
    val onShoutToChannelPicker: () -> Unit,
)
