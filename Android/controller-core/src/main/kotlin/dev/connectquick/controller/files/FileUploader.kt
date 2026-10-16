package dev.connectquick.controller.files

import dev.connectquick.controller.crypto.CryptoUtil
import dev.connectquick.controller.net.Subscription
import dev.connectquick.controller.session.SessionPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends one file to the PC (phone -> PC). The PC decides (size cap, free space, duplicate,
 * destination) and replies with the offset to resume from; data then flows as sequential chunks
 * under a flow-control window; the PC verifies SHA-256 before the file becomes visible.
 * Cancelling the coroutine (or [cancel]) tells the PC to discard the partial file.
 */
class FileUploader(private val port: SessionPort) {
    @Volatile var onProgress: ((TransferProgress) -> Unit)? = null

    @Volatile private var activeId: String? = null

    suspend fun upload(source: FileSource): TransferResult {
        if (activeId != null) return TransferResult(false, "busy", null)
        val tid = UUID.randomUUID().toString()
        activeId = tid
        val subs = mutableListOf<Subscription>()
        val answer = CompletableDeferred<Pair<Boolean, String>>()   // accept(offset) / reject(reason)
        val result = CompletableDeferred<TransferResult>()
        val acked = AtomicLong(0)
        val ackSignal = java.util.concurrent.Semaphore(0)
        fun progress(bytes: Long, status: TransferStatus, reason: String? = null, savedAs: String? = null) =
            onProgress?.invoke(TransferProgress(tid, TransferDirection.UPLOAD, source.name, bytes, source.size, status, reason, savedAs))

        try {
            subs += port.on("file.accept") { m ->
                if (m.getValue("transferId").jsonPrimitive.content == tid) answer.complete(true to (m.getValue("offset").jsonPrimitive.longOrNull ?: 0L).toString())
            }
            subs += port.on("file.reject") { m ->
                if (m.getValue("transferId").jsonPrimitive.content == tid) answer.complete(false to m.getValue("reason").jsonPrimitive.content)
            }
            subs += port.on("file.ack") { m ->
                if (m.getValue("transferId").jsonPrimitive.content == tid) {
                    val r = m.getValue("received").jsonPrimitive.longOrNull ?: return@on
                    if (r > acked.get() && r <= source.size) acked.set(r)
                    ackSignal.release()
                }
            }
            subs += port.on("file.result") { m ->
                if (m.getValue("transferId").jsonPrimitive.content == tid) {
                    result.complete(TransferResult(
                        m.getValue("ok").jsonPrimitive.booleanOrNull == true,
                        m["reason"]?.jsonPrimitive?.content, m["savedAs"]?.jsonPrimitive?.content,
                    ))
                }
            }
            subs += port.on("ctl.error") { m ->
                // The PC refused before it even looked at the file (no 'files' permission, disabled on the PC...).
                if (m["id"]?.jsonPrimitive?.content == tid) answer.complete(false to m.getValue("code").jsonPrimitive.content)
            }
            subs += port.on("file.cancel") { m ->
                if (m.getValue("transferId").jsonPrimitive.content == tid) {
                    val r = m["reason"]?.jsonPrimitive?.content ?: "cancelled_by_pc"
                    answer.complete(false to r)
                    result.complete(TransferResult(false, r, null))
                }
            }

            val sha = withContext(Dispatchers.IO) { source.sha256Hex() }
            progress(0, TransferStatus.RUNNING)
            if (!port.send("file.offer") {
                    put("transferId", tid); put("name", source.name); put("size", source.size); put("sha256", sha); put("chunkSize", CHUNK_SIZE)
                }) return TransferResult(false, "session_closed", null)

            val (accepted, detail) = withTimeout(60_000) { answer.await() }
            if (!accepted) {
                progress(0, TransferStatus.FAILED, detail)
                return TransferResult(false, detail, null)
            }
            val offsetAccepted = detail.toLong()
            if (offsetAccepted !in 0..source.size) return TransferResult(false, "bad_offset", null)
            acked.set(offsetAccepted)

            var sent = offsetAccepted
            withContext(Dispatchers.IO) {
                source.open(offsetAccepted).use { input ->
                    val buf = ByteArray(CHUNK_SIZE)
                    while (sent < source.size) {
                        if (result.isCompleted) return@withContext // the PC aborted
                        // Flow control: never run more than a window ahead of what the PC confirmed.
                        while (sent - acked.get() >= WINDOW_CHUNKS.toLong() * CHUNK_SIZE) {
                            if (result.isCompleted) return@withContext
                            if (!ackSignal.tryAcquire(30, java.util.concurrent.TimeUnit.SECONDS)) throw java.util.concurrent.TimeoutException("pc_stalled")
                        }
                        val want = minOf(CHUNK_SIZE.toLong(), source.size - sent).toInt()
                        var read = 0
                        while (read < want) {
                            val n = input.read(buf, read, want - read)
                            if (n < 0) throw java.io.EOFException()
                            read += n
                        }
                        val data = CryptoUtil.toBase64Url(buf.copyOf(read))
                        val offset = sent
                        if (!port.send("file.chunk") { put("transferId", tid); put("offset", offset); put("data", data) }) throw IllegalStateException("session_closed")
                        sent += read
                        progress(sent, TransferStatus.RUNNING)
                    }
                }
            }
            if (!result.isCompleted && !port.send("file.done") { put("transferId", tid) }) return TransferResult(false, "session_closed", null)

            val r = withTimeout(5 * 60_000) { result.await() }
            progress(if (r.ok) source.size else sent, if (r.ok) TransferStatus.DONE else TransferStatus.FAILED, r.reason, r.savedAs)
            return r
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) {
                port.send("file.cancel") { put("transferId", tid); put("reason", "user") }
                progress(0, TransferStatus.CANCELLED, "cancelled")
                throw e
            }
            progress(0, TransferStatus.FAILED, "timeout")
            return TransferResult(false, "timeout", null)
        } catch (e: java.util.concurrent.TimeoutException) {
            port.send("file.cancel") { put("transferId", tid); put("reason", "pc_stalled") }
            progress(0, TransferStatus.FAILED, "pc_stalled")
            return TransferResult(false, "pc_stalled", null)
        } catch (e: java.io.IOException) {
            port.send("file.cancel") { put("transferId", tid); put("reason", "read_failed") }
            progress(0, TransferStatus.FAILED, "read_failed")
            return TransferResult(false, "read_failed", null)
        } catch (e: IllegalStateException) {
            return TransferResult(false, e.message ?: "session_closed", null)
        } finally {
            subs.forEach { it.cancel() }
            activeId = null
        }
    }
}
