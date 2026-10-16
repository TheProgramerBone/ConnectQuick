package dev.connectquick.controller.session

import dev.connectquick.controller.identity.DeviceIdentity
import dev.connectquick.controller.net.BackendClient
import dev.connectquick.controller.net.ControllerHello
import dev.connectquick.controller.net.SignalingState
import dev.connectquick.controller.net.Subscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where the link to one PC is, in terms a screen can show without lying. Three separate layers are
 * kept apart on purpose: the signalling server ([signaling]), the PC's presence, and the control
 * session itself. The app is only "connected" in [Phase.ONLINE].
 */
data class LinkState(
    val phase: Phase,
    val signaling: SignalingState = SignalingState.DISCONNECTED,
    /** Machine-readable cause for the phase (e.g. "host_offline", "revoked", "too_many_attempts"). */
    val reason: String? = null,
    val attempt: Int = 0,
    val nextRetryInMs: Long = 0,
) {
    enum class Phase {
        /** Nothing requested. */
        STOPPED,
        /** Reaching / authenticating with the signalling server. */
        CONNECTING_SIGNALING,
        /** Signalling is up; negotiating the encrypted session with the PC. */
        OPENING_SESSION,
        /** The PC is switched off / not connected; will resume when it appears. */
        HOST_OFFLINE,
        /** A session is established and verified (the PC's first frame decrypted). */
        ONLINE,
        /** The link dropped; trying again after a backoff. */
        RECONNECTING,
        /** This phone is no longer authorized by the PC. Re-pairing is required. */
        REVOKED,
        /** Gave up (retry limit, PC refused). The user can retry. */
        FAILED,
    }
}

/**
 * Keeps one PC reachable: owns the signalling connection, opens the end-to-end session, and reopens
 * it when it drops — with exponential backoff + jitter, a hard limit on consecutive failures, and
 * immediate retry on a network change. Every transition is published as a [LinkState].
 *
 * It never papers over a real problem: a revoked phone stops trying, a PC that refuses reports why,
 * and after [MAX_ATTEMPTS] failures in a row it stops and waits for the user ([retryNow]).
 */
class RemoteLink(
    private val relay: String,
    private val identity: DeviceIdentity,
    val host: PairedHost,
    private val clientFactory: (String) -> BackendClient = { BackendClient(it) },
    private val backoffMs: (Int) -> Long = { BackendClient.backoffMs(it) },
) {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client: BackendClient = clientFactory(relay).also { it.authenticator = ControllerHello(identity) }
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val subs = mutableListOf<Subscription>()
    private var loop: Job? = null
    private var clientJob: Job? = null

    @Volatile var state: LinkState = LinkState(LinkState.Phase.STOPPED)
        private set
    @Volatile var session: ControllerSession? = null
        private set

    /** The signalling client, for components that share the connection (ICE lookup, pairing). */
    val signaling: BackendClient get() = client

    @Volatile var onState: ((LinkState) -> Unit)? = null
    /** A new verified session is ready: attach the feature clients to it here. */
    @Volatile var onSession: ((ControllerSession) -> Unit)? = null
    /** The session that was [onSession] has ended (any reason). */
    @Volatile var onSessionEnded: ((String) -> Unit)? = null

    private fun publish(s: LinkState) {
        state = s
        onState?.let { runCatching { it(s) } }
    }

    fun start() {
        if (loop?.isActive == true) return
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        subs += client.addStateListener { st ->
            if (state.phase != LinkState.Phase.STOPPED) publish(state.copy(signaling = st))
            wake.trySend(Unit)
        }
        subs += client.on("presence.update") { p ->
            if (p["hostId"]?.jsonPrimitive?.content == host.hostId && p["online"]?.jsonPrimitive?.booleanOrNull == true) wake.trySend(Unit)
        }
        subs += client.on("pairing.revoked") { p ->
            if (p["hostId"]?.jsonPrimitive?.content in setOf(host.hostId, null)) {
                session?.end("revoked", notifyPeer = false)
            }
        }
        clientJob = scope.launch { client.runForever() }
        publish(LinkState(LinkState.Phase.CONNECTING_SIGNALING, client.state))
        loop = scope.launch { run() }
    }

    /** User chose to disconnect: ends the session and stops all retrying. */
    fun stop() {
        loop?.cancel()
        clientJob?.cancel()
        session?.end("user_disconnect")
        subs.forEach { it.cancel() }
        subs.clear()
        session = null
        publish(LinkState(LinkState.Phase.STOPPED))
    }

    /** Try again right now with a fresh attempt counter (the "Reintentar" button after FAILED, or a manual nudge while waiting). */
    fun retryNow() {
        attempts = 0
        when (state.phase) {
            LinkState.Phase.STOPPED -> start()
            LinkState.Phase.FAILED, LinkState.Phase.REVOKED -> {
                if (loop?.isActive != true) loop = scope.launch { run() }
                wake.trySend(Unit)
            }
            else -> wake.trySend(Unit)
        }
    }

    /** The OS reported a different network (Wi-Fi <-> mobile data). */
    fun notifyNetworkChanged() {
        client.notifyNetworkChanged()
        wake.trySend(Unit)
    }

    fun release() {
        stop()
        scope.cancel()
    }

    @Volatile private var attempts = 0

    private suspend fun waitFor(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { wake.receive(); true } ?: false

    private suspend fun run() {
        while (scope.isActive) {
            // 1) Signalling must be authenticated before anything else.
            if (client.state != SignalingState.READY) {
                publish(LinkState(LinkState.Phase.CONNECTING_SIGNALING, client.state, attempt = attempts))
                waitFor(1_000)
                continue
            }

            // 2) Open the verified, encrypted session.
            publish(LinkState(LinkState.Phase.OPENING_SESSION, client.state, attempt = attempts))
            val opened = try {
                ControllerSession.open(client, identity, host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionFailed) {
                when (e.reason) {
                    "host_offline" -> {
                        attempts = 0
                        publish(LinkState(LinkState.Phase.HOST_OFFLINE, client.state, "host_offline"))
                        waitFor(15_000) // woken early by presence.update
                    }
                    "forbidden", "not_paired" -> {
                        publish(LinkState(LinkState.Phase.REVOKED, client.state, e.reason))
                        return
                    }
                    "user_denied", "host_not_accepting", "host_busy" -> {
                        publish(LinkState(LinkState.Phase.FAILED, client.state, e.reason, attempts))
                        return
                    }
                    else -> if (!failedAttempt(e.reason)) return
                }
                continue
            } catch (e: Exception) {
                if (!failedAttempt("session_error")) return
                continue
            }

            // 3) Online until it ends.
            attempts = 0
            session = opened
            val ended = CompletableDeferred<String>()
            opened.addEndedListener { reason -> ended.complete(reason); onSessionEnded?.invoke(reason) }
            onSession?.invoke(opened)
            publish(LinkState(LinkState.Phase.ONLINE, client.state))
            val reason = ended.await()
            session = null
            when (reason) {
                "user_disconnect" -> { publish(LinkState(LinkState.Phase.STOPPED)); return }
                "revoked" -> { publish(LinkState(LinkState.Phase.REVOKED, client.state, "revoked")); return }
                else -> {
                    publish(LinkState(LinkState.Phase.RECONNECTING, client.state, reason, attempts))
                    waitFor(backoffMs(1)) // a short, jittered pause so both ends settle before the next attempt
                }
            }
        }
    }

    /** Counts a failure, waits the backoff, and returns false when the limit was hit (state is then FAILED). */
    private suspend fun failedAttempt(reason: String): Boolean {
        attempts++
        if (attempts >= MAX_ATTEMPTS) {
            publish(LinkState(LinkState.Phase.FAILED, client.state, "too_many_attempts:$reason", attempts))
            return false
        }
        val wait = backoffMs(attempts)
        publish(LinkState(LinkState.Phase.RECONNECTING, client.state, reason, attempts, wait))
        waitFor(wait)
        return true
    }

    companion object {
        /** Consecutive failed attempts before giving up and asking the user. */
        const val MAX_ATTEMPTS = 8
    }
}
