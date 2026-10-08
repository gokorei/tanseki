package gokorei.tanseki.service.mcp

import dev.toonformat.jtoon.JToon
import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.domain.SearchMode
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.Embedder
import gokorei.tanseki.service.api.StoreApiServer
import gokorei.tanseki.testkit.InMemoryContextStore
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * The MCP `search` tool and `GET /v1/search` must answer the same query the
 * same way.
 *
 * They are two entry points onto one `QueryFacade`, so a difference between them
 * is never a modelling choice — it is one seam having quietly routed somewhere
 * else, which is exactly how `search` came to be lexical-only on MCP while `/v1`
 * had fused lexical and kNN ranks all along. These tests compare the two
 * surfaces' ranked ids directly rather than comparing each to a hand-written
 * expectation, because the property worth protecting is that they agree; a fixed
 * expectation would only pin today's ranking.
 */
class McpHttpSearchParityTest {
    private val apiKey = "search-parity-key"
    private val http = HttpClient.newHttpClient()

    @Test
    fun `hybrid mode returns the same ranked ids over MCP and HTTP`() {
        withSurfaces(embedder = ScriptedEmbedder()) { surfaces ->
            seed(surfaces.store)

            val mcp = surfaces.mcpIds("rollback", mode = "hybrid")
            val rest = httpIds(surfaces.port, query = "rollback", mode = SearchMode.Hybrid)

            assertEquals(rest, mcp, "MCP and /v1 must rank identically for mode=hybrid")
            // A parity assertion alone would still pass if `hybrid` quietly
            // degraded to lexical on both sides, so the kNN arm is shown to have
            // contributed: `notes/settlement` shares no term with the query and is
            // reachable only through the vector arm.
            assertTrue(mcp.contains("notes/settlement"), "expected the kNN arm to contribute, got $mcp")
            assertFalse(
                surfaces.mcpIds("rollback", mode = "lexical").contains("notes/settlement"),
                "lexical alone must not reach the settlement note, or the fixture proves nothing"
            )
        }
    }

    @Test
    fun `omitting mode matches an explicit lexical request`() {
        withSurfaces(embedder = ScriptedEmbedder()) { surfaces ->
            seed(surfaces.store)

            assertEquals(surfaces.mcpIds("rollback", mode = "lexical"), surfaces.mcpIds("rollback"))
            assertEquals(httpIds(surfaces.port, "rollback", SearchMode.Lexical), surfaces.mcpIds("rollback"))
        }
    }

    @Test
    fun `hybrid degrades to lexical without an embedder instead of failing`() {
        withSurfaces(embedder = null) { surfaces ->
            seed(surfaces.store)

            val hybrid = surfaces.callSearch(mapOf("query" to "rollback", "mode" to "hybrid"))

            assertFalse(hybrid.isError()!!, "no embedder must degrade, not error: ${text(hybrid)}")
            assertEquals(surfaces.mcpIds("rollback", mode = "lexical"), surfaces.mcpIds("rollback", mode = "hybrid"))
            // And the daemon agrees, so the fallback is not local to one seam.
            assertEquals(
                httpIds(surfaces.port, "rollback", SearchMode.Hybrid),
                surfaces.mcpIds("rollback", mode = "hybrid")
            )
        }
    }

    @Test
    fun `mode is a bounded enum and the tool stays read-only`() {
        withSurfaces(embedder = ScriptedEmbedder()) { surfaces ->
            val tool =
                surfaces.server
                    .tools()
                    .first { it.tool().name() == "search" }
                    .tool()

            @Suppress("UNCHECKED_CAST")
            val schema = tool.inputSchema()

            @Suppress("UNCHECKED_CAST")
            val properties = schema["properties"] as Map<String, Map<String, Any>>

            assertEquals(listOf("lexical", "hybrid"), properties["mode"]!!["enum"])
            assertEquals("string", properties["mode"]!!["type"])
            // Absent by default, so a caller written against the lexical-only
            // tool keeps the ranking it had.
            assertEquals(listOf("query"), schema["required"])
            assertTrue(tool.description().contains("mode=hybrid"), tool.description())
            assertTrue(tool.description().contains("silently falls back"), tool.description())

            assertEquals(true, tool.annotations()!!.readOnlyHint())
            assertEquals(false, tool.annotations()!!.destructiveHint())
        }
    }

    @Test
    fun `an unrecognised mode names itself instead of falling back`() {
        withSurfaces(embedder = ScriptedEmbedder()) { surfaces ->
            seed(surfaces.store)

            val result = surfaces.callSearch(mapOf("query" to "rollback", "mode" to "fuzzy"))

            assertEquals(true, result.isError())
            val payload = text(result)
            assertTrue(payload.contains("code: invalid_argument"), payload)
            assertTrue(payload.contains("validOptions[2]:"), payload)
            assertTrue(payload.contains("hybrid"), payload)
        }
    }

    /**
     * A filter resolves to the same value on both seams.
     *
     * The two seams take the same filter in two different spellings — a JSON object with a
     * real number against a `key=value` query parameter — and this is the assertion that
     * they agree on what those characters mean. A filter typed onto one seam and not the
     * other is invisible from a single endpoint, which is precisely how `GET` and the tool
     * came to disagree about ranking in the first place.
     */
    @Test
    fun `a number filter selects the number on both seams`() {
        withSurfaces(embedder = null) { surfaces ->
            surfaces.store.upsert(
                "notes/number",
                "shared token",
                null,
                null,
                mapOf("pr" to JsonPrimitive(7)),
                null,
                null,
                null
            )
            surfaces.store.upsert(
                "notes/string",
                "shared token",
                null,
                null,
                mapOf("pr" to JsonPrimitive("7")),
                null,
                null,
                null
            )

            assertEquals(
                listOf("notes/number"),
                surfaces.mcpIds("", frontmatter = mapOf("pr" to 7)),
                "a bare 7 is the number on the MCP seam"
            )
            assertEquals(
                listOf("notes/number"),
                httpIds(surfaces.port, "", SearchMode.Lexical, listOf("pr=7")),
                "and on the HTTP seam"
            )
            // Quoting is the way to the string, on both seams, and only there.
            assertEquals(
                listOf("notes/string"),
                surfaces.mcpIds("", frontmatter = mapOf("pr" to "\"7\"")),
                "a quoted 7 is the string on the MCP seam"
            )
            assertEquals(
                listOf("notes/string"),
                httpIds(surfaces.port, "", SearchMode.Lexical, listOf("pr=\"7\"")),
                "and on the HTTP seam"
            )
            // And a number nobody stored is empty on both, rather than everything.
            assertEquals(emptyList<String>(), surfaces.mcpIds("", frontmatter = mapOf("pr" to 8)))
            assertEquals(emptyList<String>(), httpIds(surfaces.port, "", SearchMode.Lexical, listOf("pr=8")))
        }
    }

    // ---- surfaces ---------------------------------------------------------

    private class Surfaces(
        val port: Int,
        val store: LocalMcpStore,
        val server: TansekiMcpServer
    ) {
        fun callSearch(args: Map<String, Any>): McpSchema.CallToolResult {
            val spec = server.tools().first { it.tool().name() == "search" }
            return spec.callHandler().apply(null, McpSchema.CallToolRequest("search", args))
        }

        fun mcpIds(
            query: String,
            mode: String? = null,
            frontmatter: Map<String, Any>? = null
        ): List<String> {
            val arguments =
                buildMap {
                    put("query", query)
                    mode?.let { put("mode", it) }
                    frontmatter?.let { put("frontmatter", it) }
                }
            val result = callSearch(arguments)
            assertFalse(result.isError()!!, "search failed: ${text(result)}")
            return decodeHits(text(result))
        }
    }

    private fun <T> withSurfaces(embedder: Embedder?, block: (Surfaces) -> T): T {
        val now = kotlin.time.Instant.fromEpochSeconds(1_700_000_000)
        val facade =
            QueryFacade(
                store = InMemoryContextStore { now },
                lookup = LuceneLookup(),
                clock = Clock { now },
                io = Dispatchers.Unconfined,
                embedder = embedder
            )
        StoreApiServer.startEphemeral(facade, apiKey).use { running ->
            var ready = false
            repeat(100) {
                if (!ready) {
                    ready = health(running.port)
                    if (!ready) Thread.sleep(20)
                }
            }
            check(ready) { "server did not become ready" }
            val store = LocalMcpStore(facade)
            return block(Surfaces(running.port, store, TansekiMcpServer(store)))
        }
    }

    /**
     * The fixture corpus.
     *
     * `notes/runbook` and `notes/ledger` share the query term and so are the only
     * documents lexical search can reach. `notes/settlement` shares no term with
     * the query and is the [ScriptedEmbedder]'s near neighbour, so it appears
     * only when the vector arm runs.
     */
    private fun seed(store: LocalMcpStore) {
        listOf(
            "notes/runbook" to "# Runbook\n\nRollback recovery steps for the ledger service.\n",
            "notes/ledger" to "# Ledger\n\nRollback drill, run every quarter.\n",
            "notes/settlement" to "# Settlement\n\nRetry policy for downstream payment providers.\n"
        ).forEach { (id, content) ->
            store.upsert(id, content, null, null, null, null, null, null)
        }
    }

    /**
     * Vectors chosen by hand so the kNN arm has exactly one near neighbour.
     *
     * A hashed bag-of-words embedder would be just as deterministic, but which
     * document came out on top would be an accident of the corpus: a change that
     * dropped the vector arm entirely could then still produce the ranking this
     * test asserts. Pinning the vectors makes the fusion observable.
     */
    private class ScriptedEmbedder : Embedder {
        override val model = "scripted"
        override val dimensions = 2

        override fun embed(texts: List<String>): List<FloatArray> =
            texts.map { text ->
                when (
                    text
                        .lineSequence()
                        .firstOrNull { it.startsWith("# ") }
                        ?.removePrefix("# ")
                        ?.trim()
                ) {
                    "Runbook" -> floatArrayOf(0f, 1f)

                    "Ledger" -> floatArrayOf(0.5f, 0.87f)

                    "Settlement" -> floatArrayOf(1f, 0f)

                    // A query carries no heading, so this is the query vector, and
                    // the settlement note is the only document near it.
                    else -> floatArrayOf(1f, 0f)
                }
            }
    }

    /**
     * A key `fm=` cannot address is refused on both seams, with the same words.
     *
     * `tags` is a typed field rather than a frontmatter value, so no `fm_tags` term is ever
     * indexed and `fm=tags=review` could only ever return nothing — which reads as "no
     * document carries that tag" and sends an operator to check data that is fine. The
     * refusal is the assertion that both seams make it, because the two spellings are
     * `?fm=tags=review` and a JSON `frontmatter` object, and a rule applied to only one of
     * them leaves the other answering the same question with a confident empty.
     */
    @Test
    fun `a contract key is refused on both seams rather than matching nothing`() {
        withSurfaces(embedder = null) { surfaces ->
            val mcp = surfaces.callSearch(mapOf("query" to "", "frontmatter" to mapOf("tags" to "review")))
            val http = httpSearch(surfaces.port, "", SearchMode.Lexical, listOf("tags=review"))

            assertEquals(400, http.statusCode(), http.body())
            assertEquals(true, mcp.isError())
            assertTrue(text(mcp).contains("code: invalid_argument"), text(mcp))
            // The same sentence on both seams, which is the whole point of resolving the
            // key in the domain rather than at each entry point.
            assertTrue(http.body().contains("is a contract key; use the tags filter"), http.body())
            assertTrue(text(mcp).contains("is a contract key; use the tags filter"), text(mcp))
        }
    }

    @Test
    fun `a contract key with no parameter of its own is refused without inventing one`() {
        withSurfaces(embedder = null) { surfaces ->
            val response = httpSearch(surfaces.port, "", SearchMode.Lexical, listOf("title=Runbook"))

            assertEquals(400, response.statusCode(), response.body())
            assertTrue(response.body().contains("is a contract key and is not filterable"), response.body())
        }
    }

    @Test
    fun `the tags parameter still reaches the document the fm spelling cannot`() {
        // The point of the refusal is that the caller has somewhere to go: `tags=` is the
        // working spelling, and it must keep working for the very document whose tags the
        // refused filter was asking about.
        withSurfaces(embedder = null) { surfaces ->
            surfaces.store.upsert(
                "notes/tagged",
                "# Tagged\n\nA note carrying a tag.\n",
                null,
                null,
                mapOf("tags" to buildJsonArray { add(JsonPrimitive("review")) }),
                null,
                null,
                null
            )

            assertEquals(
                listOf("notes/tagged"),
                httpIds(surfaces.port, "", SearchMode.Lexical, tags = listOf("review"))
            )
        }
    }

    // ---- payloads ---------------------------------------------------------

    /**
     * One `GET /v1/search`, raw, so a rejection can be inspected and not only a hit list.
     *
     * One `--data-urlencode` per filter, and each filter encoded once. A whole
     * `fm=a=b&fm=c=d` string encoded as a single name is the request that looks like an
     * unfiltered search and reads like the filter was ignored, so each filter is appended
     * under its own name here. Filters arrive unencoded and are encoded here, so a filter
     * written `pr="7"` is sent once-encoded rather than twice.
     */
    private fun httpSearch(
        port: Int,
        query: String,
        mode: SearchMode,
        frontmatter: List<String> = emptyList(),
        tags: List<String> = emptyList()
    ): HttpResponse<String> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val filters =
            frontmatter.joinToString("") { "&fm=" + URLEncoder.encode(it, StandardCharsets.UTF_8) }
        val tagFilters = tags.joinToString("") { "&tags=" + URLEncoder.encode(it, StandardCharsets.UTF_8) }
        return http.send(
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port/v1/search?q=$encoded&mode=${mode.wire}$filters$tagFilters"))
                .timeout(Duration.ofSeconds(5))
                .header("X-API-Key", apiKey)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
    }

    private fun httpIds(
        port: Int,
        query: String,
        mode: SearchMode,
        frontmatter: List<String> = emptyList(),
        tags: List<String> = emptyList()
    ): List<String> {
        val response = httpSearch(port, query, mode, frontmatter, tags)
        assertEquals(200, response.statusCode(), response.body())
        return json
            .parseToJsonElement(response.body())
            .jsonObject["hits"]!!
            .jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    private fun health(port: Int): Boolean =
        runCatching {
            http
                .send(
                    HttpRequest
                        .newBuilder(URI("http://127.0.0.1:$port/v1/health"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                ).statusCode() == 200
        }.getOrDefault(false)

    private companion object {
        val json = Json

        fun text(result: McpSchema.CallToolResult): String =
            (result.content().first() as McpSchema.TextContent).text()
    }
}

/** Ranked ids out of the tool's TOON payload, decoded rather than pattern-matched. */
private fun decodeHits(toon: String): List<String> =
    Json
        .parseToJsonElement(JToon.decodeToJson(toon))
        .jsonObject["hits"]!!
        .jsonArray
        .map { it.jsonObject["id"]!!.jsonPrimitive.content }
