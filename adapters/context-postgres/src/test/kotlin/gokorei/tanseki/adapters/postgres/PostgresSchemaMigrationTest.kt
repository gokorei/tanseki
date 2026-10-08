package gokorei.tanseki.adapters.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.ports.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.time.Instant

private val POSTGRES_IMAGE =
    "postgres:16-alpine@sha256:" +
        "721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea"

/** Exercises the versioned migration runner in [PostgresSchema]. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresSchemaMigrationTest {
    @BeforeEach
    fun dropEverything() {
        dataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "DROP TABLE IF EXISTS blob_content, blobs, edges, revisions, documents, projection_operations, schema_migrations CASCADE"
                )
            }
        }
    }

    @Test
    fun `fresh database is migrated to every version`() {
        PostgresSchema.migrate(dataSource())

        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9), versions())
        assertTrue(indexExists("edges_src_idx"), "v2 index should exist")
        assertTrue(indexExists("documents_active_collection_path_key"))
        assertTrue(indexExists("projection_operations_dead_lettered_idx"), "v7 index should exist")
        assertTrue(columnExists("projection_operations", "dead_lettered"), "v7 column should exist")
    }

    @Test
    fun `migrate is idempotent`() {
        PostgresSchema.migrate(dataSource())
        val afterFirst = versions()

        PostgresSchema.migrate(dataSource())

        assertEquals(afterFirst, versions())
    }

    @Test
    fun `store open closes its datasource when migration fails`() {
        val ownedDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 1
                    initializationFailTimeout = 20_000
                }
            )
        ownedDataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE documents (id TEXT PRIMARY KEY)")
            }
        }

        assertThrows(Exception::class.java) {
            PostgresContextStore.open(ownedDataSource, Clock { Instant.fromEpochSeconds(0) })
        }
        assertTrue(ownedDataSource.isClosed)
    }

    @Test
    fun `pending migrations are applied on top of an existing schema`() {
        PostgresSchema.migrate(dataSource())
        // Simulate a database that missed v2 (e.g. after an upgrade).
        dataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DELETE FROM schema_migrations WHERE version = 2")
                statement.execute("DROP INDEX IF EXISTS edges_src_idx")
            }
        }
        assertEquals(listOf(1, 3, 4, 5, 6, 7, 8, 9), versions())

        PostgresSchema.migrate(dataSource())

        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9), versions())
        assertTrue(indexExists("edges_src_idx"), "v2 index should be re-applied")
    }

    @Test
    fun `the dead-letter migration adds the column and keeps existing outbox rows`() {
        PostgresSchema.migrate(dataSource())
        dataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DELETE FROM schema_migrations WHERE version = 7")
                statement.execute("ALTER TABLE projection_operations DROP COLUMN dead_lettered")
            }
            connection
                .prepareStatement(
                    "INSERT INTO projection_operations " +
                        "(id, document_id, revision, content_hash, kind, edge_version, projection_version, " +
                        "created_at, attempts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, "op-1")
                    statement.setString(2, "a")
                    statement.setString(3, "rev-1")
                    statement.setString(4, "hash")
                    statement.setString(5, "UPSERT")
                    statement.setString(6, "rev-1")
                    statement.setString(7, "rev-1")
                    statement.setLong(8, 1_700_000_000_000L)
                    statement.setInt(9, 5)
                    statement.executeUpdate()
                }
        }

        PostgresSchema.migrate(dataSource())

        assertTrue(columnExists("projection_operations", "dead_lettered"))
        dataSource().connection.use { connection ->
            connection
                .prepareStatement("SELECT dead_lettered, attempts FROM projection_operations WHERE id = ?")
                .use { statement ->
                    statement.setString(1, "op-1")
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        // An existing row must come back live: defaulting the new
                        // column to TRUE would silently drain the whole outbox.
                        assertFalse(rows.getBoolean("dead_lettered"))
                        assertEquals(5, rows.getInt("attempts"))
                    }
                }
        }
    }

    @Test
    fun `the frontmatter-raw migration adds the column and keeps existing documents`() {
        PostgresSchema.migrate(dataSource())
        dataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DELETE FROM schema_migrations WHERE version = 9")
                statement.execute("ALTER TABLE documents DROP COLUMN frontmatter_raw")
            }
            connection
                .prepareStatement(
                    "INSERT INTO documents " +
                        "(id, collection, path, frontmatter, content, content_hash, revision, updated_at, deleted) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, "a")
                    statement.setString(2, "vault")
                    statement.setString(3, "a.md")
                    statement.setString(4, "")
                    statement.setString(5, "one")
                    statement.setString(6, "hash-one")
                    statement.setString(7, "rev-1")
                    statement.setLong(8, 1L)
                    statement.setBoolean(9, false)
                    statement.executeUpdate()
                }
        }

        PostgresSchema.migrate(dataSource())

        assertTrue(columnExists("documents", "frontmatter_raw"))
        assertTrue(versions().contains(9))
        assertEquals(1, documentCount("a"))
        // A row that predates the column reads back with no verbatim block,
        // i.e. "synthesized frontmatter", rather than failing.
        val store = PostgresContextStore(dataSource(), Clock { Instant.fromEpochSeconds(0) })
        val read = store.read(DocId("a"))!!
        assertEquals("one", read.content)
        assertNull(read.frontmatter.rawFrontmatter)
    }

    @Test
    fun `path uniqueness migration preserves documents and allows collection namespaces`() {
        PostgresSchema.migrate(dataSource())
        dataSource().connection.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO documents " +
                        "(id, collection, path, frontmatter, content, content_hash, revision, updated_at, deleted) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, "a")
                    statement.setString(2, "vault")
                    statement.setString(3, "shared.md")
                    statement.setString(4, "{}")
                    statement.setString(5, "one")
                    statement.setString(6, "hash-one")
                    statement.setString(7, "rev-1")
                    statement.setLong(8, 1L)
                    statement.setBoolean(9, false)
                    statement.executeUpdate()
                }
            connection.createStatement().use { statement ->
                // Reproduce a genuine pre-v4 schema: v5 already replaced the
                // v4 table constraint with the partial unique index, so both
                // ledger rows go and both v4/v5 artifacts are reverted. The
                // re-migration below then replays v4 followed by v5.
                statement.execute("DELETE FROM schema_migrations WHERE version IN (4, 5)")
                statement.execute("DROP INDEX IF EXISTS documents_active_collection_path_key")
                statement.execute("ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_collection_path_key")
                statement.execute("ALTER TABLE documents ADD CONSTRAINT documents_path_key UNIQUE (path)")
            }
        }

        PostgresSchema.migrate(dataSource())

        assertFalse(constraintExists("documents_collection_path_key"))
        assertFalse(constraintExists("documents_path_key"))
        assertTrue(indexExists("documents_active_collection_path_key"))
        assertEquals(1, documentCount("a"))
        dataSource().connection.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO documents " +
                        "(id, collection, path, frontmatter, content, content_hash, revision, updated_at, deleted) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, "b")
                    statement.setString(2, "other")
                    statement.setString(3, "shared.md")
                    statement.setString(4, "{}")
                    statement.setString(5, "two")
                    statement.setString(6, "hash-two")
                    statement.setString(7, "rev-1")
                    statement.setLong(8, 1L)
                    statement.setBoolean(9, false)
                    statement.executeUpdate()
                }
        }
        assertEquals(2, documentCount())
    }

    private fun documentCount(id: String? = null): Int =
        dataSource().connection.use { connection ->
            val sql =
                if (id == null) {
                    "SELECT COUNT(*) FROM documents"
                } else {
                    "SELECT COUNT(*) FROM documents WHERE id = ?"
                }
            connection.prepareStatement(sql).use { statement ->
                if (id != null) statement.setString(1, id)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun constraintExists(name: String): Boolean =
        dataSource().connection.use { connection ->
            connection.prepareStatement("SELECT 1 FROM pg_constraint WHERE conname = ?").use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { rows -> rows.next() }
            }
        }

    private fun versions(): List<Int> =
        dataSource().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version FROM schema_migrations ORDER BY version").use { rows ->
                    buildList { while (rows.next()) add(rows.getInt("version")) }
                }
            }
        }

    private fun columnExists(table: String, column: String): Boolean =
        dataSource().connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?"
                ).use { statement ->
                    statement.setString(1, table)
                    statement.setString(2, column)
                    statement.executeQuery().use { rows -> rows.next() }
                }
        }

    private fun indexExists(name: String): Boolean =
        dataSource().connection.use { connection ->
            connection.prepareStatement("SELECT 1 FROM pg_indexes WHERE indexname = ?").use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { rows -> rows.next() }
            }
        }

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("tanseki")
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
