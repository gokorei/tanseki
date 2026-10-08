package gokorei.tanseki.cli.transfer

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import gokorei.tanseki.adapters.context.sqlite.SqliteContextStore
import gokorei.tanseki.adapters.context.sqlite.SqliteSchema
import gokorei.tanseki.adapters.file.FileContextStore
import gokorei.tanseki.adapters.postgres.PostgresContextStore
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.testkit.FakePijulClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Instant

/**
 * End-to-end verification of the cross-profile transfer (MEF6S309): a
 * `vault -> library -> server` round trip must preserve content, ids, edges and
 * history. The server hop needs Postgres, so the test is skipped (not failed)
 * when Docker is unavailable.
 */
class CrossProfileTransferE2eTest {
    @TempDir
    lateinit var tmp: Path

    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)
    private val clock = Clock { now }

    private fun vaultStore(): FileContextStore {
        val root = Files.createDirectories(tmp.resolve("vault"))
        return FileContextStore(
            vault = VaultPath(root.toString()),
            pijul = FakePijulClient(),
            clock = clock
        )
    }

    private fun libraryStore(): SqliteContextStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SqliteSchema.create(driver)
        return SqliteContextStore(driver, clock)
    }

    private fun doc(id: String, content: String) =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = "hash-$content",
            revision = RevisionId("rev-$content"),
            updatedAt = now
        )

    @Test
    fun `vault to library to server preserves content ids edges and history`() {
        assumeTrue(dockerAvailable(), "Docker is required for the Postgres server hop")

        val vault = vaultStore()
        vault.write(doc("notes/a", "alpha"), "seed", "tester")
        vault.write(doc("notes/b", "beta"), "seed", "tester")
        // Two revisions of the same document, to exercise history transfer.
        vault.write(doc("notes/c", "gamma-1"), "seed", "tester")
        vault.write(doc("notes/c", "gamma-2"), "seed", "tester")
        vault.upsertEdge(Edge(DocId("notes/a"), DocId("notes/b"), RelTypes.LinksTo))

        val library = libraryStore()
        val vaultToLibrary = ModeTransfer().copy(vault, library)
        assertEquals(3, vaultToLibrary.documentsImported)

        val postgres = postgresContainer()
        postgres.start()
        try {
            PostgresContextStore.open(postgres.jdbcUrl, postgres.username, postgres.password, clock).use { server ->
                val libraryToServer = ModeTransfer().copy(library, server)
                assertEquals(3, libraryToServer.documentsImported)

                val ids = vault.list().map { it.id }.toSet()
                assertEquals(ids, library.list().map { it.id }.toSet())
                assertEquals(ids, server.list().map { it.id }.toSet())

                ids.forEach { id ->
                    assertEquals(vault.read(id)!!.content, library.read(id)!!.content)
                    assertEquals(vault.read(id)!!.content, server.read(id)!!.content)
                }

                assertEquals(listOf(DocId("notes/b")), library.neighbors(DocId("notes/a")).map { it.dst })
                assertEquals(listOf(DocId("notes/b")), server.neighbors(DocId("notes/a")).map { it.dst })

                val vaultHistory = vault.history(DocId("notes/c")).map { it.revision }.toSet()
                assertTrue(vaultHistory.size >= 2, "seed should create multiple revisions")
                assertEquals(vaultHistory, library.history(DocId("notes/c")).map { it.revision }.toSet())
                assertEquals(vaultHistory, server.history(DocId("notes/c")).map { it.revision }.toSet())

                // Re-running is idempotent: nothing copied, no duplicate revisions.
                val repeat = ModeTransfer().copy(library, server)
                assertEquals(0, repeat.documentsImported)
                assertEquals(0, repeat.revisionsImported)
                assertEquals(3, repeat.documentsSkipped)
            }
        } finally {
            postgres.stop()
        }
    }

    private fun postgresContainer(): PostgreSQLContainer<*> =
        PostgreSQLContainer(
            "postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea"
        ).withDatabaseName("tanseki")
            .withUsername("tanseki")
            .withPassword("tanseki")
            .waitingFor(
                Wait
                    .forListeningPort()
            )

    private fun dockerAvailable(): Boolean =
        runCatching { DockerClientFactory.instance().isDockerAvailable() }.getOrDefault(false)
}
