package dev.woms.mumdroid.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.woms.mumdroid.service.MumbleService

/**
 * Owns the Android binding to [MumbleService]: the `ServiceConnection`, the
 * session-left broadcast receiver, and the bind/unbind lifecycle.
 *
 * Split out of [ServiceSessionController] so that class keeps only the
 * session-command forwarding while the platform plumbing (bind flags, receiver
 * registration, disconnect races) lives here.
 */
internal class ServiceBinding(
    private val app: Application,
    /** The voice service became available. */
    private val onServiceReady: (MumbleService) -> Unit,
    /** The binding is gone (unbind, death, or session end). */
    private val onServiceLost: () -> Unit,
) {
    @Volatile
    var service: MumbleService? = null
        private set

    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as MumbleService.LocalBinder).service()
            service = svc
            onServiceReady(svc)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            serviceBound = false
            onServiceLost()
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            serviceBound = false
            onServiceLost()
            bindToRunningService()
        }
    }

    private val sessionLeftReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MumbleService.ACTION_SESSION_LEFT) return
            unbindService()
            onServiceLost()
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            sessionLeftReceiver,
            IntentFilter(MumbleService.ACTION_SESSION_LEFT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Unregisters the receiver and drops the binding; call once, on release. */
    fun release() {
        try {
            app.unregisterReceiver(sessionLeftReceiver)
        } catch (_: IllegalArgumentException) {
        }
        unbindService()
    }

    /**
     * Attaches if the voice service is already running. Does not start it —
     * BIND_AUTO_CREATE would spawn an empty background service.
     */
    fun bindToRunningService(autoCreate: Boolean = false) {
        if (serviceBound) return
        val flags = if (autoCreate) Context.BIND_AUTO_CREATE else 0
        serviceBound = app.bindService(
            Intent(app, MumbleService::class.java),
            serviceConnection,
            flags,
        )
    }

    /** Drops the current binding without touching the receiver. */
    fun unbindService() {
        if (!serviceBound) return
        try {
            app.unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
        }
        serviceBound = false
        service = null
    }
}
