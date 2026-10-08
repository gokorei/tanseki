package gokorei.tanseki.composition

import gokorei.tanseki.adapters.meili.MeiliLookup
import gokorei.tanseki.adapters.postgres.PostgresContextStore
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.LogLevel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import kotlin.time.Instant

/** Verifies the server profile composes Postgres + Meilisearch behind the ports. */
@Testcontainers(disabledWithoutDocker = true)
class ServerCompositionTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `server profile composes postgres and meili and round-trips`() =
        runBlocking {
            val config =
                TansekiConfig(
                    profile = Profile.SERVER,
                    path = tmp,
                    indexDir = tmp.resolve("index"),
                    logLevel = LogLevel.INFO,
                    postgresJdbcUrl = postgres.jdbcUrl,
                    postgresUser = postgres.username,
                    postgresPassword = postgres.password,
                    meiliUrl = "http://${meili.host}:${meili.getMappedPort(7700)}",
                    meiliApiKey = "test-key"
                )

            Compositions.open(config).use { composition ->
                assertTrue(composition.store is PostgresContextStore)
                assertTrue(composition.lookup is MeiliLookup)

                val doc =
                    Document(
                        id = DocId("a"),
                        collection = Collection("vault"),
                        path = "a.md",
                        content = "hello composed world",
                        contentHash = "hash-a",
                        revision = RevisionId("rev-a"),
                        updatedAt = Instant.fromEpochSeconds(1_700_000_000)
                    )
                composition.store.write(doc, "m", "tester")
                composition.lookup.index(doc, emptyList())

                assertEquals("hello composed world", composition.store.read(DocId("a"))!!.content)
                assertEquals(1, composition.lookup.searchText("composed", Filters(), 10).size)
            }
        }

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                "postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea"
            ).withDatabaseName("tanseki")
                .withUsername("tanseki")
                .withPassword("tanseki")
                .waitingFor(Wait.forListeningPort())

        @Container
        @JvmStatic
        val meili: GenericContainer<*> =
            GenericContainer(
                "getmeili/meilisearch:v1.15.2@sha256:fe500cf9cca05cb9f027981583f28eccf17d35d94499c1f8b7b844e7418152fc"
            ).withExposedPorts(7700)
                .withEnv("MEILI_MASTER_KEY", "test-key")
                .withEnv("MEILI_NO_ANALYTICS", "true")
                .waitingFor(Wait.forHttp("/health").forPort(7700).forStatusCode(200))
    }
}
