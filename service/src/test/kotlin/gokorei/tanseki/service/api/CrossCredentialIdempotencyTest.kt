package gokorei.tanseki.service.api

import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The idempotency store is shared across credentials, so a key must be scoped by
 * the credential that sent it. Otherwise one principal's key reserves a slot
 * another principal's request then sees, and the second request is refused as a
 * conflict (or, for an identical body, replayed) even though it is its own write.
 *
 * A dedicated server keeps this from perturbing the document counts other
 * authorization tests assert on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossCredentialIdempotencyTest {
    private val alphaKey = "alpha-key"
    private val betaKey = "beta-key"
    private val http: HttpClient = HttpClient.newHttpClient()
    private var port = 0
    private var server: RunningApiServer? = null

    @BeforeAll
    fun startServer() {
        val facade =
            QueryFacade(
                store = InMemoryContextStore(),
                lookup = LuceneLookup(),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                io = Dispatchers.Unconfined
            )
        val registry =
            CredentialRegistry.of(
                listOf(
                    ApiCredential(alphaKey, "alpha", setOf("alpha")),
                    ApiCredential(betaKey, "beta", setOf("beta"))
                )
            )
        val running =
            StoreApiServer.startEphemeral(
                facade = facade,
                authPolicy = ApiAuthPolicy.forRegistry(registry)
            )
        server = running
        port = running.port

        var ready = false
        repeat(100) {
            if (!ready) {
                val probe =
                    runCatching { post("/v1/documents:get", """{"id":"nobody"}""", alphaKey) }
                        .getOrNull()
                if (probe != null && probe.statusCode() in 400..499) ready = true
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
    fun `one Idempotency-Key used by two credentials does not collide on rename`() {
        assertEquals(
            200,
            post(
                "/v1/documents:upsert",
                """{"id":"alpha/idem-rename","collection":"alpha","content":"a"}""",
                alphaKey
            ).statusCode()
        )
        assertEquals(
            200,
            post(
                "/v1/documents:upsert",
                """{"id":"beta/idem-rename","collection":"beta","content":"b"}""",
                betaKey
            ).statusCode()
        )
        val shared = mapOf(HEADER_IDEMPOTENCY to "cross-credential-rename")

        val alpha =
            send(
                "POST",
                "/v1/documents:rename",
                """{"id":"alpha/idem-rename","newId":"alpha/idem-rename2","collection":"alpha"}""",
                alphaKey,
                shared
            )
        val beta =
            send(
                "POST",
                "/v1/documents:rename",
                """{"id":"beta/idem-rename","newId":"beta/idem-rename2","collection":"beta"}""",
                betaKey,
                shared
            )

        assertEquals(200, alpha.statusCode(), alpha.body())
        assertEquals(200, beta.statusCode(), beta.body())
        assertEquals(
            200,
            post("/v1/documents:get", """{"id":"beta/idem-rename2","collection":"beta"}""", betaKey).statusCode()
        )
    }

    @Test
    fun `one Idempotency-Key used by two credentials does not collide on restore`() {
        assertEquals(
            200,
            post(
                "/v1/documents:upsert",
                """{"id":"alpha/idem-restore","collection":"alpha","content":"a"}""",
                alphaKey
            ).statusCode()
        )
        assertEquals(
            200,
            post(
                "/v1/documents:upsert",
                """{"id":"beta/idem-restore","collection":"beta","content":"b"}""",
                betaKey
            ).statusCode()
        )
        assertEquals(
            200,
            post("/v1/documents:delete", """{"id":"alpha/idem-restore","collection":"alpha"}""", alphaKey).statusCode()
        )
        assertEquals(
            200,
            post("/v1/documents:delete", """{"id":"beta/idem-restore","collection":"beta"}""", betaKey).statusCode()
        )
        val shared = mapOf(HEADER_IDEMPOTENCY to "cross-credential-restore")

        val alpha =
            send(
                "POST",
                "/v1/documents:restore",
                """{"id":"alpha/idem-restore","collection":"alpha"}""",
                alphaKey,
                shared
            )
        val beta =
            send(
                "POST",
                "/v1/documents:restore",
                """{"id":"beta/idem-restore","collection":"beta"}""",
                betaKey,
                shared
            )

        assertEquals(200, alpha.statusCode(), alpha.body())
        assertEquals(200, beta.statusCode(), beta.body())
        assertEquals(
            200,
            post("/v1/documents:get", """{"id":"beta/idem-restore","collection":"beta"}""", betaKey).statusCode()
        )
    }

    private fun send(
        method: String,
        path: String,
        body: String?,
        key: String,
        headers: Map<String, String>
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header(HEADER_API_KEY, key)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val bodyPublisher =
            body?.let { value ->
                HttpRequest.BodyPublishers.ofString(value)
            } ?: HttpRequest.BodyPublishers.noBody()
        builder.method(method, bodyPublisher)
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: String, key: String) =
        send("POST", path, body, key, emptyMap())
}
