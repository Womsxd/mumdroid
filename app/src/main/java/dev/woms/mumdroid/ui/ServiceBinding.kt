package dev.woms.mumdroid.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
private const val FACADE_READY_RETRY_MS = 16L
private const val FACADE_READY_MAX_RETRIES = 100

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

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Retries left before a not-yet-wired service instance is given up on. */
    private var readyRetries = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as MumbleService.LocalBinder).service()
            service = svc
            // The binder can be handed out while MumbleService.onCreate is
            // still running, so `facade` may not exist yet. Poll a few frames
            // instead of touching the lateinit field and crashing.
            if (svc.facadeOrNull != null) {
                readyRetries = 0
                onServiceReady(svc)
            } else {
                awaitFacadeReady(svc)
            }
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

    /**
     * Waits for [MumbleService.onCreate] to publish its facade, then reports
     * the service as ready. Bounded so a service that dies mid-init does not
     * leave the caller spinning forever.
     */
    private fun awaitFacadeReady(svc: MumbleService) {
        if (readyRetries++ >= FACADE_READY_MAX_RETRIES) {
            // The service never finished its own onCreate: treat the binding as
            // dead so the UI falls back to the disconnected state.
            readyRetries = 0
            unbindService()
            onServiceLost()
            return
        }
        mainHandler.postDelayed({
            // A disconnect (or a rebind) invalidates this wait.
            if (!serviceBound || service !== svc) return@postDelayed
            if (svc.facadeOrNull != null) {
                readyRetries = 0
                onServiceReady(svc)
            } else {
                awaitFacadeReady(svc)
            }
        }, FACADE_READY_RETRY_MS)
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
