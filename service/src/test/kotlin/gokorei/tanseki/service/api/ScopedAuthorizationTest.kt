package gokorei.tanseki.service.api

import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.core.application.PendingOverlay
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.service.daemon.DaemonConfig
import gokorei.tanseki.service.daemon.OperationalMetrics
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScopedAuthorizationTest {
    private val alphaKey = "alpha-key"
    private val betaKey = "beta-key"
    private val wideKey = "wide-key"
    private val metricsDeniedKey = "metrics-denied-key"
    private val http: HttpClient = HttpClient.newHttpClient()
    private var port = 0
    private var server: RunningApiServer? = null
    private lateinit var store: InMemoryContextStore

    @BeforeAll
    fun startServer() {
        val store = InMemoryContextStore()
        this.store = store
        val facade =
            QueryFacade(
                store = store,
                lookup = LuceneLookup(),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                io = Dispatchers.Unconfined
            )
        val registry =
            CredentialRegistry.of(
                listOf(
                    ApiCredential(alphaKey, "alpha", setOf("alpha")),
                    ApiCredential(betaKey, "beta", setOf("beta")),
                    ApiCredential(
                        metricsDeniedKey,
                        "metrics-denied",
                        setOf("alpha"),
                        setOf(ApiOperation.GET)
                    ),
                    ApiCredential(
                        wideKey,
                        "wide",
                        setOf("alpha", "beta"),
                        setOf(ApiOperation.METRICS)
                    )
                )
            )
        val metrics =
            OperationalMetrics(
                store = store,
                overlay = PendingOverlay(),
                config =
                    DaemonConfig(
                        vault = VaultPath("/tmp"),
                        indexDir = Path.of("/tmp/tanseki-scoped-metrics-index"),
                        socketPath = Path.of("/tmp/tanseki-scoped-metrics.sock")
                    )
            )
        val running =
            StoreApiServer.startEphemeral(
                facade = facade,
                authPolicy = ApiAuthPolicy.forRegistry(registry),
                metrics = metrics
            )
        server = running
        port = running.port

        var ready = false
        repeat(100) {
            if (!ready) {
                runCatching {
                    if (send("GET", "/v1/health").statusCode() == 200) ready = true
                }
                if (!ready) Thread.sleep(50)
            }
        }
        check(ready) { "server did not become ready" }
        assertEquals(
            200,
            post(
                "/v1/documents:upsert",
                """{"id":"a/source","collection":"alpha","content":"links to [[b/target]]"}""",
                alphaKey
            ).statusCode()
        )
        assertEquals(
            200,
            post(
                "/v1/documents:upsert",
                """{"id":"b/target","collection":"beta","content":"scope-secret"}""",
                betaKey
            ).statusCode()
        )
    }

    @AfterAll
    fun stopServer() {
        server?.close()
    }

    @Test
    fun `metrics use explicit operation and collection authorization`() {
        val alpha = json(get("/v1/metrics", alphaKey).body())
        val beta = json(get("/v1/metrics", betaKey).body())
        assertEquals(200, send("GET", "/v1/metrics", key = alphaKey).statusCode())
        assertEquals(200, send("GET", "/v1/metrics", key = betaKey).statusCode())
        assertEquals(401, send("GET", "/v1/metrics").statusCode())
        assertEquals(403, send("GET", "/v1/metrics", key = metricsDeniedKey).statusCode())
        assertEquals(404, get("/v1/metrics?collection=beta", alphaKey).statusCode())
        assertEquals(
            listOf("alpha"),
            alpha["scope"]!!.jsonObject["collections"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("beta"),
            beta["scope"]!!.jsonObject["collections"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals("true", alpha["scope"]!!.jsonObject["collectionFiltered"]!!.jsonPrimitive.content)
    }

    @Test
    fun `health remains public while every document operation enforces collection scope`() {
        assertEquals(200, send("GET", "/v1/health").statusCode())
        assertEquals(404, post("/v1/documents:get", """{"id":"b/target"}""", alphaKey).statusCode())
        assertEquals(404, get("/v1/documents?collection=beta", alphaKey).statusCode())
        val query =
            post(
                "/v1/documents:query",
                """{"ids":["a/source","b/target"]}""",
                alphaKey
            )
        assertEquals(200, query.statusCode())
        assertEquals(
            listOf("a/source"),
            json(query.body())["documents"]!!
                .jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        )
        assertEquals(404, post("/v1/documents:history", """{"id":"b/target"}""", alphaKey).statusCode())
        assertEquals(404, get("/v1/search?q=scope-secret&collection=beta", alphaKey).statusCode())
        assertTrue(
            json(
                post(
                    "/v1/documents:traverse",
                    """{"id":"a/source","depth":2}""",
                    alphaKey
                ).body()
            )["ids"]!!
                .jsonArray
                .isEmpty()
        )
        assertEquals(
            404,
            post(
                "/v1/documents:upsert",
                """{"id":"b/new","collection":"beta","content":"no"}""",
                alphaKey
            ).statusCode()
        )
        assertEquals(
            404,
            post(
                "/v1/documents:delete",
                """{"id":"b/target","collection":"beta"}""",
                alphaKey
            ).statusCode()
        )
        assertEquals(
            200,
            post(
                "/v1/documents:get",
                """{"id":"b/target","collection":"beta"}""",
                betaKey
            ).statusCode()
        )
    }

    @Test
    fun `list and search without a collection only return the principal scope`() {
        val listResponse = get("/v1/documents", alphaKey)
        assertEquals(200, listResponse.statusCode())
        val list = json(listResponse.body())
        assertEquals(
            listOf("a/source"),
            list["documents"]!!
                .jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        )

        val search = json(get("/v1/search?q=scope-secret", alphaKey).body())
        assertTrue(search["hits"]!!.jsonArray.isEmpty())
        assertEquals(200, get("/v1/search?q=scope-secret", betaKey).statusCode())
    }

    @Test
    fun `deleted history remains collection scoped`() {
        val id = "alpha/deleted"
        assertEquals(
            200,
            post(
                "/v1/documents:upsert",
                """{"id":"$id","collection":"alpha","content":"temporary"}""",
                alphaKey
            ).statusCode()
        )
        assertEquals(
            200,
            post(
                "/v1/documents:delete",
                """{"id":"$id","collection":"alpha"}""",
                alphaKey
            ).statusCode()
        )

        assertEquals(200, post("/v1/documents:history", """{"id":"$id","collection":"alpha"}""", alphaKey).statusCode())
        assertEquals(404, post("/v1/documents:history", """{"id":"$id","collection":"alpha"}""", betaKey).statusCode())
    }

    @Test
    fun `tombstone ownership prevents cross-collection resurrection`() {
        val id = "alpha/tombstone-owned"
        assertEquals(
            200,
            post(
                "/v1/documents:upsert",
                """{"id":"$id","collection":"alpha","content":"original"}""",
                alphaKey
            ).statusCode()
        )
        assertEquals(200, post("/v1/documents:delete", """{"id":"$id","collection":"alpha"}""", alphaKey).statusCode())
        assertEquals(
            404,
            post(
                "/v1/documents:upsert",
                """{"id":"$id","collection":"beta","content":"hijack"}""",
                betaKey
            ).statusCode()
        )
        assertEquals(
            200,
            post("/v1/documents:upsert", """{"id":"$id","content":"resurrected"}""", alphaKey).statusCode()
        )
        assertEquals(200, post("/v1/documents:history", """{"id":"$id"}""", alphaKey).statusCode())
        assertEquals(404, post("/v1/documents:history", """{"id":"$id","collection":"beta"}""", betaKey).statusCode())
    }

    @Test
    fun `trash listing and restore respect collection scope`() {
        val alphaId = "alpha/trash-scoped"
        val betaId = "beta/trash-scoped"
        assertEquals(
            200,
            post(
                "/v1/documents:upsert",
                """{"id":"$alphaId","collection":"alpha","content":"a"}""",
                alphaKey
            ).statusCode()
        )
        assertEquals(
            200,
            post(
                "/v1/documents:upsert",
                """{"id":"$betaId","collection":"beta","content":"b"}""",
                betaKey
            ).statusCode()
        )
        assertEquals(
            200,
            post("/v1/documents:delete", """{"id":"$alphaId","collection":"alpha"}""", alphaKey).statusCode()
        )
        assertEquals(
            200,
            post("/v1/documents:delete", """{"id":"$betaId","collection":"beta"}""", betaKey).statusCode()
        )

        // Alpha's trash listing must not disclose that beta has a tombstone at all.
        val alphaTrash = json(post("/v1/documents:deleted", """{"collection":"alpha"}""", alphaKey).body())
        val alphaIds = alphaTrash["documents"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(alphaIds.contains(alphaId))
        assertFalse(alphaIds.contains(betaId), "beta tombstone leaked into alpha's trash listing")

        // Restoring a tombstone outside your scope is a 404, not a 403: a
        // credential must not be able to probe for deleted documents it cannot read.
        assertEquals(
            404,
            post("/v1/documents:restore", """{"id":"$betaId","collection":"beta"}""", alphaKey).statusCode()
        )
        // And the refusal must not have revived it.
        assertEquals(404, post("/v1/documents:get", """{"id":"$betaId","collection":"beta"}""", betaKey).statusCode())
        // Alpha can still restore its own.
        assertEquals(
            200,
            post("/v1/documents:restore", """{"id":"$alphaId","collection":"alpha"}""", alphaKey).statusCode()
        )
    }

    @Test
    fun `metrics counts are scoped to the authenticated credential`() {
        val alphaMetrics = json(get("/v1/metrics", alphaKey).body())
        val betaMetrics = json(get("/v1/metrics", betaKey).body())
        val alphaList = json(get("/v1/documents", alphaKey).body())
        val betaList = json(get("/v1/documents", betaKey).body())
        assertTrue(
            alphaMetrics["currentDocuments"]!!.jsonPrimitive.content.toInt() <=
                alphaList["total"]!!.jsonPrimitive.content.toInt()
        )
        assertTrue(
            betaMetrics["currentDocuments"]!!.jsonPrimitive.content.toInt() <=
                betaList["total"]!!.jsonPrimitive.content.toInt()
        )
        assertTrue(alphaMetrics["failureReasons"]!!.jsonArray.none { it.jsonPrimitive.content.contains("target") })
        assertTrue(betaMetrics["failureReasons"]!!.jsonArray.none { it.jsonPrimitive.content.contains("source") })
    }

    /**
     * A `?collection=` filter has to narrow everything the store can attribute
     * to that collection, not only the document count: the credential below
     * may read both collections, so a scoped read that answered with the
     * credential's whole scope would still look plausible.
     */
    @Test
    fun `a collection filter narrows edges, the outbox, and the breakdown`() {
        assertEquals(
            200,
            post(
                "/v1/documents:upsert",
                """{"id":"b/link","collection":"beta","content":"links to [[a/source]]"}""",
                betaKey
            ).statusCode()
        )
        val beta = store.read(DocId("b/target"))!!
        // An undelivered projection for a beta document, so the outbox differs per collection.
        store
            .projectionOperations()
            ?.enqueue(ProjectionOperation.upsert(beta, beta.revision, beta.updatedAt, beta.contentHash))

        val alpha = json(get("/v1/metrics?collection=alpha", wideKey).body())
        assertEquals("1", alpha["currentDocuments"]!!.jsonPrimitive.content)
        assertEquals("1", alpha["currentEdges"]!!.jsonPrimitive.content, alpha.toString())
        assertEquals("0", alpha["outbox"]!!.jsonObject["pending"]!!.jsonPrimitive.content, alpha.toString())
        assertEquals(
            listOf("alpha"),
            alpha["scope"]!!.jsonObject["collections"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        val alphaBreakdown = alpha["collections"]!!.jsonArray.single().jsonObject
        assertEquals("alpha", alphaBreakdown["collection"]!!.jsonPrimitive.content)
        assertEquals("1", alphaBreakdown["currentDocuments"]!!.jsonPrimitive.content)
        assertEquals("1", alphaBreakdown["currentEdges"]!!.jsonPrimitive.content, alphaBreakdown.toString())

        val betaScoped = json(get("/v1/metrics?collection=beta", wideKey).body())
        assertEquals("2", betaScoped["currentDocuments"]!!.jsonPrimitive.content, betaScoped.toString())
        assertEquals("1", betaScoped["currentEdges"]!!.jsonPrimitive.content, betaScoped.toString())
        assertEquals("1", betaScoped["outbox"]!!.jsonObject["pending"]!!.jsonPrimitive.content, betaScoped.toString())

        val both = json(get("/v1/metrics", wideKey).body())
        assertEquals("3", both["currentDocuments"]!!.jsonPrimitive.content, both.toString())
        assertEquals("2", both["currentEdges"]!!.jsonPrimitive.content, both.toString())
        assertEquals("1", both["outbox"]!!.jsonObject["pending"]!!.jsonPrimitive.content, both.toString())
        assertEquals(
            setOf("alpha", "beta"),
            both["collections"]!!
                .jsonArray
                .map { it.jsonObject["collection"]!!.jsonPrimitive.content }
                .toSet()
        )
    }

    @Test
    fun `invalid request errors use a stable public envelope`() {
        val response = post("/v1/documents:upsert", "{", alphaKey)
        assertEquals(400, response.statusCode())
        val error = json(response.body())["error"]!!.jsonObject
        // `code` and the envelope shape are the contract a client branches on, and
        // both are unchanged. The message is not: it used to be the literal
        // "invalid request" for every 400, which told a caller nothing about which
        // of its fields was wrong. It now carries the reason, which is why the
        // assertion below is that a reason arrived rather than which one.
        assertEquals("invalid_argument", error["code"]!!.jsonPrimitive.content)
        assertNull(error["detail"])
        val message = error["message"]!!.jsonPrimitive.content
        assertNotEquals("invalid request", message)
        assertTrue(message.isNotBlank(), "a reason must say something")
    }

    private fun send(method: String, path: String, body: String? = null, key: String? = null): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
        key?.let { builder.header(HEADER_API_KEY, it) }
        val bodyPublisher =
            body?.let { value ->
                HttpRequest.BodyPublishers.ofString(value)
            } ?: HttpRequest.BodyPublishers.noBody()
        builder.method(method, bodyPublisher)
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String, key: String) = send("GET", path, key = key)

    private fun post(path: String, body: String, key: String) = send("POST", path, body, key)

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
