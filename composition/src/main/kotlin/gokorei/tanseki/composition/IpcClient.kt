package gokorei.tanseki.composition

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object IpcClient {
    private const val MAX_REQUEST_BYTES = 1_048_576

    fun request(
        socketPath: Path,
        request: String,
        timeout: Duration = 5.seconds,
        maxFrameBytes: Int = 65_536
    ): String {
        require(timeout.isPositive()) { "timeout must be positive" }
        require(maxFrameBytes > 0) { "maxFrameBytes must be > 0" }
        require(request.none { it == '\n' || it == '\r' || it.isISOControl() }) {
            "IPC request must be one control-free frame"
        }
        val requestBytes = request.toByteArray()
        require(requestBytes.size <= MAX_REQUEST_BYTES) { "IPC request exceeds $MAX_REQUEST_BYTES bytes" }

        val deadline = System.nanoTime() + timeout.inWholeNanoseconds.coerceAtLeast(1)
        return SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(false)
            Selector.open().use { selector ->
                val key = channel.register(selector, SelectionKey.OP_CONNECT)
                val address = UnixDomainSocketAddress.of(socketPath)
                connect(channel, key, selector, address, deadline)
                writeFrame(channel, key, selector, requestBytes, deadline)
                readFrame(channel, key, selector, maxFrameBytes, deadline)
            }
        }
    }

    private fun connect(
        channel: SocketChannel,
        key: SelectionKey,
        selector: Selector,
        address: UnixDomainSocketAddress,
        deadline: Long
    ) {
        channel.connect(address)
        while (!channel.finishConnect()) {
            if (!await(key, selector, SelectionKey.OP_CONNECT, deadline)) {
                throw SocketTimeoutException("IPC connect timed out")
            }
        }
    }

    private fun writeFrame(
        channel: SocketChannel,
        key: SelectionKey,
        selector: Selector,
        payload: ByteArray,
        deadline: Long
    ) {
        val frame = ByteBuffer.allocate(payload.size + 1)
        frame.put(payload).put('\n'.code.toByte()).flip()
        while (frame.hasRemaining()) {
            if (!await(key, selector, SelectionKey.OP_WRITE, deadline)) {
                throw SocketTimeoutException("IPC write timed out")
            }
            channel.write(frame)
        }
    }

    private fun readFrame(
        channel: SocketChannel,
        key: SelectionKey,
        selector: Selector,
        maxFrameBytes: Int,
        deadline: Long
    ): String {
        val frame = ByteArrayOutputStream(minOf(maxFrameBytes, 8_192))
        val buffer = ByteBuffer.allocate(8_192)
        while (true) {
            if (!await(key, selector, SelectionKey.OP_READ, deadline)) {
                throw SocketTimeoutException("IPC response timed out")
            }
            buffer.clear()
            val read = channel.read(buffer)
            if (read < 0) incompleteResponse(frame.size() == 0)
            val delimiter = buffer.array().take(read).indexOf('\n'.code.toByte())
            val payloadLength = if (delimiter >= 0) delimiter else read
            if (frame.size() + payloadLength > maxFrameBytes) responseTooLarge(maxFrameBytes)
            frame.write(buffer.array(), 0, payloadLength)
            if (delimiter >= 0) {
                if (delimiter > 0 && buffer.array()[delimiter - 1] == '\r'.code.toByte()) {
                    invalidResponseDelimiter()
                }
                return decodeFrame(frame.toByteArray())
            }
        }
    }

    private fun incompleteResponse(empty: Boolean): Nothing =
        throw EOFException(
            if (empty) "IPC peer closed without a response frame" else "IPC peer closed before the response delimiter"
        )

    private fun responseTooLarge(maxFrameBytes: Int): Nothing =
        throw IllegalArgumentException("IPC response exceeds $maxFrameBytes bytes")

    private fun invalidResponseDelimiter(): Nothing =
        throw IllegalArgumentException("IPC response has an invalid frame delimiter")

    /**
     * Waits for [readyOperation] without ever outlasting the caller's [deadline].
     *
     * A remaining budget shorter than the 1 ms granularity of [Selector.select]
     * must never be passed to it: `select(0)` blocks *indefinitely*, so a
     * sub-millisecond remainder would turn an expiring request into a request
     * that hangs until the peer happens to write. Such a remainder polls once
     * and reports "not ready", which the callers turn into a timeout.
     */
    private fun await(
        key: SelectionKey,
        selector: Selector,
        readyOperation: Int,
        deadline: Long
    ): Boolean {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0 || !key.isValid) return false
        key.interestOps(readyOperation)
        val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
        val selected = if (millis > 0) selector.select(millis) else selector.selectNow()
        if (selected == 0) return false
        val ready = key.readyOps() and readyOperation != 0
        selector.selectedKeys().clear()
        return key.isValid && ready
    }

    private fun decodeFrame(bytes: ByteArray): String =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
}
