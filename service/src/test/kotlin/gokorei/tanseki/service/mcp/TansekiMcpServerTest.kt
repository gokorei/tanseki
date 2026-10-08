package gokorei.tanseki.service.mcp

import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.domain.McpLimits
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.testkit.InMemoryContextStore
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TansekiMcpServerTest {
    private fun server(): TansekiMcpServer {
        val facade =
            QueryFacade(
                store = InMemoryContextStore(),
                lookup = LuceneLookup(),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                io = Dispatchers.Unconfined
            )
        return TansekiMcpServer(LocalMcpStore(facade))
    }

    private fun call(server: TansekiMcpServer, tool: String, args: Map<String, Any>): McpSchema.CallToolResult {
        val spec = server.tools().first { it.tool().name() == tool }
        return spec.callHandler().apply(null, McpSchema.CallToolRequest(tool, args))
    }

    private fun text(result: McpSchema.CallToolResult): String =
        (result.content().first() as McpSchema.TextContent).text()

    @Test
    fun `exposes three consolidated tools`() {
        val names = server().tools().map { it.tool().name() }.toSet()
        assertEquals(setOf("search", "document", "traverse"), names)
    }

    @Test
    fun `closed sets are schema enums and annotations mark side effects`() {
        val server = server()
        val document = server.tools().first { it.tool().name() == "document" }.tool()
        val traverse = server.tools().first { it.tool().name() == "traverse" }.tool()
        val search = server.tools().first { it.tool().name() == "search" }.tool()

        @Suppress("UNCHECKED_CAST")
        val documentSchema = document.inputSchema()

        @Suppress("UNCHECKED_CAST")
        val documentProps = documentSchema["properties"] as Map<String, Map<String, Any>>
        assertEquals(listOf("get", "upsert", "delete", "history"), documentProps["action"]!!["enum"])
        assertEquals(false, documentSchema["additionalProperties"])
        assertEquals(McpLimits.MAX_CONTENT_BYTES.toInt(), documentProps["content"]!!["maxLength"])
        assertEquals(McpLimits.MAX_FRONTMATTER_ENTRIES, documentProps["frontmatter"]!!["maxProperties"])
        assertEquals(McpLimits.MAX_PAGE_LIMIT, documentProps["limit"]!!["maximum"])

        @Suppress("UNCHECKED_CAST")
        val traverseProps = traverse.inputSchema()["properties"] as Map<String, Map<String, Any>>
        assertEquals(listOf("links-to", "references", "embeds", "mentions"), traverseProps["rel"]!!["enum"])
        assertEquals(0, traverseProps["depth"]!!["minimum"])
        assertEquals(McpLimits.MAX_TRAVERSAL_DEPTH, traverseProps["depth"]!!["maximum"])

        assertEquals(true, search.annotations()!!.readOnlyHint())
        assertEquals(false, document.annotations()!!.readOnlyHint())
        assertEquals(true, document.annotations()!!.destructiveHint())
    }

    @Test
    fun `document tool round-trips upsert get history delete as TOON`() {
        val server = server()
        val id = "org/repo/pr-1/e1"

        val upserted =
            call(
                server,
                "document",
                mapOf(
                    "action" to "upsert",
                    "id" to id,
                    "content" to "Because X.",
                    "frontmatter" to mapOf("repo" to "org/repo")
                )
            )
        assertFalse(upserted.isError()!!)
        assertTrue(text(upserted).contains("created: true"), text(upserted))

        val fetched = call(server, "document", mapOf("action" to "get", "id" to id))
        assertFalse(fetched.isError()!!)
        assertTrue(text(fetched).contains("frontmatter:"), text(fetched))
        assertTrue(text(fetched).contains("repo: org/repo"), text(fetched))

        val history = call(server, "document", mapOf("action" to "history", "id" to id))
        assertTrue(text(history).contains("revisions["), text(history))

        val deleted = call(server, "document", mapOf("action" to "delete", "id" to id))
        assertTrue(text(deleted).contains("revision:"), text(deleted))

        val missing = call(server, "document", mapOf("action" to "get", "id" to id))
        assertEquals(true, missing.isError())
        assertTrue(text(missing).contains("code: not_found"), text(missing))
    }

    @Test
    fun `search returns a TOON tabular envelope and filters by frontmatter`() {
        val server = server()
        call(server, "document", mapOf("action" to "upsert", "id" to "s/a", "content" to "shared token"))
        call(
            server,
            "document",
            mapOf(
                "action" to "upsert",
                "id" to "s/b",
                "content" to "shared token",
                "frontmatter" to mapOf("repo" to "org/other")
            )
        )

        val all = call(server, "search", mapOf("query" to "token"))
        assertTrue(text(all).contains("hits[2]"), text(all))

        val filtered = call(server, "search", mapOf("query" to "token", "frontmatter" to mapOf("repo" to "org/other")))
        assertTrue(text(filtered).contains("s/b"), text(filtered))
        assertFalse(text(filtered).contains("s/a"), text(filtered))
    }

    @Test
    fun `errors are returned as TOON values with self-healing context`() {
        val server = server()

        val missingContent = call(server, "document", mapOf("action" to "upsert", "id" to "x"))
        assertEquals(true, missingContent.isError())
        assertTrue(text(missingContent).contains("code: invalid_argument"), text(missingContent))
        assertTrue(text(missingContent).contains("hint:"), text(missingContent))

        val unsafeId = call(server, "document", mapOf("action" to "get", "id" to "../../outside"))
        assertTrue(text(unsafeId).contains("code: invalid_argument"), text(unsafeId))

        val notFound = call(server, "document", mapOf("action" to "get", "id" to "nope"))
        assertTrue(text(notFound).contains("code: not_found"), text(notFound))

        // handler-level call bypasses schema enum validation, so the runtime guard still self-heals
        val badRel = call(server, "traverse", mapOf("from" to "x", "rel" to "bogus"))
        assertTrue(text(badRel).contains("code: invalid_argument"), text(badRel))
        assertTrue(text(badRel).contains("validOptions[4]:"), text(badRel))
        assertTrue(text(badRel).contains("links-to"), text(badRel))
    }

    @Test
    fun `mcp boundaries return invalid_argument values`() {
        val server = server()
        val boundedSearch = call(server, "search", mapOf("query" to "q", "limit" to 1, "offset" to 0))
        assertFalse(boundedSearch.isError()!!, text(boundedSearch))
        assertFalse(call(server, "traverse", mapOf("from" to "missing", "depth" to 0)).isError()!!)
        val longQuery = "q".repeat(McpLimits.MAX_QUERY_BYTES.toInt() + 1)
        assertTrue(text(call(server, "search", mapOf("query" to longQuery))).contains("code: invalid_argument"))
        assertTrue(
            text(call(server, "search", mapOf("query" to "q", "limit" to McpLimits.MAX_PAGE_LIMIT + 1)))
                .contains("code: invalid_argument")
        )
        assertTrue(
            text(call(server, "search", mapOf("query" to "q", "offset" to McpLimits.MAX_PAGE_OFFSET + 1)))
                .contains("code: invalid_argument")
        )
        assertTrue(
            text(
                call(
                    server,
                    "document",
                    mapOf(
                        "action" to "upsert",
                        "id" to "limits/content",
                        "content" to "x".repeat(McpLimits.MAX_CONTENT_BYTES.toInt() + 1)
                    )
                )
            ).contains("code: request_too_large")
        )
        val tags = (1..(McpLimits.MAX_TAG_COUNT + 1)).map { "tag-$it" }
        assertTrue(
            text(call(server, "search", mapOf("query" to "q", "tags" to tags))).contains("code: invalid_argument")
        )
        assertTrue(
            text(call(server, "traverse", mapOf("from" to "a", "depth" to McpLimits.MAX_TRAVERSAL_DEPTH + 1)))
                .contains("code: invalid_argument")
        )
        assertTrue(
            text(
                call(
                    server,
                    "document",
                    mapOf(
                        "action" to "history",
                        "id" to "a",
                        "limit" to McpLimits.MAX_PAGE_LIMIT + 1
                    )
                )
            ).contains("code: invalid_argument")
        )
        val frontmatter =
            (1..(McpLimits.MAX_FRONTMATTER_ENTRIES + 1)).associate { "key-$it" to "value" }
        assertTrue(
            text(
                call(
                    server,
                    "document",
                    mapOf("action" to "upsert", "id" to "a", "content" to "x", "frontmatter" to frontmatter)
                )
            ).contains("code: invalid_argument")
        )
        var nested: Any = "value"
        repeat(McpLimits.MAX_FRONTMATTER_DEPTH + 2) { nested = mapOf("nested" to nested) }
        assertTrue(
            text(
                call(
                    server,
                    "document",
                    mapOf("action" to "upsert", "id" to "a", "content" to "x", "frontmatter" to nested)
                )
            ).contains("code: invalid_argument")
        )
        assertTrue(
            text(call(server, "search", mapOf("query" to "q", "unknown" to true)))
                .contains("code: invalid_argument")
        )
    }

    @Test
    fun `traverse accepts relationship aliases`() {
        val server = server()
        call(server, "document", mapOf("action" to "upsert", "id" to "t/b", "content" to "target"))
        call(server, "document", mapOf("action" to "upsert", "id" to "t/a", "content" to "links to [[t/b]]"))

        val result = call(server, "traverse", mapOf("from" to "t/a", "rel" to "link", "depth" to 2))
        assertFalse(result.isError()!!)
        assertTrue(text(result).contains("t/b"), text(result))
    }

    @Test
    fun `builds over stdio transport`() {
        val server = server()
        val mcp = server.build(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        try {
            assertEquals("tanseki", mcp.getServerInfo().name())
        } finally {
            mcp.close()
        }
    }

    @Test
    fun `serves the mcp protocol over stdio`() {
        val serverIn = PipedInputStream(1 shl 16)
        val clientOut = PipedOutputStream(serverIn)
        val serverOut = PipedOutputStream()
        val clientIn = PipedInputStream(serverOut, 1 shl 16)
        val mcp = server().build(serverIn, serverOut)
        val writer = BufferedWriter(OutputStreamWriter(clientOut))
        val reader = BufferedReader(InputStreamReader(clientIn))
        try {
            writer.write(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
            )
            writer.newLine()
            writer.flush()

            val initialized = readLine(reader, 10_000)
            assertNotNull(initialized, "expected an initialize response")
            assertTrue(initialized!!.contains("serverInfo"), "unexpected initialize response: $initialized")

            writer.write("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            writer.newLine()
            writer.write("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
            writer.newLine()
            writer.flush()

            var toolsResponse: String? = null
            val deadline = System.currentTimeMillis() + 10_000
            while (toolsResponse == null && System.currentTimeMillis() < deadline) {
                val line = readLine(reader, 10_000) ?: break
                if (line.contains("\"id\":2")) toolsResponse = line
            }
            assertNotNull(toolsResponse, "expected a tools/list response")
            assertTrue(toolsResponse!!.contains("document"), "tools/list should include document: $toolsResponse")

            // tools/call over the real transport: upsert then search
            writer.write(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"document","arguments":{"action":"upsert","id":"stdio/a","content":"hello stdio"}}}"""
            )
            writer.newLine()
            writer.flush()
            val upsertResponse = readResponse(reader, 3)
            assertTrue(upsertResponse!!.contains("created: true"), upsertResponse)

            writer.write(
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search","arguments":{"query":"stdio"}}}"""
            )
            writer.newLine()
            writer.flush()
            val searchResponse = readResponse(reader, 4)
            assertTrue(searchResponse!!.contains("stdio/a"), searchResponse)
        } finally {
            runCatching { clientOut.close() }
            runCatching { mcp.close() }
        }
    }

    private fun readResponse(reader: BufferedReader, id: Int): String? {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val line = readLine(reader, 10_000) ?: return null
            if (line.contains("\"id\":$id")) return line
        }
        return null
    }

    private fun readLine(reader: BufferedReader, timeoutMillis: Long): String? {
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<String?> { reader.readLine() }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        } finally {
            executor.shutdownNow()
        }
    }
}
