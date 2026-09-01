package dev.woms.mumdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import dev.woms.mumdroid.R
import dev.woms.mumdroid.ui.activity.ConnectionActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground connection notification and incoming-chat heads-up banners.
 */
internal class ConnectionNotifications(private val service: Service) {

    companion object {
        private const val TAG = "MumbleService"
        const val CHANNEL_ID = "mumdroid_connection"
        const val CHAT_CHANNEL_ID = "mumdroid_chat"
        const val NOTIFICATION_ID = 1001
        const val CHAT_NOTIFICATION_ID = 1002
        const val PRIVATE_CHAT_NOTIFICATION_BASE = 0x20000000
        const val DISCONNECT_REQUEST_CODE = 1
        const val RECONNECT_NOW_REQUEST_CODE = 2
        const val KEY_TEXT_REPLY = "private_reply_text"
        const val ACTION_REPLY_PRIVATE = "dev.woms.mumdroid.action.REPLY_PRIVATE"
        const val EXTRA_REPLY_SESSION = "reply_session"
        const val EXTRA_REPLY_ACTOR = "reply_actor"
    }

    private val privateChatNotifIds = ConcurrentHashMap.newKeySet<Int>()

    fun createChannels() {
        val manager = service.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                service.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHAT_CHANNEL_ID,
                service.getString(R.string.notification_chat_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    fun startForegroundSafe(text: String, serverName: String, reconnectCountdown: Int) {
        val notification = build(text, serverName, reconnectCountdown)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun update(text: String, serverName: String, reconnectCountdown: Int) {
        service.getSystemService(NotificationManager::class.java)?.notify(
            NOTIFICATION_ID,
            build(text, serverName, reconnectCountdown),
        )
    }

    fun notifyChannelChat(actor: String, channel: String, text: String) {
        try {
            val preview = text.take(80)
            val notification = Notification.Builder(service, CHAT_CHANNEL_ID)
                .setContentTitle(service.getString(R.string.notification_channel_chat, actor))
                .setContentText(preview)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(sessionTapPending())
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .apply {
                    val label = channel.trim()
                    if (label.isNotEmpty()) setSubText("#$label")
                }
                .build()
            service.getSystemService(NotificationManager::class.java)
                .notify(CHAT_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify channel chat", e)
        }
    }

    fun notifyPrivateChat(
        session: Int,
        actorName: String,
        text: String,
        onlyAlertOnce: Boolean = false,
    ) {
        try {
            val preview = text.take(80)
            val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
                .setLabel(service.getString(R.string.notification_reply_hint))
                .build()
            val replyIntent = Intent(ACTION_REPLY_PRIVATE).apply {
                setPackage(service.packageName)
                putExtra(EXTRA_REPLY_SESSION, session)
                putExtra(EXTRA_REPLY_ACTOR, actorName)
            }
            val replyPending = PendingIntent.getBroadcast(
                service,
                session,
                replyIntent,
                mutablePendingFlags(),
            )
            val replyActionBuilder = Notification.Action.Builder(
                Icon.createWithResource(service, android.R.drawable.ic_menu_send),
                service.getString(R.string.notification_reply_now),
                replyPending,
            ).addRemoteInput(remoteInput)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                replyActionBuilder.setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            }
            val id = PRIVATE_CHAT_NOTIFICATION_BASE + session
            privateChatNotifIds.add(id)
            val notification = Notification.Builder(service, CHAT_CHANNEL_ID)
                .setContentTitle(service.getString(R.string.notification_private_chat, actorName))
                .setContentText(preview)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(sessionTapPending())
                .setAutoCancel(true)
                .setOnlyAlertOnce(onlyAlertOnce)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .addAction(replyActionBuilder.build())
                .build()
            service.getSystemService(NotificationManager::class.java).notify(id, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify private chat", e)
        }
    }

    fun parsePrivateReply(intent: Intent): Pair<Int, Pair<String, String>>? {
        val session = intent.getIntExtra(EXTRA_REPLY_SESSION, 0)
        val actorName = intent.getStringExtra(EXTRA_REPLY_ACTOR).orEmpty()
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_TEXT_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (session <= 0 || text.isEmpty()) return null
        return session to (actorName to text)
    }

    fun cancelChat() {
        val manager = service.getSystemService(NotificationManager::class.java) ?: return
        privateChatNotifIds.forEach { manager.cancel(it) }
        privateChatNotifIds.clear()
        manager.cancel(CHAT_NOTIFICATION_ID)
    }

    private fun build(text: String, serverName: String, reconnectCountdown: Int): Notification {
        val title = serverName.ifEmpty { service.getString(R.string.app_name) }
        val tap = Intent(service, ConnectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            service,
            0,
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectPending = PendingIntent.getForegroundService(
            service,
            DISCONNECT_REQUEST_CODE,
            MumbleService.disconnectIntent(service),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectAction = Notification.Action.Builder(
            Icon.createWithResource(service, android.R.drawable.ic_menu_close_clear_cancel),
            service.getString(R.string.disconnect),
            disconnectPending,
        ).build()
        val builder = Notification.Builder(service, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pending)
            .setAutoCancel(false)
        if (reconnectCountdown > 0) {
            val reconnectPending = PendingIntent.getForegroundService(
                service,
                RECONNECT_NOW_REQUEST_CODE,
                MumbleService.reconnectNowIntent(service),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(service, android.R.drawable.ic_popup_sync),
                    service.getString(R.string.reconnect_now),
                    reconnectPending,
                ).build(),
            )
        }
        builder.addAction(disconnectAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun sessionTapPending(): PendingIntent {
        val tap = Intent(service, ConnectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            service,
            0,
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun mutablePendingFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return flags
    }
}
