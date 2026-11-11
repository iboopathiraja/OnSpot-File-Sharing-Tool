package com.beyondzero.onspot.data.transfer

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.beyondzero.onspot.domain.model.TransferType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/**
 * The TCP side of OnSpot. Wire format (shared with the desktop app, do not change):
 * int32 type ordinal, then for FILE: writeUTF(name) + int64 size + raw bytes; for every other
 * type: writeUTF(text).
 */
class TransferManager(private val context: Context) {
    companion object {
        private const val TAG = "TransferManager"
        // Matches the desktop app: long enough for a slow Wi-Fi handshake, short enough to fail fast.
        private const val CONNECT_TIMEOUT_MS = 15000
        // A peer that stops sending mid-file for this long is treated as gone (matches desktop).
        private const val IDLE_TIMEOUT_MS = 30000
        // Keep this much space free after a file lands so a big transfer can't fill the phone.
        private const val FREE_SPACE_MARGIN = 256L * 1024 * 1024
        private const val PROGRESS_INTERVAL_MS = 150L
        private const val BUFFER = 64 * 1024

        private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        /** Strips anything that could escape the OnSpot folder or confuse the file system. */
        fun sanitizeFileName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            var name = ILLEGAL.replace(base, "_").trim().trim('.').trim()
            if (name.isEmpty()) name = "file"
            if (name.length > 180) {
                val ext = name.substringAfterLast('.', "").take(12)
                name = name.take(180 - ext.length - 1) + if (ext.isNotEmpty()) ".$ext" else ""
            }
            return name
        }
    }

    /** Callbacks from the receiving side. Called on background threads. */
    interface Listener {
        /** Return false to refuse a connection from [host] (e.g. not a Nearby device). */
        fun shouldAccept(host: String): Boolean
        fun onIncomingStarted(id: String, host: String, fileName: String, size: Long)
        fun onIncomingProgress(id: String, bytes: Long, size: Long)
        fun onIncomingCompleted(id: String, localUri: String?)
        fun onIncomingFailed(id: String, reason: String, cancelled: Boolean)
        fun onIncomingRejected(host: String, fileName: String, reason: String)
        fun onText(host: String, type: TransferType, text: String)
    }

    private var serverSocket: ServerSocket? = null
    var listeningPort: Int = -1
        private set

    @Volatile
    var isListening: Boolean = false
        private set

    private val seq = AtomicLong(0)
    private val incomingSockets = ConcurrentHashMap<String, Socket>()
    private val cancelledIncoming = ConcurrentHashMap.newKeySet<String>()
    private val outgoingSockets = ConcurrentHashMap<String, Socket>()

    /** Unblocks a send stuck in a socket write; the caller cancels the coroutine too. */
    fun cancelOutgoing(tag: String) {
        try { outgoingSockets[tag]?.close() } catch (_: Exception) {}
    }

    /** Safe to call more than once: a second call while already listening is a no-op. */
    fun startListening(listener: Listener) {
        if (isListening) return
        isListening = true
        // Bound on its own thread: socket work on the main thread trips StrictMode.
        Thread({
            val server = try { ServerSocket(0) } catch (e: Exception) {
                Log.e(TAG, "Could not open the receiving port", e)
                isListening = false
                return@Thread
            }
            serverSocket = server
            listeningPort = server.localPort
            Log.d(TAG, "Listening on port $listeningPort")
            try {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (e: IOException) { break }
                    Thread({ handleIncoming(socket, listener) }, "onspot-in").start()
                }
            } finally {
                if (serverSocket === server) {
                    isListening = false
                    listeningPort = -1
                }
            }
        }, "onspot-server").start()
    }

    /** Stops an incoming file. The partial file is deleted by the receiving thread. */
    fun cancelIncoming(id: String) {
        cancelledIncoming.add(id)
        try { incomingSockets[id]?.close() } catch (_: Exception) {}
    }

    private fun handleIncoming(socket: Socket, listener: Listener) {
        val host = socket.inetAddress?.hostAddress ?: "Unknown"
        try {
            socket.soTimeout = IDLE_TIMEOUT_MS
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER))
            val typeInt = input.readInt()
            val type = TransferType.entries.getOrNull(typeInt)
            if (type == null) {
                Log.w(TAG, "Unknown transfer type $typeInt from $host")
                return
            }
            if (type == TransferType.FILE) {
                receiveFile(socket, input, host, listener)
            } else {
                val text = input.readUTF()
                // Clipboard handshakes come from devices we have not "accepted" yet by design;
                // ordinary messages and clipboard content follow the accept policy.
                val gated = type == TransferType.TEXT || type == TransferType.CLIPBOARD
                if (gated && !listener.shouldAccept(host)) {
                    listener.onIncomingRejected(host, "Message", "Not a Nearby device")
                } else {
                    listener.onText(host, type, text)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Incoming connection from $host failed: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun receiveFile(socket: Socket, input: DataInputStream, host: String, listener: Listener) {
        val fileName = sanitizeFileName(input.readUTF())
        val fileSize = input.readLong()
        if (fileSize < 0) {
            listener.onIncomingRejected(host, fileName, "Invalid file size")
            return
        }
        if (!listener.shouldAccept(host)) {
            listener.onIncomingRejected(host, fileName, "Not a Nearby device")
            return
        }
        val free = freeSpace()
        if (free in 0 until fileSize + FREE_SPACE_MARGIN) {
            listener.onIncomingRejected(host, fileName, "Not enough storage space")
            return
        }

        val id = "in-${System.currentTimeMillis()}-${seq.incrementAndGet()}"
        incomingSockets[id] = socket
        val target = try { createTarget(fileName) } catch (e: Exception) {
            incomingSockets.remove(id)
            listener.onIncomingRejected(host, fileName, "Could not save the file")
            return
        }
        listener.onIncomingStarted(id, host, target.displayName, fileSize)
        var success = false
        try {
            BufferedOutputStream(target.stream, BUFFER).use { out ->
                val buffer = ByteArray(BUFFER)
                var total = 0L
                var lastTick = 0L
                while (total < fileSize) {
                    val want = minOf(buffer.size.toLong(), fileSize - total).toInt()
                    val n = input.read(buffer, 0, want)
                    if (n == -1) throw EOFException("Connection closed after $total of $fileSize bytes")
                    out.write(buffer, 0, n)
                    total += n
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= PROGRESS_INTERVAL_MS) {
                        lastTick = now
                        listener.onIncomingProgress(id, total, fileSize)
                    }
                }
                out.flush()
            }
            val uri = target.finish()
            success = true
            listener.onIncomingCompleted(id, uri)
        } catch (e: Exception) {
            val cancelled = cancelledIncoming.remove(id)
            val reason = when {
                cancelled -> "Cancelled"
                e is java.net.SocketTimeoutException -> "The other device stopped responding"
                e is EOFException -> "The other device stopped sending"
                else -> e.message ?: "Transfer failed"
            }
            listener.onIncomingFailed(id, reason, cancelled)
        } finally {
            incomingSockets.remove(id)
            if (!success) target.discard()
        }
    }

    /** Where an incoming file is written: a pending MediaStore row (Q+) or a plain file. */
    private inner class Target(
        val displayName: String,
        val stream: OutputStream,
        private val uri: Uri?,
        private val file: File?
    ) {
        fun finish(): String? {
            if (uri != null) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, values, null, null)
                return uri.toString()
            }
            if (file != null) return scanFile(file)
            return null
        }

        fun discard() {
            try { stream.close() } catch (_: Exception) {}
            try { if (uri != null) context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            try { file?.delete() } catch (_: Exception) {}
        }
    }

    private fun createTarget(fileName: String): Target {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OnSpot")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create the file")
            val stream = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                throw IOException("Could not open the file")
            }
            // MediaStore renames duplicates ("photo (1).jpg"); show what it actually picked.
            val actual = try {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            } catch (_: Exception) { null }
            return Target(actual ?: fileName, stream, uri, null)
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OnSpot")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create the OnSpot folder")
        val file = uniqueFile(dir, fileName)
        return Target(file.name, FileOutputStream(file), null, file)
    }

    /** Never overwrite an existing file: "name.ext" → "name (1).ext" → … */
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists() && f.createNewFile()) return f
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isNotEmpty() && it != name) ".$it" else "" }
        for (i in 1..9999) {
            f = File(dir, "$stem ($i)$ext")
            if (!f.exists() && f.createNewFile()) return f
        }
        throw IOException("Too many files named $name")
    }

    private fun scanFile(file: File): String? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, uri ->
            result.set(uri?.toString())
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return result.get()
    }

    private fun freeSpace(): Long = try {
        @Suppress("DEPRECATION")
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (_: Exception) { -1L }

    // ---- sending ---------------------------------------------------------------------------

    /**
     * Streams one file to a peer. Throws on any failure so the caller can mark it failed and
     * retry. Cancelling the calling coroutine closes the socket immediately.
     */
    suspend fun sendFile(uri: Uri, host: InetAddress, port: Int, tag: String, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val name = sanitizeFileName(getFileName(uri) ?: "file")
        var size = getFileSize(uri)
        var temp: File? = null
        if (size < 0) {
            // Size unknown (some providers don't report it): the protocol sends the length up
            // front, so copy to a temp file first rather than sending a wrong length.
            temp = File.createTempFile("send", ".tmp", context.cacheDir)
            openInput(uri).use { inp -> FileOutputStream(temp).use { inp.copyTo(it, BUFFER) } }
            size = temp.length()
        }
        val socket = Socket()
        outgoingSockets[tag] = socket
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = IDLE_TIMEOUT_MS
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER))
            out.writeInt(TransferType.FILE.ordinal)
            out.writeUTF(name)
            out.writeLong(size)
            val source: InputStream = if (temp != null) temp.inputStream() else openInput(uri)
            source.use { inp ->
                val buffer = ByteArray(BUFFER)
                var sent = 0L
                var lastTick = 0L
                while (sent < size) {
                    currentCoroutineContext().ensureActive()
                    val n = inp.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt())
                    if (n == -1) throw IOException("The file got shorter while sending")
                    out.write(buffer, 0, n)
                    sent += n
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= PROGRESS_INTERVAL_MS) {
                        lastTick = now
                        onProgress(sent, size)
                    }
                }
            }
            out.flush()
            socket.shutdownOutput()
            // Wait for the receiver to close its side so we know every byte landed.
            try { socket.getInputStream().read() } catch (_: Exception) {}
            onProgress(size, size)
        } finally {
            outgoingSockets.remove(tag)
            try { socket.close() } catch (_: Exception) {}
            temp?.delete()
        }
    }

    suspend fun sendText(text: String, host: InetAddress, port: Int, type: TransferType = TransferType.TEXT) = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = IDLE_TIMEOUT_MS
            val out = DataOutputStream(socket.getOutputStream())
            out.writeInt(type.ordinal)
            out.writeUTF(text)
            out.flush()
        }
    }

    private fun openInput(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the file to send")

    private fun getFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) {
                        val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) it.getString(i)?.let { n -> return n }
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    /** -1 when the provider can't tell. */
    private fun getFileSize(uri: Uri): Long {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst()) {
                        val i = it.getColumnIndex(OpenableColumns.SIZE)
                        if (i >= 0 && !it.isNull(i)) return it.getLong(i)
                    }
                }
            } catch (_: Exception) {}
        }
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0) return afd.length
            }
        } catch (_: Exception) {}
        return -1L
    }

    fun getFileMetadata(uri: Uri): Pair<String?, Long> = Pair(getFileName(uri), getFileSize(uri))

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        isListening = false
        listeningPort = -1
    }
}
