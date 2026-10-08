package gokorei.tanseki.service.api

import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.core.application.PendingOverlay
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.LogLevel
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.service.daemon.DaemonConfig
import gokorei.tanseki.service.daemon.OperationalMetrics
import gokorei.tanseki.service.logging.JsonStructuredLogger
import gokorei.tanseki.testkit.InMemoryContextStore
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The public-seam guarantees a hostile client and a failing dependency both
 * probe: closed request bodies, one frontmatter shape, a stable error envelope,
 * and bounded correlation ids.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreApiHardeningTest {
    private val apiKey = "hardening-key"
    private val http: HttpClient = HttpClient.newHttpClient()
    private val logs = CopyOnWriteArrayList<String>()
    private var port = 0
    private var server: RunningApiServer? = null

    @BeforeAll
    fun startServer() {
        val facade = facade(ExplodingLookup)
        val running =
            StoreApiServer.startEphemeral(
                facade = facade,
                apiKey = apiKey,
                logger = JsonStructuredLogger(logs::add, LogLevel.DEBUG)
            )
        server = running
        port = running.port
        var ready = false
        repeat(100) {
            if (!ready) {
                runCatching { if (send("GET", "/v1/health", authenticated = false).statusCode() == 200) ready = true }
                if (!ready) Thread.sleep(50)
            }
        }
        check(ready) { "server did not become ready" }
    }

    @AfterAll
    fun stopServer() {
        server?.close()
    }

    @Test
    fun `every request body is a closed set`() {
        listOf(
            "/v1/documents:get" to """{"id":"a/b","nope":true}""",
            "/v1/documents:query" to """{"ids":["a/b"],"nope":true}""",
            "/v1/documents:upsert" to """{"id":"a/b","content":"x","nope":true}""",
            "/v1/documents:delete" to """{"id":"a/b","nope":true}""",
            "/v1/documents:history" to """{"id":"a/b","nope":true}""",
            "/v1/documents:traverse" to """{"id":"a/b","nope":true}"""
        ).forEach { (path, body) ->
            val response = post(path, body)
            assertEquals(400, response.statusCode(), "$path must reject an unknown field")
            assertEquals("invalid_argument", errorCode(response), "$path must answer with invalid_argument")
        }
    }

    @Test
    fun `a misspelled optional field is refused rather than silently dropped`() {
        val typo = post("/v1/documents:upsert", """{"id":"a/b","content":"x","ifRev":"r1"}""")
        assertEquals(400, typo.statusCode())
        assertEquals("invalid_argument", errorCode(typo))
    }

    @Test
    fun `frontmatter keeps its shape in the http seam and is bounded`() {
        // A nested object used to be refused rather than flattened to an opaque
        // string that could no longer round-trip or match a filter. The value
        // model now carries it, so it is kept -- and the YAML seam, which always
        // accepted one, no longer disagrees with this route.
        val nested =
            post(
                "/v1/documents:upsert",
                """{"id":"a/b","content":"x","frontmatter":{"meta":{"nested":true}}}"""
            )
        assertEquals(200, nested.statusCode())

        // `tags` is the exception, and deliberately so: it is modelled as a
        // `List<String>`, so a collection under it would be accepted here and then
        // silently absent from the typed view. Refused instead.
        val nestedArray =
            post(
                "/v1/documents:upsert",
                """{"id":"a/b","content":"x","frontmatter":{"tags":[["a"]]}}"""
            )
        assertEquals(400, nestedArray.statusCode())

        val blankKey =
            post(
                "/v1/documents:upsert",
                """{"id":"a/b","content":"x","frontmatter":{"":"v"}}"""
            )
        assertEquals(400, blankKey.statusCode())

        val flat =
            post(
                "/v1/documents:upsert",
                """{"id":"a/flat","content":"x","frontmatter":{"repo":"org/repo","n":1,"tags":["a","b"]}}"""
            )
        assertEquals(200, flat.statusCode())
    }

    @Test
    fun `a backend failure never reaches the client or the log sink`() {
        val response = get("/v1/search?q=anything")
        assertEquals(500, response.statusCode())
        assertEquals("internal", errorCode(response))
        assertFalse(BACKEND_SECRET in response.body(), response.body())
        assertFalse(BACKEND_ENDPOINT in response.body(), response.body())
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { BACKEND_SECRET in it }, "the throwable message reached a log record")
        assertTrue(logs.none { BACKEND_ENDPOINT in it }, "the dependency endpoint reached a log record")
        assertTrue(
            logs.any { it.contains("\"errorType\":\"IllegalStateException\"") },
            "the log record should carry the error type only: ${logs.lastOrNull()}"
        )
    }

    @Test
    fun `caller supplied request ids are bounded`() {
        val tooLong = "r".repeat(MAX_REQUEST_ID_LENGTH + 1)
        val echoed =
            get("/v1/health", authenticated = false, headers = mapOf(HEADER_REQUEST_ID to tooLong)).let {
                it.headers().firstValue(HEADER_REQUEST_ID).orElse("")
            }
        assertNotEquals(tooLong, echoed)
        assertTrue(UUID.fromString(echoed).toString() == echoed, "expected a generated id, found $echoed")

        listOf("with space", "tab\tid", "   ").forEach { unsafe ->
            val response =
                get("/v1/health", authenticated = false, headers = mapOf(HEADER_REQUEST_ID to unsafe))
            val id = response.headers().firstValue(HEADER_REQUEST_ID).orElse("")
            assertNotEquals(unsafe, id)
            assertTrue(UUID.fromString(id).toString() == id, "'$unsafe' produced an unbounded id: $id")
        }

        // Control characters cannot be sent by the JDK client, so the normaliser
        // itself is asserted for the whole hostile input class.
        listOf("new\nline", "carriage\rreturn", "nul\u0000byte", "bell\u0007", "\u0000").forEach { hostile ->
            val generated = requestIdOrGenerated(hostile)
            assertNotEquals(hostile, generated)
            assertTrue(generated.length <= MAX_REQUEST_ID_LENGTH, generated)
        }

        val accepted = "trace-1-2-3"
        assertEquals(
            accepted,
            get("/v1/health", authenticated = false, headers = mapOf(HEADER_REQUEST_ID to accepted))
                .headers()
                .firstValue(HEADER_REQUEST_ID)
                .orElse("")
        )
    }

    @Test
    fun `an empty collection scope is refused at the credential model`() {
        assertThrows(IllegalArgumentException::class.java) { ApiCredential(apiKey, "empty", emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { ApiCredential(apiKey, "blank", setOf(" ")) }
        assertThrows(IllegalArgumentException::class.java) { ApiCredential(apiKey, "ops", null, emptySet()) }
        assertThrows(IllegalArgumentException::class.java) {
            ApiPrincipal("empty", emptySet(), ApiOperation.entries.toSet(), "empty")
        }
        assertThrows(IllegalArgumentException::class.java) { CredentialRegistry.parse("name|$apiKey|,") }
    }

    @Test
    fun `a scoped principal resolves to a non empty scope and never a global read`() {
        val principal =
            ApiAuthPolicy
                .forRegistry(CredentialRegistry.of(listOf(ApiCredential(apiKey, "alpha", setOf("alpha")))))
                .authenticate(apiKey)
        requireNotNull(principal)

        assertEquals(setOf("alpha"), principal.collections)
        assertEquals(setOf("alpha"), principal.scopedCollections(null))
        assertEquals(setOf("alpha"), principal.scopedCollections("alpha"))
        assertEquals(setOf("beta"), principal.scopedCollections("beta"))
        assertFalse(principal.allows("beta"))
        assertFalse(principal.allows(null) && principal.collections == null)
    }

    @Test
    fun `an unknown collection never widens to a global read`() {
        val scoped =
            StoreApiServer.startEphemeral(
                facade = facade(LuceneLookup()),
                authPolicy =
                    ApiAuthPolicy.forRegistry(
                        CredentialRegistry.of(listOf(ApiCredential(apiKey, "alpha", setOf("alpha"))))
                    )
            )
        try {
            val absent =
                HttpRequest
                    .newBuilder(URI("http://127.0.0.1:${scoped.port}/v1/search?q=x&collection=absent"))
                    .timeout(Duration.ofSeconds(5))
                    .header(HEADER_API_KEY, apiKey)
                    .GET()
                    .build()
            val response = http.send(absent, HttpResponse.BodyHandlers.ofString())
            assertEquals(404, response.statusCode())
        } finally {
            scoped.close()
        }
    }

    @Test
    fun `a scope that matches no collection reports zero, not the whole store`() {
        val store = InMemoryContextStore()
        val scoped =
            StoreApiServer.startEphemeral(
                facade = facade(LuceneLookup(), store),
                authPolicy =
                    ApiAuthPolicy.forRegistry(
                        CredentialRegistry.of(listOf(ApiCredential(apiKey, "absent", setOf("absent"))))
                    ),
                metrics =
                    OperationalMetrics(
                        store = store,
                        overlay = PendingOverlay(),
                        config =
                            DaemonConfig(
                                vault = VaultPath("/tmp"),
                                indexDir = Path.of("/tmp/tanseki-hardening-index"),
                                socketPath = Path.of("/tmp/tanseki-hardening.sock")
                            )
                    )
            )
        try {
            val request =
                HttpRequest
                    .newBuilder(URI("http://127.0.0.1:${scoped.port}/v1/metrics"))
                    .timeout(Duration.ofSeconds(5))
                    .header(HEADER_API_KEY, apiKey)
                    .GET()
                    .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode())
            val body = Json.parseToJsonElement(response.body()).jsonObject
            assertEquals("0", body["currentDocuments"]!!.jsonPrimitive.content)
            assertEquals("0", body["currentEdges"]!!.jsonPrimitive.content)
            assertEquals(
                listOf("absent"),
                body["scope"]!!.jsonObject["collections"]!!.jsonArray.map { it.jsonPrimitive.content }
            )
        } finally {
            scoped.close()
        }
    }

    @Test
    fun `a single document read is bounded by the content limit`() {
        val store = InMemoryContextStore()
        val reading =
            StoreApiServer.startEphemeral(
                facade = facade(LuceneLookup(), store),
                apiKey = apiKey
            )
        try {
            val withinBound = "bounded/doc"
            seed(store, withinBound, "c".repeat(MAX_CONTENT_BYTES.toInt()))
            val accepted =
                sendTo(reading.port, "POST", "/v1/documents:upsert", """{"id":"$withinBound","content":"small"}""")
            assertEquals(200, accepted.statusCode(), accepted.body())

            val readable = sendTo(reading.port, "POST", "/v1/documents:get", """{"id":"$withinBound"}""")
            assertEquals(200, readable.statusCode(), readable.body())

            // Written past the seam, as another writer or a transferred vault would leave it.
            val oversized = "oversized/doc"
            seed(store, oversized, "x".repeat(MAX_CONTENT_BYTES.toInt() + 1))
            val refused = sendTo(reading.port, "POST", "/v1/documents:get", """{"id":"$oversized"}""")
            assertEquals(413, refused.statusCode(), refused.body())
            assertEquals("content_too_large", errorCode(refused), refused.body())
            assertFalse("x".repeat(1_024) in refused.body(), refused.body())
        } finally {
            reading.close()
        }
    }

    /**
     * The provider that cannot scope itself: the API layer has to narrow the
     * snapshot it is handed, edges included, so a collection-filtered read never
     * answers with another collection's graph.
     */
    @Test
    fun `a collection filter narrows the edge count of a provider that cannot scope itself`() {
        val store = InMemoryContextStore()
        store.write(seedDocument("alpha/doc", "alpha", "links to [[beta/doc]]"), "seed", "tester")
        store.write(seedDocument("beta/doc", "beta", "links to [[alpha/doc]]"), "seed", "tester")
        // Derivation belongs to the write path, so the graph is seeded directly here.
        listOf("alpha/doc" to "beta/doc", "beta/doc" to "alpha/doc").forEach { (src, dst) ->
            store.upsertEdge(Edge(DocId(src), DocId(dst), RelType("links-to")))
        }
        val server =
            StoreApiServer.startEphemeral(
                facade = facade(LuceneLookup(), store),
                apiKey = apiKey,
                metrics = FixedMetrics(documents = 2, edges = 2)
            )
        try {
            val alpha =
                Json
                    .parseToJsonElement(
                        sendTo(server.port, "GET", "/v1/metrics?collection=alpha", null).body()
                    ).jsonObject
            assertEquals("1", alpha["currentDocuments"]!!.jsonPrimitive.content, alpha.toString())
            assertEquals("1", alpha["currentEdges"]!!.jsonPrimitive.content, alpha.toString())
            val breakdown = alpha["collections"]!!.jsonArray.single().jsonObject
            assertEquals("alpha", breakdown["collection"]!!.jsonPrimitive.content)
            assertEquals("1", breakdown["currentEdges"]!!.jsonPrimitive.content, breakdown.toString())
            assertEquals(
                listOf("alpha"),
                alpha["scope"]!!.jsonObject["collections"]!!.jsonArray.map { it.jsonPrimitive.content }
            )
        } finally {
            server.close()
        }
    }

    private fun seedDocument(
        id: String,
        collection: String,
        content: String
    ) =
        Document(
            id = DocId(id),
            collection =
                gokorei.tanseki.core.domain
                    .Collection(collection),
            path = "$id.md",
            content = content,
            contentHash = "hash-$id",
            revision =
                gokorei.tanseki.core.domain
                    .RevisionId("seed-1"),
            updatedAt = kotlin.time.Instant.fromEpochSeconds(0)
        )

    /** A provider with no scoped view of its own: it only ever reports totals. */
    private class FixedMetrics(
        private val documents: Int,
        private val edges: Int
    ) : MetricsRecorder {
        override fun metrics(): MetricsResponse =
            MetricsResponse(
                collectedAt = "1970-01-01T00:00:00Z",
                currentDocuments = documents,
                currentEdges = edges,
                dependencies = DependencyMetrics("available", "available", "available"),
                projection = ProjectionMetrics(state = "fresh"),
                pendingOverlay = PendingOverlayMetrics(0, 0),
                outbox = OutboxMetrics(0),
                reconcile =
                    ReconcileMetrics(
                        0,
                        0,
                        0,
                        "not_run",
                        readFailures = 0,
                        projectionAttempted = 0,
                        projectionCompleted = 0,
                        projectionFailed = 0
                    ),
                watcher = WatcherMetrics("not_configured"),
                failedEvents = 0,
                requests = LatencyMetrics(0, 0, 0, 0, maxLatencyMillis = 0),
                indexing = LatencyMetrics(0, 0, 0, 0, maxLatencyMillis = 0),
                ready = true,
                degraded = false
            )

        override fun recordRequest(durationNanos: Long, statusCode: Int, error: String?) = Unit

        override fun readiness(): ReadinessResponse = throw UnsupportedOperationException("not used")
    }

    private fun seed(
        store: InMemoryContextStore,
        id: String,
        content: String
    ) {
        store.write(
            Document(
                id = DocId(id),
                collection =
                    gokorei.tanseki.core.domain
                        .Collection("vault"),
                path = "$id.md",
                content = content,
                contentHash = "hash-$id",
                revision =
                    gokorei.tanseki.core.domain
                        .RevisionId("seed-1"),
                updatedAt = kotlin.time.Instant.fromEpochSeconds(0)
            ),
            "seed",
            "tester"
        )
    }

    private fun sendTo(
        port: Int,
        method: String,
        path: String,
        body: String?
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header(HEADER_API_KEY, apiKey)
        builder.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        )
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun errorCode(response: HttpResponse<String>): String? =
        Json
            .parseToJsonElement(response.body())
            .jsonObject["error"]
            ?.jsonObject
            ?.get("code")
            ?.jsonPrimitive
            ?.content

    private fun facade(
        lookup: Lookup,
        store: ContextStore = InMemoryContextStore()
    ): QueryFacade =
        QueryFacade(
            store = store,
            lookup = lookup,
            clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
            io = Dispatchers.Unconfined
        )

    private fun get(
        path: String,
        authenticated: Boolean = true,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<String> = send("GET", path, null, authenticated, headers)

    private fun post(path: String, body: String): HttpResponse<String> = send("POST", path, body)

    private fun send(
        method: String,
        path: String,
        body: String? = null,
        authenticated: Boolean = true,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
        if (authenticated) builder.header(HEADER_API_KEY, apiKey)
        headers.forEach { (key, value) -> builder.header(key, value) }
        builder.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        )
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private object ExplodingLookup : Lookup {
        override fun index(doc: Document, edges: List<Edge>) = Unit

        override fun remove(id: DocId) = Unit

        override fun searchText(
            q: String,
            filters: Filters,
            limit: Int
        ): List<Hit> = throw boom()

        override fun searchVector(
            vector: FloatArray,
            filters: Filters,
            limit: Int
        ): List<Hit> = throw boom()

        override fun traverse(
            id: DocId,
            rel: RelType,
            depth: Int
        ): List<DocId> = throw boom()

        override fun rebuild(store: ContextStore) = Unit

        private fun boom(): Nothing =
            throw IllegalStateException(
                "$BACKEND_SECRET: cannot reach $BACKEND_ENDPOINT (pijul exited with 128, vault at /Users/someone/vault)"
            )
    }

    private companion object {
        const val BACKEND_SECRET = "s3cr3t-token-abcdef"
        const val BACKEND_ENDPOINT = "postgres://tanseki:hunter2@db.internal:5432/tanseki"
    }
}
