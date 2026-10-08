package gokorei.tanseki.service.api

import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.core.application.ChangeFeed
import gokorei.tanseki.core.application.ChangeKind
import gokorei.tanseki.core.application.PendingOverlay
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.RequestLimits
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.LogLevel
import gokorei.tanseki.service.daemon.DaemonConfig
import gokorei.tanseki.service.daemon.OperationalMetrics
import gokorei.tanseki.service.logging.JsonStructuredLogger
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreApiTest {
    private val apiKey = "secret"
    private val http: HttpClient = HttpClient.newHttpClient()
    private val logs = mutableListOf<String>()
    private var port: Int = 0
    private var server: RunningApiServer? = null
    private lateinit var metrics: OperationalMetrics
    private lateinit var store: InMemoryContextStore
    private lateinit var changeFeed: ChangeFeed

    @BeforeAll
    fun startServer() {
        store = InMemoryContextStore()
        changeFeed = ChangeFeed()
        val facade =
            QueryFacade(
                store = store,
                lookup = LuceneLookup(),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                io = Dispatchers.Unconfined,
                changeFeed = changeFeed
            )
        metrics =
            OperationalMetrics(
                store = store,
                overlay = PendingOverlay(),
                config =
                    DaemonConfig(
                        vault = VaultPath("/tmp"),
                        indexDir = Path.of("/tmp/tanseki-metrics-index"),
                        socketPath = Path.of("/tmp/tanseki-metrics.sock")
                    )
            )
        val running =
            StoreApiServer.startEphemeral(
                facade,
                apiKey,
                logger = JsonStructuredLogger(logs::add, LogLevel.DEBUG),
                metrics = metrics,
                changeFeed = changeFeed
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
        if (authenticated) builder.header("X-API-Key", apiKey)
        headers.forEach { (key, value) -> builder.header(key, value) }
        builder.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        )
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String) = send("GET", path)

    /** Posts raw bytes, which the attachment route reads verbatim. */
    private fun upload(
        path: String,
        bytes: ByteArray,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(15))
                .header("X-API-Key", apiKey)
        headers.forEach { (key, value) -> builder.header(key, value) }
        builder.header("Content-Type", "application/octet-stream")
        builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun download(path: String): HttpResponse<ByteArray> {
        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(5))
                .header("X-API-Key", apiKey)
                .GET()
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun post(path: String, body: String, headers: Map<String, String> = emptyMap()) =
        send(
            "POST",
            path,
            body,
            headers = headers
        )

    private fun upsert(body: String, headers: Map<String, String> = emptyMap()) =
        post(
            "/v1/documents:upsert",
            body,
            headers
        )

    /**
     * Opens the event stream and reads it on a background thread.
     *
     * An SSE stream does not end, so the response cannot be read to EOF: it has
     * to be sampled while a test drives writes through the API. That also means
     * assertions wait for a condition with a deadline rather than reading a
     * fixed number of lines, since how many arrive depends on timing.
     */
    private fun eventProbe(path: String): EventProbe {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 0
        socket.getOutputStream().bufferedWriter().apply {
            write("GET $path HTTP/1.1\r\n")
            write("Host: 127.0.0.1:$port\r\n")
            write("X-API-Key: $apiKey\r\n")
            write("Accept: text/event-stream\r\n")
            write("\r\n")
            flush()
        }
        val received = java.util.concurrent.CopyOnWriteArrayList<String>()
        val reader =
            Thread {
                runCatching {
                    socket.getInputStream().bufferedReader().forEachLine { received += it }
                }
            }.apply {
                isDaemon = true
                start()
            }
        // A publish that happens before the server registers the subscription is
        // delivered to nobody: a fresh subscriber is owed the present, not the
        // retained window. Waiting for the feed to report the new subscriber
        // removes that race instead of hoping the request wins it. The deadline
        // is generous on purpose: on a saturated CI runner the server thread can
        // stall for seconds while other modules compile in parallel, and a
        // registration timeout is otherwise indistinguishable from a dead feed.
        val baseline = changeFeed.subscriberCount()
        val deadline = System.currentTimeMillis() + 30_000
        while (changeFeed.subscriberCount() <= baseline && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(
            changeFeed.subscriberCount() > baseline,
            "the subscription should be registered before the test publishes anything"
        )
        return EventProbe(socket, reader, received)
    }

    /** A connected event stream, read in the background. */
    private class EventProbe(
        private val socket: Socket,
        private val reader: Thread,
        private val received: List<String>
    ) : AutoCloseable {
        fun lines(): List<String> = received.toList()

        /** Waits for a line satisfying [predicate], or null if it never arrives. */
        fun awaitFor(timeoutMillis: Long = 5_000, predicate: (String) -> Boolean): String? {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                received.firstOrNull(predicate)?.let { return it }
                Thread.sleep(25)
            }
            return received.firstOrNull(predicate)
        }

        override fun close() {
            runCatching { socket.close() }
            runCatching { reader.join(500) }
        }
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `health is unauthenticated`() {
        val response = send("GET", "/v1/health", authenticated = false)
        assertEquals(200, response.statusCode())
        assertEquals("ok", json(response.body())["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `liveness stays healthy while readiness reports degraded dependencies`() {
        val live = send("GET", "/v1/live", authenticated = false)
        assertEquals(200, live.statusCode())
        assertEquals("ok", json(live.body())["status"]!!.jsonPrimitive.content)

        metrics.markWatcherNotApplicable()
        metrics.markProjectionCompleted()
        val ready = send("GET", "/v1/ready", authenticated = false)
        assertEquals(200, ready.statusCode())
        assertEquals("ready", json(ready.body())["status"]!!.jsonPrimitive.content)
        // An unauthenticated readiness must not count documents by scanning the
        // store: counts come from an authenticated /v1/metrics call.
        assertEquals("0", json(ready.body())["currentDocuments"]!!.jsonPrimitive.content)
        assertEquals("0", json(ready.body())["currentEdges"]!!.jsonPrimitive.content)

        metrics.markDegraded("dependency_unavailable")
        val degraded = send("GET", "/v1/ready", authenticated = false)
        assertEquals(503, degraded.statusCode())
        assertEquals("degraded", json(degraded.body())["status"]!!.jsonPrimitive.content)
        assertTrue(
            json(degraded.body())["failureReasons"]!!
                .jsonArray
                .any { it.jsonPrimitive.content == "dependency_unavailable" }
        )
        assertEquals(200, send("GET", "/v1/health", authenticated = false).statusCode())
        metrics.clearDegraded("dependency_unavailable")
    }

    @Test
    fun `request id is threaded through successful and failed structured logs`() {
        val successId = "api-success-123"
        val failureId = "api-failure-456"
        assertEquals(
            200,
            send(
                "GET",
                "/v1/health",
                authenticated = false,
                headers = mapOf(HEADER_REQUEST_ID to successId)
            ).statusCode()
        )
        assertEquals(
            401,
            send(
                "GET",
                "/v1/metrics",
                authenticated = false,
                headers = mapOf(HEADER_REQUEST_ID to failureId)
            ).statusCode()
        )
        val records = logs.map { Json.parseToJsonElement(it).jsonObject }
        assertTrue(
            records.any {
                it["message"]?.jsonPrimitive?.content == "api request completed" &&
                    it["requestId"]?.jsonPrimitive?.content == successId &&
                    it["correlationId"]?.jsonPrimitive?.content == successId
            }
        )
        assertTrue(
            records.any {
                it["message"]?.jsonPrimitive?.content == "api request failed" &&
                    it["requestId"]?.jsonPrimitive?.content == failureId &&
                    it["correlationId"]?.jsonPrimitive?.content == failureId
            }
        )
    }

    @Test
    fun `metrics are protected and expose the operational contract`() {
        assertEquals(401, send("GET", "/v1/metrics", authenticated = false).statusCode())
        val response = get("/v1/metrics")
        assertEquals(200, response.statusCode())
        val body = json(response.body())
        assertEquals("1", body["schemaVersion"]!!.jsonPrimitive.content)
        assertTrue(body["currentDocuments"]!!.jsonPrimitive.content.toInt() >= 0)
        assertTrue(body["currentEdges"]!!.jsonPrimitive.content.toInt() >= 0)
        assertTrue(
            setOf("fresh", "stale").contains(body["projection"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        )
        assertEquals("0", body["pendingOverlay"]!!.jsonObject["size"]!!.jsonPrimitive.content)
        assertEquals("0", body["outbox"]!!.jsonObject["pending"]!!.jsonPrimitive.content)
        val requestAttempts =
            body["requests"]!!
                .jsonObject["attempts"]!!
                .jsonPrimitive.content
                .toLong()
        val requestErrors =
            body["requests"]!!
                .jsonObject["errors"]!!
                .jsonPrimitive.content
                .toLong()
        assertTrue(requestAttempts > 0)
        assertTrue(requestErrors > 0)
        assertEquals("0", body["indexing"]!!.jsonObject["errors"]!!.jsonPrimitive.content)
    }

    @Test
    fun `documents require the api key and return an error envelope`() {
        val response = send("POST", "/v1/documents:get", """{"id":"a"}""", authenticated = false)
        assertEquals(401, response.statusCode())
        assertEquals("unauthenticated", json(response.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `documents reject invalid api keys with the documented envelope`() {
        val invalid =
            send(
                "POST",
                "/v1/documents:get",
                """{"id":"a"}""",
                authenticated = false,
                headers = mapOf(HEADER_API_KEY to "invalid")
            )
        assertEquals(401, invalid.statusCode())
        assertEquals("unauthenticated", json(invalid.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `upsert creates then updates idempotently and round-trips frontmatter`() {
        // ids are path-derived with slashes — addressed in the body, not the path
        val id = "org/repo/pr-42/e1"
        val payload =
            """{"id":"$id","content":"Because X.","frontmatter":{"title":"Why?","author":"dev","tags":["design"],"repo":"org/repo","pr":"42"}}"""
        val first = upsert(payload)
        assertEquals(200, first.statusCode())
        val firstBody = json(first.body())
        assertEquals("true", firstBody["created"]!!.jsonPrimitive.content)

        val fetched = post("/v1/documents:get", """{"id":"$id","collection":"vault"}""")
        assertEquals(200, fetched.statusCode())
        val document = json(fetched.body())
        assertTrue(document["content"]!!.jsonPrimitive.content.contains("Because X."))
        val frontmatter = document["frontmatter"]!!.jsonObject
        assertEquals("org/repo", frontmatter["repo"]!!.jsonPrimitive.content)
        assertEquals("42", frontmatter["pr"]!!.jsonPrimitive.content)

        val second = upsert(payload)
        assertEquals("false", json(second.body())["created"]!!.jsonPrimitive.content)
        assertEquals(firstBody["revision"], json(second.body())["revision"])
    }

    @Test
    fun `batch query, list pagination and history envelopes`() {
        upsert("""{"id":"p/a","content":"alpha"}""")
        upsert("""{"id":"p/b","content":"beta"}""")
        upsert("""{"id":"p/c","content":"gamma"}""")

        val query = post("/v1/documents:query", """{"ids":["p/a","p/b","missing"]}""")
        assertEquals(2, json(query.body())["documents"]!!.jsonArray.size)

        val listBody = json(get("/v1/documents?limit=2&offset=0").body())
        assertEquals(2, listBody["documents"]!!.jsonArray.size)
        assertTrue(listBody["total"]!!.jsonPrimitive.content.toInt() >= 3)
        assertEquals("true", listBody["hasMore"]!!.jsonPrimitive.content)

        val page2 = json(get("/v1/documents?limit=2&offset=2").body())
        assertTrue(page2["documents"]!!.jsonArray.isNotEmpty())

        val historyBody = json(post("/v1/documents:history", """{"id":"p/a"}""").body())
        assertTrue(historyBody["revisions"]!!.jsonArray.isNotEmpty())
        assertEquals("1", historyBody["total"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete then restore returns a document to every read surface`() {
        upsert("""{"id":"trash/note","content":"links to [[trash/other]]"}""")
        upsert("""{"id":"trash/other","content":"target"}""")
        assertTrue(json(get("/v1/search?q=target").body())["hits"]!!.jsonArray.isNotEmpty())

        post("/v1/documents:delete", """{"id":"trash/note"}""")

        // Gone from listing, search, and get.
        assertFalse(
            json(get("/v1/documents?limit=50&pathPrefix=trash%2F").body())["documents"]!!
                .jsonArray
                .any { it.jsonObject["id"]!!.jsonPrimitive.content == "trash/note" }
        )
        assertTrue(
            json(get("/v1/search?q=target").body())["hits"]!!
                .jsonArray
                .none { it.jsonObject["id"]!!.jsonPrimitive.content == "trash/note" }
        )

        val restore = post("/v1/documents:restore", """{"id":"trash/note"}""")
        assertEquals(200, restore.statusCode())
        assertEquals("trash/note", json(restore.body())["id"]!!.jsonPrimitive.content)
        assertTrue(json(restore.body())["created"]!!.jsonPrimitive.content.toBoolean())

        // Back in listing and search, not just in the store.
        assertTrue(
            json(get("/v1/documents?limit=50&pathPrefix=trash%2F").body())["documents"]!!
                .jsonArray
                .any { it.jsonObject["id"]!!.jsonPrimitive.content == "trash/note" }
        )
        assertTrue(
            json(get("/v1/search?q=links").body())["hits"]!!
                .jsonArray
                .any { it.jsonObject["id"]!!.jsonPrimitive.content == "trash/note" }
        )
    }

    @Test
    fun `documents deleted lists tombstones with their deletion metadata`() {
        upsert("""{"id":"gone/one","collection":"trash-probe","content":"a"}""")
        upsert("""{"id":"gone/two","collection":"trash-probe","content":"b"}""")
        upsert("""{"id":"gone/live","collection":"trash-probe","content":"c"}""")
        post("/v1/documents:delete", """{"id":"gone/one","collection":"trash-probe"}""")
        post("/v1/documents:delete", """{"id":"gone/two","collection":"trash-probe"}""")

        val body = json(post("/v1/documents:deleted", """{"collection":"trash-probe"}""").body())
        val ids = body["documents"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()

        assertEquals(setOf("gone/one", "gone/two"), ids, "only tombstones should be listed")
        assertEquals(2, body["total"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `renaming moves the document and reports the new path`() {
        upsert("""{"id":"mv/old","content":"the body"}""")

        val renamed = post("/v1/documents:rename", """{"id":"mv/old","newId":"folder/new"}""")

        assertEquals(200, renamed.statusCode(), renamed.body())
        val body = json(renamed.body())
        assertEquals("folder/new", body["id"]!!.jsonPrimitive.content)
        assertEquals("mv/old", body["previousId"]!!.jsonPrimitive.content)
        assertEquals("folder/new.md", body["path"]!!.jsonPrimitive.content)
        // Reachable at the new id, and gone from the old one: the id IS the path.
        assertEquals(200, post("/v1/documents:get", """{"id":"folder/new"}""").statusCode())
        assertEquals(404, post("/v1/documents:get", """{"id":"mv/old"}""").statusCode())
    }

    @Test
    fun `renaming onto an existing document reports a conflict and changes nothing`() {
        upsert("""{"id":"mv/old","content":"old body"}""")
        upsert("""{"id":"mv/taken","content":"holder body"}""")

        val renamed = post("/v1/documents:rename", """{"id":"mv/old","newId":"mv/taken"}""")

        assertEquals(409, renamed.statusCode(), renamed.body())
        assertEquals("old body", store.read(DocId("mv/old"))!!.content)
        assertEquals("holder body", store.read(DocId("mv/taken"))!!.content)
    }

    @Test
    fun `renaming an unknown or out-of-scope document reports not found`() {
        assertEquals(404, post("/v1/documents:rename", """{"id":"mv/absent","newId":"mv/x"}""").statusCode())
    }

    @Test
    fun `renaming onto the same id is rejected`() {
        upsert("""{"id":"mv/same","content":"body"}""")

        val renamed = post("/v1/documents:rename", """{"id":"mv/same","newId":"mv/same"}""")

        assertEquals(400, renamed.statusCode(), renamed.body())
        assertEquals("body", store.read(DocId("mv/same"))!!.content)
    }

    @Test
    fun `a stale If-Match is refused and nothing moves`() {
        upsert("""{"id":"mv/ifmatch","content":"body"}""")
        val current = store.read(DocId("mv/ifmatch"))!!.revision.value

        val refused =
            post(
                "/v1/documents:rename",
                """{"id":"mv/ifmatch","newId":"mv/ifmatch2","ifRevision":"not-the-current-revision"}"""
            )
        assertEquals(409, refused.statusCode(), refused.body())
        assertEquals("body", store.read(DocId("mv/ifmatch"))!!.content)

        val accepted =
            post(
                "/v1/documents:rename",
                """{"id":"mv/ifmatch","newId":"mv/ifmatch2","ifRevision":"$current"}"""
            )
        assertEquals(200, accepted.statusCode(), accepted.body())
        assertEquals("body", store.read(DocId("mv/ifmatch2"))!!.content)
    }

    @Test
    fun `an Idempotency-Key replays a rename instead of moving twice`() {
        upsert("""{"id":"mv/idem","content":"body"}""")
        val key = mapOf("Idempotency-Key" to "rename-once")

        val first = post("/v1/documents:rename", """{"id":"mv/idem","newId":"mv/idem2"}""", key)
        val replay = post("/v1/documents:rename", """{"id":"mv/idem","newId":"mv/idem2"}""", key)

        assertEquals(200, first.statusCode(), first.body())
        assertEquals(200, replay.statusCode(), replay.body())
        assertEquals(first.body(), replay.body(), "the replay must return the first response verbatim")
        // A second move would have failed: the source is gone. Returning 200 with
        // the same body is only correct if nothing was applied twice.
        assertEquals("body", store.read(DocId("mv/idem2"))!!.content)
    }

    @Test
    fun `reusing an Idempotency-Key for a different rename is a conflict`() {
        upsert("""{"id":"mv/k1","content":"a"}""")
        upsert("""{"id":"mv/k2","content":"b"}""")
        val key = mapOf("Idempotency-Key" to "rename-reused")

        post("/v1/documents:rename", """{"id":"mv/k1","newId":"mv/k1b"}""", key)
        val reused = post("/v1/documents:rename", """{"id":"mv/k2","newId":"mv/k2b"}""", key)

        assertEquals(409, reused.statusCode(), reused.body())
        assertEquals("b", store.read(DocId("mv/k2"))!!.content)
    }

    @Test
    fun `a deleted path cannot be claimed by another document`() {
        upsert("""{"id":"claim/note","content":"original"}""")
        post("/v1/documents:delete", """{"id":"claim/note"}""")
        // Paths derive from ids, so no other id can take the tombstoned path:
        // a divergent path is invalid input rather than a new owner.
        val squat = upsert("""{"id":"claim/squatter","path":"claim/note.md","content":"squatter"}""")

        assertEquals(400, squat.statusCode(), squat.body())

        val restore = post("/v1/documents:restore", """{"id":"claim/note"}""")
        assertEquals(200, restore.statusCode(), restore.body())
        assertEquals("original", store.read(DocId("claim/note"))!!.content)
    }

    @Test
    fun `restoring an already live document is idempotent`() {
        upsert("""{"id":"live/note","content":"body"}""")

        val first = post("/v1/documents:restore", """{"id":"live/note"}""")
        val second = post("/v1/documents:restore", """{"id":"live/note"}""")

        assertEquals(200, first.statusCode())
        assertEquals(200, second.statusCode())
        assertFalse(json(first.body())["created"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(json(second.body())["created"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `restore replays a stored response for a repeated Idempotency-Key`() {
        upsert("""{"id":"idem/note","collection":"idem","content":"body"}""")
        post("/v1/documents:delete", """{"id":"idem/note","collection":"idem"}""")

        val first =
            post(
                "/v1/documents:restore",
                """{"id":"idem/note","collection":"idem"}""",
                headers = mapOf("Idempotency-Key" to "restore-key-1")
            )
        val second =
            post(
                "/v1/documents:restore",
                """{"id":"idem/note","collection":"idem"}""",
                headers = mapOf("Idempotency-Key" to "restore-key-1")
            )

        assertEquals(200, first.statusCode())
        assertEquals(200, second.statusCode())
        // A replay must return the original response, including `created: true`:
        // by the time the second request runs the document is already live, so
        // recomputing the result would wrongly report created=false.
        assertEquals(first.body(), second.body())
        assertTrue(json(second.body())["created"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `restore rejects an Idempotency-Key reused for a different request`() {
        upsert("""{"id":"idem/a","collection":"idem","content":"a"}""")
        upsert("""{"id":"idem/b","collection":"idem","content":"b"}""")
        post("/v1/documents:delete", """{"id":"idem/a","collection":"idem"}""")
        post("/v1/documents:delete", """{"id":"idem/b","collection":"idem"}""")

        post(
            "/v1/documents:restore",
            """{"id":"idem/a","collection":"idem"}""",
            headers = mapOf("Idempotency-Key" to "shared-key")
        )
        val conflict =
            post(
                "/v1/documents:restore",
                """{"id":"idem/b","collection":"idem"}""",
                headers = mapOf("Idempotency-Key" to "shared-key")
            )

        assertEquals(409, conflict.statusCode())
        assertEquals(
            "idempotency_conflict",
            json(conflict.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
        )
        // The second restore must not have happened.
        assertEquals(200, post("/v1/documents:restore", """{"id":"idem/b","collection":"idem"}""").statusCode())
    }

    @Test
    fun `documents deleted omits tombstones outside the requested collection`() {
        upsert("""{"id":"scope/keep","collection":"scope-a","content":"a"}""")
        upsert("""{"id":"scope/hide","collection":"scope-b","content":"b"}""")
        post("/v1/documents:delete", """{"id":"scope/keep","collection":"scope-a"}""")
        post("/v1/documents:delete", """{"id":"scope/hide","collection":"scope-b"}""")

        val body = json(post("/v1/documents:deleted", """{"collection":"scope-a"}""").body())
        val ids = body["documents"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()

        assertEquals(setOf("scope/keep"), ids, "scope-b must not be disclosed to a scope-a listing")
    }

    @Test
    fun `an attachment round-trips byte for byte through upload and download`() {
        val bytes = byteArrayOf(0, 1, 2, -1, -2, 127, -128, 42)

        val uploaded = upload("/v1/blobs", bytes)
        val ref = json(uploaded.body())

        assertEquals(200, uploaded.statusCode())
        assertEquals("sha256", ref["algorithm"]!!.jsonPrimitive.content)
        assertEquals(bytes.size.toLong(), ref["size"]!!.jsonPrimitive.content.toLong())

        val downloaded = download("/v1/blobs/${ref["hash"]!!.jsonPrimitive.content}")
        assertEquals(200, downloaded.statusCode())
        assertArrayEquals(bytes, downloaded.body())
    }

    @Test
    fun `uploading identical bytes twice yields the same reference`() {
        val bytes = "same bytes".toByteArray()

        val first = json(upload("/v1/blobs", bytes).body())
        val second = json(upload("/v1/blobs", bytes).body())

        assertEquals(first, second, "identity is the digest, so the same bytes are the same blob")
    }

    @Test
    fun `an attachment is refused rather than served when its bytes were corrupted`() {
        val uploaded = upload("/v1/blobs", "honest bytes".toByteArray())
        val hash = json(uploaded.body())["hash"]!!.jsonPrimitive.content
        store.corruptBlob(hash)

        val response = download("/v1/blobs/$hash")

        assertEquals(500, response.statusCode())
        assertEquals(
            "blob_corrupt",
            json(String(response.body()))["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `an oversized attachment is refused with the documented error`() {
        // Declared over the ceiling but never sent. The route refuses on the
        // declared Content-Length before reading a byte, so this exercises the
        // guard without streaming 32 MiB. Sending the bytes for real instead
        // makes the test fail intermittently: the server answers 413 and closes
        // while the client is still writing, which surfaces as an EOFException
        // rather than as the status under test.
        val socket = Socket("127.0.0.1", port)
        try {
            socket.soTimeout = 5_000
            val declared = RequestLimits.MAX_ATTACHMENT_BYTES + 1
            socket.getOutputStream().bufferedWriter().apply {
                write(
                    buildString {
                        append("POST /v1/blobs HTTP/1.1\r\n")
                        append("Host: 127.0.0.1:$port\r\n")
                        append("X-API-Key: $apiKey\r\n")
                        append("Content-Type: application/octet-stream\r\n")
                        append("Content-Length: $declared\r\n")
                        append("\r\n")
                    }
                )
                flush()
            }

            val statusLine = socket.getInputStream().bufferedReader().readLine()
            val status = statusLine?.split(' ')?.getOrNull(1)?.toIntOrNull()

            assertEquals(413, status, "expected 413 for a declared length of $declared, got: $statusLine")
        } finally {
            socket.close()
        }
    }

    @Test
    fun `an unknown attachment digest is a not found`() {
        assertEquals(404, download("/v1/blobs/${"0".repeat(64)}").statusCode())
    }

    @Test
    fun `a malformed attachment digest is rejected rather than looked up`() {
        assertEquals(400, download("/v1/blobs/not-a-digest").statusCode())
    }

    @Test
    fun `attachments require authentication`() {
        val builder =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port/v1/blobs/${"0".repeat(64)}"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()

        val response = http.send(builder, HttpResponse.BodyHandlers.ofByteArray())

        assertEquals(401, response.statusCode())
    }

    @Test
    fun `an empty attachment is refused`() {
        assertEquals(400, upload("/v1/blobs", ByteArray(0)).statusCode())
    }

    @Test
    fun `a subscribed client receives an event for an API upsert`() {
        eventProbe("/v1/events").use { probe ->
            assertEquals(200, upsert("""{"id":"feed/one","collection":"vault","content":"live"}""").statusCode())

            val event = probe.awaitFor { it.contains("feed/one") }

            assertNotNull(event, "the upsert should reach the subscriber: ${probe.lines().take(8)}")
            assertTrue(event!!.contains("\"kind\":\"upsert\""), "event kind should be upsert: $event")
            assertTrue(event.contains("\"sequence\""), "event should carry a resumable sequence: $event")
            assertTrue(
                probe.lines().any { Regex("^id: \\d+$").containsMatchIn(it) },
                "the event must be framed with an SSE id for Last-Event-ID: ${probe.lines().take(8)}"
            )
        }
    }

    @Test
    fun `a subscribed client receives an event for a delete`() {
        upsert("""{"id":"feed/gone","collection":"vault","content":"bye"}""")

        eventProbe("/v1/events").use { probe ->
            assertEquals(
                200,
                post("/v1/documents:delete", """{"id":"feed/gone","collection":"vault"}""").statusCode()
            )

            assertNotNull(
                probe.awaitFor { it.contains("\"kind\":\"delete\"") && it.contains("feed/gone") },
                "delete should be announced: ${probe.lines().take(8)}"
            )
        }
    }

    @Test
    fun `a subscriber resuming with Last-Event-ID is replayed what it missed`() {
        var sequence: Long? = null
        eventProbe("/v1/events").use { probe ->
            assertEquals(200, upsert("""{"id":"feed/first","collection":"vault","content":"a"}""").statusCode())
            val seen = probe.awaitFor { it.contains("feed/first") }
            assertNotNull(seen, "first write should be announced: ${probe.lines().take(8)}")
            sequence =
                Regex("id: (\\d+)")
                    .find(seen!!)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
                    ?: Regex("\"sequence\":(\\d+)")
                        .find(seen)
                        ?.groupValues
                        ?.get(1)
                        ?.toLong()
            assertNotNull(sequence, "the event must expose a sequence to resume from: $seen")
        }

        // Published while nobody is listening, so only a replay can deliver it.
        assertEquals(200, upsert("""{"id":"feed/missed","collection":"vault","content":"b"}""").statusCode())

        eventProbe("/v1/events?after=$sequence").use { probe ->
            val replayed = probe.awaitFor { it.contains("feed/missed") }

            assertNotNull(replayed, "a resuming subscriber should be replayed the gap: ${probe.lines().take(8)}")
            assertTrue(
                replayed!!.contains("\"sequence\":${sequence!! + 1}"),
                "replay must resume immediately after the sequence the client had: $replayed"
            )
        }
    }

    @Test
    fun `a subscriber scoped to a collection receives its events and not others`() {
        eventProbe("/v1/events?collection=vault").use { probe ->
            assertEquals(200, upsert("""{"id":"feed/in","collection":"vault","content":"in"}""").statusCode())
            assertEquals(200, upsert("""{"id":"feed/out","collection":"other","content":"out"}""").statusCode())

            assertNotNull(
                probe.awaitFor { it.contains("feed/in") && it.contains("\"collection\":\"vault\"") },
                "in-scope change should arrive"
            )
            assertTrue(
                probe.lines().none { it.contains("feed/out") },
                "out-of-scope change must not arrive: ${probe.lines().take(8)}"
            )
        }
    }

    @Test
    fun `a client may ask to be told to reload rather than to resume`() {
        // This is what a client does after a daemon restart, when its sequence
        // numbers mean nothing. It must be told to reload rather than handed a
        // stream that silently starts from wherever the new process is.
        eventProbe("/v1/events?resync=true").use { probe ->
            assertNotNull(
                probe.awaitFor { it.contains("\"kind\":\"resync\"") },
                "resync=true should announce that a reload is needed: ${probe.lines().take(8)}"
            )
        }
    }

    @Test
    fun `a profile with no change feed says so instead of opening a silent stream`() {
        // A stream that never emits is indistinguishable from a quiet vault, and a
        // client waiting on changes cannot tell the difference. Refusing is the
        // only honest answer.
        val feedless =
            StoreApiServer.startEphemeral(
                QueryFacade(
                    store = InMemoryContextStore(),
                    lookup = LuceneLookup(),
                    clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                    io = Dispatchers.Unconfined
                ),
                apiKey
            )
        try {
            val request =
                HttpRequest
                    .newBuilder(URI("http://127.0.0.1:${feedless.port}/v1/events"))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-API-Key", apiKey)
                    .GET()
                    .build()

            val response = http.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(501, response.statusCode())
            assertEquals(
                "not_implemented",
                json(response.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
            )
        } finally {
            feedless.close()
        }
    }

    @Test
    fun `events require authentication`() {
        val socket = Socket("127.0.0.1", port)
        try {
            socket.soTimeout = 4_000
            socket.getOutputStream().bufferedWriter().apply {
                write("GET /v1/events HTTP/1.1\r\n")
                write("Host: 127.0.0.1:$port\r\n")
                write("\r\n")
                flush()
            }
            val statusLine = socket.getInputStream().bufferedReader().readLine()

            assertTrue(
                statusLine?.contains(" 401") == true,
                "an unauthenticated subscriber should be refused, got: $statusLine"
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `a stalled event reader is detached instead of buffering without bound`() {
        // Opens the stream but never reads a byte of it. The writer's backpressure
        // has to reach the feed, which then detaches the stalled subscriber: before
        // the bridge was bounded the subscription drained the feed forever, so a
        // stalled client could make the daemon accumulate events until it ran out
        // of memory.
        val baseline = changeFeed.subscriberCount()
        val socket = Socket("127.0.0.1", port)
        try {
            socket.soTimeout = 0
            socket.getOutputStream().bufferedWriter().apply {
                write("GET /v1/events HTTP/1.1\r\n")
                write("Host: 127.0.0.1:$port\r\n")
                write("X-API-Key: $apiKey\r\n")
                write("Accept: text/event-stream\r\n")
                write("\r\n")
                flush()
            }

            val subscribedBy = System.currentTimeMillis() + 5_000
            while (changeFeed.subscriberCount() <= baseline && System.currentTimeMillis() < subscribedBy) {
                Thread.sleep(10)
            }
            assertTrue(
                changeFeed.subscriberCount() > baseline,
                "the stream should have subscribed before the burst"
            )

            // Far more than the bridge and the feed's own buffers can hold.
            repeat(50_000) {
                changeFeed.publish(DocId("stall/$it"), Collection("vault"), ChangeKind.UPSERT)
            }

            val detachedBy = System.currentTimeMillis() + 10_000
            while (changeFeed.subscriberCount() > baseline && System.currentTimeMillis() < detachedBy) {
                Thread.sleep(10)
            }
            assertEquals(
                baseline,
                changeFeed.subscriberCount(),
                "a stalled reader must be detached, not buffered without bound"
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `restoring an unknown document is a not found`() {
        val response = post("/v1/documents:restore", """{"id":"never/existed"}""")

        assertEquals(404, response.statusCode())
    }

    @Test
    fun `a stale if-match on restore reports a conflict`() {
        upsert("""{"id":"stale/note","content":"body"}""")
        post("/v1/documents:delete", """{"id":"stale/note"}""")

        val response =
            post(
                "/v1/documents:restore",
                """{"id":"stale/note","ifRevision":"not-the-deletion-revision"}""",
                headers = mapOf("If-Match" to "\"not-the-deletion-revision\"")
            )

        assertTrue(response.statusCode() == 409 || response.statusCode() == 412, "was ${response.statusCode()}")
    }

    @Test
    fun `a cursor walks the whole listing without repeating or skipping`() {
        (0 until 12).forEach { index ->
            val id = "c/%02d".format(index)
            upsert("""{"id":"$id","content":"body $index"}""")
        }

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        while (pages++ < 20) {
            val query =
                if (cursor == null) {
                    "?limit=5&pathPrefix=c%2F"
                } else {
                    "?limit=5&pathPrefix=c%2F&cursor=$cursor"
                }
            val body = json(get("/v1/documents$query").body())
            val ids = body["documents"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            if (ids.isEmpty()) break
            seen += ids
            cursor = body["nextCursor"]?.jsonPrimitive?.content
            if (body["hasMore"]!!.jsonPrimitive.content == "false") break
        }

        assertEquals(12, seen.size, "cursor walk visited ${seen.size}: $seen")
        assertEquals(seen.distinct(), seen, "cursor walk repeated a document")
        assertEquals((0 until 12).map { "c/%02d".format(it) }, seen, "cursor walk skipped or reordered")
    }

    @Test
    fun `a cursor page reports the total size of the matching set`() {
        (0 until 7).forEach { index ->
            upsert("""{"id":"tally/$index","content":"body"}""")
        }

        val body = json(get("/v1/documents?limit=2&pathPrefix=tally%2F").body())

        assertEquals(2, body["documents"]!!.jsonArray.size)
        assertEquals(7, body["total"]!!.jsonPrimitive.content.toInt(), "total counts the matching set")
        assertTrue(body["hasMore"]!!.jsonPrimitive.content == "true")
        assertTrue(body["nextCursor"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `a path prefix narrows the listing to one subtree`() {
        upsert("""{"id":"p/notes/a","content":"a"}""")
        upsert("""{"id":"p/notes/deep/b","content":"b"}""")
        upsert("""{"id":"p/notesx/c","content":"c"}""")
        upsert("""{"id":"p/other/d","content":"d"}""")

        val body = json(get("/v1/documents?pathPrefix=p/notes/&limit=50").body())

        val ids = body["documents"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("p/notes/a", "p/notes/deep/b"), ids)
    }

    @Test
    fun `a path prefix escapes LIKE metacharacters`() {
        upsert("""{"id":"pct/100%/a","content":"a"}""")
        upsert("""{"id":"pct/1000/b","content":"b"}""")

        val body = json(get("/v1/documents?pathPrefix=pct/100%25/&limit=50").body())

        val ids = body["documents"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("pct/100%/a"), ids, "a folder named 100% must be matched literally")
    }

    @Test
    fun `cursor and offset cannot be combined`() {
        val response = get("/v1/documents?limit=5&offset=0&cursor=abc")

        assertEquals(400, response.statusCode())
        assertEquals("invalid_argument", json(response.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a malformed cursor is rejected rather than silently ignored`() {
        val response = get("/v1/documents?limit=5&cursor=not-a-cursor!!")

        assertEquals(400, response.statusCode())
        assertEquals("invalid_argument", json(response.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `offset pagination is unchanged`() {
        (0 until 6).forEach { index -> upsert("""{"id":"o/$index","content":"body"}""") }

        val body = json(get("/v1/documents?limit=2&offset=2&pathPrefix=o%2F").body())

        val ids = body["documents"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("o/2", "o/3"), ids)
        assertEquals(2, body["offset"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `search returns an envelope and paginates`() {
        upsert("""{"id":"s/one","content":"hello searchable world"}""")
        val body = json(get("/v1/search?q=searchable&limit=5").body())
        assertTrue(body.containsKey("hits"))
        assertTrue(body.containsKey("hasMore"))
        assertTrue(body["hits"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == "s/one" })
    }

    @Test
    fun `search hits are renderable without a follow-up fetch`() {
        val filler = "filler line\\n".repeat(60)
        upsert("""{"id":"s/deep","content":"${filler}needle in the haystack","frontmatter":{"title":"Deep Note"}}""")

        val hit =
            json(get("/v1/search?q=needle").body())["hits"]!!
                .jsonArray
                .first { it.jsonObject["id"]!!.jsonPrimitive.content == "s/deep" }
                .jsonObject

        assertEquals("s/deep.md", hit["path"]!!.jsonPrimitive.content)
        assertEquals("Deep Note", hit["title"]!!.jsonPrimitive.content)
        val snippet = hit["snippet"]!!.jsonPrimitive.content
        assertTrue(snippet.contains("needle"), "snippet should contain the match: ${snippet.take(80)}")

        val highlights = hit["highlights"]!!.jsonArray
        assertTrue(highlights.isNotEmpty(), "expected highlight offsets")
        highlights.forEach { range ->
            val start =
                range.jsonObject["start"]!!
                    .jsonPrimitive.content
                    .toInt()
            val end =
                range.jsonObject["end"]!!
                    .jsonPrimitive.content
                    .toInt()
            assertEquals("needle", snippet.substring(start, end))
        }
    }

    @Test
    fun `search filters by frontmatter`() {
        upsert("""{"id":"f/a","content":"shared token","frontmatter":{"repo":"org/repo"}}""")
        upsert("""{"id":"f/b","content":"shared token","frontmatter":{"repo":"org/other"}}""")

        val body = json(get("/v1/search?q=token&fm=repo=org/repo").body())
        val ids = body["hits"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("f/a"), ids)
    }

    @Test
    fun `repeated search filters are applied independently`() {
        upsert("""{"id":"f/tagged","content":"shared token","frontmatter":{"tags":["api","design"]}}""")
        upsert("""{"id":"f/single","content":"shared token","frontmatter":{"tags":["api"]}}""")

        // The generated clients send one query entry per value; a CSV value would
        // be read as the single tag "api,design" and match nothing.
        val body = json(get("/v1/search?q=token&tags=api&tags=design").body())
        val ids = body["hits"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("f/tagged"), ids)

        val single = json(get("/v1/search?q=token&tags=api").body())
        val singleIds = single["hits"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(setOf("f/tagged", "f/single"), singleIds.toSet())
    }

    @Test
    fun `openapi spec is served from the routes`() {
        val response = send("GET", "/openapi.json", authenticated = false)
        assertEquals(200, response.statusCode())
        val spec = json(response.body())
        assertTrue(spec.containsKey("openapi"), spec.toString())

        val paths = spec["paths"]!!.jsonObject
        assertTrue(paths.containsKey("/v1/search"), paths.keys.toString())
        assertTrue(paths.containsKey("/v1/metrics"), paths.keys.toString())
        assertTrue(paths.containsKey("/v1/documents:upsert"), paths.keys.toString())

        assertTrue(paths.containsKey("/v1/documents:traverse"), paths.keys.toString())
        assertTrue(paths.containsKey("/v1/documents:backlinks"), paths.keys.toString())

        val schemas = spec["components"]!!.jsonObject["schemas"]!!.jsonObject
        assertTrue(schemas.keys.any { it.contains("UpsertDocumentRequest") }, schemas.keys.toString())
        assertTrue(schemas.keys.any { it.contains("SearchResponse") }, schemas.keys.toString())
        assertTrue(schemas["UpsertDocumentRequest"]!!.jsonObject["properties"]!!.jsonObject.containsKey("contentHash"))
        assertTrue(schemas["UpsertDocumentRequest"]!!.jsonObject["properties"]!!.jsonObject.containsKey("ifRevision"))
        assertTrue(schemas["DeleteDocumentRequest"]!!.jsonObject["properties"]!!.jsonObject.containsKey("ifRevision"))
        assertFalse(schemas["TraverseRequest"]!!.jsonObject["properties"]!!.jsonObject.containsKey("direction"))

        val securitySchemes = spec["components"]!!.jsonObject["securitySchemes"]!!.jsonObject
        val apiKey = securitySchemes["apiKey"]!!.jsonObject
        assertEquals("apiKey", apiKey["type"]!!.jsonPrimitive.content)
        assertEquals(HEADER_API_KEY, apiKey["name"]!!.jsonPrimitive.content)
        assertEquals("header", apiKey["in"]!!.jsonPrimitive.content)
        val bearer = securitySchemes["bearerAuth"]!!.jsonObject
        assertEquals("http", bearer["type"]!!.jsonPrimitive.content)
        assertEquals("bearer", bearer["scheme"]!!.jsonPrimitive.content)

        paths.forEach { (path, pathItem) ->
            pathItem.jsonObject
                .filterKeys { it == "get" || it == "post" }
                .forEach { (method, operationValue) ->
                    val operation = operationValue.jsonObject
                    val headerNames =
                        operation["parameters"]
                            ?.jsonArray
                            ?.map { it.jsonObject }
                            ?.filter { it["in"]?.jsonPrimitive?.content == "header" }
                            ?.map { it["name"]!!.jsonPrimitive.content }
                            .orEmpty()
                    assertTrue(HEADER_REQUEST_ID in headerNames, "$method $path missing request id metadata")

                    if (method == "post") {
                        assertEquals("true", operation["requestBody"]!!.jsonObject["required"]!!.jsonPrimitive.content)
                    }
                    if (path in setOf("/v1/health", "/v1/live", "/v1/ready")) {
                        assertEquals(null, operation["security"])
                    } else {
                        val requirements = operation["security"]!!.jsonArray
                        assertEquals(setOf("apiKey"), requirements[0].jsonObject.keys)
                        assertEquals(setOf("bearerAuth"), requirements[1].jsonObject.keys)
                        assertEquals("403", operation["responses"]!!.jsonObject.keys.single { it == "403" })
                    }
                    if (path in setOf("/v1/documents:upsert", "/v1/documents:delete")) {
                        assertTrue("If-Match" in headerNames)
                        assertTrue(HEADER_IDEMPOTENCY in headerNames)
                    }
                    if (path in setOf("/v1/documents:get", "/v1/documents:upsert", "/v1/documents:delete")) {
                        val ok = operation["responses"]!!.jsonObject["200"]!!.jsonObject
                        assertTrue(ok["headers"]!!.jsonObject.containsKey("ETag"))
                    }
                }
        }

        val searchParameters =
            paths["/v1/search"]!!
                .jsonObject["get"]!!
                .jsonObject["parameters"]!!
                .jsonArray
                .map { it.jsonObject }
                .associateBy { it["name"]!!.jsonPrimitive.content }
        listOf("tags", "fm").forEach { name ->
            assertEquals("form", searchParameters[name]!!["style"]!!.jsonPrimitive.content)
            assertEquals("true", searchParameters[name]!!["explode"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `hybrid search mode is accepted and an unknown mode is rejected`() {
        upsert("""{"id":"h/one","content":"hybrid searchable"}""")

        val hybrid = json(get("/v1/search?q=hybrid&mode=hybrid").body())
        assertTrue(hybrid["hits"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == "h/one" })

        val bad = get("/v1/search?q=hybrid&mode=bogus")
        assertEquals(400, bad.statusCode())
        assertEquals("invalid_argument", json(bad.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `traverse returns ids envelope`() {
        upsert("""{"id":"t/b","content":"target"}""")
        upsert("""{"id":"t/a","content":"links to [[t/b]]"}""")
        val response = post("/v1/documents:traverse", """{"id":"t/a","rel":"links-to","depth":2}""")
        assertEquals(200, response.statusCode())
        assertTrue(json(response.body())["ids"]!!.jsonArray.any { it.jsonPrimitive.content == "t/b" })
    }

    /**
     * The relationship vocabulary is closed: every type the edge deriver can
     * produce is accepted by the traversal seam, so the two cannot drift apart
     * silently the way a free string allowed.
     */
    @Test
    fun `traverse accepts every known relationship type`() {
        upsert("""{"id":"v/b","content":"target"}""")
        upsert("""{"id":"v/a","content":"links to [[v/b]]"}""")
        listOf("links-to", "references", "embeds", "mentions").forEach { rel ->
            val response = post("/v1/documents:traverse", """{"id":"v/a","rel":"$rel","depth":1}""")
            assertEquals(200, response.statusCode(), "rel '$rel' must be accepted")
        }
    }

    /**
     * Frontmatter keys are edge sources, not relationship types. Asking for one
     * used to answer `200` with an empty list — indistinguishable from "no such
     * edges" — sending the caller off to inspect data that was perfectly fine.
     */
    @Test
    fun `traverse rejects a frontmatter key as rel and names the valid values`() {
        upsert("""{"id":"k/a","content":"body"}""")
        listOf("files", "repo", "jira", "pr", "author", "bogus").forEach { rel ->
            val response = post("/v1/documents:traverse", """{"id":"k/a","rel":"$rel","depth":1}""")
            assertEquals(400, response.statusCode(), "rel '$rel' must be rejected")
            val error = json(response.body())["error"]!!.jsonObject
            assertEquals("invalid_argument", error["code"]!!.jsonPrimitive.content)
            val message = error["message"]!!.jsonPrimitive.content
            listOf("links-to", "references", "embeds", "mentions").forEach { valid ->
                assertTrue(message.contains(valid), "rejection of '$rel' must name '$valid': $message")
            }
        }
    }

    @Test
    fun `references traversal resolves a repo value to its document`() {
        upsert("""{"id":"yy-target","content":"---\ntitle: T\n---\n\nbody\n"}""")
        upsert("""{"id":"yy-src","content":"---\nrepo: yy-target\n---\n\nbody\n"}""")

        val resolved = post("/v1/documents:traverse", """{"id":"yy-src","rel":"references","depth":1}""")
        assertEquals(200, resolved.statusCode())
        assertTrue(json(resolved.body())["ids"]!!.jsonArray.any { it.jsonPrimitive.content == "yy-target" })
    }

    @Test
    fun `a dangling references value traverses to an empty envelope not an error`() {
        upsert("""{"id":"dg-src","content":"---\nrepo: org/nowhere\n---\n\nbody\n"}""")

        val response = post("/v1/documents:traverse", """{"id":"dg-src","rel":"references","depth":1}""")
        assertEquals(200, response.statusCode())
        assertTrue(json(response.body())["ids"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `backlinks returns the documents linking to a document`() {
        upsert("""{"id":"b/target","content":"target"}""")
        upsert("""{"id":"b/one","content":"links to [[b/target]]"}""")
        upsert("""{"id":"b/two","content":"also links to [[b/target]]"}""")

        val response = post("/v1/documents:backlinks", """{"id":"b/target"}""")

        assertEquals(200, response.statusCode())
        val ids = json(response.body())["ids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("b/one", "b/two"), ids)
    }

    @Test
    fun `backlinks of a document with no inbound links is empty`() {
        upsert("""{"id":"b/lonely","content":"nothing points here"}""")

        val response = post("/v1/documents:backlinks", """{"id":"b/lonely"}""")

        assertEquals(200, response.statusCode())
        assertTrue(json(response.body())["ids"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `backlinks of an unknown document is an empty envelope not an error`() {
        val response = post("/v1/documents:backlinks", """{"id":"b/missing"}""")

        assertEquals(200, response.statusCode())
        assertTrue(json(response.body())["ids"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `backlinks narrows by relation`() {
        // A `repo:` frontmatter reference resolves to DocId("repo/<ref>"), so the
        // shared target is addressed as repo/shared to be reachable both by
        // wikilink and by relation.
        upsert("""{"id":"repo/shared","content":"target"}""")
        upsert("""{"id":"r/linker","content":"links to [[repo/shared]]"}""")
        upsert("""{"id":"r/referencer","content":"---\nrepo: [shared]\n---\nmentions a repo"}""")

        val linksTo = post("/v1/documents:backlinks", """{"id":"repo/shared","rel":"links-to"}""")
        assertEquals(
            setOf("r/linker"),
            json(linksTo.body())["ids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        )

        val references = post("/v1/documents:backlinks", """{"id":"repo/shared","rel":"references"}""")
        assertEquals(
            setOf("r/referencer"),
            json(references.body())["ids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        )

        val all = post("/v1/documents:backlinks", """{"id":"repo/shared"}""")
        assertEquals(
            setOf("r/linker", "r/referencer"),
            json(all.body())["ids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        )
    }

    @Test
    fun `backlinks excludes a deleted source`() {
        upsert("""{"id":"d/target","content":"target"}""")
        upsert("""{"id":"d/one","content":"links to [[d/target]]"}""")
        post("/v1/documents:delete", """{"id":"d/one"}""")

        val response = post("/v1/documents:backlinks", """{"id":"d/target"}""")

        assertEquals(200, response.statusCode())
        assertTrue(json(response.body())["ids"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `if-match enforces optimistic concurrency`() {
        val id = "m/a"
        upsert("""{"id":"$id","content":"one"}""")
        val etag = post("/v1/documents:get", """{"id":"$id"}""").headers().firstValue("ETag").orElse("")
        assertTrue(etag.isNotBlank())

        val stale = upsert("""{"id":"$id","content":"two"}""", headers = mapOf("If-Match" to "\"stale\""))
        assertEquals(412, stale.statusCode())
        assertEquals("failed_precondition", json(stale.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        assertEquals(200, upsert("""{"id":"$id","content":"two"}""", headers = mapOf("If-Match" to etag)).statusCode())
    }

    @Test
    fun `if-match wildcard requires an active representation`() {
        val id = "m/wildcard"
        assertEquals(200, upsert("""{"id":"$id","content":"one"}""").statusCode())
        assertEquals(200, upsert("""{"id":"$id","content":"two"}""", headers = mapOf("If-Match" to "*")).statusCode())
        assertEquals(
            412,
            upsert("""{"id":"m/missing-wildcard","content":"none"}""", headers = mapOf("If-Match" to "*")).statusCode()
        )
    }

    @Test
    fun `upsert content hash is an atomic client precondition`() {
        val id = "c/precondition"
        val first = upsert("""{"id":"$id","content":"one"}""")
        assertEquals(200, first.statusCode())
        val contentHash = json(first.body())["contentHash"]!!.jsonPrimitive.content

        val accepted = upsert("""{"id":"$id","content":"two","contentHash":"$contentHash"}""")
        assertEquals(200, accepted.statusCode())
        val currentHash = json(accepted.body())["contentHash"]!!.jsonPrimitive.content

        val rejected = upsert("""{"id":"$id","content":"three","contentHash":"$contentHash"}""")
        assertEquals(412, rejected.statusCode())
        assertEquals("failed_precondition", json(rejected.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        val unchanged = post("/v1/documents:get", """{"id":"$id"}""")
        assertEquals(200, unchanged.statusCode())
        assertEquals("two", json(unchanged.body())["content"]!!.jsonPrimitive.content)
        assertEquals(currentHash, json(unchanged.body())["contentHash"]!!.jsonPrimitive.content)

        val missing = upsert("""{"id":"c/missing","content":"new","contentHash":"$contentHash"}""")
        assertEquals(412, missing.statusCode())
    }

    @Test
    fun `delete if revision is enforced by the store and header body must agree`() {
        val id = "d/precondition"
        val first = upsert("""{"id":"$id","content":"one"}""")
        assertEquals(200, first.statusCode())
        val current = post("/v1/documents:get", """{"id":"$id"}""").headers().firstValue("ETag").orElse("")
        assertTrue(current.isNotBlank())

        val bodyAccepted = post("/v1/documents:delete", """{"id":"$id","ifRevision":"${current.trim('"')}"}""")
        assertEquals(200, bodyAccepted.statusCode())

        val staleId = "d/precondition-stale"
        assertEquals(200, upsert("""{"id":"$staleId","content":"one"}""").statusCode())
        val stale = post("/v1/documents:delete", """{"id":"$staleId","ifRevision":"stale"}""")
        assertEquals(409, stale.statusCode())
        assertEquals(200, post("/v1/documents:get", """{"id":"$staleId"}""").statusCode())

        val disagreementId = "d/precondition-disagreement"
        assertEquals(200, upsert("""{"id":"$disagreementId","content":"one"}""").statusCode())
        val disagreementEtag =
            post("/v1/documents:get", """{"id":"$disagreementId"}""").headers().firstValue("ETag").orElse("")
        val disagreement =
            post(
                "/v1/documents:delete",
                """{"id":"$disagreementId","ifRevision":"stale"}""",
                headers = mapOf("If-Match" to disagreementEtag)
            )
        assertEquals(400, disagreement.statusCode())
        assertEquals(200, post("/v1/documents:get", """{"id":"$disagreementId"}""").statusCode())
    }

    @Test
    fun `idempotency key replays the original response`() {
        val id = "i/a"
        val body = """{"id":"$id","content":"once"}"""
        val first = upsert(body, headers = mapOf("Idempotency-Key" to "key-1"))
        val replay = upsert(body, headers = mapOf("Idempotency-Key" to "key-1"))
        assertEquals(first.body(), replay.body())
        assertEquals(
            first.headers().firstValue("ETag"),
            replay.headers().firstValue("ETag")
        )
        assertEquals(
            "1",
            json(post("/v1/documents:history", """{"id":"$id"}""").body())["total"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `idempotency key reuse with a different request conflicts`() {
        val key = "request-bound-${UUID.randomUUID()}"
        val first = upsert("""{"id":"i/bound-a","content":"one"}""", headers = mapOf("Idempotency-Key" to key))
        assertEquals(200, first.statusCode())
        val conflict = upsert("""{"id":"i/bound-b","content":"two"}""", headers = mapOf("Idempotency-Key" to key))
        assertEquals(409, conflict.statusCode())
        assertEquals(
            "idempotency_conflict",
            json(conflict.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `concurrent requests with one idempotency key commit once`() {
        val id = "i/concurrent-${UUID.randomUUID()}"
        val body = """{"id":"$id","content":"once"}"""
        val headers = mapOf("Idempotency-Key" to "concurrent-${UUID.randomUUID()}")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val requests =
                (1..2).map {
                    executor.submit<HttpResponse<String>> {
                        start.await()
                        upsert(body, headers)
                    }
                }
            start.countDown()
            val responses = requests.map { it.get(10, TimeUnit.SECONDS) }
            // The loser of the race waits for the holder and replays its response
            // rather than failing the client's retry.
            assertTrue(
                responses.all { it.statusCode() == 200 },
                responses.joinToString { it.statusCode().toString() }
            )
            assertEquals(1, responses.map { it.body() }.distinct().size)
            assertEquals(
                responses.first().headers().firstValue("ETag"),
                responses.last().headers().firstValue("ETag")
            )
            assertEquals(
                "1",
                json(post("/v1/documents:history", """{"id":"$id"}""").body())["total"]!!.jsonPrimitive.content
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `request and pagination bounds are rejected before processing`() {
        val oversized = """{"id":"bounds/body","content":"${"x".repeat(MAX_REQUEST_BODY_BYTES.toInt() + 1)}"}"""
        val oversizedResponse = upsert(oversized)
        assertEquals(413, oversizedResponse.statusCode())
        assertEquals(
            "request_too_large",
            json(oversizedResponse.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
        )
        assertEquals(
            "body must be at most $MAX_REQUEST_BODY_BYTES bytes",
            json(oversizedResponse.body())["error"]!!.jsonObject["detail"]!!.jsonPrimitive.content
        )
        assertEquals(400, get("/v1/search?limit=not-a-number").statusCode())
        assertEquals(400, get("/v1/search?offset=${Int.MAX_VALUE}").statusCode())
        val tooManyFilters = (1..(MAX_SEARCH_FILTERS + 1)).joinToString("&") { "fm=k$it=v" }
        assertEquals(400, get("/v1/search?$tooManyFilters").statusCode())
    }

    @Test
    fun `delete hides the document while preserving authorized history`() {
        upsert("""{"id":"d/a","content":"bye"}""")
        val deleted = post("/v1/documents:delete", """{"id":"d/a","message":"remove"}""")
        assertEquals(200, deleted.statusCode())
        val fetched = post("/v1/documents:get", """{"id":"d/a"}""")
        assertEquals(404, fetched.statusCode())
        assertEquals("not_found", json(fetched.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)

        val history = json(post("/v1/documents:history", """{"id":"d/a"}""").body())
        assertEquals("2", history["total"]!!.jsonPrimitive.content)
        assertEquals(
            json(deleted.body())["revision"],
            history["revisions"]!!.jsonArray.last().jsonObject["revision"]
        )
    }

    @Test
    fun `malformed body returns invalid_argument not 500`() {
        val response = post("/v1/documents:upsert", """{"id":"x" """)
        assertEquals(400, response.statusCode())
        assertEquals("invalid_argument", json(response.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unsafe document ids return invalid_argument`() {
        val response = post("/v1/documents:upsert", """{"id":"../../outside","content":"no"}""")
        assertEquals(400, response.statusCode())
        assertEquals("invalid_argument", json(response.body())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `shared request limits reject upper-bound violations`() {
        assertEquals(200, get("/v1/search?q=x&limit=1&offset=0").statusCode())
        assertEquals(200, get("/v1/documents?limit=$MAX_PAGE_LIMIT&offset=$MAX_PAGE_OFFSET").statusCode())
        assertEquals(400, get("/v1/search?q=${"x".repeat(MAX_QUERY_BYTES.toInt() + 1)}").statusCode())
        assertEquals(400, get("/v1/documents?limit=${MAX_PAGE_LIMIT + 1}").statusCode())
        assertEquals(400, get("/v1/documents?offset=${MAX_PAGE_OFFSET + 1}").statusCode())
        assertEquals(
            400,
            post("/v1/documents:traverse", """{"id":"a","depth":${MAX_REQUEST_DEPTH + 1}}""").statusCode()
        )
        assertEquals(
            400,
            post(
                "/v1/documents:query",
                """{"ids":[${(1..(MAX_BATCH_IDS + 1)).joinToString(",") { "\"$it\"" }}]}"""
            ).statusCode()
        )
        val tags = (1..(MAX_SEARCH_FILTERS + 1)).joinToString("&") { "tags=t$it" }
        assertEquals(400, get("/v1/search?$tags").statusCode())
        val frontmatter = (1..(MAX_FRONTMATTER_ENTRIES + 1)).joinToString(",") { "\"key-$it\":\"value\"" }
        assertEquals(
            400,
            upsert("""{"id":"bounds/frontmatter","content":"x","frontmatter":{$frontmatter}}""").statusCode()
        )
        assertEquals(
            400,
            upsert(
                """{"id":"bounds/depth","content":"x","frontmatter":{"nested":${"{\"value\":".repeat(
                    MAX_FRONTMATTER_DEPTH + 1
                )}1${"}".repeat(MAX_FRONTMATTER_DEPTH + 1)}}}"""
            ).statusCode()
        )
    }

    @Test
    fun `list pagination remains ordered while writes are concurrent`() {
        val prefix = "concurrent/${UUID.randomUUID()}"
        val documents = (0 until 9).map { index -> "$prefix/$index" }
        val executor = Executors.newFixedThreadPool(3)
        try {
            val writes =
                executor.submit {
                    documents.forEach { id -> upsert("""{"id":"$id","content":"$id"}""") }
                }
            val reads =
                executor.submit {
                    (0 until 9).map { get("/v1/documents?limit=3&offset=0") }
                }
            writes.get(10, TimeUnit.SECONDS)
            reads.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        val all =
            json(get("/v1/documents?limit=${MAX_PAGE_LIMIT}").body())["documents"]!!.jsonArray.map {
                it.jsonObject["id"]!!.jsonPrimitive.content
            }
        val ids = all.filter { it.startsWith(prefix) }
        assertEquals(documents, ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `request id is generated and echoed`() {
        assertFalse(
            send("GET", "/v1/health", authenticated = false)
                .headers()
                .firstValue("X-Request-Id")
                .orElse("")
                .isBlank()
        )
        val withHeader = send("GET", "/v1/health", authenticated = false, headers = mapOf("X-Request-Id" to "req-123"))
        assertEquals("req-123", withHeader.headers().firstValue("X-Request-Id").orElse(""))
    }
}
