package gokorei.tanseki.composition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class IpcClientTest {
    @TempDir
    lateinit var tmp: Path

    /** How long the test peer keeps a connection open without answering it. */
    private val peerHold = 300.milliseconds

    @Test
    fun `reads a response delivered in fragments`() {
        val socket = tmp.resolve("fragmented.sock")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        Files.deleteIfExists(socket)
        server.bind(UnixDomainSocketAddress.of(socket))
        val executor = Executors.newSingleThreadExecutor()
        val serverResult =
            executor.submit<Pair<String, String>> {
                server.use { listener ->
                    listener.accept().use { client ->
                        val request = readFrame(client)
                        client.write(ByteBuffer.wrap("first-".toByteArray()))
                        Thread.sleep(25)
                        client.write(ByteBuffer.wrap("second\n".toByteArray()))
                        request to "first-second"
                    }
                }
            }
        try {
            assertEquals("first-second", IpcClient.request(socket, "request", timeout = 2.seconds))
            assertEquals("request", serverResult.get(2, TimeUnit.SECONDS).first)
        } finally {
            executor.shutdownNow()
            server.close()
            Files.deleteIfExists(socket)
        }
    }

    @Test
    fun `response wait is bounded by the timeout`() {
        val socket = tmp.resolve("timeout.sock")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        Files.deleteIfExists(socket)
        server.bind(UnixDomainSocketAddress.of(socket))
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val accepted =
            executor.submit<Unit> {
                server.use { listener ->
                    listener.accept().use { client ->
                        readFrame(client)
                        release.await(2, TimeUnit.SECONDS)
                    }
                }
            }
        try {
            assertThrows(SocketTimeoutException::class.java) {
                IpcClient.request(socket, "request", timeout = 50.milliseconds)
            }
        } finally {
            release.countDown()
            accepted.get(2, TimeUnit.SECONDS)
            executor.shutdownNow()
            server.close()
            Files.deleteIfExists(socket)
        }
    }

    @Test
    fun `response frames are bounded`() {
        val socket = tmp.resolve("bounded.sock")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        Files.deleteIfExists(socket)
        server.bind(UnixDomainSocketAddress.of(socket))
        val executor = Executors.newSingleThreadExecutor()
        val accepted =
            executor.submit<Unit> {
                server.use { listener ->
                    listener.accept().use { client ->
                        readFrame(client)
                        client.write(ByteBuffer.wrap("12345".toByteArray()))
                        Thread.sleep(500)
                    }
                }
            }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                IpcClient.request(socket, "request", timeout = 2.seconds, maxFrameBytes = 4)
            }
        } finally {
            accepted.get(2, TimeUnit.SECONDS)
            executor.shutdownNow()
            server.close()
            Files.deleteIfExists(socket)
        }
    }

    /**
     * A remaining budget shorter than the 1 ms granularity of `Selector.select`
     * must not reach it: `select(0)` blocks until the peer speaks, so a request
     * whose deadline is a fraction of a millisecond away would wait for the peer
     * instead of timing out.
     *
     * That window cannot be scheduled from outside the client, so the timeouts
     * are swept instead: each request is due a different distance after the
     * connect and write it has already spent, and one of them lands inside the
     * sub-millisecond remainder. The peer holds every connection open well past
     * the longest deadline, so a client that blocks fails on the elapsed time and
     * on the closed-connection error it gets instead of a timeout, never by
     * hanging.
     */
    @Test
    fun `a sub millisecond budget times out instead of blocking on select`() {
        val socket = tmp.resolve("submillis.sock")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        Files.deleteIfExists(socket)
        server.bind(UnixDomainSocketAddress.of(socket))
        val connections = Executors.newCachedThreadPool()
        val acceptor =
            Thread {
                while (!Thread.currentThread().isInterrupted) {
                    val client = runCatching { server.accept() }.getOrNull() ?: return@Thread
                    connections.submit {
                        runCatching { Thread.sleep(peerHold.inWholeMilliseconds) }
                        runCatching { client.close() }
                    }
                }
            }
        acceptor.isDaemon = true
        acceptor.start()
        try {
            (1..12).forEach { step ->
                val timeout = (step * 3).milliseconds
                val elapsed =
                    measureTime {
                        assertThrows(SocketTimeoutException::class.java) {
                            IpcClient.request(socket, "request", timeout = timeout)
                        }
                    }
                assertTrue(
                    elapsed < peerHold,
                    "a $timeout budget blocked for $elapsed instead of honouring its deadline"
                )
            }
        } finally {
            acceptor.interrupt()
            connections.shutdownNow()
            server.close()
            Files.deleteIfExists(socket)
        }
    }

    @Test
    fun `rejects unframed requests before connecting`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcClient.request(tmp.resolve("unused.sock"), "first\nsecond")
        }
    }

    private fun readFrame(client: SocketChannel): String {
        val response = StringBuilder()
        val buffer = ByteBuffer.allocate(256)
        while ('\n' !in response) {
            buffer.clear()
            val read = client.read(buffer)
            if (read < 0) break
            response.append(String(buffer.array(), 0, read))
        }
        return response.toString().removeSuffix("\n")
    }
}
