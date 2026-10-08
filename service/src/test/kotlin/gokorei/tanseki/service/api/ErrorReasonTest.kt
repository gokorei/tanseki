package gokorei.tanseki.service.api

import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.testkit.InMemoryContextStore
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A rejected request has to say what was wrong with it.
 *
 * Every `400` used to answer with the literal string "invalid request" and
 * discard the exception's own text. That text is written for this audience — it
 * names the offending key and the shape that was expected — so throwing it away
 * left a dependent unable to tell a missing suffix from a missing key, and unable
 * to fix its request without guessing.
 *
 * The trust rules matter as much as the plumbing: a reason is passed on only when
 * it was authored for a caller. Anything else is answered without one rather than
 * with text that might describe an internal path or a dependency's internals.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErrorReasonTest {
    private val http: HttpClient = HttpClient.newHttpClient()
    private var server: RunningApiServer? = null
    private var port = 0

    private class SilentLookup : Lookup {
        override fun index(doc: Document, edges: List<Edge>) = Unit

        override fun remove(id: DocId) = Unit

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    @BeforeAll
    fun startServer() {
        val facade =
            QueryFacade(
                store = InMemoryContextStore(),
                lookup = SilentLookup(),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                io = Dispatchers.Unconfined
            )
        val running = StoreApiServer.startEphemeral(facade)
        server = running
        port = running.port
    }

    @AfterAll
    fun stopServer() {
        server?.close()
    }

    private fun upsert(body: String): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port/v1/documents:upsert"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun errorMessage(response: HttpResponse<String>): String? {
        val parsed = runCatching { Json.parseToJsonElement(response.body()).jsonObject }.getOrNull() ?: return null
        val error = parsed["error"]?.jsonObject ?: return null
        return error["message"]?.jsonPrimitive?.content
    }

    @Test
    fun `a rejected frontmatter value says what was wrong with it`() {
        val response = upsert("""{"id":"a/b","collection":"c","content":"x\n","frontmatter":{"":"v"}}""")

        assertEquals(400, response.statusCode(), response.body())
        val message = errorMessage(response)
        // Which layer rejects this is not the point: deserialization catches the
        // blank field name before the frontmatter validator sees it. What matters
        // is that the caller is told which field was wrong rather than being told
        // the request was invalid.
        assertTrue(
            message != null && message.contains("must not be blank") && message != "invalid request",
            "expected a specific reason, got: $message"
        )
    }

    @Test
    fun `a rejected path says why rather than that the request was invalid`() {
        val response = upsert("""{"id":"p1","collection":"c","content":"x\n","path":"../escape.md"}""")

        assertEquals(400, response.statusCode())
        val message = errorMessage(response)
        assertTrue(
            message != null && message.contains("path") && message != "invalid request",
            "expected the reason to name the path problem, got: $message"
        )
    }

    @Test
    fun `a nested object in the JSON frontmatter field is now accepted`() {
        // The content seam has always accepted a nested map. Refusing it here made
        // the two seams disagree about the same document, which is the half-fixed
        // state: a caller could not tell whether the shape was wrong or the route.
        val response =
            upsert(
                """{"id":"n1","collection":"c","content":"---\nmeta:\n  owner: me\n---\n\nbody\n",
                   "frontmatter":{"meta":{"owner":"me"}}}"""
            )

        assertEquals(200, response.statusCode(), response.body())
    }

    @Test
    fun `a nested array of objects is accepted too`() {
        val response =
            upsert(
                """{"id":"n2","collection":"c","content":"x\n","frontmatter":{"items":[{"k":"v"}]}}"""
            )

        assertEquals(200, response.statusCode(), response.body())
    }

    @Test
    fun `a fatal Error is not rewritten as the internal JSON envelope`() {
        // A LinkageError is a broken-JVM condition, not a request failure. The
        // StatusPages handler must not fold it into the ordinary 500 envelope,
        // which would describe a process-level fault as a retryable API error.
        val facade =
            QueryFacade(
                store = InMemoryContextStore(),
                lookup = FatalLookup(),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                io = Dispatchers.Unconfined
            )
        val running = StoreApiServer.startEphemeral(facade)
        try {
            val request =
                HttpRequest
                    .newBuilder(URI("http://127.0.0.1:${running.port}/v1/search?q=x"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
            val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
            val body = response?.body().orEmpty()
            assertFalse(
                body.contains("\"error\"") && body.contains("\"internal\""),
                "an Error must not be rewritten as the internal envelope: $body"
            )
        } finally {
            running.close()
        }
    }

    private class FatalLookup : Lookup {
        override fun index(doc: Document, edges: List<Edge>) = Unit

        override fun remove(id: DocId) = Unit

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = throw LinkageError("fatal")

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    @Test
    fun `a generic UnsupportedOperationException is a 500 not a false capability claim`() {
        // UnsupportedOperationException is thrown by JDK immutable collections and
        // iterators too. Mapping every one to 501 would tell a client the store
        // cannot do something it can, when the failure is really an internal bug.
        val facade =
            QueryFacade(
                store = InMemoryContextStore(),
                lookup = UnsupportedLookup(),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                io = Dispatchers.Unconfined
            )
        val running = StoreApiServer.startEphemeral(facade)
        try {
            val request =
                HttpRequest
                    .newBuilder(URI("http://127.0.0.1:${running.port}/v1/search?q=x"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(500, response.statusCode(), response.body())
            assertFalse(
                response.body().contains("not_implemented"),
                "a JDK UnsupportedOperationException must not be reported as a capability gap: ${response.body()}"
            )
        } finally {
            running.close()
        }
    }

    private class UnsupportedLookup : Lookup {
        override fun index(doc: Document, edges: List<Edge>) = Unit

        override fun remove(id: DocId) = Unit

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> =
            throw UnsupportedOperationException("immutable view")

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    // --- the trust rules, without a server in the way ------------------------

    @Test
    fun `an authored validation message is passed through`() {
        val reason = callerReason(InvalidInputException("frontmatter key must not be blank"))

        assertEquals("frontmatter key must not be blank", reason)
    }

    @Test
    fun `an argument-validation message is passed through`() {
        val reason = callerReason(IllegalArgumentException("document path must end in .md"))

        assertEquals("document path must end in .md", reason)
    }

    @Test
    fun `a framework failure with nothing to say is not given a reason`() {
        // Ktor's message is the status description, which the status code already
        // said. Passing it on would add noise and imply a cause that is not known.
        assertNull(callerReason(BadRequestException("Bad Request")))
    }

    @Test
    fun `an unrecognised failure is not given a reason`() {
        assertNull(callerReason(RuntimeException("/var/lib/secret/notes.md is unreadable")))
    }

    @Test
    fun `a reason is one bounded line`() {
        val noisy = IllegalArgumentException("first line\nsecond line\nthird")

        assertEquals("first line", callerReason(noisy))
    }

    @Test
    fun `control characters in a reason are removed`() {
        val reason = callerReason(InvalidInputException("bad key[31m here"))

        assertEquals("bad key [31m here", reason)
    }

    @Test
    fun `a very long reason is truncated`() {
        val reason = callerReason(InvalidInputException("x".repeat(5_000)))

        assertTrue(reason != null && reason.length < 300, "length was ${reason?.length}")
        assertTrue(reason!!.endsWith("…"), reason.takeLast(8))
    }

    @Test
    fun `an empty message yields no reason rather than a blank one`() {
        assertNull(callerReason(InvalidInputException("   \n  ")))
    }
}

/**
 * A frontmatter block that could not be read has to be visible from outside.
 *
 * The write succeeds and the block is preserved, but nothing behind the typed view
 * was indexed — so a caller that only checks the status code believes a document
 * with no properties was stored. Three pieces have to agree for that to be
 * visible, and each was lost in a merge at least once: the field on the response
 * DTO, the mapping that fills it, and the counter the daemon keeps. These assert
 * all three, because a partial restoration looks exactly like a feature that was
 * never built.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FrontmatterDegradationTest {
    private val http: HttpClient = HttpClient.newHttpClient()
    private var server: RunningApiServer? = null
    private var port = 0
    private val metrics = RecordingMetrics()

    /** Records what the seam reports, which is the wiring under test. */
    private class RecordingMetrics : MetricsRecorder {
        val degraded = mutableListOf<String>()

        override fun recordRequest(durationNanos: Long, statusCode: Int, error: String?) = Unit

        override fun recordFrontmatterDegraded(reason: String) {
            degraded += reason
        }

        override fun metrics(): MetricsResponse = error("not used")

        override fun readiness(): ReadinessResponse = error("not used")
    }

    private class SilentLookup : Lookup {
        override fun index(doc: Document, edges: List<Edge>) = Unit

        override fun remove(id: DocId) = Unit

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    @BeforeAll
    fun startServer() {
        val facade =
            QueryFacade(
                store = InMemoryContextStore(),
                lookup = SilentLookup(),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                io = Dispatchers.Unconfined
            )
        val running = StoreApiServer.startEphemeral(facade, metrics = metrics)
        server = running
        port = running.port
    }

    @AfterAll
    fun stopServer() {
        server?.close()
    }

    /** A block the parser cannot read: a tab where YAML forbids one for indentation. */
    private fun upsertMalformedFrontmatter(id: String): HttpResponse<String> {
        val body = """{"id":"$id","collection":"c","content":"---\ntitle: T\n\tbad: indent\n---\n\nbody\n"}"""
        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port/v1/documents:upsert"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun upsertWellFormed(id: String): HttpResponse<String> {
        val body = """{"id":"$id","collection":"c","content":"---\ntitle: T\n---\n\nbody\n"}"""
        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port/v1/documents:upsert"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun errorOf(response: HttpResponse<String>): String? =
        runCatching { Json.parseToJsonElement(response.body()).jsonObject }
            .getOrNull()
            ?.get("error")
            ?.jsonObject
            ?.get("message")
            ?.jsonPrimitive
            ?.content

    @Test
    fun `an unreadable frontmatter block is reported on a successful upsert`() {
        val response = upsertMalformedFrontmatter("deg/1")

        // The write is not refused: the block is preserved verbatim, which is the
        // point. It is the empty typed view that has to be reported instead.
        assertEquals(200, response.statusCode(), response.body())
        val parsed = Json.parseToJsonElement(response.body()).jsonObject
        val reason = parsed["frontmatterError"]?.jsonPrimitive?.content
        assertNotNull(reason, "the upsert response must carry the degradation reason: $parsed")
        assertTrue(reason!!.isNotBlank(), "the reason must say something")
    }

    @Test
    fun `a readable block reports no degradation`() {
        val response = upsertWellFormed("deg/2")

        assertEquals(200, response.statusCode(), response.body())
        val parsed = Json.parseToJsonElement(response.body()).jsonObject
        assertNull(parsed["frontmatterError"], "a clean block must not report a reason: $parsed")
    }

    @Test
    fun `only a degraded write is reported to the recorder`() {
        // A delta, not an absolute: the recorder is shared across this class, so
        // an absolute count would depend on the order JUnit happens to pick.
        val before = metrics.degraded.size

        upsertWellFormed("deg/3")
        assertEquals(before, metrics.degraded.size, "a readable block is not a degradation")

        upsertMalformedFrontmatter("deg/4")
        assertEquals(before + 1, metrics.degraded.size, "the seam must reach the recorder exactly once")

        // Counting is what makes the loss visible in aggregate rather than only to
        // whoever happened to look at one response. This is the wiring a merge
        // dropped once already, so it is asserted rather than assumed.
        assertTrue(metrics.degraded.last().isNotBlank(), "the reason recorded must say something")
    }

    @Test
    fun `the stored document keeps its block even though it did not read`() {
        upsertMalformedFrontmatter("deg/5")

        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port/v1/documents:get"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString("""{"id":"deg/5","collection":"c"}""")
                ).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        val content =
            Json
                .parseToJsonElement(response.body())
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content
        assertTrue(content != null && content.contains("bad: indent"), "the block must survive verbatim: $content")
    }
}
