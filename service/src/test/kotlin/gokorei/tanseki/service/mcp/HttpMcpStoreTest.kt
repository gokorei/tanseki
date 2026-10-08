package gokorei.tanseki.service.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * The daemon-proxy MCP store: the revision guard has to survive the HTTP hop,
 * the response has to stay bounded, and a daemon error body must not be
 * relayed to the model.
 */
class HttpMcpStoreTest {
    private lateinit var server: HttpServer
    private var baseUrl = ""
    private val requests = mutableListOf<Recorded>()
    private var responder: (HttpExchange) -> Unit = { exchange ->
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { it.write("{}".toByteArray()) }
    }

    private data class Recorded(
        val method: String,
        val path: String,
        val body: String
    )

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            requests +=
                Recorded(
                    exchange.requestMethod,
                    exchange.requestURI.toString(),
                    exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                )
            exchange.use {
                responder(exchange)
            }
        }
        server.executor = null
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun store(maxResponseBytes: Int = 1_048_576) = HttpMcpStore(baseUrl, maxResponseBytes = maxResponseBytes)

    @Test
    fun `delete carries ifRevision across the seam`() {
        responder = { exchange ->
            val body = """{"id":"a/b","collection":"vault","revision":"r2"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val result = store().delete("a/b", "dropped", "mcp", "r1")

        assertEquals("a/b", result["id"]!!.jsonPrimitiveContent())
        assertEquals("r2", result["revision"]!!.jsonPrimitiveContent())
        val recorded = requests.single()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/documents:delete", recorded.path)
        assertTrue(recorded.body.contains("\"ifRevision\":\"r1\""), recorded.body)
        assertFalse(recorded.body.contains("null"), recorded.body)
    }

    @Test
    fun `upsert carries ifRevision across the seam`() {
        responder = { exchange ->
            val body = """{"id":"a/b","revision":"r1","contentHash":"h","created":true}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        store().upsert("a/b", "body", "vault", "a/b.md", null, "msg", "mcp", "r0")

        val recorded = requests.single()
        assertEquals("/v1/documents:upsert", recorded.path)
        assertTrue(recorded.body.contains("\"ifRevision\":\"r0\""), recorded.body)
    }

    @Test
    fun `a stale revision conflict is mapped without relaying the daemon body`() {
        responder = { exchange ->
            val body =
                """
                {"error":{"code":"failed_precondition","message":"revision mismatch at
                /Users/someone/vault/.tanseki/store.jsonl","detail":"pijul exited 128"}}

                """.trimIndent()
                    .replace("\n", " ")
                    .toByteArray()
            exchange.sendResponseHeaders(412, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val error =
            assertThrows(McpToolError::class.java) {
                store().delete("a/b", null, "mcp", "stale")
            }

        assertEquals("failed_precondition", error.code)
        assertFalse("/Users/someone" in error.message.orEmpty(), error.message.orEmpty())
        assertFalse("pijul" in error.message.orEmpty(), error.message.orEmpty())
        assertFalse("pijul" in error.hint.orEmpty(), error.hint.orEmpty())
        assertTrue(error.hint.orEmpty().isNotEmpty())
    }

    @Test
    fun `a missing document is a not_found value without the daemon detail`() {
        responder = { exchange ->
            val body = """{"error":{"code":"not_found","message":"no such id at /Users/someone/vault"}}""".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        // A read maps 404 onto the nullable lookup result...
        assertEquals(null, store().get("a/b"))
        // ...while every other operation turns it into a not_found tool error.
        val error = assertThrows(McpToolError::class.java) { store().history("a/b", 10, 0) }

        assertEquals("not_found", error.code)
        assertFalse("/Users/someone" in error.message.orEmpty(), error.message.orEmpty())
        assertFalse("a/b" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a declared oversized response is refused before it is buffered`() {
        responder = { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 4_096)
            exchange.responseBody.use { it.write(ByteArray(4_096)) }
        }

        val error = assertThrows(McpToolError::class.java) { store(maxResponseBytes = 1_024).get("a/b") }

        assertEquals("response_too_large", error.code)
    }

    @Test
    fun `an undeclared oversized response is refused while streaming`() {
        responder = { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                val chunk = ByteArray(512) { 'x'.code.toByte() }
                repeat(8) { body.write(chunk) }
            }
        }

        val error = assertThrows(McpToolError::class.java) { store(maxResponseBytes = 1_024).get("a/b") }

        assertEquals("response_too_large", error.code)
    }

    @Test
    fun `an oversized request is refused before it leaves the process`() {
        val error =
            assertThrows(McpToolError::class.java) {
                store(maxResponseBytes = 1_024).upsert("a/b", "y".repeat(4_096), null, null, null, null, null, null)
            }

        assertEquals("request_too_large", error.code)
        assertTrue(requests.isEmpty(), "an oversized request must not be sent: $requests")
    }

    private fun JsonElement.jsonPrimitiveContent(): String = (this as JsonPrimitive).content
}
