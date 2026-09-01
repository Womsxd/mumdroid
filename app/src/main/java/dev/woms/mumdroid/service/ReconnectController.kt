package dev.woms.mumdroid.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 5/10/15 s backoff after an unexpected drop. Manual disconnect and
 * kick/ban must not start a retry.
 */
internal class ReconnectController(private val scope: CoroutineScope) {

    private val delaysSeconds = intArrayOf(5, 10, 15)
    private var job: Job? = null
    var attempt: Int = 0
        private set
    var sessionHadConnected: Boolean = false
        private set

    private val _countdown = MutableStateFlow(0)
    val countdown: StateFlow<Int> = _countdown

    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting

    fun noteConnected() {
        _reconnecting.value = false
        _countdown.value = 0
        attempt = 0
        sessionHadConnected = true
    }

    /** Fresh user-initiated connect: reset retry eligibility unless already reconnecting. */
    fun beginConnect() {
        if (!_reconnecting.value) {
            sessionHadConnected = false
            attempt = 0
        }
    }

    fun abortWaitingCountdown() {
        if (_countdown.value > 0) {
            job?.cancel()
            job = null
        }
        _reconnecting.value = false
        _countdown.value = 0
    }

    fun cancel() {
        job?.cancel()
        job = null
        _reconnecting.value = false
        _countdown.value = 0
    }

    fun cancelAndResetAttempts() {
        cancel()
        attempt = 0
    }

    fun markNotReconnecting() {
        _reconnecting.value = false
    }

    fun canRetry(
        autoReconnect: Boolean,
        hasParams: Boolean,
        manualDisconnect: Boolean,
        serverForced: Boolean,
    ): Boolean =
        autoReconnect && hasParams && !manualDisconnect && !serverForced &&
            sessionHadConnected && attempt < delaysSeconds.size

    /**
     * @return true when a countdown was started or one is already running.
     */
    fun startCountdown(
        onTick: (remaining: Int) -> Unit,
        onRetry: () -> Unit,
    ): Boolean {
        _reconnecting.value = true
        if (job?.isActive == true) return true
        val delaySeconds = delaysSeconds[attempt]
        attempt++
        _countdown.value = delaySeconds
        onTick(delaySeconds)
        job = scope.launch {
            var remaining = delaySeconds
            while (remaining > 0) {
                _countdown.value = remaining
                onTick(remaining)
                delay(1000)
                remaining--
            }
            _countdown.value = 0
            onRetry()
        }
        return true
    }

    /** Skip the waiting countdown and retry immediately. */
    fun reconnectNow(onRetry: () -> Unit): Boolean {
        if (_countdown.value <= 0) return false
        job?.cancel()
        job = null
        _countdown.value = 0
        onRetry()
        return true
    }
}
