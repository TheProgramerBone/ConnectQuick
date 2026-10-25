package dev.connectquick.controller.session

import dev.connectquick.controller.clipboard.ClipboardClient
import dev.connectquick.controller.files.FileDownloader
import dev.connectquick.controller.files.FileOffer
import dev.connectquick.controller.files.FileSink
import dev.connectquick.controller.files.FileUploader
import dev.connectquick.controller.identity.DeviceIdentity
import dev.connectquick.controller.input.InputClient
import dev.connectquick.controller.media.MediaClient
import dev.connectquick.controller.net.Subscription
import dev.connectquick.controller.power.PowerClient
import dev.connectquick.controller.stream.IceConfig
import dev.connectquick.controller.stream.IceConfigProvider
import dev.connectquick.controller.stream.RtcEngine
import dev.connectquick.controller.stream.StreamController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything the app needs to talk to ONE paired PC, assembled in one place: the self-healing
 * [RemoteLink] and, for each verified session, a fresh set of feature clients bound to it. Screens
 * observe it ([addListener]) and read the current clients; they never build protocol objects.
 *
 * The clients are replaced on every new session and set to null when it ends, so a screen can never
 * send through a dead session by accident: `hub.input?.…` is simply a no-op while offline.
 *
 * Pure JVM on purpose: the Android app injects only what is truly platform-specific (the WebRTC
 * engine and where received files go).
 */
class ControllerHub(
    val host: PairedHost,
    identity: DeviceIdentity,
    private val rtcEngine: RtcEngine?,
    /** Decides about a file the PC offers: return where to store it, or null to decline. */
    private val sinkFor: (FileOffer) -> FileSink?,
    link: RemoteLink? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val link: RemoteLink = link ?: RemoteLink(host.relay, identity, host)
    private val ice = IceConfigProvider(this.link.signaling)

    @Volatile var session: ControllerSession? = null
        private set
    @Volatile var input: InputClient? = null
        private set
    @Volatile var clipboard: ClipboardClient? = null
        private set
    @Volatile var media: MediaClient? = null
        private set
    @Volatile var power: PowerClient? = null
        private set
    @Volatile var uploader: FileUploader? = null
        private set
    @Volatile var downloader: FileDownloader? = null
        private set
    @Volatile var streams: StreamController? = null
        private set

    /** Text the PC pushed (clipboard auto-sync); the app writes it to the system clipboard. */
    @Volatile var onRemoteClipboard: ((String) -> Unit)? = null

    /** The PC reported a different list of relays; the app stores it so the next launch already knows them. */
    @Volatile var onRelaysLearned: ((List<String>) -> Unit)? = null

    val state: LinkState get() = this.link.state

    /** True only when a verified session exists AND the PC granted [permission] at pairing. */
    fun can(permission: String): Boolean = session?.permissions?.contains(permission) == true

    fun addListener(listener: () -> Unit): Subscription {
        listeners.add(listener)
        return Subscription { listeners.remove(listener) }
    }

    private fun changed() = listeners.forEach { runCatching { it() } }

    init {
        this.link.onState = { changed() }
        this.link.onSession = { s -> attach(s) }
        this.link.onSessionEnded = { detach() }
    }

    private fun attach(s: ControllerSession) {
        detach()
        session = s
        input = InputClient(s)
        clipboard = ClipboardClient(s).also { it.onRemoteText = { text -> onRemoteClipboard?.invoke(text) } }
        media = MediaClient(s)
        power = PowerClient(s)
        uploader = FileUploader(s)
        downloader = FileDownloader(s, { s.hostCaps?.maxFileBytes ?: 0L }, scope).also { it.onOffer = sinkFor }
        learnRelays(s.hostCaps?.relays.orEmpty())
        streams = rtcEngine?.let { engine -> StreamController(s, { ice.get() }, engine) }
        changed()
    }

    private fun learnRelays(advertised: List<String>) {
        // Only websocket addresses, and never more than the PC sends; an empty list means "not said", keep ours.
        val relays = advertised.filter { it.startsWith("ws://") || it.startsWith("wss://") }.take(4)
        if (relays.isEmpty() || relays == host.relays) return
        link.updateAlternateRelays(relays)
        onRelaysLearned?.let { runCatching { it(relays) } }
    }

    private fun detach() {
        val had = session != null
        // Release whatever the PC may still be holding for us, then drop every client.
        runCatching { if (session?.isOpen == true) input?.releaseAll() }
        runCatching { streams?.release() }
        runCatching { downloader?.sessionEnded() }
        session = null; input = null; clipboard = null; media = null; power = null
        uploader = null; downloader = null; streams = null
        if (had) changed()
    }

    fun start() = link.start()
    fun stop() = link.stop()
    fun retryNow() = link.retryNow()
    fun networkChanged() = link.notifyNetworkChanged()

    fun release() {
        link.release()
        detach()
        scope.cancel()
        listeners.clear()
    }

    /** ICE servers the next stream would use (for the "streaming needs a relay on different networks" hint). */
    suspend fun iceConfig(): IceConfig = ice.get()
}
