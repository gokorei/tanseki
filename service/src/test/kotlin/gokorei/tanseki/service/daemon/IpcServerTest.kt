package gokorei.tanseki.service.daemon

import gokorei.tanseki.composition.IpcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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

class IpcServerTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `handles concurrent clients`() {
        val socket = tmp.resolve("concurrent.sock")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val server =
            IpcServer(socket, handler = { request ->
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                request
            })
        val executor = Executors.newFixedThreadPool(2)
        server.start(scope)
        try {
            val requests =
                listOf("first", "second").map { request ->
                    executor.submit<String> { IpcClient.request(socket, request, timeout = 3.seconds) }
                }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
            } finally {
                release.countDown()
            }

            assertEquals(setOf("first", "second"), requests.map { it.get(3, TimeUnit.SECONDS) }.toSet())
        } finally {
            release.countDown()
            executor.shutdownNow()
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun `round-trips large frames across partial socket operations`() {
        val socket = tmp.resolve("large.sock")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val response = "response:" + "x".repeat(100_000)
        val server = IpcServer(socket, { response }, maxFrameBytes = 200_000)
        server.start(scope)
        try {
            val request = "request:" + "y".repeat(100_000)
            assertEquals(response, IpcClient.request(socket, request, timeout = 3.seconds, maxFrameBytes = 200_000))
        } finally {
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun `unfinished request times out and close removes the socket`() {
        val socket = tmp.resolve("timeout.sock")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = IpcServer(socket, { it }, ioTimeout = 50.milliseconds)
        server.start(scope)
        val client = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            client.connect(UnixDomainSocketAddress.of(socket))
            client.write(ByteBuffer.wrap("partial".toByteArray()))
            val response = readResponse(client)

            assertEquals("ERROR: invalid_frame", response)
            server.close()
            assertFalse(Files.exists(socket))
        } finally {
            client.close()
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun `a live socket is reported as a collision and never removed`() {
        val socket = tmp.resolve("owned.sock")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = IpcServer(socket, { it })
        owner.start(scope)
        val contender = IpcServer(socket, handler = { "hijacked" })
        try {
            val error = assertThrows(IllegalStateException::class.java) { contender.start(scope) }
            assertTrue("already served" in error.message.orEmpty(), error.message.orEmpty())
            assertTrue(Files.exists(socket), "the collision must not unlink the live socket")
            assertEquals("ping", IpcClient.request(socket, "ping", timeout = 3.seconds))

            contender.close()
            assertTrue(Files.exists(socket), "a refused server must not unlink the owner's socket")
            assertEquals("ping", IpcClient.request(socket, "ping", timeout = 3.seconds))

            owner.close()
            assertFalse(Files.exists(socket))
        } finally {
            contender.close()
            owner.close()
            scope.cancel()
        }
    }

    @Test
    fun `an abandoned socket file is reclaimed`() {
        val socket = tmp.resolve("stale.sock")
        val abandoned = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        abandoned.bind(UnixDomainSocketAddress.of(socket))
        abandoned.close()
        assertTrue(Files.exists(socket), "a closed listener leaves its socket file behind")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = IpcServer(socket, handler = { it })
        try {
            server.start(scope)
            assertEquals("ping", IpcClient.request(socket, "ping", timeout = 3.seconds))
        } finally {
            server.close()
            scope.cancel()
        }
        assertFalse(Files.exists(socket))
    }

    @Test
    fun `a non socket path is refused and left untouched`() {
        val socket = tmp.resolve("regular.file")
        Files.writeString(socket, "not a socket")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = IpcServer(socket, handler = { it })
        try {
            val error = assertThrows(IllegalStateException::class.java) { server.start(scope) }
            assertTrue("non-socket" in error.message.orEmpty(), error.message.orEmpty())
            assertEquals("not a socket", Files.readString(socket))
        } finally {
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun `a symlinked socket path is refused and the target survives`() {
        val target = tmp.resolve("target.json")
        Files.writeString(target, "intact")
        val socket = tmp.resolve("link.sock")
        Files.createSymbolicLink(socket, target)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = IpcServer(socket, handler = { it })
        try {
            assertThrows(IllegalStateException::class.java) { server.start(scope) }
            assertEquals("intact", Files.readString(target))
        } finally {
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun `close never unlinks a socket this instance no longer owns`() {
        val socket = tmp.resolve("takeover.sock")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = IpcServer(socket, handler = { it })
        server.start(scope)
        // A crash-recovery takeover: the path is replaced under the running server.
        Files.delete(socket)
        val replacement = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        replacement.bind(UnixDomainSocketAddress.of(socket))
        try {
            server.close()
            assertTrue(Files.exists(socket), "close unlinked a socket it no longer owned")
        } finally {
            replacement.close()
            Files.deleteIfExists(socket)
            scope.cancel()
        }
    }

    private fun readResponse(client: SocketChannel): String {
        val response = StringBuilder()
        val buffer = ByteBuffer.allocate(256)
        while ('\n' !in response) {
            buffer.clear()
            val read = client.read(buffer)
            if (read < 0) break
            response.append(String(buffer.array(), 0, read))
        }
        return response.toString().trim()
    }
}
