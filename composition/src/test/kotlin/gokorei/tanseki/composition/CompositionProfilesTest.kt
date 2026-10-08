package gokorei.tanseki.composition

import gokorei.tanseki.adapters.context.sqlite.SqliteContextStore
import gokorei.tanseki.adapters.file.FileContextStore
import gokorei.tanseki.adapters.plain.PlainDirectoryStore
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.ports.LogLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CompositionProfilesTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `vault mode selects the file store`() {
        // Vault mode shells out to the `pijul` binary on open, so without one
        // this is an environment gap rather than a product defect — skipped, in
        // the same way container suites skip without Docker. The pijul job
        // covers a real vault through the /v1 seam instead.
        assumeTrue(pijulAvailable(), "pijul binary is required to open vault mode")
        val vault = Files.createDirectories(tmp.resolve("vault"))
        Compositions.openLocal(vault, Profile.VAULT, tmp.resolve("index")).use { composition ->
            assertTrue(composition.store is FileContextStore)
            assertEquals(Profile.VAULT, composition.profile)
            assertEquals(0, composition.store.list(Collection("vault")).size)
            assertEquals(Consistency.READ_YOUR_WRITES, composition.store.capabilities().consistency)
        }
    }

    @Test
    fun `library mode selects the sqlite store and is idempotent to open`() {
        val db = tmp.resolve("library.db")
        Compositions.openLocal(db, Profile.LIBRARY, tmp.resolve("index")).use { first ->
            assertTrue(first.store is SqliteContextStore)
            assertEquals(Consistency.STRONG, first.store.capabilities().consistency)
        }
        Compositions.openLocal(db, Profile.LIBRARY, tmp.resolve("index")).use { second ->
            assertTrue(second.store is SqliteContextStore)
        }
    }

    @Test
    fun `directory mode selects the plain read-only store`() {
        val foreign = Files.createDirectories(tmp.resolve("foreign"))
        Files.writeString(foreign.resolve("note.md"), "# note\n")
        Compositions.openLocal(foreign, Profile.DIRECTORY, tmp.resolve("index")).use { composition ->
            assertTrue(composition.store is PlainDirectoryStore)
            assertEquals(Profile.DIRECTORY, composition.profile)
            assertEquals(listOf(DocId("note")), composition.store.list().map { it.id })
            assertFalse(composition.store.capabilities().supportsHistory)
        }
    }

    @Test
    fun `server mode fails fast as deferred`() {
        assertThrows(UnsupportedOperationException::class.java) {
            Compositions.openLocal(tmp, Profile.SERVER, tmp.resolve("index"))
        }
    }

    @Test
    fun `config loads from the environment`() {
        val config =
            TansekiConfig.fromEnv(
                mapOf(
                    TansekiConfig.ENV_PROFILE to "library",
                    TansekiConfig.ENV_PATH to "/tmp/tanseki",
                    TansekiConfig.ENV_INDEX_DIR to "/tmp/tanseki-index",
                    TansekiConfig.ENV_LOG_LEVEL to "debug",
                    TansekiConfig.ENV_API_KEY to "secret"
                )
            )

        assertEquals(Profile.LIBRARY, config.profile)
        assertEquals(Path.of("/tmp/tanseki"), config.path)
        assertEquals(Path.of("/tmp/tanseki-index"), config.indexDir)
        assertEquals(LogLevel.DEBUG, config.logLevel)
        assertEquals("secret", config.apiKey)
    }

    @Test
    fun `unknown profile fails closed`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                TansekiConfig.fromEnv(mapOf(TansekiConfig.ENV_PROFILE to "mystery"))
            }

        assertTrue(error.message.orEmpty().contains("unknown profile 'mystery'"))
        assertTrue(error.message.orEmpty().contains("vault, library, server"))
    }

    @Test
    fun `server profile reports missing configuration`() {
        val config = TansekiConfig(Profile.SERVER, tmp, tmp.resolve("index"), LogLevel.INFO)

        val error = assertThrows(IllegalArgumentException::class.java) { Compositions.open(config) }

        assertTrue(error.message.orEmpty().contains(TansekiConfig.ENV_POSTGRES_URL))
        assertTrue(error.message.orEmpty().contains(TansekiConfig.ENV_POSTGRES_USER))
        assertTrue(error.message.orEmpty().contains(TansekiConfig.ENV_POSTGRES_PASSWORD))
        assertTrue(error.message.orEmpty().contains(TansekiConfig.ENV_MEILI_URL))
    }

    @Test
    fun `server profile requires explicit postgres credentials`() {
        val base =
            TansekiConfig(
                profile = Profile.SERVER,
                path = tmp,
                indexDir = tmp.resolve("index"),
                logLevel = LogLevel.INFO,
                postgresJdbcUrl = "jdbc:postgresql://localhost/tanseki",
                meiliUrl = "http://localhost:7700"
            )

        val missingUser = assertThrows(IllegalArgumentException::class.java) { Compositions.open(base) }
        val missingPassword =
            assertThrows(IllegalArgumentException::class.java) {
                Compositions.open(base.copy(postgresUser = "tanseki"))
            }

        assertTrue(missingUser.message.orEmpty().contains(TansekiConfig.ENV_POSTGRES_USER))
        assertTrue(missingPassword.message.orEmpty().contains(TansekiConfig.ENV_POSTGRES_PASSWORD))
    }

    @Test
    fun `fully populated server config passes validation`() {
        val config =
            TansekiConfig(
                profile = Profile.SERVER,
                path = tmp,
                indexDir = tmp.resolve("index"),
                logLevel = LogLevel.INFO,
                postgresJdbcUrl = "jdbc:postgresql://localhost/tanseki",
                postgresUser = "tanseki",
                postgresPassword = "secret",
                meiliUrl = "http://localhost:7700"
            )

        Compositions.requireServerConfig(config)
    }

    @Test
    fun `each missing server field is named and present fields are not`() {
        val complete =
            TansekiConfig(
                profile = Profile.SERVER,
                path = tmp,
                indexDir = tmp.resolve("index"),
                logLevel = LogLevel.INFO,
                postgresJdbcUrl = "jdbc:postgresql://localhost/tanseki",
                postgresUser = "tanseki",
                postgresPassword = "secret",
                meiliUrl = "http://localhost:7700"
            )

        val cases =
            listOf(
                complete.copy(postgresJdbcUrl = null) to TansekiConfig.ENV_POSTGRES_URL,
                complete.copy(postgresUser = null) to TansekiConfig.ENV_POSTGRES_USER,
                complete.copy(postgresPassword = null) to TansekiConfig.ENV_POSTGRES_PASSWORD,
                complete.copy(meiliUrl = null) to TansekiConfig.ENV_MEILI_URL
            )

        for ((config, expected) in cases) {
            val error = assertThrows(IllegalArgumentException::class.java) { Compositions.requireServerConfig(config) }
            val message = error.message.orEmpty()
            assertTrue(message.contains(expected), "expected $expected in '$message'")
            val onlyMissing =
                listOf(
                    TansekiConfig.ENV_POSTGRES_URL,
                    TansekiConfig.ENV_POSTGRES_USER,
                    TansekiConfig.ENV_POSTGRES_PASSWORD,
                    TansekiConfig.ENV_MEILI_URL
                ).filter { it != expected }
            for (present in onlyMissing) {
                assertTrue(!message.contains(present), "present field $present wrongly reported in '$message'")
            }
        }
    }

    @Test
    fun `whitespace-only server password is rejected`() {
        val config =
            TansekiConfig(
                profile = Profile.SERVER,
                path = tmp,
                indexDir = tmp.resolve("index"),
                logLevel = LogLevel.INFO,
                postgresJdbcUrl = "jdbc:postgresql://localhost/tanseki",
                postgresUser = "tanseki",
                postgresPassword = "   ",
                meiliUrl = "http://localhost:7700"
            )

        val error = assertThrows(IllegalArgumentException::class.java) { Compositions.requireServerConfig(config) }
        assertTrue(error.message.orEmpty().contains(TansekiConfig.ENV_POSTGRES_PASSWORD))
    }

    @Test
    fun `config loads embedding settings`() {
        val config =
            TansekiConfig.fromEnv(
                mapOf(
                    TansekiConfig.ENV_EMBEDDING_DIR to "/tmp/models",
                    TansekiConfig.ENV_EMBEDDING_MODEL to "all-MiniLM-L6-v2"
                )
            )

        assertEquals(Path.of("/tmp/models"), config.embeddingDir)
        assertEquals("all-MiniLM-L6-v2", config.embeddingModel)
    }

    @Test
    fun `embedder falls back to null when no usable model is configured`() {
        val base = TansekiConfig(Profile.VAULT, tmp, tmp.resolve("index"), LogLevel.INFO)
        assertNull(Compositions.openEmbedder(base))
        assertNull(Compositions.openEmbedder(base.copy(embeddingDir = tmp)))
    }

    @Test
    fun `config defaults to vault in the current directory`() {
        val config = TansekiConfig.fromEnv(emptyMap())
        assertEquals(Profile.VAULT, config.profile)
        assertEquals(Path.of("."), config.path)
        assertEquals(Path.of("./.tanseki/index"), config.indexDir)
        assertEquals(LogLevel.INFO, config.logLevel)
    }

    @Test
    fun `unknown log level fails closed and empty values use defaults`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                TansekiConfig.fromEnv(mapOf(TansekiConfig.ENV_LOG_LEVEL to "verbose"))
            }
        assertTrue(error.message.orEmpty().contains(TansekiConfig.ENV_LOG_LEVEL))

        val empty =
            TansekiConfig.fromEnv(
                mapOf(
                    TansekiConfig.ENV_LOG_LEVEL to "",
                    TansekiConfig.ENV_TLS_MODE to "",
                    TansekiConfig.ENV_TRUST_PROXY to ""
                )
            )
        assertEquals(LogLevel.INFO, empty.logLevel)
        assertEquals(ApiTlsMode.NONE, empty.apiTlsMode)
        assertEquals(false, empty.trustProxy)
    }

    @Test
    fun `config loads remote transport and credential policy`() {
        val config =
            TansekiConfig.fromEnv(
                mapOf(
                    TansekiConfig.ENV_HTTP_HOST to "10.0.0.10",
                    TansekiConfig.ENV_HTTP_PORT to "9443",
                    TansekiConfig.ENV_TLS_MODE to "trusted_proxy",
                    TansekiConfig.ENV_TRUST_PROXY to "true",
                    TansekiConfig.ENV_TRUSTED_PROXY_SOURCES to "10.0.0.10/32,2001:db8::/64",
                    TansekiConfig.ENV_API_CREDENTIALS to "reader|reader-key|alpha"
                )
            )

        assertEquals("10.0.0.10", config.httpHost)
        assertEquals(9443, config.httpPort)
        assertEquals(ApiTlsMode.TRUSTED_PROXY, config.apiTlsMode)
        assertTrue(config.trustProxy)
        assertEquals(setOf("10.0.0.10/32", "2001:db8::/64"), config.trustedProxySources)
        assertEquals("reader|reader-key|alpha", config.apiCredentials)
    }

    @Test
    fun `invalid remote transport values fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            TansekiConfig.fromEnv(mapOf(TansekiConfig.ENV_HTTP_PORT to "not-a-port"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TansekiConfig.fromEnv(mapOf(TansekiConfig.ENV_TLS_MODE to "plain"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TansekiConfig.fromEnv(mapOf(TansekiConfig.ENV_TRUST_PROXY to "sometimes"))
        }
    }

    private fun pijulAvailable(): Boolean {
        // Same resolution as PijulCliClient: explicit binary env first, `pijul`
        // on PATH otherwise. A probe, not a version check — vault mode only
        // needs the binary to exist here; the pijul job pins the version.
        val binary = System.getenv("TANSEKI_PIJUL_BINARY") ?: "pijul"
        return runCatching {
            ProcessBuilder(binary, "--version").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
    }
}
