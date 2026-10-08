package gokorei.tanseki.adapters.postgres

import java.sql.Connection
import javax.sql.DataSource

/**
 * Server-profile Context Store schema, managed as an ordered list of **versioned
 * migrations**.
 *
 * Documents/revisions/edges/blob content live in one Postgres database.
 * Embeddings are **not** here — they are derived and live in the Lookup (see
 * `lookup-meili` / pgvector).
 *
 * Applied versions are recorded in `schema_migrations`, so startup [migrate]
 * replays only what a given database has not seen yet. Each migration is
 * replay-safe (`IF NOT EXISTS`) and committed atomically together with its
 * version row, making [migrate] idempotent and safe to run on every start. A
 * session-scoped advisory lock serializes concurrent starters.
 *
 * The table reference lives in the module `README.md`.
 */
object PostgresSchema {
    /** One ordered schema change. Statements must be replay-safe. */
    private data class Migration(
        val version: Int,
        val name: String,
        val statements: List<String>
    )

    private val MIGRATIONS: List<Migration> =
        listOf(
            Migration(
                version = 1,
                name = "initial_schema",
                statements =
                    listOf(
                        """
                        CREATE TABLE IF NOT EXISTS documents (
                            id           TEXT PRIMARY KEY,
                            collection   TEXT NOT NULL,
                            path         TEXT NOT NULL UNIQUE,
                            frontmatter  TEXT NOT NULL DEFAULT '{}',
                            content      TEXT NOT NULL,
                            content_hash TEXT NOT NULL,
                            revision     TEXT NOT NULL,
                            updated_at   BIGINT NOT NULL,
                            deleted      BOOLEAN NOT NULL DEFAULT FALSE,
                            frontmatter_raw TEXT
                        )
                        """.trimIndent(),
                        "CREATE INDEX IF NOT EXISTS documents_collection_idx ON documents(collection)",
                        """
                        CREATE TABLE IF NOT EXISTS revisions (
                            doc_id       TEXT NOT NULL,
                            revision     TEXT NOT NULL,
                            author       TEXT NOT NULL,
                            message      TEXT NOT NULL,
                            content_hash TEXT NOT NULL,
                            content      TEXT,
                            created_at   BIGINT NOT NULL,
                            deps         TEXT NOT NULL DEFAULT '',
                            PRIMARY KEY (doc_id, revision)
                        )
                        """.trimIndent(),
                        """
                        CREATE TABLE IF NOT EXISTS edges (
                            src   TEXT NOT NULL,
                            dst   TEXT NOT NULL,
                            rel   TEXT NOT NULL,
                            props TEXT NOT NULL DEFAULT '',
                            PRIMARY KEY (src, rel, dst)
                        )
                        """.trimIndent(),
                        "CREATE INDEX IF NOT EXISTS edges_dst_idx ON edges(dst, rel)",
                        "CREATE TABLE IF NOT EXISTS blobs (hash TEXT PRIMARY KEY, algorithm TEXT NOT NULL DEFAULT 'sha256', size BIGINT NOT NULL)",
                        "CREATE TABLE IF NOT EXISTS blob_content (hash TEXT PRIMARY KEY REFERENCES blobs(hash), bytes BYTEA NOT NULL)"
                    )
            ),
            Migration(
                version = 2,
                name = "edges_src_index",
                statements = listOf("CREATE INDEX IF NOT EXISTS edges_src_idx ON edges(src, rel)")
            ),
            Migration(
                version = 3,
                name = "projection_operations",
                statements =
                    listOf(
                        """
                        CREATE TABLE IF NOT EXISTS projection_operations (
                            id                 TEXT PRIMARY KEY,
                            document_id        TEXT NOT NULL,
                            revision           TEXT NOT NULL,
                            content_hash       TEXT NOT NULL,
                            kind               TEXT NOT NULL,
                            edge_version       TEXT NOT NULL,
                            projection_version TEXT NOT NULL,
                            created_at         BIGINT NOT NULL
                        )
                        """.trimIndent(),
                        "CREATE INDEX IF NOT EXISTS projection_operations_pending_idx ON projection_operations(created_at, id)"
                    )
            ),
            Migration(
                version = 4,
                name = "document_path_per_collection",
                statements =
                    listOf(
                        "ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_path_key",
                        """
                        DO $$
                        BEGIN
                            IF NOT EXISTS (
                                SELECT 1
                                FROM pg_constraint
                                WHERE conrelid = 'documents'::regclass
                                  AND conname = 'documents_collection_path_key'
                            ) THEN
                                ALTER TABLE documents
                                    ADD CONSTRAINT documents_collection_path_key UNIQUE (collection, path);
                            END IF;
                        END
                        $$;
                        """.trimIndent()
                    )
            ),
            Migration(
                version = 5,
                name = "active_document_path_per_collection",
                statements =
                    listOf(
                        "ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_collection_path_key",
                        "CREATE UNIQUE INDEX IF NOT EXISTS documents_active_collection_path_key ON documents(collection, path) WHERE deleted = FALSE"
                    )
            ),
            Migration(
                version = 6,
                name = "projection_attempts",
                statements =
                    listOf(
                        "ALTER TABLE projection_operations ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0"
                    )
            ),
            Migration(
                version = 7,
                name = "projection_dead_letter",
                statements =
                    listOf(
                        "ALTER TABLE projection_operations ADD COLUMN IF NOT EXISTS dead_lettered BOOLEAN NOT NULL DEFAULT FALSE",
                        """
                        CREATE INDEX IF NOT EXISTS projection_operations_dead_lettered_idx
                        ON projection_operations(dead_lettered, created_at, id)
                        """.trimIndent()
                    )
            ),
            Migration(
                version = 8,
                name = "projection_lease",
                statements =
                    listOf(
                        // NULL means unowned, and every one of these is nullable on
                        // purpose: a row that has never been claimed must read as
                        // claimable, not as owned by nobody.
                        "ALTER TABLE projection_operations ADD COLUMN IF NOT EXISTS lease_owner TEXT",
                        "ALTER TABLE projection_operations ADD COLUMN IF NOT EXISTS lease_token TEXT",
                        "ALTER TABLE projection_operations ADD COLUMN IF NOT EXISTS lease_expires_at BIGINT",
                        """
                        CREATE INDEX IF NOT EXISTS projection_operations_lease_idx
                        ON projection_operations(lease_expires_at)
                        """.trimIndent()
                    )
            ),
            Migration(
                version = 9,
                name = "frontmatter_raw",
                statements =
                    listOf(
                        // Verbatim frontmatter provenance: the typed `frontmatter`
                        // column cannot model every YAML shape (duplicate keys,
                        // comments, key order), so without this the read side
                        // re-renders an empty canonical block and a later body
                        // edit deletes the user's block. NULL means synthesized
                        // frontmatter, which renders canonically.
                        "ALTER TABLE documents ADD COLUMN IF NOT EXISTS frontmatter_raw TEXT"
                    )
            )
        )

    private const val MIGRATION_ADVISORY_LOCK = 7_271_001L

    private val MIGRATIONS_TABLE_DDL =
        """
        CREATE TABLE IF NOT EXISTS schema_migrations (
            version    INT    PRIMARY KEY,
            name       TEXT   NOT NULL,
            applied_at BIGINT NOT NULL
        )
        """.trimIndent()

    /** Applies every migration this database has not yet recorded. Idempotent. */
    fun migrate(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute(MIGRATIONS_TABLE_DDL)
                    // Serialize concurrent startups; the lock releases on commit/rollback.
                    statement.execute("SELECT pg_advisory_xact_lock($MIGRATION_ADVISORY_LOCK)")
                }
                val applied = appliedVersions(connection)
                MIGRATIONS.filterNot { it.version in applied }.forEach { apply(connection, it) }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun appliedVersions(connection: Connection): Set<Int> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version FROM schema_migrations").use { rows ->
                buildSet { while (rows.next()) add(rows.getInt("version")) }
            }
        }

    private fun apply(connection: Connection, migration: Migration) {
        connection.createStatement().use { statement ->
            migration.statements.forEach(statement::execute)
        }
        connection
            .prepareStatement("INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)")
            .use { statement ->
                statement.setInt(1, migration.version)
                statement.setString(2, migration.name)
                statement.setLong(3, System.currentTimeMillis())
                statement.executeUpdate()
            }
    }

    /**
     * Truncate all data tables (test isolation). The migration ledger is left
     * intact so the schema is not re-derived between tests.
     */
    fun reset(dataSource: DataSource) {
        dataSource.connection.use { connection: Connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE documents, revisions, edges, projection_operations, blobs, blob_content")
            }
        }
    }
}
