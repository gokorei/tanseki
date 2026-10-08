package gokorei.tanseki.adapters.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.ProjectionKind
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.text.MarkdownParser
import gokorei.tanseki.core.text.MarkdownSerializer
import gokorei.tanseki.testkit.ContextStoreContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private val POSTGRES_IMAGE =
    "postgres:16-alpine@sha256:" +
        "721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea"

/** Runs the shared [ContextStoreContract] against the Postgres Context Store. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresContextStoreContractTest : ContextStoreContract() {
    override fun newStore(): ContextStore {
        val dataSource = dataSource()
        PostgresSchema.migrate(dataSource)
        PostgresSchema.reset(dataSource)
        return PostgresContextStore(dataSource, Clock { Instant.fromEpochSeconds(0) })
    }

    @Test
    fun `delete rolls back the tombstone and history when the outbox write fails`() {
        val dataSource = dataSource()
        PostgresSchema.migrate(dataSource)
        PostgresSchema.reset(dataSource)
        val store = PostgresContextStore(dataSource, Clock { Instant.fromEpochSeconds(0) })
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE FUNCTION reject_delete_outbox() RETURNS trigger AS $$
                    BEGIN
                        IF NEW.kind = 'DELETE' THEN
                            RAISE EXCEPTION 'outbox failure';
                        END IF;
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql
                    """.trimIndent()
                )
                statement.execute(
                    "CREATE TRIGGER reject_delete_outbox BEFORE INSERT ON projection_operations " +
                        "FOR EACH ROW EXECUTE FUNCTION reject_delete_outbox()"
                )
            }
        }

        try {
            store.write(document("a", "x"), "add", "tester")

            assertThrows(Exception::class.java) {
                store.delete(DocId("a"), "remove", "tester")
            }

            assertEquals("x", store.read(DocId("a"))!!.content)
            assertEquals(1, store.history(DocId("a")).size)
            assertTrue(store.pending(10).none { it.kind == ProjectionKind.DELETE })
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER IF EXISTS reject_delete_outbox ON projection_operations")
                    statement.execute("DROP FUNCTION IF EXISTS reject_delete_outbox()")
                }
            }
        }
    }

    @Test
    fun `re-enqueuing an operation clears a stale lease so it is immediately claimable`() {
        val operation =
            gokorei.tanseki.core.domain.ProjectionOperation.upsert(
                document("leased", "content"),
                RevisionId("rev-1"),
                Instant.fromEpochSeconds(1_700_000_000)
            )
        val operations = requireNotNull(store.projectionOperations())
        operations.enqueue(operation)

        val held = operations.claim(operation.id, "worker", 1.hours, Instant.fromEpochSeconds(1_700_000_000))
        assertNotNull(held, "the first claim should take the lease")

        // A re-enqueue is a fresh intent: the previous attempt's live lease must not
        // leave the operation un-claimable until it expires.
        operations.enqueue(operation)

        val reclaimed = operations.claim(operation.id, "other", 1.hours, Instant.fromEpochSeconds(1_700_000_001))
        assertNotNull(reclaimed, "re-enqueue must clear the stale lease")
    }

    @Test
    fun `write rejects deleted documents`() {
        assertThrows(InvalidInputException::class.java) {
            store.write(document("a", "x").copy(deleted = true), "add", "tester")
        }
        assertNull(store.read(DocId("a")))
        assertTrue(store.history(DocId("a")).isEmpty())
    }

    @Test
    fun `revision ids cannot be reused for different content`() {
        store.write(document("a", "first", revision = "rev-1"), "add", "tester")

        assertThrows(ConflictException::class.java) {
            store.write(document("a", "second", revision = "rev-1"), "edit", "tester")
        }
        assertEquals("first", store.read(DocId("a"))!!.content)
        assertEquals(1, store.history(DocId("a")).size)
    }

    @Test
    fun `a verbatim-degraded frontmatter block survives the postgres write path`() {
        val markdown = "---\ntitle: First Title\ntitle: Second Title\n---\n\nbody\n"
        val parsed = MarkdownParser.parse(markdown)
        assertNotNull(parsed.frontmatterError, "the fixture must actually degrade, not parse")
        store.write(
            document("notes/dup", markdown, revision = "rev-1").copy(frontmatter = parsed.frontmatter),
            "add",
            "tester"
        )

        val read = store.read(DocId("notes/dup"))!!
        assertEquals(markdown, read.content, "content must stay byte-identical")
        assertNull(read.frontmatter.title, "the typed view must stay empty for a degraded block")
        assertEquals(
            parsed.frontmatter.rawFrontmatter,
            read.frontmatter.rawFrontmatter,
            "the verbatim block was dropped by the postgres write path"
        )

        // A body edit re-renders through MarkdownSerializer, exactly as
        // DocumentCommandService.upsert does: only the preserved verbatim block
        // keeps the degraded properties alive.
        val submitted = read.content.replace("body", "edited body")
        val reparsed = MarkdownParser.parse(submitted)
        val rendered = MarkdownSerializer.render(reparsed.frontmatter, reparsed.body)
        store.write(
            read.copy(
                content = rendered,
                contentHash = "hash-dup-2",
                revision = RevisionId("rev-2"),
                frontmatter = reparsed.frontmatter
            ),
            "edit",
            "tester"
        )

        val after = store.read(DocId("notes/dup"))!!
        assertTrue(
            after.content.contains("title: First Title") && after.content.contains("title: Second Title"),
            "the body edit deleted the degraded block: ${after.content}"
        )
        assertEquals(parsed.frontmatter.rawFrontmatter, after.frontmatter.rawFrontmatter)
    }

    @Test
    fun `collection ownership remains immutable across deletion`() {
        store.write(document("a", "first", revision = "rev-1"), "add", "tester")
        assertThrows(ConflictException::class.java) {
            store.write(
                document("a", "second", revision = "rev-2").copy(collection = Collection("other")),
                "move",
                "tester"
            )
        }
        store.delete(DocId("a"), "remove", "tester")
        assertThrows(ConflictException::class.java) {
            store.write(
                document("a", "recreated", revision = "rev-3").copy(collection = Collection("other")),
                "recreate",
                "tester"
            )
        }
        assertEquals(Collection("vault"), store.historyOwner(DocId("a"))!!.collection)
    }

    @Test
    fun `write and delete work with a single-connection pool`() {
        val singleConnectionDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 1
                    connectionTimeout = 1_000
                    initializationFailTimeout = 20_000
                }
            )
        try {
            PostgresSchema.migrate(singleConnectionDataSource)
            PostgresSchema.reset(singleConnectionDataSource)
            val singleConnectionStore =
                PostgresContextStore(singleConnectionDataSource, Clock { Instant.fromEpochSeconds(0) })
            singleConnectionStore.write(document("a", "one", revision = "rev-1"), "add", "tester")
            val deleted = singleConnectionStore.delete(DocId("a"), "remove", "tester")
            singleConnectionStore.write(
                document("a", "two", revision = "rev-2"),
                "recreate",
                "tester",
                ifRevision = deleted.revision
            )

            assertEquals("two", singleConnectionStore.read(DocId("a"))!!.content)
            assertEquals(
                listOf(RevisionId("rev-1"), deleted.revision, RevisionId("rev-2")),
                singleConnectionStore.history(DocId("a")).map { it.revision }
            )
        } finally {
            singleConnectionDataSource.close()
        }
    }

    override fun closeStore(store: ContextStore) = Unit

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName
                    .parse("postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea")
                    .asCompatibleSubstituteFor("postgres")
            ).withDatabaseName("tanseki")
                .withUsername("tanseki")
                .withPassword("tanseki")
                .waitingFor(
                    org.testcontainers.containers.wait.strategy.Wait
                        .forListeningPort()
                )

        private var dataSource: HikariDataSource? = null

        private fun dataSource(): HikariDataSource =
            dataSource ?: HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 2
                    initializationFailTimeout = 20_000
                }
            ).also { dataSource = it }
    }
}
