package gokorei.tanseki.service.api

import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.composition.ApiTlsMode
import gokorei.tanseki.composition.Profile
import gokorei.tanseki.composition.TansekiConfig
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.LogLevel
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreApiProxyTest {
    private val key = "proxy-key"
    private val http = HttpClient.newHttpClient()
    private var port = 0
    private var server: RunningApiServer? = null

    @BeforeAll
    fun startServer() {
        val running = StoreApiServer.startEphemeral(facade = facade(), serverPolicy = trustedProxyPolicy())
        server = running
        port = running.port
        repeat(100) {
            runCatching { if (send(forwarded = false, authenticated = false).statusCode() == 200) return@repeat }
            Thread.sleep(20)
        }
    }

    @AfterAll
    fun stopServer() {
        server?.close()
    }

    @Test
    fun `allowlisted proxy sources are accepted with or without forwarding metadata`() {
        assertEquals(403, send(forwarded = false, authenticated = true).statusCode())
        assertEquals(401, send(forwarded = true, authenticated = false).statusCode())
        assertEquals(200, send(forwarded = true, authenticated = true).statusCode())
    }

    @Test
    fun `a trusted listener refuses a source outside the allowlist`() {
        withServer(
            facade(),
            trustProxy = true,
            trustedProxySources = setOf("10.0.0.0/8")
        ) { otherPort ->
            assertEquals(
                403,
                get(otherPort, forwardedFor = "127.0.0.1", forwardedProto = "https", authenticated = true).statusCode()
            )
        }
    }

    @Test
    fun `a trusted listener requires a forwarded https client address`() {
        assertEquals(403, get(forwardedFor = "127.0.0.1", forwardedProto = "http", authenticated = true).statusCode())
        assertEquals(403, get(forwardedFor = "127.0.0.1", authenticated = true).statusCode())
        assertEquals(403, get(forwardedProto = "https", authenticated = true).statusCode())
    }

    @Test
    fun `a trusted listener refuses a non literal or placeholder client address`() {
        listOf("unknown", "attacker.example.com", "127.0.0.1:1234", "not-an-ip").forEach { client ->
            assertEquals(
                403,
                get(forwardedFor = client, forwardedProto = "https", authenticated = true).statusCode(),
                "client '$client' must be refused"
            )
        }
    }

    @Test
    fun `a trusted listener accepts a multi hop chain of literal addresses`() {
        assertEquals(
            200,
            get(forwardedFor = "203.0.113.7, 127.0.0.1", forwardedProto = "https", authenticated = true)
                .statusCode()
        )
    }

    @Test
    fun `an untrusted listener refuses forwarded metadata outright`() {
        withServer(facade(), trustProxy = false, trustedProxySources = emptySet()) { otherPort ->
            assertEquals(
                403,
                get(otherPort, forwardedFor = "127.0.0.1", forwardedProto = "https", authenticated = true).statusCode()
            )
            assertEquals(200, get(otherPort, authenticated = true).statusCode())
        }
    }

    @Test
    fun `every start overload honours the trusted proxy posture`() {
        val untrusted = facade()
        val policy =
            ApiAuthPolicy.forRegistry(
                CredentialRegistry.of(listOf(ApiCredential(key, "reader", setOf("alpha")))),
                trustProxy = true
            )

        // The policy declares proxy trust, so the overloads must be given the
        // matching allowlist instead of silently starting an unguarded listener.
        val missingSources =
            assertThrows(IllegalArgumentException::class.java) {
                StoreApiServer.startEphemeral(untrusted, policy).close()
            }
        assertTrue("trusted proxy source" in missingSources.message.orEmpty(), missingSources.message.orEmpty())

        val emptySources =
            assertThrows(IllegalArgumentException::class.java) {
                StoreApiServer
                    .startEphemeral(
                        facade = untrusted,
                        apiKey = key,
                        trustProxy = true,
                        trustedProxySources = emptySet()
                    ).close()
            }
        assertTrue("trusted proxy source" in emptySources.message.orEmpty(), emptySources.message.orEmpty())

        val malformedSource =
            assertThrows(IllegalArgumentException::class.java) {
                StoreApiServer
                    .startEphemeral(
                        facade = untrusted,
                        apiKey = key,
                        trustProxy = true,
                        trustedProxySources = setOf("not-a-cidr")
                    ).close()
            }
        assertTrue("IP literals or CIDR" in malformedSource.message.orEmpty(), malformedSource.message.orEmpty())

        val blankSource =
            assertThrows(IllegalArgumentException::class.java) {
                StoreApiServer
                    .startEphemeral(
                        facade = untrusted,
                        authPolicy = policy,
                        trustProxy = true,
                        trustedProxySources = setOf("  ")
                    ).close()
            }
        assertTrue("must not be blank" in blankSource.message.orEmpty(), blankSource.message.orEmpty())

        withServer(
            untrusted,
            authPolicy = policy,
            trustProxy = true,
            trustedProxySources = setOf("127.0.0.1/32")
        ) { otherPort ->
            assertEquals(
                401,
                get(otherPort, forwardedProto = "https", forwardedFor = "127.0.0.1", authenticated = false)
                    .statusCode(),
                "a trusted-proxy listener must still enforce the credential"
            )
            assertEquals(
                200,
                get(otherPort, forwardedProto = "https", forwardedFor = "127.0.0.1", authenticated = true)
                    .statusCode(),
                "a trusted-proxy listener with an allowlist serves an allowlisted proxy"
            )
        }
    }

    private fun withServer(
        facade: QueryFacade,
        authPolicy: ApiAuthPolicy =
            ApiAuthPolicy.forRegistry(
                CredentialRegistry.of(listOf(ApiCredential(key, "reader", setOf("alpha"))))
            ),
        trustProxy: Boolean,
        trustedProxySources: Set<String>,
        block: (Int) -> Unit
    ) {
        val running =
            StoreApiServer.startEphemeral(
                facade = facade,
                authPolicy = authPolicy,
                trustProxy = trustProxy,
                trustedProxySources = trustedProxySources
            )
        try {
            block(running.port)
        } finally {
            running.close()
        }
    }

    private fun trustedProxyPolicy(): ServerApiPolicy =
        ServerApiPolicy.fromConfig(
            TansekiConfig(
                profile = Profile.SERVER,
                path =
                    java.nio.file.Path
                        .of("."),
                indexDir =
                    java.nio.file.Path
                        .of("index"),
                logLevel = LogLevel.INFO,
                httpHost = "127.0.0.1",
                httpPort = DEFAULT_POLICY_PORT,
                apiTlsMode = ApiTlsMode.TRUSTED_PROXY,
                trustProxy = true,
                trustedProxySources = setOf("127.0.0.1/32"),
                apiCredentials = "proxy-reader|$key|alpha|GET,LIST,METRICS"
            )
        )

    private fun facade(): QueryFacade =
        QueryFacade(
            store = InMemoryContextStore(),
            lookup = LuceneLookup(),
            clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
            io = Dispatchers.Unconfined
        )

    private fun send(forwarded: Boolean, authenticated: Boolean): HttpResponse<String> =
        get(
            forwardedFor = "127.0.0.1".takeIf { forwarded },
            forwardedProto = "https".takeIf { forwarded },
            authenticated = authenticated
        )

    private fun get(
        port: Int = this.port,
        forwardedFor: String? = null,
        forwardedProto: String? = null,
        authenticated: Boolean
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port/v1/documents"))
                .timeout(Duration.ofSeconds(5))
                .header("X-API-Key", key.takeIf { authenticated } ?: "invalid")
                .apply {
                    forwardedFor?.let { header("X-Forwarded-For", it) }
                    forwardedProto?.let { header("X-Forwarded-Proto", it) }
                }.GET()
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `the policy allowlist is part of the recorded server posture`() {
        assertNotNull(trustedProxyPolicy().trustedProxySources)
        assertTrue(trustedProxyPolicy().trustProxy)
    }

    companion object {
        private const val DEFAULT_POLICY_PORT = 8_088
    }
}
