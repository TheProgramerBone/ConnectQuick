package dev.connectquick.controller.files

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A file the person picked with the system file picker. Only its name, size and bytes ever leave the phone. */
class AndroidFileSource private constructor(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val name: String,
    override val size: Long,
) : FileSource {
    override fun sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun open(offset: Long): InputStream {
        val input = resolver.openInputStream(uri) ?: throw java.io.IOException("cannot open")
        var left = offset
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                if (input.read() < 0) break
                left--
            } else left -= skipped
        }
        return input
    }

    companion object {
        /** Null when the provider does not report a usable name/size and the content cannot be measured. */
        fun from(resolver: ContentResolver, uri: Uri): AndroidFileSource? {
            var name: String? = null
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
            if (size < 0) {
                // Some providers hide the size: measure by reading once rather than guessing.
                size = resolver.openInputStream(uri)?.use { s ->
                    var total = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = s.read(buf); if (n < 0) break; total += n }
                    total
                } ?: return null
            }
            return AndroidFileSource(resolver, uri, FileNames.safe(name ?: uri.lastPathSegment ?: "archivo"), size)
        }
    }
}

/**
 * Where files from the PC land: the public Downloads/ConnectQuick folder on Android 10+ (MediaStore,
 * no storage permission needed) or the app's own downloads folder on older versions. The name the PC
 * proposes is never used as a path ([FileNames.safe]); a half-received file is invisible until its
 * SHA-256 matched, and a declined or failed transfer leaves nothing behind.
 */
class DownloadsSink(private val context: Context, offerName: String, offerId: String) : FileSink {
    private val name = FileNames.safe(offerName)
    private val resolver = context.contentResolver
    private var pendingUri: Uri? = null
    private val legacyPart: File? = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "ConnectQuick").apply { mkdirs() }
        File(dir, "${offerId.filter { it.isLetterOrDigit() }.take(12)}-$name.part")
    } else null

    override fun existingLength(): Long = legacyPart?.takeIf { it.exists() }?.length() ?: 0L

    override fun open(append: Boolean): OutputStream {
        legacyPart?.let { return FileOutputStream(it, append) }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ConnectQuick")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("cannot create download")
        pendingUri = uri
        return resolver.openOutputStream(uri, "w") ?: throw java.io.IOException("cannot open download")
    }

    override fun sha256HexOfStored(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input: InputStream = legacyPart?.let { java.io.FileInputStream(it) } ?: resolver.openInputStream(pendingUri!!)!!
        input.use {
            val buf = ByteArray(64 * 1024)
            while (true) { val n = it.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun commit(): String {
        legacyPart?.let { part ->
            val dir = part.parentFile!!
            var target = File(dir, name)
            var i = 1
            while (target.exists()) {
                target = File(dir, name.substringBeforeLast('.', name) + " ($i)" + (if (name.contains('.')) "." + name.substringAfterLast('.') else ""))
                i++
            }
            if (!part.renameTo(target)) throw java.io.IOException("cannot finalise download")
            return target.name
        }
        val uri = pendingUri ?: throw java.io.IOException("nothing to commit")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
    }

    override fun discard() {
        legacyPart?.delete()
        pendingUri?.let { runCatching { resolver.delete(it, null, null) } }
        pendingUri = null
    }

    private fun mimeOf(fileName: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
}
