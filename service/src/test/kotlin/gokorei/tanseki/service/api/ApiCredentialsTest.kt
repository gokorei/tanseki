package gokorei.tanseki.service.api

import gokorei.tanseki.composition.ApiTlsMode
import gokorei.tanseki.composition.Profile
import gokorei.tanseki.composition.TansekiConfig
import gokorei.tanseki.core.ports.LogLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ApiCredentialsTest {
    @Test
    fun `credential registry parses named collection and operation scopes`() {
        val registry = CredentialRegistry.parse("reader|reader-key|alpha, beta|GET,LIST,SEARCH")

        assertEquals("reader", registry.authenticate("reader-key")?.name)
        assertEquals(setOf("alpha", "beta"), registry.authenticate("reader-key")?.collections)
        assertEquals(null, registry.authenticate("missing"))
    }

    @Test
    fun `auth policy rejects a registry combined with an unauthenticated principal`() {
        val registry = CredentialRegistry.parse("reader|reader-key|alpha")

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                ApiAuthPolicy(
                    registry = registry,
                    unauthenticated = ApiPrincipal("anon", null, ApiOperation.entries.toSet(), "")
                )
            }
        assertNotNull(error.message)

        // Either one alone is still a valid policy.
        assertNotNull(ApiAuthPolicy.forRegistry(registry).registry)
        assertEquals(false, ApiAuthPolicy.unrestricted().authenticationRequired)
    }

    @Test
    fun `server policy requires scoped credentials and trusted proxy tls`() {
        val config =
            TansekiConfig(
                profile = Profile.SERVER,
                path = Path.of("."),
                indexDir = Path.of("index"),
                logLevel = LogLevel.INFO,
                httpHost = "127.0.0.1",
                apiTlsMode = ApiTlsMode.TRUSTED_PROXY,
                trustProxy = true,
                trustedProxySources = setOf("127.0.0.1/32"),
                apiCredentials = "reader|reader-key|alpha"
            )

        val policy = ServerApiPolicy.fromConfig(config)

        assertEquals("127.0.0.1", policy.host)
        assertNotNull(policy.authPolicy.registry)
        assertEquals(ApiTlsMode.TRUSTED_PROXY, policy.tlsMode)
    }

    @Test
    fun `server policy rejects missing or unscoped credentials`() {
        val missing =
            TansekiConfig(
                profile = Profile.SERVER,
                path = Path.of("."),
                indexDir = Path.of("index"),
                logLevel = LogLevel.INFO,
                apiTlsMode = ApiTlsMode.TRUSTED_PROXY,
                trustProxy = true,
                trustedProxySources = setOf("127.0.0.1/32")
            )
        assertThrows(IllegalArgumentException::class.java) { ServerApiPolicy.fromConfig(missing) }

        val unscoped = missing.copy(apiCredentials = "admin|admin-key")
        assertThrows(IllegalArgumentException::class.java) { ServerApiPolicy.fromConfig(unscoped) }
    }

    @Test
    fun `remote policy rejects missing tls and wildcard binds`() {
        val base =
            TansekiConfig(
                profile = Profile.LIBRARY,
                path = Path.of("."),
                indexDir = Path.of("index"),
                logLevel = LogLevel.INFO,
                httpHost = "10.0.0.10",
                apiCredentials = "reader|reader-key|alpha"
            )

        assertThrows(IllegalArgumentException::class.java) { ServerApiPolicy.fromConfig(base) }
        assertThrows(IllegalArgumentException::class.java) {
            ServerApiPolicy.fromConfig(
                base.copy(
                    httpHost = "0.0.0.0",
                    apiTlsMode = ApiTlsMode.TRUSTED_PROXY,
                    trustProxy = true,
                    trustedProxySources = setOf("127.0.0.1/32")
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerApiPolicy.fromConfig(
                base.copy(
                    httpHost = "10.0.0.10",
                    apiTlsMode = ApiTlsMode.TRUSTED_PROXY,
                    trustProxy = true,
                    trustedProxySources = setOf("10.0.0.10/32")
                )
            )
        }
    }

    @Test
    fun `trusted proxy source allowlist accepts exact and cidr sources`() {
        val sources = setOf("127.0.0.1/32", "10.20.0.0/24")

        assertEquals(true, isTrustedProxySource("127.0.0.1", sources))
        assertEquals(true, isTrustedProxySource("10.20.0.42", sources))
        assertEquals(false, isTrustedProxySource("10.20.1.42", sources))
        assertThrows(IllegalArgumentException::class.java) { validateTrustedProxySource("10.20.0.0/33") }
        assertThrows(IllegalArgumentException::class.java) { validateTrustedProxySource("proxy.example") }
    }

    @Test
    fun `trusted proxy sources accept ipv6 cidr ranges`() {
        val sources = setOf("::1/128", "2001:db8::/32", "[2001:db8:1::]/48")

        assertEquals(true, isTrustedProxySource("::1", sources))
        assertEquals(true, isTrustedProxySource("[::1]", sources))
        assertEquals(true, isTrustedProxySource("2001:db8:1:2::9", sources))
        assertEquals(false, isTrustedProxySource("2001:db9::1", sources))
        assertEquals(false, isTrustedProxySource("127.0.0.1", sources))
    }

    @Test
    fun `a host with a port or a malformed octet is not a trusted proxy source`() {
        // The IPv6 character class alone would accept every one of these.
        listOf("127.0.0.1:1234", "10.0.0.256", "::", ":", "1:2", "2001:db8:::1", "0x7f.0.0.1")
            .forEach { source ->
                assertThrows(
                    IllegalArgumentException::class.java,
                    { validateTrustedProxySource(source) },
                    source
                )
            }
        // Surrounding whitespace is an env-var artefact, not a different address.
        validateTrustedProxySource(" 10.0.0.1/32 ")
        assertEquals(true, isTrustedProxySource("10.0.0.1", setOf("10.0.0.1/32")))
    }

    @Test
    fun `only real ip literals are recognised as literals`() {
        listOf("127.0.0.1", "0.0.0.0", "255.255.255.255", "::1", "2001:db8::1", "::ffff:127.0.0.1")
            .forEach { assertEquals(true, isIpLiteral(it), it) }
        listOf(
            "",
            "  ",
            "127.0.0.1:80",
            "127.0.0.256",
            "999.1.1.1",
            "localhost",
            "proxy.example.com",
            "::",
            ":",
            "1:2",
            "2001:db8:::1",
            "fe80::1%eth0",
            "1.2.3.4.5",
            "0x7f.0.0.1"
        ).forEach { assertEquals(false, isIpLiteral(it), it) }
    }
}
