package dev.connectquick.controller

import dev.connectquick.controller.crypto.CryptoUtil
import dev.connectquick.controller.files.FileDownloader
import dev.connectquick.controller.files.FileSink
import dev.connectquick.controller.files.FileSource
import dev.connectquick.controller.files.FileUploader
import dev.connectquick.controller.files.TransferProgress
import dev.connectquick.controller.files.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList

class FilesTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun rnd(n: Int) = ByteArray(n).also { Random(n.toLong()).nextBytes(it) }

    private class MemSource(override val name: String, val data: ByteArray, val claimedSha: String? = null) : FileSource {
        override val size = data.size.toLong()
        override fun sha256Hex() = claimedSha ?: MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        override fun open(offset: Long): InputStream = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
    }

    private class MemSink(val existing: ByteArray = ByteArray(0)) : FileSink {
        val buffer = ByteArrayOutputStream().also { it.write(existing) }
        @Volatile var committedAs: String? = null
        @Volatile var discarded = false
        override fun existingLength() = existing.size.toLong()
        override fun open(append: Boolean): OutputStream = buffer
        override fun sha256HexOfStored() = MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()).joinToString("") { "%02x".format(it) }
        override fun commit(): String { committedAs = "saved.bin"; return "saved.bin" }
        override fun discard() { discarded = true }
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun roundTrip(data: ByteArray, sink: MemSink = MemSink(), max: Long = 0, claimedSha: String? = null) = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        val downloader = FileDownloader(pc, { max }, scope())
        downloader.onOffer = { sink }
        val result = FileUploader(phone).upload(MemSource("f.bin", data, claimedSha))
        result to sink
    }

    @Test
    fun files_of_all_sizes_arrive_intact_over_the_real_protocol() {
        for (size in listOf(0, 1, 16383, 16384, 16385, 100_000, 400_000)) {
            val data = rnd(size)
            val (result, sink) = roundTrip(data)
            assertTrue("size $size: ${result.reason}", result.ok)
            assertEquals("saved.bin", result.savedAs)
            assertArrayEquals("size $size", data, sink.buffer.toByteArray())
        }
    }

    @Test
    fun an_interrupted_download_resumes_from_what_is_already_stored() {
        val data = rnd(80_000)
        val sink = MemSink(existing = data.copyOf(32_768))
        val (result, finished) = roundTrip(data, sink)
        assertTrue(result.reason, result.ok)
        assertArrayEquals(data, finished.buffer.toByteArray())
    }

    @Test
    fun a_file_whose_hash_does_not_match_is_discarded_and_reported() {
        val data = rnd(20_000)
        val (result, sink) = roundTrip(data, claimedSha = sha(rnd(20_001)) /* different bytes => different hash (rnd seeds by size) */)
        assertFalse(result.ok)
        assertEquals("hash_mismatch", result.reason)
        assertTrue(sink.discarded)
        assertEquals(null, sink.committedAs)
    }

    @Test
    fun the_receiver_can_decline_and_the_sender_learns_why() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        FileDownloader(pc, { 0 }, scope()).onOffer = { null }
        val result = FileUploader(phone).upload(MemSource("f.bin", rnd(100)))
        assertFalse(result.ok)
        assertEquals("declined", result.reason)
    }

    @Test
    fun offers_over_the_pcs_advertised_limit_are_refused_before_any_data() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        var asked = false
        FileDownloader(pc, { 1000 }, scope()).onOffer = { asked = true; MemSink() }
        val result = FileUploader(phone).upload(MemSource("big.bin", rnd(5000)))
        assertEquals("too_large", result.reason)
        assertFalse("the sink must not even be asked", asked)
        assertTrue(phone.sentOfType("file.chunk").isEmpty())
    }

    @Test
    fun a_misbehaving_sender_cannot_make_the_receiver_store_more_than_announced() = runBlocking {
        val (hostile, pc) = LoopbackPort.pair()
        val sink = MemSink()
        val progress = CopyOnWriteArrayList<TransferProgress>()
        val dl = FileDownloader(pc, { 0 }, scope())
        dl.onOffer = { sink }
        dl.onProgress = { progress += it }
        val tid = java.util.UUID.randomUUID().toString()
        hostile.send("file.offer") { put("transferId", tid); put("name", "x"); put("size", 10); put("sha256", "a".repeat(64)); put("chunkSize", 16384) }
        delay(200)
        hostile.send("file.chunk") { put("transferId", tid); put("offset", 0); put("data", CryptoUtil.toBase64Url(ByteArray(20))) }
        delay(300)
        assertTrue(sink.buffer.size() <= 0)
        val result = pc.sentOfType("file.result").single()
        assertEquals("overflow", result.getValue("reason").jsonPrimitive.content)
        assertTrue(sink.discarded)
    }

    @Test
    fun out_of_order_chunks_abort_the_transfer() = runBlocking {
        val (hostile, pc) = LoopbackPort.pair()
        val dl = FileDownloader(pc, { 0 }, scope())
        dl.onOffer = { MemSink() }
        val tid = java.util.UUID.randomUUID().toString()
        hostile.send("file.offer") { put("transferId", tid); put("name", "x"); put("size", 40000); put("sha256", "a".repeat(64)); put("chunkSize", 16384) }
        delay(150)
        hostile.send("file.chunk") { put("transferId", tid); put("offset", 16384); put("data", CryptoUtil.toBase64Url(ByteArray(100))) }
        delay(200)
        assertEquals("bad_offset", pc.sentOfType("file.result").single().getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun a_sender_that_goes_silent_is_timed_out() = runBlocking {
        val (hostile, pc) = LoopbackPort.pair()
        val sink = MemSink()
        val dl = FileDownloader(pc, { 0 }, scope(), idleTimeoutMs = 300)
        dl.onOffer = { sink }
        val tid = java.util.UUID.randomUUID().toString()
        hostile.send("file.offer") { put("transferId", tid); put("name", "x"); put("size", 40000); put("sha256", "a".repeat(64)); put("chunkSize", 16384) }
        delay(1000)
        assertEquals("idle_timeout", pc.sentOfType("file.result").single().getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun chunks_for_an_unknown_transfer_are_cancelled() = runBlocking {
        val (hostile, pc) = LoopbackPort.pair()
        FileDownloader(pc, { 0 }, scope())
        hostile.send("file.chunk") { put("transferId", java.util.UUID.randomUUID().toString()); put("offset", 0); put("data", CryptoUtil.toBase64Url(ByteArray(10))) }
        delay(200)
        assertEquals(1, pc.sentOfType("file.cancel").size)
    }

    @Test
    fun cancelling_an_upload_tells_the_receiver_to_discard_the_partial_file() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        val sink = MemSink()
        val dl = FileDownloader(pc, { 0 }, scope())
        dl.onOffer = { sink }
        // Stall the receiver so the upload is still running when we cancel it.
        pc.on("file.chunk") { Thread.sleep(50) }
        val job = scope().async { FileUploader(phone).upload(MemSource("big.bin", rnd(2_000_000))) }
        delay(400)
        job.cancelAndJoin()
        delay(300)
        assertTrue(phone.sentOfType("file.cancel").isNotEmpty())
        assertTrue(sink.discarded)
    }

    @Test
    fun a_pc_that_refuses_the_offer_with_a_ctl_error_fails_the_upload_immediately() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        pc.on("file.offer") { m -> pc.send("ctl.error") { put("id", m.getValue("transferId").jsonPrimitive.content); put("code", "forbidden"); put("message", "no") } }
        val started = System.nanoTime()
        val result = FileUploader(phone).upload(MemSource("f.bin", rnd(100)))
        assertEquals("forbidden", result.reason)
        assertTrue((System.nanoTime() - started) < 3_000_000_000L)
    }

    @Test
    fun the_pc_listing_and_outbox_request_flow_works() = runBlocking {
        val (phone, pc) = LoopbackPort.pair()
        val dl = FileDownloader(phone, { 0 }, scope())
        pc.on("file.list") { m ->
            pc.send("file.listing") {
                put("id", m.getValue("id").jsonPrimitive.content)
                put("files", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject { put("name", "a.txt"); put("size", 5); put("modifiedMs", 1234) })
                })
            }
        }
        val files = dl.list()
        assertNotNull(files)
        assertEquals("a.txt", files!!.single().name)
        assertTrue(dl.request("a.txt"))
        val req = phone.sentOfType("file.request").single()
        assertEquals("a.txt", req.getValue("name").jsonPrimitive.content)
        assertFalse("a request carries a bare name, never a path", req.toString().contains("path"))
    }
}
