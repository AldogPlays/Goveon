package com.goveon.app.lighting

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

enum class BleFailureReason { RETRYABLE, PERMISSION, RADIO_UNAVAILABLE, UNSUPPORTED, AUTHENTICATION }
internal class BleFailure(message: String, val kind: BleFailureReason = BleFailureReason.RETRYABLE) : Exception(message)
enum class BleSessionPhase { CONNECTING, CONNECTED, RETRYING, STOPPED }
data class BleSessionStatus(val phase: BleSessionPhase, val attempts: Int = 0, val message: String? = null, val reason: BleFailureReason? = null)
internal data class BleSessionTarget(
    val id: String, val connection: BleConnection, val canConnect: Boolean = true,
    val failureReason: BleFailureReason? = null,
)
internal sealed interface BleSessionAttempt {
    data object Connected : BleSessionAttempt
    data class Retryable(val message: String, val reason: BleFailureReason = BleFailureReason.RETRYABLE) : BleSessionAttempt
    data class Terminal(val message: String, val reason: BleFailureReason) : BleSessionAttempt
}

/** Owns direct connections for the current foreground control page. Never scans or requests permissions.
 * Each device has one worker, including after a terminal stop. State emissions cannot restart it.
 * No identifiers are logged or persisted. */
internal class BleSessionController(
    private val scope: CoroutineScope,
    private val connect: suspend (String) -> BleSessionAttempt,
    private val disconnect: (String) -> Unit,
) {
    private class Session(val explicitRetry: Boolean = false) { lateinit var job: Job }
    private val lock = Any()
    private val sessions = mutableMapOf<String, Session>()
    private val targets = MutableStateFlow<Map<String, BleSessionTarget>>(emptyMap())
    private val mutableStatus = MutableStateFlow<Map<String, BleSessionStatus>>(emptyMap())
    val status: StateFlow<Map<String, BleSessionStatus>> = mutableStatus.asStateFlow()
    private val connections = Semaphore(3)
    private var closed = false

    fun update(targets: List<BleSessionTarget>, foreground: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        val desired = if (foreground) targets.filter { it.canConnect }.associateBy { it.id } else emptyMap()
        this.targets.value = desired
        for (id in sessions.keys.toList() - desired.keys) release(id)
        mutableStatus.update { it.filterKeys(desired::containsKey) }
        for (id in desired.keys - sessions.keys) start(id)
    }

    /** Explicit retry, for example after the user grants permission or changes a model. */
    fun retry(id: String) = synchronized(lock) {
        if (closed || id !in targets.value) return@synchronized
        release(id)
        start(id, explicitRetry = true)
    }

    fun close() = synchronized(lock) {
        closed = true
        targets.value = emptyMap()
        sessions.keys.toList().forEach(::release)
        mutableStatus.value = emptyMap()
    }

    private fun release(id: String) {
        sessions.remove(id)?.job?.cancel()
        disconnect(id)
    }

    private fun start(id: String, explicitRetry: Boolean = false) {
        val owner = Session(explicitRetry)
        owner.job = scope.launch(start = CoroutineStart.LAZY) {
            try { maintain(id, owner) }
            finally {
                synchronized(lock) {
                    // Also release when the owning scope is cancelled without an explicit close.
                    if (sessions[id] === owner && owner.job.isCancelled) {
                        sessions.remove(id)
                        disconnect(id)
                        mutableStatus.update { it - id }
                    }
                }
            }
        }
        sessions[id] = owner
        owner.job.start()
    }

    private fun publish(id: String, owner: Session, state: BleSessionStatus) = synchronized(lock) {
        if (sessions[id] === owner) mutableStatus.update { it + (id to state) }
    }

    private suspend fun maintain(id: String, owner: Session) {
        var attempts = 0
        var firstPass = true
        while (true) {
            val target = targets.value[id] ?: return
            val ignorePreviousFailure = firstPass && (owner.explicitRetry || target.failureReason in setOf(BleFailureReason.PERMISSION, BleFailureReason.RADIO_UNAVAILABLE))
            target.failureReason?.takeIf { !ignorePreviousFailure && it != BleFailureReason.RETRYABLE }?.let {
                publish(id, owner, BleSessionStatus(BleSessionPhase.STOPPED, attempts, terminalMessage(it), it))
                return
            }
            when (target.connection) {
                BleConnection.CONNECTING -> {
                    publish(id, owner, BleSessionStatus(BleSessionPhase.CONNECTING, attempts))
                    val changed = withTimeoutOrNull(25_000) { targets.first { it[id]?.connection != BleConnection.CONNECTING } }
                    if (changed == null) {
                        publish(id, owner, BleSessionStatus(BleSessionPhase.STOPPED, attempts, "Bluetooth setup is still running. Retry when it finishes.", BleFailureReason.RETRYABLE))
                        return
                    }
                    continue
                }
                BleConnection.CONNECTED -> {
                    firstPass = false
                    publish(id, owner, BleSessionStatus(BleSessionPhase.CONNECTED, attempts))
                    // Brief connect/drop cycles keep their retry budget. A stable connection resets it.
                    val dropped = withTimeoutOrNull(30_000) { targets.first { it[id]?.connection != BleConnection.CONNECTED } }
                    if (dropped == null) {
                        attempts = 0
                        targets.first { it[id]?.connection != BleConnection.CONNECTED }
                    }
                    continue
                }
                BleConnection.DISCONNECTED -> Unit
            }
            if (attempts >= 5) {
                publish(id, owner, BleSessionStatus(BleSessionPhase.STOPPED, attempts, "Bluetooth could not reconnect. Move closer and retry.", BleFailureReason.RETRYABLE))
                return
            }
            if (attempts > 0) {
                publish(id, owner, BleSessionStatus(BleSessionPhase.RETRYING, attempts, "Reconnecting Bluetooth…"))
                delay(1_000L shl (attempts - 1))
                // State may have changed during backoff, including a manual connection.
                val latest = targets.value[id] ?: return
                if (latest.connection != BleConnection.DISCONNECTED || latest.failureReason?.let { it != BleFailureReason.RETRYABLE } == true) continue
            }
            val result = connections.withPermit {
                val latest = targets.value[id] ?: return
                if (latest.connection != BleConnection.DISCONNECTED) return@withPermit null
                val ignoreOldFailure = firstPass && (owner.explicitRetry || latest.failureReason in setOf(BleFailureReason.PERMISSION, BleFailureReason.RADIO_UNAVAILABLE))
                if (!ignoreOldFailure && latest.failureReason?.let { it != BleFailureReason.RETRYABLE } == true) return@withPermit null
                attempts++
                firstPass = false
                publish(id, owner, BleSessionStatus(BleSessionPhase.CONNECTING, attempts))
                try { connect(id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: SecurityException) { BleSessionAttempt.Terminal(terminalMessage(BleFailureReason.PERMISSION), BleFailureReason.PERMISSION) }
                catch (failure: BleFailure) {
                    if (failure.kind == BleFailureReason.RETRYABLE) BleSessionAttempt.Retryable(failure.message ?: "Bluetooth connection failed.")
                    else BleSessionAttempt.Terminal(failure.message ?: terminalMessage(failure.kind), failure.kind)
                }
                catch (_: Exception) { BleSessionAttempt.Retryable("Bluetooth connection failed. Move closer and retry.") }
            } ?: continue
            when (result) {
                BleSessionAttempt.Connected -> {
                    // Repository state is committed before return, but its collector may lag this worker.
                    if (targets.value[id]?.connection != BleConnection.CONNECTED) {
                        withTimeoutOrNull(1_000) {
                            targets.first { snapshot ->
                                val latest = snapshot[id]
                                latest == null || latest.connection == BleConnection.CONNECTED || latest.failureReason?.let { it != BleFailureReason.RETRYABLE } == true
                            }
                        }
                    }
                }
                is BleSessionAttempt.Retryable -> publish(id, owner, BleSessionStatus(BleSessionPhase.RETRYING, attempts, result.message, result.reason))
                is BleSessionAttempt.Terminal -> {
                    publish(id, owner, BleSessionStatus(BleSessionPhase.STOPPED, attempts, result.message, result.reason))
                    return
                }
            }
        }
    }
}

internal fun terminalMessage(reason: BleFailureReason): String = when (reason) {
    BleFailureReason.PERMISSION -> "Allow Nearby devices to connect over Bluetooth."
    BleFailureReason.RADIO_UNAVAILABLE -> "Turn on Bluetooth, then retry."
    BleFailureReason.UNSUPPORTED -> "This light's Bluetooth service or control profile is not supported."
    BleFailureReason.AUTHENTICATION -> "This light requires an unsupported Bluetooth authentication handshake."
    BleFailureReason.RETRYABLE -> "Bluetooth could not reconnect. Retry when the light is nearby."
}

/** ATT callback status codes, distinct from BluetoothStatusCodes submission results.
 * Published definitions: developer.android.com/reference/android/bluetooth/BluetoothGatt */
internal fun bleAttributeFailureReason(status: Int): BleFailureReason = when (status) {
    5, 8, 15 -> BleFailureReason.AUTHENTICATION
    2, 3, 6, 7, 13 -> BleFailureReason.UNSUPPORTED
    else -> BleFailureReason.RETRYABLE
}

/** API 33 write submission codes. These must never be interpreted as ATT callback codes.
 * Published definitions: developer.android.com/reference/android/bluetooth/BluetoothStatusCodes */
internal fun bleSubmissionFailureReason(status: Int): BleFailureReason = when (status) {
    1 -> BleFailureReason.RADIO_UNAVAILABLE
    2, 6 -> BleFailureReason.PERMISSION
    3 -> BleFailureReason.AUTHENTICATION
    200 -> BleFailureReason.UNSUPPORTED
    else -> BleFailureReason.RETRYABLE
}
