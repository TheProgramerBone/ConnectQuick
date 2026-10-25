package dev.connectquick.controller.harness

import dev.connectquick.controller.clipboard.ClipboardClient
import dev.connectquick.controller.clipboard.ClipboardOutcome
import dev.connectquick.controller.clipboard.PullOutcome
import dev.connectquick.controller.crypto.CryptoUtil
import dev.connectquick.controller.files.FileDownloader
import dev.connectquick.controller.files.FileSink
import dev.connectquick.controller.files.FileSource
import dev.connectquick.controller.files.FileUploader
import dev.connectquick.controller.identity.InMemoryDeviceIdentity
import dev.connectquick.controller.input.InputClient
import dev.connectquick.controller.input.MouseButton
import dev.connectquick.controller.media.MediaAction
import dev.connectquick.controller.media.MediaClient
import dev.connectquick.controller.net.BackendClient
import dev.connectquick.controller.pairing.PairingController
import dev.connectquick.controller.pairing.PairingOutcome
import dev.connectquick.controller.pairing.QrPairingPayload
import dev.connectquick.controller.power.PowerAction
import dev.connectquick.controller.power.PowerChallenge
import dev.connectquick.controller.power.PowerClient
import dev.connectquick.controller.power.PowerRequestOutcome
import dev.connectquick.controller.session.ControllerHub
import dev.connectquick.controller.session.ControllerSession
import dev.connectquick.controller.session.PairedHost
import dev.connectquick.controller.session.LinkState
import dev.connectquick.controller.session.RemoteLink
import dev.connectquick.controller.session.SessionFailed
import dev.connectquick.controller.stream.IceConfig
import dev.connectquick.controller.stream.IceConfigProvider
import dev.connectquick.controller.stream.RtcCandidate
import dev.connectquick.controller.stream.RtcEngine
import dev.connectquick.controller.stream.RtcSession
import dev.connectquick.controller.stream.RtcState
import dev.connectquick.controller.stream.StreamController
import dev.connectquick.controller.stream.StreamRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * Headless Controller for cross-platform integration tests. It runs the SAME production classes the
 * Android app runs (BackendClient, PairingController, ControllerSession, every feature client);
 * only the UI and the Keystore are absent. Controlled through JSON lines on stdin/stdout:
 *   stdin : {"cmd": "...", "id": "<correlation>", ...args}
 *   stdout: {"evt": "...", ...} asynchronous events and {"evt":"reply","id":...} for every command.
 */
private val json = Json { ignoreUnknownKeys = true }
private val outLock = Any()
// stdout/stdin encoding follows the platform locale (ASCII under POSIX); the protocol is UTF-8 JSON lines.
private val stdout = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8")
private val stdin = java.io.BufferedReader(java.io.InputStreamReader(System.`in`, Charsets.UTF_8))
private fun emit(o: JsonObject) = synchronized(outLock) { stdout.println(o.toString()); stdout.flush() }
private fun evt(name: String, fill: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
    emit(buildJsonObject { put("evt", name); fill() })

private fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

private class MemSource(override val name: String, val data: ByteArray) : FileSource {
    override val size = data.size.toLong()
    override fun sha256Hex() = sha256Hex(data)
    override fun open(offset: Long): InputStream = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
}

private class MemSink(val name: String, private val onCommit: (String, ByteArray) -> Unit) : FileSink {
    val buffer = ByteArrayOutputStream()
    override fun existingLength() = 0L
    override fun open(append: Boolean): OutputStream = buffer
    override fun sha256HexOfStored() = sha256Hex(buffer.toByteArray())
    override fun commit(): String { onCommit(name, buffer.toByteArray()); return name }
    override fun discard() { buffer.reset() }
}

/** Stands in for libwebrtc: answers any offer and lets the scenario drive the connection state. */
private class HarnessRtcSession(val ice: IceConfig) : RtcSession {
    override var onState: ((RtcState) -> Unit)? = null
    @Volatile var closed = false
    @Volatile var offer: String? = null
    val remoteCandidates = CopyOnWriteArrayList<String>()
    override fun answer(offerSdp: String, onCandidate: (RtcCandidate) -> Unit): String {
        offer = offerSdp
        onCandidate(RtcCandidate("candidate:1 1 udp 2130706431 192.168.0.9 50000 typ host", "0", 0))
        return "v=0\r\nharness-answer"
    }
    override fun addRemoteCandidate(candidate: RtcCandidate) { remoteCandidates += candidate.candidate }
    override fun close() { closed = true }
}

private class HarnessRtcEngine : RtcEngine {
    val sessions = CopyOnWriteArrayList<HarnessRtcSession>()
    override fun createSession(ice: IceConfig): RtcSession = HarnessRtcSession(ice).also { sessions += it }
}

fun main(): Unit = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var identity: InMemoryDeviceIdentity? = null
    var client: BackendClient? = null
    var clientJob: kotlinx.coroutines.Job? = null
    var host: PairedHost? = null
    var session: ControllerSession? = null

    var input: InputClient? = null
    var clipboard: ClipboardClient? = null
    var media: MediaClient? = null
    var power: PowerClient? = null
    var uploader: FileUploader? = null
    var downloader: FileDownloader? = null
    var pendingChallenge: PowerChallenge? = null
    var streams: StreamController? = null
    var link: RemoteLink? = null
    var iceProvider: IceConfigProvider? = null
    val rtcEngine = HarnessRtcEngine()

    val remoteClipboard = CopyOnWriteArrayList<String>()
    val downloads = ConcurrentHashMap<String, ByteArray>()
    val transfers = CopyOnWriteArrayList<String>()
    val errors = CopyOnWriteArrayList<String>()
    val pairedPermissions = CopyOnWriteArrayList<String>()
    val hostErrors = CopyOnWriteArrayList<String>()

    fun dataB64(o: JsonObject) = Base64.getDecoder().decode(o.getValue("dataB64").jsonPrimitive.content)

    fun attachSession(s: ControllerSession) {
        session = s
        input = InputClient(s)
        clipboard = ClipboardClient(s).also { it.onRemoteText = { t -> remoteClipboard += t } }
        media = MediaClient(s)
        power = PowerClient(s)
        uploader = FileUploader(s)
        downloader = FileDownloader(s, { s.hostCaps?.maxFileBytes ?: 0 }, scope).also { d ->
            d.onOffer = { offer -> MemSink(offer.name) { n, b -> downloads[n] = b } }
            d.onProgress = { p -> transfers += "${p.direction}:${p.status}:${p.name}:${p.reason ?: ""}" }
        }
        s.on("ctl.error") { m -> hostErrors += m.getValue("code").jsonPrimitive.content }
        streams?.release()
        streams = StreamController(s, { iceProvider!!.get() }, rtcEngine)
        s.addEndedListener { reason -> evt("sessionEnded") { put("reason", reason) } }
    }

    suspend fun handle(cmd: JsonObject): JsonObject = when (val name = cmd.getValue("cmd").jsonPrimitive.content) {
        "init" -> {
            val seed = cmd["seed"]?.jsonPrimitive?.content
            val id = if (seed != null) InMemoryDeviceIdentity.fromSeed(CryptoUtil.fromBase64Url(seed)) else InMemoryDeviceIdentity.generate()
            identity = id
            val c = BackendClient(
                cmd.getValue("relay").jsonPrimitive.content,
                heartbeatMs = System.getenv("CQ_HEARTBEAT_MS")?.toLongOrNull() ?: BackendClient.HEARTBEAT_MS,
                deadAfterMs = System.getenv("CQ_DEAD_AFTER_MS")?.toLongOrNull() ?: BackendClient.DEAD_AFTER_MS,
            )
            c.authenticator = dev.connectquick.controller.net.ControllerHello(id)
            client = c
            c.onFault = { errors += it }
            c.addStateListener { s -> evt("signaling") { put("state", s.name) } }
            c.on("pairing.revoked") { p -> evt("revoked") { put("hostId", p["hostId"]?.jsonPrimitive?.content ?: "") } }
            c.on("presence.update") { p -> evt("presence") { put("hostId", p.getValue("hostId").jsonPrimitive.content); put("online", p.getValue("online").jsonPrimitive.boolean) } }
            iceProvider = IceConfigProvider(c)
            clientJob = scope.launch { c.runForever() }
            buildJsonObject { put("deviceId", id.deviceId) }
        }
        "pair" -> {
            val qr = json.decodeFromString(QrPairingPayload.serializer(), cmd.getValue("qr").toString())
            val c = client!!
            val outcome = CompletableDeferred<PairingOutcome>()
            // The phone is already authenticated on its one signalling connection; pairing rides on it.
            val pc = PairingController(c, identity!!, deviceName = cmd["deviceName"]?.jsonPrimitive?.content ?: "Harness Phone")
            pc.onOutcome = { outcome.complete(it) }
            var sas = ""
            pc.onSasReady = { sas = it }
            withTimeout(15_000) { while (c.state != dev.connectquick.controller.net.SignalingState.READY) kotlinx.coroutines.delay(50) }
            pc.submitPairing(qr)
            val result = withTimeout(30_000) { outcome.await() }
            when (result) {
                is PairingOutcome.Success -> {
                    pairedPermissions.clear(); pairedPermissions += result.permissions
                    host = PairedHost(result.hostId, result.hostName, qr.hostPub, qr.relay, result.permissions, System.currentTimeMillis(), qr.relays)
                    buildJsonObject { put("hostId", result.hostId); put("sas", sas); put("permissions", buildJsonArray { result.permissions.forEach { add(JsonPrimitive(it)) } }) }
                }
                is PairingOutcome.Failure -> buildJsonObject { put("error", result.reason) }
            }
        }
        "connect" -> {
            session?.end("reconnecting")
            try {
                val s = ControllerSession.open(client!!, identity!!, host!!, timeoutMs = (cmd["timeoutMs"]?.jsonPrimitive?.content?.toLong() ?: 20_000))
                attachSession(s)
                val caps = s.hostCaps!!
                buildJsonObject {
                    put("sessionId", s.sessionId)
                    put("permissions", buildJsonArray { s.permissions.sorted().forEach { add(JsonPrimitive(it)) } })
                    put("features", buildJsonArray { caps.features.sorted().forEach { add(JsonPrimitive(it)) } })
                    put("maxFileBytes", caps.maxFileBytes)
                }
            } catch (e: SessionFailed) {
                buildJsonObject { put("error", e.reason) }
            }
        }
        "linkInit" -> {
            val seed = cmd["seed"]?.jsonPrimitive?.content
            val id = if (seed != null) InMemoryDeviceIdentity.fromSeed(CryptoUtil.fromBase64Url(seed)) else InMemoryDeviceIdentity.generate()
            identity = id
            host = json.decodeFromJsonElement(PairedHost.serializer(), cmd.getValue("host"))
            val l = RemoteLink(cmd.getValue("relay").jsonPrimitive.content, id, host!!, backoffMs = { 300L })
            link = l
            client = l.signaling
            l.signaling.onFault = { errors += it }
            iceProvider = IceConfigProvider(l.signaling)
            // The same assembly the Android app uses (feature clients per verified session), so the integration suite exercises it for real.
            val h = ControllerHub(host!!, id, rtcEngine, { offer -> MemSink(offer.name) { n, b -> downloads[n] = b } }, l)
            h.onRemoteClipboard = { t -> remoteClipboard += t }
            var attached: ControllerSession? = null
            h.addListener {
                val st = l.state
                evt("link") { put("phase", st.phase.name); put("reason", st.reason ?: ""); put("attempt", st.attempt) }
                val sess = h.session
                if (sess != null && sess !== attached) {
                    attached = sess
                    session = sess
                    input = h.input; clipboard = h.clipboard; media = h.media; power = h.power
                    uploader = h.uploader; downloader = h.downloader; streams = h.streams
                    h.downloader?.onProgress = { p -> transfers += "${p.direction}:${p.status}:${p.name}:${p.reason ?: ""}" }
                    sess.on("ctl.error") { m -> hostErrors += m.getValue("code").jsonPrimitive.content }
                    sess.addEndedListener { reason -> evt("sessionEnded") { put("reason", reason) } }
                    evt("linkSession") { put("permissions", buildJsonArray { sess.permissions.sorted().forEach { add(JsonPrimitive(it)) } }) }
                }
            }
            buildJsonObject { put("deviceId", id.deviceId) }
        }
        "linkStart" -> { link!!.start(); buildJsonObject { } }
        "linkStop" -> { link!!.stop(); buildJsonObject { } }
        "linkRetry" -> { link!!.retryNow(); buildJsonObject { } }
        "linkState" -> buildJsonObject {
            val st = link?.state
            put("phase", st?.phase?.name ?: "NONE")
            put("reason", st?.reason ?: "")
            put("attempt", st?.attempt ?: 0)
            put("signaling", st?.signaling?.name ?: "")
        }
        "exportHost" -> json.encodeToJsonElement(PairedHost.serializer(), host!!).jsonObject
        "importHost" -> { host = json.decodeFromJsonElement(PairedHost.serializer(), cmd.getValue("host")); buildJsonObject { } }
        "streamStart" -> {
            val ok = streams!!.start(StreamRequest(
                cmd["maxWidth"]?.jsonPrimitive?.int ?: 1280, cmd["maxHeight"]?.jsonPrimitive?.int ?: 720,
                cmd["maxFps"]?.jsonPrimitive?.int ?: 30, cmd["maxBitrateKbps"]?.jsonPrimitive?.int ?: 2500,
            ))
            buildJsonObject { put("sent", ok) }
        }
        "streamStop" -> { streams!!.stop(); buildJsonObject { } }
        "rtcState" -> {
            rtcEngine.sessions.last().onState?.invoke(RtcState.valueOf(cmd.getValue("state").jsonPrimitive.content))
            buildJsonObject { }
        }
        "streamState" -> {
            val st = streams?.status
            val last = rtcEngine.sessions.lastOrNull()
            buildJsonObject {
                put("phase", st?.phase?.name ?: "NONE")
                put("reason", st?.reason ?: "")
                put("width", st?.width ?: 0)
                put("height", st?.height ?: 0)
                put("blocked", st?.blocked ?: "")
                put("fps", st?.stats?.fps ?: -1.0)
                put("kbps", st?.stats?.bitrateKbps ?: -1)
                put("rtcSessions", rtcEngine.sessions.size)
                put("rtcClosed", last?.closed ?: false)
                put("offer", last?.offer ?: "")
                put("remoteCandidates", last?.remoteCandidates?.size ?: 0)
                put("hasTurn", last?.ice?.hasTurn ?: false)
                put("hasStun", last?.ice?.hasStun ?: false)
                put("turnUsername", last?.ice?.servers?.firstOrNull { it.username != null }?.username ?: "")
            }
        }
        "disconnect" -> { session?.end("user_disconnect"); buildJsonObject { } }
        "move" -> { input!!.mouseMove(cmd.getValue("dx").jsonPrimitive.int, cmd.getValue("dy").jsonPrimitive.int); buildJsonObject { } }
        "abs" -> { input!!.mouseAbsolute(cmd.getValue("x").jsonPrimitive.int, cmd.getValue("y").jsonPrimitive.int); buildJsonObject { } }
        "button" -> {
            input!!.mouseButton(MouseButton.values().first { it.wire == cmd.getValue("button").jsonPrimitive.content }, cmd.getValue("down").jsonPrimitive.boolean)
            buildJsonObject { }
        }
        "wheel" -> { input!!.wheel(cmd.getValue("dx").jsonPrimitive.int, cmd.getValue("dy").jsonPrimitive.int); buildJsonObject { } }
        "key" -> { input!!.key(cmd.getValue("code").jsonPrimitive.content, cmd.getValue("down").jsonPrimitive.boolean); buildJsonObject { } }
        "text" -> { input!!.typeText(cmd.getValue("text").jsonPrimitive.content); buildJsonObject { } }
        "releaseAll" -> { input!!.releaseAll(); buildJsonObject { } }
        "raw" -> {
            // Send an arbitrary inner message exactly as given (hostile-controller scenarios).
            val ok = session!!.send(cmd.getValue("type").jsonPrimitive.content) { (cmd["fields"] as? JsonObject)?.forEach { (k, v) -> put(k, v) } }
            buildJsonObject { put("sent", ok) }
        }
        "clipboardPush" -> buildJsonObject {
            when (val o = clipboard!!.push(cmd.getValue("text").jsonPrimitive.content)) {
                ClipboardOutcome.Sent -> put("outcome", "sent")
                ClipboardOutcome.Duplicate -> put("outcome", "duplicate")
                is ClipboardOutcome.Failed -> { put("outcome", "failed"); put("reason", o.reason) }
            }
        }
        "clipboardPull" -> buildJsonObject {
            when (val o = clipboard!!.pull()) {
                is PullOutcome.Text -> { put("outcome", "text"); put("text", o.text) }
                is PullOutcome.Failed -> { put("outcome", "failed"); put("reason", o.reason) }
            }
        }
        "media" -> {
            val a = MediaAction.values().first { it.wire == cmd.getValue("action").jsonPrimitive.content }
            val o = media!!.control(a, cmd["steps"]?.jsonPrimitive?.int ?: 1)
            buildJsonObject { put("delivered", o.delivered); put("confirmed", o.confirmed); put("reason", o.reason ?: "") }
        }
        "power" -> {
            val a = PowerAction.values().first { it.wire == cmd.getValue("action").jsonPrimitive.content }
            when (val o = power!!.request(a)) {
                is PowerRequestOutcome.NeedsConfirmation -> { pendingChallenge = o.challenge; buildJsonObject { put("needsConfirmation", true); put("delaySeconds", o.challenge.delaySeconds) } }
                is PowerRequestOutcome.Done -> buildJsonObject { put("ok", o.result.ok); put("reason", o.result.reason ?: "") }
                is PowerRequestOutcome.Failed -> buildJsonObject { put("error", o.reason) }
            }
        }
        "powerConfirm" -> {
            val r = power!!.confirm(pendingChallenge!!)
            pendingChallenge = null
            buildJsonObject { put("ok", r.ok); put("reason", r.reason ?: ""); put("delaySeconds", r.delaySeconds) }
        }
        "upload" -> {
            val r = uploader!!.upload(MemSource(cmd.getValue("name").jsonPrimitive.content, dataB64(cmd)))
            buildJsonObject { put("ok", r.ok); put("reason", r.reason ?: ""); put("savedAs", r.savedAs ?: "") }
        }
        "list" -> {
            val files = downloader!!.list()
            buildJsonObject {
                put("files", buildJsonArray { files?.forEach { add(buildJsonObject { put("name", it.name); put("size", it.size) }) } })
                put("timedOut", files == null)
            }
        }
        "download" -> { downloader!!.request(cmd.getValue("name").jsonPrimitive.content); buildJsonObject { } }
        "state" -> buildJsonObject {
            put("signaling", client?.state?.name ?: "NONE")
            put("sessionOpen", session?.isOpen ?: false)
            put("sessionEnd", session?.endReason ?: "")
            put("rttMs", session?.rttMs ?: -1)
            put("remoteClipboard", buildJsonArray { remoteClipboard.forEach { add(JsonPrimitive(it)) } })
            put("downloads", buildJsonObject { downloads.forEach { (k, v) -> put(k, sha256Hex(v)) } })
            put("downloadSizes", buildJsonObject { downloads.forEach { (k, v) -> put(k, v.size) } })
            put("transfers", buildJsonArray { transfers.forEach { add(JsonPrimitive(it)) } })
            put("errors", buildJsonArray { errors.forEach { add(JsonPrimitive(it)) } })
            put("lastViolation", session?.lastViolation ?: "")
            put("hostErrors", buildJsonArray { hostErrors.forEach { add(JsonPrimitive(it)) } })
            put("pairedPermissions", buildJsonArray { pairedPermissions.forEach { add(JsonPrimitive(it)) } })
        }
        "networkChanged" -> { client?.notifyNetworkChanged(); buildJsonObject { } }
        "quit" -> buildJsonObject { put("quit", true) }
        else -> buildJsonObject { put("error", "unknown_command:$name") }
    }

    evt("ready")
    while (true) {
        val line = stdin.readLine() ?: break
        val cmd = try { json.parseToJsonElement(line).jsonObject } catch (e: Exception) { evt("error") { put("message", "bad command line") }; continue }
        val reply: JsonObject = try {
            handle(cmd)
        } catch (e: Exception) {
            buildJsonObject { put("error", "${e.javaClass.simpleName}: ${e.message}") }
        }
        emit(buildJsonObject {
            put("evt", "reply")
            cmd["id"]?.let { put("id", it) }
            reply.forEach { (k, v) -> put(k, v) }
        })
        if (reply["quit"] != null) break
    }
    session?.end("harness_quit")
    clientJob?.cancel()
    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    kotlin.system.exitProcess(0)
}
