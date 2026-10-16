package dev.connectquick.controller.files

import java.io.InputStream
import java.io.OutputStream

/** A local file the user chose to send. The UI never builds paths for the PC: only name, size and bytes cross the wire. */
interface FileSource {
    val name: String
    val size: Long
    fun sha256Hex(): String
    /** Opens the content positioned at [offset] (used to resume). */
    fun open(offset: Long): InputStream
}

/** Where a file coming from the PC is stored until it is verified. */
interface FileSink {
    /** Bytes already stored from an earlier, interrupted attempt of the same file (0 if none). */
    fun existingLength(): Long
    /** Opens for writing at the end of what is already stored (or from scratch when [append] is false). */
    fun open(append: Boolean): OutputStream
    fun sha256HexOfStored(): String
    /** Makes the verified content visible under its final name; returns that name. */
    fun commit(): String
    /** Deletes everything stored for this transfer. */
    fun discard()
}

enum class TransferDirection { UPLOAD, DOWNLOAD }
enum class TransferStatus { RUNNING, DONE, FAILED, CANCELLED }

data class TransferProgress(
    val transferId: String,
    val direction: TransferDirection,
    val name: String,
    val bytes: Long,
    val total: Long,
    val status: TransferStatus,
    val reason: String? = null,
    val savedAs: String? = null,
)

data class TransferResult(val ok: Boolean, val reason: String?, val savedAs: String?)

data class RemoteFile(val name: String, val size: Long, val modifiedMs: Long)

data class FileOffer(val transferId: String, val name: String, val size: Long, val sha256: String, val chunkSize: Int)

const val CHUNK_SIZE = 16 * 1024
const val WINDOW_CHUNKS = 8
const val ACK_EVERY_BYTES = 128 * 1024
