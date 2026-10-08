package gokorei.tanseki.service.daemon

import gokorei.tanseki.service.logging.Redaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class IpcServer(
    private val socketPath: Path,
    private val handler: (String) -> String,
    private val maxFrameBytes: Int = 65_536,
    private val maxConcurrentClients: Int = 64,
    private val ioTimeout: Duration = 5.seconds
) : AutoCloseable {
    private val channel: ServerSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    private val clients = ConcurrentHashMap.newKeySet<SocketChannel>()
    private val clientJobs = ConcurrentHashMap.newKeySet<Job>()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var acceptJob: Job? = null

    /** Identity of the socket this instance bound; guards the unlink on close. */
    @Volatile
    private var ownedSocketKey: Any? = null

    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be > 0" }
        require(maxConcurrentClients > 0) { "maxConcurrentClients must be > 0" }
        require(ioTimeout.isPositive()) { "ioTimeout must be positive" }
    }

    fun start(scope: CoroutineScope) {
        check(!closed.get()) { "IPC server is closed" }
        check(started.compareAndSet(false, true)) { "IPC server already started" }
        try {
            socketPath.parent?.let { Files.createDirectories(it) }
            clearStaleSocket()
            channel.bind(UnixDomainSocketAddress.of(socketPath))
            ownedSocketKey = socketIdentity(socketPath)
            restrictPermissions()
            val job = scope.launch(Dispatchers.IO) { acceptLoop(scope) }
            acceptJob = job
            job.invokeOnCompletion {
                if (!closed.get()) close()
            }
        } catch (error: Exception) {
            // bind() never succeeded, so this instance owns nothing: never unlink.
            runCatching { channel.close() }
            ownedSocketKey = null
            started.set(false)
            throw error
        }
    }

    private fun acceptLoop(scope: CoroutineScope) {
        while (channel.isOpen && !closed.get()) {
            val client = acceptClient() ?: return
            launchClient(scope, client)
        }
    }

    /**
     * Accepts the next client, retrying a transient accept failure. Returns null
     * once the server is closing or the thread interrupted, which ends the loop.
     */
    private fun acceptClient(): SocketChannel? {
        while (channel.isOpen && !closed.get()) {
            val client =
                try {
                    channel.accept()
                } catch (_: Exception) {
                    if (Thread.currentThread().isInterrupted || closed.get()) return null
                    continue
                }
            return client
        }
        return null
    }

    private fun launchClient(scope: CoroutineScope, client: SocketChannel) {
        if (clients.size >= maxConcurrentClients) {
            client.close()
            return
        }
        clients += client
        val job =
            scope.launch(Dispatchers.IO) {
                try {
                    client.configureBlocking(false)
                    val request =
                        try {
                            readFrame(client)
                        } catch (_: Exception) {
                            runCatching { writeFrame(client, "ERROR: invalid_frame") }
                            null
                        } ?: return@launch
                    val response = runCatching { handler(request) }.getOrElse { "ERROR: request_failed" }
                    if (response.toByteArray().size > maxFrameBytes) {
                        writeFrame(client, "ERROR: response_too_large")
                    } else {
                        writeFrame(client, response)
                    }
                } catch (_: Exception) {
                    client.close()
                } finally {
                    clients -= client
                    clientJobs -= coroutineContext[Job]
                    client.close()
                }
            }
        clientJobs += job
        job.invokeOnCompletion { clientJobs -= job }
    }

    private suspend fun readFrame(client: SocketChannel): String {
        val frame = ByteArrayOutputStream(minOf(maxFrameBytes, 8_192))
        val buffer = ByteBuffer.allocate(8_192)
        val deadline = System.nanoTime() + ioTimeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            buffer.clear()
            val read = client.read(buffer)
            if (read < 0) incompleteRequest(frame.size() == 0)
            if (read == 0) {
                delay(1.milliseconds)
                continue
            }
            val delimiter = (0 until read).firstOrNull { buffer.array()[it] == '\n'.code.toByte() } ?: -1
            val payloadLength = if (delimiter >= 0) delimiter else read
            if (frame.size() + payloadLength > maxFrameBytes) requestTooLarge(maxFrameBytes)
            frame.write(buffer.array(), 0, payloadLength)
            if (delimiter >= 0) {
                if (delimiter > 0 && buffer.array()[delimiter - 1] == '\r'.code.toByte()) {
                    invalidRequestDelimiter()
                }
                return decodeFrame(frame.toByteArray())
            }
        }
        throw IOException("IPC request timed out")
    }

    private fun incompleteRequest(empty: Boolean): Nothing =
        throw EOFException(
            if (empty) "IPC peer closed without a frame" else "IPC peer closed before the frame delimiter"
        )

    private fun requestTooLarge(maxFrameBytes: Int): Nothing =
        throw IOException("IPC request exceeds $maxFrameBytes bytes")

    private fun invalidRequestDelimiter(): Nothing =
        throw IOException("IPC request has an invalid frame delimiter")

    private suspend fun writeFrame(client: SocketChannel, payload: String) {
        require(payload.none { it == '\n' || it == '\r' }) { "IPC response must be one frame" }
        val bytes = payload.toByteArray()
        require(bytes.size <= maxFrameBytes) { "IPC response exceeds $maxFrameBytes bytes" }
        val frame = ByteBuffer.allocate(bytes.size + 1)
        frame.put(bytes).put('\n'.code.toByte()).flip()
        val deadline = System.nanoTime() + ioTimeout.inWholeNanoseconds
        while (frame.hasRemaining()) {
            if (System.nanoTime() >= deadline) throw IOException("IPC response timed out")
            if (client.write(frame) == 0) delay(1.milliseconds)
        }
    }

    private fun decodeFrame(bytes: ByteArray): String =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    /**
     * Frees the socket path only when nothing is listening on it. An existing
     * socket with a live listener means another daemon owns the vault, so the
     * collision is reported instead of silently stealing the path.
     *
     * The probe and the unlink cannot be one atomic step on a POSIX path, so the
     * window is closed as far as the filesystem allows: the path's identity is
     * read before the probe and again after it, and a daemon that bound the path
     * in between (which necessarily produces a *different* socket inode) makes
     * the unlink refuse rather than delete a live listener's socket. A path that
     * reports no identity at all is unlinked after the probe, as before.
     */
    private fun clearStaleSocket() {
        if (!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) return
        check(!Files.isSymbolicLink(socketPath) && isUnixSocket(socketPath)) {
            "refusing to replace non-socket path: ${Redaction.fileName(socketPath.toString())}"
        }
        val probed = socketIdentity(socketPath)
        check(!isLiveSocket()) {
            "IPC socket is already served by another daemon: ${Redaction.fileName(socketPath.toString())}"
        }
        val afterProbe = socketIdentity(socketPath)
        if (probed != null && probed != afterProbe) {
            error(
                "IPC socket changed while probing it: ${Redaction.fileName(socketPath.toString())}"
            )
        }
        Files.deleteIfExists(socketPath)
    }

    /** A successful connect proves a live listener owns the path. */
    private fun isLiveSocket(): Boolean =
        runCatching {
            SocketChannel
                .open(StandardProtocolFamily.UNIX)
                .use { probe ->
                    probe.connect(UnixDomainSocketAddress.of(socketPath))
                    true
                }
        }.getOrDefault(false)

    /**
     * Identity of the socket file itself. A filesystem that reports no inode
     * falls back to the timestamps a fresh bind always changes, so a takeover
     * still differs from the socket that was probed.
     */
    private fun socketIdentity(path: Path): Any? =
        runCatching {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            attributes.fileKey()
                ?: "mtime=${attributes.lastModifiedTime().toMillis()},ctime=${attributes.creationTime().toMillis()}"
        }.getOrNull()

    private fun isUnixSocket(path: Path): Boolean =
        runCatching {
            val mode = Files.getAttribute(path, "unix:mode") as? Number
            mode != null && (mode.toLong() and 0xF000L) == 0xC000L
        }.getOrDefault(false)

    private fun restrictPermissions() {
        runCatching {
            Files.setPosixFilePermissions(
                socketPath,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { channel.close() }
        acceptJob?.cancel()
        clients.forEach { runCatching { it.close() } }
        clientJobs.toList().forEach { it.cancel() }
        runBlocking {
            withTimeoutOrNull(5.seconds) {
                acceptJob?.join()
                clientJobs.toList().forEach { it.join() }
            }
        }
        clients.clear()
        clientJobs.clear()
        unlinkOwnedSocket()
    }

    /**
     * Unlinks the socket only while it is still the one this instance bound.
     * After a crash-recovery takeover the path belongs to another daemon, and
     * deleting it would break live clients.
     */
    private fun unlinkOwnedSocket() {
        val owned = ownedSocketKey ?: return
        ownedSocketKey = null
        val current = socketIdentity(socketPath) ?: return
        if (current != owned) return
        if (!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS) || !isUnixSocket(socketPath)) return
        runCatching { Files.deleteIfExists(socketPath) }
    }
}
