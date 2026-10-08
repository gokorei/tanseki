package gokorei.tanseki.service.mcp

import dev.toonformat.jtoon.JToon
import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.McpLimits
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.LogLevel
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.service.logging.JsonStructuredLogger
import gokorei.tanseki.testkit.InMemoryContextStore
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The MCP seam's own guardrails: closed argument sets, a bounded whole-request
 * envelope, one flat frontmatter shape, an honoured `ifRevision` on delete, and
 * a backend failure that never reaches the model as adapter detail.
 */
class TansekiMcpHardeningTest {
    private fun facade(
        lookup: Lookup = LuceneLookup(),
        store: InMemoryContextStore = InMemoryContextStore()
    ): QueryFacade =
        QueryFacade(
            store = store,
            lookup = lookup,
            clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
            io = Dispatchers.Unconfined
        )

    private fun server(store: McpStore, logger: TansekiLogger = TansekiLogger.Noop): TansekiMcpServer =
        TansekiMcpServer(store, logger)

    private fun call(
        server: TansekiMcpServer,
        tool: String,
        args: Map<String, Any>,
        meta: Map<String, Any>? = null
    ): McpSchema.CallToolResult {
        val spec = server.tools().first { it.tool().name() == tool }
        val request =
            if (meta == null) {
                McpSchema.CallToolRequest(tool, args)
            } else {
                McpSchema.CallToolRequest(tool, args, meta)
            }
        return spec.callHandler().apply(null, request)
    }

    private fun text(result: McpSchema.CallToolResult): String =
        (result.content().first() as McpSchema.TextContent).text()

    @Test
    fun `every tool publishes a closed argument set`() {
        val schemas =
            server(LocalMcpStore(facade()))
                .tools()
                .associate { spec -> spec.tool().name() to spec.tool().inputSchema() }
        assertEquals(setOf("search", "document", "traverse"), schemas.keys)
        schemas.forEach { (name, schema) ->
            assertEquals(false, schema["additionalProperties"], "$name must publish additionalProperties: false")
            assertEquals("object", schema["type"], name)
        }
    }

    @Test
    fun `an unknown argument is refused by every tool`() {
        val server = server(LocalMcpStore(facade()))
        listOf(
            Triple("search", mapOf("query" to "q"), mapOf("typo" to true)),
            Triple("document", mapOf("action" to "get", "id" to "a/b"), mapOf("typo" to true)),
            Triple("traverse", mapOf("from" to "a/b", "depth" to 1), mapOf("typo" to true))
        ).forEach { (tool, allowed, unknown) ->
            val result = call(server, tool, allowed + unknown)
            assertEquals(true, result.isError(), "$tool should reject $unknown")
            assertTrue(text(result).contains("code: invalid_argument"), text(result))
            assertTrue("unsupported argument" in text(result), text(result))
        }
    }

    @Test
    fun `the whole request envelope is bounded before the handler runs`() {
        val server = server(LocalMcpStore(facade()))

        val oversizedMeta =
            call(
                server,
                "search",
                mapOf("query" to "q"),
                meta = mapOf("blob" to "x".repeat(McpLimits.MAX_REQUEST_ENVELOPE_BYTES.toInt() + 16))
            )
        assertEquals(true, oversizedMeta.isError())
        assertTrue(text(oversizedMeta).contains("code: request_too_large"), text(oversizedMeta))

        val tooManyMetaEntries =
            call(
                server,
                "search",
                mapOf("query" to "q"),
                meta = (0..64).associate { "key-$it" to "value" }
            )
        assertEquals(true, tooManyMetaEntries.isError())
        assertTrue(text(tooManyMetaEntries).contains("code: request_too_large"), text(tooManyMetaEntries))

        val withinBounds = call(server, "search", mapOf("query" to "q"), meta = mapOf("progressToken" to 7))
        assertFalse(withinBounds.isError()!!, text(withinBounds))
    }

    @Test
    fun `frontmatter keeps structure when stored and stays scalar when filtered`() {
        val server = server(LocalMcpStore(facade()))

        // A stored nested map is carried, not refused: the value model keeps
        // structure, so it round-trips and its leaves stay filterable, and `/v1`
        // accepts the same document. Refusing it here made the seams disagree.
        val nestedObject =
            call(
                server,
                "document",
                mapOf(
                    "action" to "upsert",
                    "id" to "struct/a",
                    "content" to "x",
                    "frontmatter" to mapOf("meta" to mapOf("nested" to true))
                )
            )
        assertFalse(nestedObject.isError()!!, text(nestedObject))

        val nestedArray =
            call(
                server,
                "document",
                mapOf(
                    "action" to "upsert",
                    "id" to "struct/b",
                    "content" to "x",
                    "frontmatter" to mapOf("tags" to listOf(listOf("a")))
                )
            )
        assertEquals(true, nestedArray.isError())
        assertTrue(text(nestedArray).contains("code: invalid_argument"), text(nestedArray))

        // A filter is the opposite case and stays scalar-only. It is flattened to
        // a string and compared against the encoded form the index stored, so a
        // nested value would match nothing -- accepted, and quietly wrong.
        val nestedFilter =
            call(
                server,
                "search",
                mapOf("query" to "q", "frontmatter" to mapOf("meta" to mapOf("nested" to true)))
            )
        assertEquals(true, nestedFilter.isError())
        assertTrue(text(nestedFilter).contains("code: invalid_argument"), text(nestedFilter))

        val flat =
            call(
                server,
                "document",
                mapOf(
                    "action" to "upsert",
                    "id" to "struct/ok",
                    "content" to "x",
                    "frontmatter" to mapOf("repo" to "org/repo", "n" to 1, "tags" to listOf("a", "b"))
                )
            )
        assertFalse(flat.isError()!!, text(flat))
    }

    @Test
    fun `a filter value that cannot be resolved is refused by name`() {
        // The same rejection `GET /v1/search` makes, with the same words: both seams call
        // the one resolver in the domain, and a value that reached the index as a filter
        // on one seam and not the other is the disagreement this suite exists to catch.
        val server = server(LocalMcpStore(facade()))

        listOf(
            "" to "frontmatter filter 'pr=' has no value; write \"\" for the empty string",
            "\"42" to "frontmatter filter 'pr=\"42' has an unterminated quoted value; close the quote"
        ).forEach { (value, expected) ->
            val result = call(server, "search", mapOf("query" to "q", "frontmatter" to mapOf("pr" to value)))

            assertEquals(true, result.isError(), text(result))
            assertTrue(text(result).contains("code: invalid_argument"), text(result))
            // Decoded rather than substring-matched: the TOON payload quotes and escapes
            // the message, so comparing against the raw text would be asserting on that.
            assertEquals(
                expected,
                Json
                    .parseToJsonElement(JToon.decodeToJson(text(result)))
                    .jsonObject["error"]!!
                    .jsonPrimitive.content
            )
        }
    }

    @Test
    fun `delete honours ifRevision on the local store`() {
        val server = server(LocalMcpStore(facade()))
        val id = "cas/target"
        val upserted = call(server, "document", mapOf("action" to "upsert", "id" to id, "content" to "first"))
        assertFalse(upserted.isError()!!, text(upserted))
        val current = text(upserted).substringAfter("revision: ").substringBefore('\n').trim()
        assertTrue(current.isNotEmpty(), text(upserted))

        val stale = call(server, "document", mapOf("action" to "delete", "id" to id, "ifRevision" to "stale-revision"))
        assertEquals(true, stale.isError(), text(stale))
        assertTrue(text(stale).contains("code: conflict"), text(stale))
        assertFalse(text(stale).contains(BACKEND_DETAIL), text(stale))

        // The rejected delete must not have removed anything.
        val stillThere = call(server, "document", mapOf("action" to "get", "id" to id))
        assertFalse(stillThere.isError()!!, text(stillThere))

        val deleted = call(server, "document", mapOf("action" to "delete", "id" to id, "ifRevision" to current))
        assertFalse(deleted.isError()!!, text(deleted))
        assertTrue(text(deleted).contains("revision:"), text(deleted))
        assertEquals(true, call(server, "document", mapOf("action" to "get", "id" to id)).isError())
    }

    @Test
    fun `a backend failure is a generic value and never reaches the log verbatim`() {
        val logs = CopyOnWriteArrayList<String>()
        val server = server(ExplodingStore, JsonStructuredLogger(logs::add, LogLevel.DEBUG))
        server.tools()

        val result = call(server, "search", mapOf("query" to "anything"))
        assertEquals(true, result.isError())
        assertTrue(text(result).contains("code: internal"), text(result))
        assertFalse(BACKEND_DETAIL in text(result), text(result))
        assertFalse(BACKEND_ENDPOINT in text(result), text(result))
        assertTrue(
            logs.any { it.contains("\"errorType\":\"IllegalStateException\"") },
            "expected an error-type-only log record, found: ${logs.lastOrNull()}"
        )
        assertTrue(logs.none { BACKEND_DETAIL in it }, "the throwable message reached a log record")
    }

    @Test
    fun `a missing document is a not_found value without a document dump`() {
        val server = server(LocalMcpStore(facade()))
        val result = call(server, "document", mapOf("action" to "get", "id" to "absent/doc"))
        assertEquals(true, result.isError())
        assertTrue(text(result).contains("code: not_found"), text(result))
    }

    @Test
    fun `a single document read is bounded by the content limit`() {
        val store = InMemoryContextStore()
        val oversized = seed(store, "huge/doc", "x".repeat(McpLimits.MAX_CONTENT_BYTES.toInt() + 1))
        val atLimit = seed(store, "large/doc", "y".repeat(McpLimits.MAX_CONTENT_BYTES.toInt()))
        val server = server(LocalMcpStore(facade(store = store)))

        val refused = call(server, "document", mapOf("action" to "get", "id" to oversized))
        assertEquals(true, refused.isError(), text(refused))
        assertTrue(text(refused).contains("code: content_too_large"), text(refused))

        val returned = call(server, "document", mapOf("action" to "get", "id" to atLimit))
        assertFalse(returned.isError()!!, text(returned))
        assertTrue(text(returned).contains("id: $atLimit"), text(returned).take(200))
    }

    /**
     * The envelope check above runs on an already-deserialized request, so the
     * transport is the only place a hostile line can be stopped first.
     *
     * Two lines are sent, and the sizes are chosen to separate the two bounds:
     * one over the request envelope but under the transport cap, which the SDK
     * must read and this server must answer with an errors-as-value
     * `request_too_large`, and one over the transport cap, which the SDK must
     * refuse before deserializing. The second case only distinguishes anything
     * because the cap under test is far below the SDK's own 16 MiB default.
     */
    @Test
    fun `the transport refuses an over cap line before the sdk deserializes it`() {
        val toClient = PipedOutputStream()
        val toServer = PipedInputStream(toClient, 1 shl 16)
        val output = ByteArrayOutputStream()
        val server = server(LocalMcpStore(facade())).build(toServer, output)
        try {
            toClient.write("$INITIALIZE_LINE\n".toByteArray())
            toClient.flush()
            awaitOutput(output, "\"id\":1")
            toClient.write("$INITIALIZED_NOTIFICATION\n".toByteArray())
            toClient.flush()

            toClient.write("${oversizedUpsert(2)}\n".toByteArray())
            toClient.flush()
            awaitOutput(output, "\"id\":2")
            assertTrue(
                "request_too_large" in output.toString(Charsets.UTF_8),
                "a line within the transport cap must be answered, not dropped: " +
                    output.toString(Charsets.UTF_8).takeLast(600)
            )

            // The transport stops reading here, so the remaining writes cannot block the test.
            val remainder =
                Thread {
                    runCatching {
                        toClient.write("${toolCall(3, TRANSPORT_OVER_CAP_CHARS)}\n".toByteArray())
                        toClient.write("$TOOLS_LIST_LINE\n".toByteArray())
                        toClient.flush()
                    }
                }
            remainder.isDaemon = true
            remainder.start()
            Thread.sleep(1_000)

            val written = output.toString(Charsets.UTF_8)
            assertTrue("\"id\":1" in written && "\"id\":2" in written, written.take(400))
            assertFalse("\"id\":3" in written, "the over-cap line was deserialized and answered")
            assertFalse("\"id\":4" in written, "the session continued past the over-cap line")
        } finally {
            runCatching { toClient.close() }
            server.close()
        }
    }

    /**
     * A `tools/call` that is over the request body and the request envelope —
     * `content` at the published schema limit plus a large frontmatter value —
     * while staying under the transport cap. The SDK must read it and the server
     * must answer it with an errors-as-value `request_too_large`.
     */
    private fun oversizedUpsert(id: Int): String =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"document",""" +
            """"arguments":{"action":"upsert","id":"huge/doc",""" +
            """"content":"${"c".repeat(McpLimits.MAX_CONTENT_BYTES.toInt())}",""" +
            """"frontmatter":{"note":"${"f".repeat(ENVELOPE_OVER_CAP_CHARS)}"}}}}"""

    private fun toolCall(
        id: Int,
        queryChars: Int
    ): String =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"search",""" +
            """"arguments":{"query":"${"q".repeat(queryChars)}"}}}"""

    private fun awaitOutput(
        output: ByteArrayOutputStream,
        expected: String
    ) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (expected in output.toString(Charsets.UTF_8)) return
            Thread.sleep(20)
        }
        fail<Unit>("the server never wrote $expected: ${output.toString(Charsets.UTF_8).take(400)}")
    }

    private fun seed(
        store: InMemoryContextStore,
        id: String,
        content: String
    ): String {
        val doc =
            Document(
                id = DocId(id),
                collection = Collection("vault"),
                path = "$id.md",
                content = content,
                contentHash = "hash-$id",
                revision = RevisionId("rev-1"),
                updatedAt = kotlin.time.Instant.fromEpochSeconds(0)
            )
        store.write(doc, "seed", "tester")
        return id
    }

    private object ExplodingStore : McpStore {
        private fun boom(): Nothing = throw IllegalStateException("$BACKEND_DETAIL at $BACKEND_ENDPOINT")

        override fun search(
            query: String,
            mode: gokorei.tanseki.core.domain.SearchMode,
            collections: Set<String>,
            tags: Set<String>,
            frontmatter: Map<String, String>,
            limit: Int,
            offset: Int
        ): kotlinx.serialization.json.JsonArray = boom()

        override fun get(id: String): kotlinx.serialization.json.JsonObject? = boom()

        override fun upsert(
            id: String,
            content: String,
            collection: String?,
            path: String?,
            frontmatter: Map<String, kotlinx.serialization.json.JsonElement>?,
            message: String?,
            author: String?,
            ifRevision: String?
        ): kotlinx.serialization.json.JsonObject = boom()

        override fun delete(
            id: String,
            message: String?,
            author: String?,
            ifRevision: String?
        ): kotlinx.serialization.json.JsonObject = boom()

        override fun history(id: String, limit: Int, offset: Int): kotlinx.serialization.json.JsonArray = boom()

        override fun traverse(from: String, rel: String, depth: Int): kotlinx.serialization.json.JsonArray = boom()
    }

    private companion object {
        const val BACKEND_DETAIL = "pijul exited with 128 while replaying org/repo/pr-42"
        const val BACKEND_ENDPOINT = "postgres://tanseki:hunter2@db.internal:5432/tanseki"

        /**
         * Over the request body (1_048_576 bytes) when added to a schema-legal
         * `content`, and under the transport cap, so the SDK must read the line
         * and the server must answer it.
         */
        const val ENVELOPE_OVER_CAP_CHARS = 200_000

        /**
         * Over the transport cap Tanseki installs, and still under the SDK's own
         * 16 MiB default, so the refusal can only come from Tanseki's cap.
         */
        const val TRANSPORT_OVER_CAP_CHARS = 2_000_000

        const val INITIALIZE_LINE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"tanseki-test","version":"1.0.0"}}}"""
        const val TOOLS_LIST_LINE = """{"jsonrpc":"2.0","id":4,"method":"tools/list","params":{}}"""
        const val INITIALIZED_NOTIFICATION = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
    }
}
