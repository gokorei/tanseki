package gokorei.tanseki.service.api

import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.composition.Profile
import gokorei.tanseki.composition.TansekiConfig
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.LogLevel
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * Cross-origin behaviour of the `/v1` seam.
 *
 * Two servers are started: one with no allowlist (the default) and one naming a
 * single dev origin. The default case matters as much as the enabled one — a
 * knowledge store should not answer cross-origin requests at all unless an
 * operator asked for it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorsPolicyTest {
    private val allowed = "http://localhost:5173"
    private val key = "cors-test-key"
    private val denied = "https://evil.example"
    private val http: HttpClient = HttpClient.newHttpClient()
    private var openPort = 0
    private var closedPort = 0
    private var openServer: RunningApiServer? = null
    private var closedServer: RunningApiServer? = null

    private fun facade() =
        QueryFacade(
            store = InMemoryContextStore(),
            lookup = LuceneLookup(),
            clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
            io = Dispatchers.Unconfined
        )

    @BeforeAll
    fun startServers() {
        val withCors =
            StoreApiServer.startEphemeral(
                facade = facade(),
                authPolicy = ApiAuthPolicy.legacy(key),
                cors = CorsPolicy(setOf(allowed))
            )
        val withoutCors =
            StoreApiServer.startEphemeral(
                facade = facade(),
                authPolicy = ApiAuthPolicy.legacy(key)
            )
        openServer = withCors
        closedServer = withoutCors
        openPort = withCors.port
        closedPort = withoutCors.port
        awaitReady(openPort)
        awaitReady(closedPort)
    }

    @AfterAll
    fun stopServers() {
        openServer?.close()
        closedServer?.close()
    }

    private fun awaitReady(target: Int) {
        var ready = false
        repeat(100) {
            if (!ready) {
                runCatching { if (get(target, "/v1/health", null).statusCode() == 200) ready = true }
                if (!ready) Thread.sleep(50)
            }
        }
        check(ready) { "server on $target did not become ready" }
    }

    private fun get(
        port: Int,
        path: String,
        origin: String?,
        extra: Map<String, String> = emptyMap()
    ): HttpResponse<*> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET()
        origin?.let { builder.header("Origin", it) }
        extra.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun preflight(
        port: Int,
        path: String,
        origin: String,
        requestMethod: String = "POST",
        requestHeaders: List<String> = emptyList()
    ): HttpResponse<*> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .method(
                    "OPTIONS",
                    HttpRequest.BodyPublishers.noBody()
                ).header("Origin", origin)
                .header("Access-Control-Request-Method", requestMethod)
        requestHeaders.forEach { builder.header("Access-Control-Request-Headers", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `an allowed origin receives the cors header`() {
        val response = get(openPort, "/v1/health", allowed)

        assertEquals(allowed, response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
    }

    @Test
    fun `a disallowed origin receives no cors header`() {
        val response = get(openPort, "/v1/health", denied)

        assertFalse(
            response.headers().firstValue("Access-Control-Allow-Origin").isPresent,
            "a disallowed origin must not be granted access"
        )
    }

    @Test
    fun `cors is off unless origins are configured`() {
        val response = get(closedPort, "/v1/health", allowed)

        assertFalse(
            response.headers().firstValue("Access-Control-Allow-Origin").isPresent,
            "the default seam must not answer cross-origin requests"
        )
    }

    @Test
    fun `a same-origin request is unaffected`() {
        // No Origin header at all: this is the non-browser case (agents, CLI,
        // SDKs). It must behave exactly as before.
        val response = get(openPort, "/v1/health", null)

        assertEquals(200, response.statusCode())
        assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent)
    }

    @Test
    fun `a preflight for a protected route succeeds and advertises the seam headers`() {
        val response =
            preflight(
                openPort,
                "/v1/documents:upsert",
                allowed,
                requestHeaders = listOf(HEADER_API_KEY, HEADER_IDEMPOTENCY, "If-Match", "Content-Type")
            )

        assertTrue(response.statusCode() in 200..299, "preflight was ${response.statusCode()}")
        assertEquals(allowed, response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))

        val allowHeaders =
            response
                .headers()
                .firstValue("Access-Control-Allow-Headers")
                .orElse("")
                .lowercase()
        listOf(HEADER_API_KEY, HEADER_IDEMPOTENCY, "if-match", "content-type").forEach {
            assertTrue(
                allowHeaders.contains(it.lowercase()),
                "preflight should allow $it, was: $allowHeaders"
            )
        }
    }

    @Test
    fun `cors never advertises credentialed access`() {
        val response = preflight(openPort, "/v1/documents:upsert", allowed)

        assertFalse(
            response.headers().firstValue("Access-Control-Allow-Credentials").isPresent,
            "the seam uses header keys, so credentialed CORS must stay off"
        )
    }

    @Test
    fun `a preflight from a disallowed origin is not granted`() {
        val response = preflight(openPort, "/v1/documents:upsert", denied)

        assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent)
    }

    @Test
    fun `a protected route still requires a credential from a browser`() {
        // CORS must not become an authentication bypass. A browser from an
        // allowed origin gets CORS headers but no data without a key.
        val deniedResponse = get(openPort, "/v1/documents", allowed)

        assertEquals(
            401,
            deniedResponse.statusCode(),
            "an allowed origin must not bypass authentication"
        )
        assertEquals(
            allowed,
            deniedResponse.headers().firstValue("Access-Control-Allow-Origin").orElse(null),
            "the 401 should still carry CORS headers so a browser can read the failure"
        )

        val allowedResponse = get(openPort, "/v1/documents", allowed, extra = mapOf(HEADER_API_KEY to key))
        assertEquals(200, allowedResponse.statusCode())
    }

    @Test
    fun `the etag response header is exposed to browsers`() {
        val response = get(openPort, "/v1/health", allowed)

        val exposed = response.headers().firstValue("Access-Control-Expose-Headers").orElse("")
        assertTrue(exposed.contains("ETag", ignoreCase = true), "ETag should be readable, was: $exposed")
    }

    @Test
    fun `a loopback policy is constructed with the configured cors allowlist`() {
        // The loopback branch used to drop the CORS policy, so a developer running a
        // local daemon with TANSEKI_CORS_ALLOW_ORIGINS set got no CORS headers at all.
        val config =
            TansekiConfig(
                profile = Profile.LIBRARY,
                path = Path.of("."),
                indexDir = Path.of("index"),
                logLevel = LogLevel.INFO,
                httpHost = "127.0.0.1",
                corsAllowOrigins = allowed
            )

        val policy = ServerApiPolicy.fromConfig(config)

        assertTrue(policy.cors.enabled, "the loopback policy must carry the parsed CORS policy")
        assertEquals(setOf(allowed), policy.cors.allowedOrigins)
    }

    @Test
    fun `a loopback policy without configured origins keeps cors off`() {
        val config =
            TansekiConfig(
                profile = Profile.LIBRARY,
                path = Path.of("."),
                indexDir = Path.of("index"),
                logLevel = LogLevel.INFO,
                httpHost = "127.0.0.1"
            )

        assertFalse(ServerApiPolicy.fromConfig(config).cors.enabled)
    }

    @Test
    fun `the policy refuses a wildcard at construction`() {
        assertThrows<IllegalArgumentException> { CorsPolicy(setOf("*")) }
        assertThrows<IllegalArgumentException> { CorsPolicy(setOf("https://*.example")) }
    }

    @Test
    fun `the policy refuses an origin without a scheme`() {
        assertThrows<IllegalArgumentException> { CorsPolicy(setOf("localhost:5173")) }
    }

    @Test
    fun `parse reads a comma separated list and trims it`() {
        val policy =
            CorsPolicy.parse(" http://localhost:5173 , https://app.example ")

        assertEquals(setOf("http://localhost:5173", "https://app.example"), policy.allowedOrigins)
        assertTrue(policy.enabled)
    }

    @Test
    fun `parse of null or blank disables the policy`() {
        assertFalse(CorsPolicy.parse(null).enabled)
        assertFalse(CorsPolicy.parse("").enabled)
        assertFalse(CorsPolicy.parse("   ").enabled)
    }

    @Test
    fun `parse never invents a wildcard from a blank value`() {
        assertNull(CorsPolicy.parse("").allowedOrigins.firstOrNull())
        assertEquals(emptySet<String>(), CorsPolicy.parse("").allowedOrigins)
    }
}
