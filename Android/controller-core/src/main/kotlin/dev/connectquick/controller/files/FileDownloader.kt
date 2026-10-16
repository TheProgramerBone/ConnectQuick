package dev.connectquick.controller.files

import dev.connectquick.controller.crypto.CryptoUtil
import dev.connectquick.controller.session.Pending
import dev.connectquick.controller.session.SessionPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Receives files from the PC (PC -> phone): either ones the PC user pushed, or ones this phone
 * asked for BY NAME from the PC's Outbox via [request]. Every offer is vetted before a byte is
 * accepted (size cap from the PC's own capabilities, sink decides destination), chunks must arrive
 * strictly in order and never exceed the announced size, and the SHA-256 must match before
 * [FileSink.commit] makes the file visible.
 */
class FileDownloader(
    private val port: SessionPort,
    private val maxBytes: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val idleTimeoutMs: Long = 30_000,
) {
    /** Decide about an offer: return a sink to accept it, or null to decline. */
    @Volatile var onOffer: ((FileOffer) -> FileSink?)? = null
    @Volatile var onProgress: ((TransferProgress) -> Unit)? = null

    private class Active(val offer: FileOffer, val sink: FileSink, val out: OutputStream) {
        var received = 0L
        var lastAcked = 0L
        val lastActivity = AtomicLong(System.nanoTime())
        @Volatile var finishing = false
    }

    private val listings = Pending<List<RemoteFile>>()
    @Volatile private var active: Active? = null
    private val lock = Any()

    init {
        port.on("file.listing") { m ->
            val files = m.getValue("files").jsonArray.map {
                val o = it.jsonObject
                RemoteFile(o.getValue("name").jsonPrimitive.content, o.getValue("size").jsonPrimitive.longOrNull ?: 0, o.getValue("modifiedMs").jsonPrimitive.longOrNull ?: 0)
            }
            listings.complete(m.getValue("id").jsonPrimitive.content, files)
        }
        port.on("file.offer") { m -> onOfferMsg(m) }
        port.on("file.chunk") { m -> onChunk(m) }
        port.on("file.done") { m -> onDone(m) }
        port.on("file.cancel") { m -> onPeerCancel(m) }
        port.on("file.reject") { m ->
            // The PC declined an Outbox request (not_found, too_large, busy...).
            onProgress?.invoke(TransferProgress(m.getValue("transferId").jsonPrimitive.content, TransferDirection.DOWNLOAD, "", 0, 0, TransferStatus.FAILED, m.getValue("reason").jsonPrimitive.content))
        }
    }

    suspend fun list(timeoutMs: Long = 8_000): List<RemoteFile>? {
        val id = listings.newId()
        val waiting = listings.register(id)
        if (!port.send("file.list") { put("id", id) }) return null
        return try { listings.await(id, waiting, timeoutMs) } catch (e: TimeoutCancellationException) { null }
    }

    /** Asks the PC to send [name] from its Outbox. The PC replies with a `file.offer`, handled like any other. */
    fun request(name: String): Boolean {
        val id = UUID.randomUUID().toString()
        return port.send("file.request") { put("id", id); put("transferId", UUID.randomUUID().toString()); put("name", name) }
    }

    private fun reject(tid: String, reason: String) {
        port.send("file.reject") { put("transferId", tid); put("reason", reason) }
    }

    private fun onOfferMsg(m: JsonObject) {
        val offer = FileOffer(
            m.getValue("transferId").jsonPrimitive.content, m.getValue("name").jsonPrimitive.content,
            m.getValue("size").jsonPrimitive.longOrNull ?: -1, m.getValue("sha256").jsonPrimitive.content,
            (m.getValue("chunkSize").jsonPrimitive.longOrNull ?: CHUNK_SIZE.toLong()).toInt(),
        )
        synchronized(lock) { if (active != null) { reject(offer.transferId, "busy"); return } }
        val cap = maxBytes()
        if (offer.size < 0 || (cap > 0 && offer.size > cap)) return reject(offer.transferId, "too_large")
        val sink = onOffer?.invoke(offer) ?: return reject(offer.transferId, "declined")
        try {
            var offset = sink.existingLength()
            if (offset > offer.size) { sink.discard(); offset = 0 }
            val out = sink.open(append = offset > 0)
            val a = Active(offer, sink, out).also { it.received = offset; it.lastAcked = offset }
            synchronized(lock) { active = a }
            watchIdle(a)
            onProgress?.invoke(TransferProgress(offer.transferId, TransferDirection.DOWNLOAD, offer.name, offset, offer.size, TransferStatus.RUNNING))
            port.send("file.accept") { put("transferId", offer.transferId); put("offset", offset) }
        } catch (e: java.io.IOException) {
            runCatching { sink.discard() }
            reject(offer.transferId, "storage_error")
        }
    }

    private fun onChunk(m: JsonObject) {
        val a = active
        val tid = m.getValue("transferId").jsonPrimitive.content
        if (a == null || a.offer.transferId != tid || a.finishing) {
            port.send("file.cancel") { put("transferId", tid); put("reason", "unknown_transfer") }
            return
        }
        val offset = m.getValue("offset").jsonPrimitive.longOrNull ?: -1
        if (offset != a.received) return fail(a, "bad_offset", keep = true)
        val data = try { CryptoUtil.fromBase64Url(m.getValue("data").jsonPrimitive.content) } catch (e: IllegalArgumentException) { return fail(a, "bad_data", keep = true) }
        if (data.isEmpty() || data.size > a.offer.chunkSize) return fail(a, "bad_chunk_size", keep = true)
        if (a.received + data.size > a.offer.size) return fail(a, "overflow", keep = false)
        try { a.out.write(data) } catch (e: java.io.IOException) { return fail(a, "write_failed", keep = true) }
        a.received += data.size
        a.lastActivity.set(System.nanoTime())
        onProgress?.invoke(TransferProgress(a.offer.transferId, TransferDirection.DOWNLOAD, a.offer.name, a.received, a.offer.size, TransferStatus.RUNNING))
        if (a.received - a.lastAcked >= ACK_EVERY_BYTES || a.received == a.offer.size) {
            a.lastAcked = a.received
            port.send("file.ack") { put("transferId", a.offer.transferId); put("received", a.received) }
        }
    }

    private fun onDone(m: JsonObject) {
        val a = active ?: return
        if (a.offer.transferId != m.getValue("transferId").jsonPrimitive.content) return
        if (a.received != a.offer.size) return fail(a, "truncated", keep = true)
        a.finishing = true
        scope.launch {
            try {
                a.out.flush(); a.out.close()
                if (!a.sink.sha256HexOfStored().equals(a.offer.sha256, ignoreCase = true)) {
                    a.sink.discard()
                    finish(a, TransferResult(false, "hash_mismatch", null))
                    return@launch
                }
                val saved = a.sink.commit()
                finish(a, TransferResult(true, null, saved))
            } catch (e: Exception) {
                runCatching { a.sink.discard() }
                finish(a, TransferResult(false, "storage_error", null))
            }
        }
    }

    private fun finish(a: Active, r: TransferResult) {
        synchronized(lock) { if (active === a) active = null }
        onProgress?.invoke(TransferProgress(a.offer.transferId, TransferDirection.DOWNLOAD, a.offer.name, a.received, a.offer.size,
            if (r.ok) TransferStatus.DONE else TransferStatus.FAILED, r.reason, r.savedAs))
        port.send("file.result") {
            put("transferId", a.offer.transferId); put("ok", r.ok)
            if (r.reason != null) put("reason", r.reason)
            if (r.savedAs != null) put("savedAs", r.savedAs)
        }
    }

    private fun fail(a: Active, reason: String, keep: Boolean) {
        synchronized(lock) { if (active !== a) return; active = null }
        runCatching { a.out.close() }
        if (!keep || a.received == 0L) runCatching { a.sink.discard() }
        onProgress?.invoke(TransferProgress(a.offer.transferId, TransferDirection.DOWNLOAD, a.offer.name, a.received, a.offer.size, TransferStatus.FAILED, reason))
        port.send("file.result") { put("transferId", a.offer.transferId); put("ok", false); put("reason", reason) }
    }

    private fun onPeerCancel(m: JsonObject) {
        val a = active ?: return
        if (a.offer.transferId != m.getValue("transferId").jsonPrimitive.content) return
        synchronized(lock) { active = null }
        runCatching { a.out.close() }
        runCatching { a.sink.discard() }
        onProgress?.invoke(TransferProgress(a.offer.transferId, TransferDirection.DOWNLOAD, a.offer.name, a.received, a.offer.size, TransferStatus.CANCELLED, m["reason"]?.jsonPrimitive?.content))
    }

    private fun watchIdle(a: Active) {
        scope.launch {
            while (active === a && !a.finishing) {
                delay(maxOf(50, idleTimeoutMs / 4))
                if ((System.nanoTime() - a.lastActivity.get()) / 1_000_000 > idleTimeoutMs && !a.finishing) {
                    fail(a, "idle_timeout", keep = true)
                    return@launch
                }
            }
        }
    }

    /** The session ended: close handles but keep partial data so the same file can resume. */
    fun sessionEnded() {
        val a = synchronized(lock) { active.also { active = null } } ?: return
        runCatching { a.out.close() }
        if (a.received == 0L) runCatching { a.sink.discard() }
        onProgress?.invoke(TransferProgress(a.offer.transferId, TransferDirection.DOWNLOAD, a.offer.name, a.received, a.offer.size, TransferStatus.FAILED, "session_ended"))
    }
}
