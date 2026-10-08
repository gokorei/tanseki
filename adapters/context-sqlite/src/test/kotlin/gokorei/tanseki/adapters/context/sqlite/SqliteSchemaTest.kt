package gokorei.tanseki.adapters.context.sqlite

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import gokorei.tanseki.adapters.context.sqlite.db.TansekiDatabase
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.time.Instant

class SqliteSchemaTest {
    private fun freshDatabase(): Pair<JdbcSqliteDriver, TansekiDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val database = SqliteSchema.create(driver)
        return driver to database
    }

    private fun execute(driver: JdbcSqliteDriver, sql: String) {
        driver.execute(null, sql, 0) {}
    }

    private fun userVersion(driver: JdbcSqliteDriver): Long =
        driver
            .executeQuery(
                identifier = null,
                sql = "PRAGMA user_version",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0))
                },
                parameters = 0
            ).value ?: 0L

    @Test
    fun `schema exposes at least one version`() {
        assertTrue(SqliteSchema.version >= 1)
    }

    @Test
    fun `ensure is idempotent and persists the current version`() {
        val (driver, _) = freshDatabase()
        try {
            assertEquals(SqliteSchema.version, userVersion(driver))
            val database = SqliteSchema.ensure(driver)
            assertEquals(SqliteSchema.version, userVersion(driver))
            assertNull(database.documentsQueries.selectById("missing").executeAsOneOrNull())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `ensure upgrades a legacy stamped database before checking for missing tables`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            listOf(
                """
                CREATE TABLE documents (
                    id TEXT NOT NULL PRIMARY KEY,
                    collection TEXT NOT NULL,
                    path TEXT NOT NULL UNIQUE,
                    frontmatter TEXT NOT NULL DEFAULT '{}',
                    content TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE revisions (
                    doc_id TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    author TEXT NOT NULL,
                    message TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    content TEXT,
                    created_at INTEGER NOT NULL,
                    deps TEXT NOT NULL DEFAULT '[]',
                    PRIMARY KEY (doc_id, revision)
                )
                """,
                """
                CREATE TABLE edges (
                    src TEXT NOT NULL,
                    dst TEXT NOT NULL,
                    rel TEXT NOT NULL,
                    props TEXT NOT NULL DEFAULT '{}',
                    PRIMARY KEY (src, rel, dst)
                )
                """,
                "CREATE INDEX edges_dst_idx ON edges(dst, rel)",
                "CREATE INDEX edges_src_idx ON edges(src, rel)",
                // A database at version 2 has already been through 1.sqm.
                """
                CREATE TABLE projection_operations (
                    id                 TEXT    PRIMARY KEY,
                    document_id        TEXT    NOT NULL,
                    revision           TEXT    NOT NULL,
                    content_hash       TEXT    NOT NULL,
                    kind               TEXT    NOT NULL,
                    edge_version       TEXT    NOT NULL,
                    projection_version TEXT    NOT NULL,
                    created_at         INTEGER NOT NULL
                )
                """,
                "CREATE INDEX projection_operations_pending_idx ON projection_operations(created_at, id)"
            ).forEach { execute(driver, it.trimIndent()) }
            execute(
                driver,
                "INSERT INTO documents VALUES ('a', 'vault', 'a.md', '', 'kept', 'hash', 'rev-1', 1, 0)"
            )
            execute(driver, "PRAGMA user_version = 1")

            val database = SqliteSchema.ensure(driver)

            assertEquals(SqliteSchema.version, userVersion(driver))
            assertEquals(
                "kept",
                database.documentsQueries
                    .selectById("a")
                    .executeAsOne()
                    .content
            )
            val store = SqliteContextStore(driver, Clock { Instant.fromEpochSeconds(0) })
            assertEquals("kept", store.read(DocId("a"))!!.content)
            val ref = store.putBlob("hello".toByteArray())
            assertEquals("hello", String(store.getBlob(ref)))
            assertEquals(0, store.projectionOperations()!!.backlog().pending)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `ensure upgrades an unstamped legacy database before checking for missing tables`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            listOf(
                """
                CREATE TABLE documents (
                    id TEXT NOT NULL PRIMARY KEY,
                    collection TEXT NOT NULL,
                    path TEXT NOT NULL,
                    frontmatter TEXT NOT NULL DEFAULT '{}',
                    content TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE revisions (
                    doc_id TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    author TEXT NOT NULL,
                    message TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    content TEXT,
                    created_at INTEGER NOT NULL,
                    deps TEXT NOT NULL DEFAULT '[]',
                    PRIMARY KEY (doc_id, revision)
                )
                """,
                """
                CREATE TABLE edges (
                    src TEXT NOT NULL,
                    dst TEXT NOT NULL,
                    rel TEXT NOT NULL,
                    props TEXT NOT NULL DEFAULT '{}',
                    PRIMARY KEY (src, rel, dst)
                )
                """
            ).forEach { execute(driver, it.trimIndent()) }
            execute(
                driver,
                "INSERT INTO documents VALUES ('a', 'vault', 'a.md', '', 'kept', 'hash', 'rev-1', 1, 0)"
            )
            assertEquals(0L, userVersion(driver))

            val database = SqliteSchema.ensure(driver)

            assertEquals(SqliteSchema.version, userVersion(driver))
            assertEquals(
                "kept",
                database.documentsQueries
                    .selectById("a")
                    .executeAsOne()
                    .content
            )
            val store = SqliteContextStore(driver, Clock { Instant.fromEpochSeconds(0) })
            assertEquals("kept", store.read(DocId("a"))!!.content)
            val ref = store.putBlob("hello".toByteArray())
            assertEquals("hello", String(store.getBlob(ref)))
            store.write(
                Document(
                    id = DocId("b"),
                    collection = Collection("vault"),
                    path = "b.md",
                    content = "body",
                    contentHash = "hash-b",
                    revision = RevisionId("rev-b"),
                    updatedAt = Instant.fromEpochSeconds(0)
                ),
                "m",
                "agent"
            )
            val operation = store.projectionOperations()!!.pending(10).single()
            assertEquals(1, store.projectionOperations()!!.recordFailure(operation))
            assertEquals(1, store.projectionOperations()!!.backlog().stuck)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `ensure refuses an unstamped database whose table is missing a required column`() {
        val (driver, _) = freshDatabase()
        try {
            execute(driver, "ALTER TABLE documents RENAME TO documents_current")
            execute(
                driver,
                """
                CREATE TABLE documents (
                    id TEXT NOT NULL PRIMARY KEY,
                    collection TEXT NOT NULL,
                    path TEXT NOT NULL,
                    frontmatter TEXT NOT NULL DEFAULT '{}',
                    content TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            execute(
                driver,
                "INSERT INTO documents SELECT id, collection, path, frontmatter, content, content_hash, " +
                    "revision, updated_at FROM documents_current"
            )
            execute(driver, "DROP TABLE documents_current")
            execute(driver, "PRAGMA user_version = 0")

            val error = assertThrows(IllegalStateException::class.java) { SqliteSchema.ensure(driver) }

            assertTrue(error.message.orEmpty().contains("documents.deleted"))
            assertEquals(0L, userVersion(driver))
        } finally {
            driver.close()
        }
    }

    @Test
    fun `configure enables wal and a busy timeout for concurrent connections`() {
        val file = Files.createTempFile("tanseki-sqlite-", ".db")
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}")
            try {
                SqliteSchema.configure(driver)
                SqliteSchema.ensure(driver)

                assertEquals("wal", pragma(driver, "journal_mode"))
                // sqlite-jdbc re-applies its own 3s connection default after a
                // PRAGMA assignment, so only the pragma being accepted is
                // observable here; the WAL guarantee is the enforceable one.
                assertTrue(pragma(driver, "busy_timeout").toLong() > 0)
            } finally {
                driver.close()
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun pragma(driver: JdbcSqliteDriver, name: String): String =
        driver
            .executeQuery(
                identifier = null,
                sql = "PRAGMA $name",
                mapper = { cursor ->
                    QueryResult.Value(if (cursor.next().value) cursor.getString(0).orEmpty() else "")
                },
                parameters = 0
            ).value

    @Test
    fun `ensure fails closed for a partial schema without deleting its data`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            listOf(
                """
                CREATE TABLE documents (
                    id TEXT NOT NULL PRIMARY KEY,
                    collection TEXT NOT NULL,
                    path TEXT NOT NULL,
                    frontmatter TEXT NOT NULL DEFAULT '{}',
                    content TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0
                )
                """,
                // `deps` is missing, and no migration or legacy upgrade can add a
                // column to a table that already carries rows.
                """
                CREATE TABLE revisions (
                    doc_id TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    author TEXT NOT NULL,
                    message TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    content TEXT,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY (doc_id, revision)
                )
                """
            ).forEach { execute(driver, it.trimIndent()) }
            execute(
                driver,
                "INSERT INTO documents VALUES ('a', 'vault', 'a.md', '{}', 'kept', 'hash', 'rev', 1, 0)"
            )
            execute(
                driver,
                "INSERT INTO revisions VALUES ('a', 'rev', 'agent', 'init', 'hash', null, 1)"
            )

            val error = assertThrows(IllegalStateException::class.java) { SqliteSchema.ensure(driver) }
            assertTrue(error.message.orEmpty().contains("partial"))
            val document = TansekiDatabase(driver).documentsQueries.selectById("a").executeAsOne()
            assertEquals("kept", document.content)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `ensure fails closed when a required documents index is missing`() {
        // documents_path_idx is created on fresh databases, so REQUIRED_INDEXES is
        // what stops a partial database missing it from passing ensure() silently.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            SqliteSchema.configure(driver)
            SqliteSchema.create(driver)
            execute(driver, "DROP INDEX documents_path_idx")

            val error = assertThrows(IllegalStateException::class.java) { SqliteSchema.ensure(driver) }
            assertTrue(error.message.orEmpty().contains("documents_path_idx"), error.message.orEmpty())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `documents round-trip through the generated queries`() {
        val (driver, db) = freshDatabase()
        try {
            db.documentsQueries.insertOrIgnore(
                id = "notes/a",
                collection = "vault",
                path = "notes/a.md",
                frontmatter = """{"title":"A"}""",
                content = "hello",
                content_hash = "sha256:a",
                revision = "rev-1",
                updated_at = 1_700_000_000_000L,
                deleted = 0L,
                frontmatter_raw = null
            )

            val all = db.documentsQueries.selectAll().executeAsList()
            assertEquals(1, all.size)
            assertEquals("notes/a", all.first().id)
            assertEquals("hello", all.first().content)

            val byPath = db.documentsQueries.selectByPath("notes/a.md").executeAsOne()
            assertEquals("vault", byPath.collection)
            assertEquals("sha256:a", byPath.content_hash)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `deleted documents are excluded from selectAll`() {
        val (driver, db) = freshDatabase()
        try {
            db.documentsQueries.insertOrIgnore(
                id = "a",
                collection = "vault",
                path = "a.md",
                frontmatter = "{}",
                content = "x",
                content_hash = "h",
                revision = "r",
                updated_at = 1L,
                deleted = 1L,
                frontmatter_raw = null
            )
            assertTrue(
                db.documentsQueries
                    .selectAll()
                    .executeAsList()
                    .isEmpty()
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun `revisions edges and blobs round-trip`() {
        val (driver, db) = freshDatabase()
        try {
            db.documentsQueries.insertOrIgnore(
                id = "a",
                collection = "vault",
                path = "a.md",
                frontmatter = "{}",
                content = "x",
                content_hash = "h",
                revision = "r",
                updated_at = 1L,
                deleted = 0L,
                frontmatter_raw = null
            )
            db.revisionsQueries.insert(
                doc_id = "a",
                revision = "r",
                author = "agent",
                message = "init",
                content_hash = "h",
                content = null,
                created_at = 1L,
                deps = "[]"
            )
            db.edgesQueries.upsert(src = "a", dst = "b", rel = "links-to", props = "{}")
            db.blobsQueries.upsert(hash = "sha256:a", algorithm = "sha256", size = 3L)

            assertEquals(
                1,
                db.revisionsQueries
                    .selectForDoc("a")
                    .executeAsList()
                    .size
            )
            assertEquals(
                1,
                db.edgesQueries
                    .neighborsOut("a")
                    .executeAsList()
                    .size
            )
            assertEquals(
                "sha256:a",
                db.blobsQueries
                    .selectByHash("sha256:a")
                    .executeAsOne()
                    .hash
            )
            assertNotNull(db.documentsQueries.selectById("a").executeAsOneOrNull())
            assertNull(db.documentsQueries.selectById("missing").executeAsOneOrNull())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `the frontmatter-raw migration adds the column and keeps existing rows`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // A database at version 7: every migration through 6.sqm applied,
            // so the documents table has its 7.sqm column missing and nothing else.
            execute(
                driver,
                """
                CREATE TABLE documents (
                    id TEXT NOT NULL PRIMARY KEY,
                    collection TEXT NOT NULL,
                    path TEXT NOT NULL,
                    frontmatter TEXT NOT NULL DEFAULT '{}',
                    content TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            execute(
                driver,
                """
                CREATE TABLE projection_operations (
                    id TEXT PRIMARY KEY,
                    document_id TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    edge_version TEXT NOT NULL,
                    projection_version TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    dead_lettered INTEGER NOT NULL DEFAULT 0,
                    lease_owner TEXT,
                    lease_token TEXT,
                    lease_expires_at INTEGER
                )
                """.trimIndent()
            )
            execute(
                driver,
                "INSERT INTO documents " +
                    "(id, collection, path, frontmatter, content, content_hash, revision, updated_at, deleted) " +
                    "VALUES ('a', 'vault', 'a.md', '', 'kept', 'hash', 'rev-1', 1, 0)"
            )
            execute(driver, "PRAGMA user_version = 7")

            SqliteSchema.migrate(driver, from = 7, to = SqliteSchema.version)

            val database = TansekiDatabase(driver)
            val migrated =
                database.documentsQueries
                    .selectById("a")
                    .executeAsOne()
            assertEquals("kept", migrated.content)
            // A row that predates the column reads back with no verbatim block,
            // i.e. "synthesized frontmatter", rather than failing.
            assertNull(migrated.frontmatter_raw)
            val store = SqliteContextStore(driver, Clock { Instant.fromEpochSeconds(0) })
            assertNull(store.read(DocId("a"))!!.frontmatter.rawFrontmatter)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `path uniqueness migration preserves documents and allows collection namespaces`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            execute(
                driver,
                """
                CREATE TABLE documents (
                    id TEXT NOT NULL PRIMARY KEY,
                    collection TEXT NOT NULL,
                    path TEXT NOT NULL UNIQUE,
                    frontmatter TEXT NOT NULL DEFAULT '{}',
                    content TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            execute(driver, "CREATE INDEX documents_collection_idx ON documents(collection)")
            // A database at version 2 has already been through 1.sqm, so the
            // outbox table exists without its `attempts` column yet.
            listOf(
                """
                CREATE TABLE projection_operations (
                    id                 TEXT    PRIMARY KEY,
                    document_id        TEXT    NOT NULL,
                    revision           TEXT    NOT NULL,
                    content_hash       TEXT    NOT NULL,
                    kind               TEXT    NOT NULL,
                    edge_version       TEXT    NOT NULL,
                    projection_version TEXT    NOT NULL,
                    created_at         INTEGER NOT NULL
                )
                """,
                "CREATE INDEX projection_operations_pending_idx ON projection_operations(created_at, id)"
            ).forEach { execute(driver, it.trimIndent()) }
            driver.execute(
                null,
                "INSERT INTO documents " +
                    "(id, collection, path, frontmatter, content, content_hash, revision, updated_at, deleted) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                9
            ) {
                bindString(0, "a")
                bindString(1, "vault")
                bindString(2, "shared.md")
                bindString(3, "")
                bindString(4, "one")
                bindString(5, "hash-one")
                bindString(6, "rev-1")
                bindLong(7, 1L)
                bindLong(8, 0L)
            }
            execute(driver, "PRAGMA user_version = 2")

            val database = SqliteSchema.migrate(driver, from = 2, to = SqliteSchema.version)

            assertEquals(
                "one",
                database.documentsQueries
                    .selectById("a")
                    .executeAsOne()
                    .content
            )
            database.documentsQueries.insertOrIgnore(
                id = "b",
                collection = "other",
                path = "shared.md",
                frontmatter = "{}",
                content = "two",
                content_hash = "hash-two",
                revision = "rev-1",
                updated_at = 1L,
                deleted = 0L,
                frontmatter_raw = null
            )
            assertEquals(
                2,
                database.documentsQueries
                    .selectAll()
                    .executeAsList()
                    .size
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun `the dead-letter migration adds the column and keeps existing outbox rows`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            // Version 5 is the shape right before 5.sqm: `attempts` exists,
            // `dead_lettered` does not. The documents table travels along
            // because later migrations (7.sqm) touch it.
            execute(
                driver,
                """
                CREATE TABLE documents (
                    id TEXT NOT NULL PRIMARY KEY,
                    collection TEXT NOT NULL,
                    path TEXT NOT NULL,
                    frontmatter TEXT NOT NULL DEFAULT '{}',
                    content TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            execute(
                driver,
                """
                CREATE TABLE projection_operations (
                    id                 TEXT    PRIMARY KEY,
                    document_id        TEXT    NOT NULL,
                    revision           TEXT    NOT NULL,
                    content_hash       TEXT    NOT NULL,
                    kind               TEXT    NOT NULL,
                    edge_version       TEXT    NOT NULL,
                    projection_version TEXT    NOT NULL,
                    created_at         INTEGER NOT NULL,
                    attempts           INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            execute(
                driver,
                "INSERT INTO projection_operations " +
                    "(id, document_id, revision, content_hash, kind, edge_version, projection_version, " +
                    "created_at, attempts) VALUES ('op-1', 'a', 'rev-1', 'hash', 'UPSERT', 'rev-1', " +
                    "'rev-1', 1700000000000, 5)"
            )
            // Version 5 is the shape right before 5.sqm: `attempts` exists, `dead_lettered` does not.
            execute(driver, "PRAGMA user_version = 5")

            val database = SqliteSchema.migrate(driver, from = 5, to = SqliteSchema.version)

            val migrated = database.projectionOperationsQueries.selectById("op-1").executeAsOne()
            // An existing row must come back live, not pre-flagged: a migration that
            // defaulted the new column to TRUE would silently drain the outbox.
            assertEquals(0L, migrated.dead_lettered)
            assertEquals(5L, migrated.attempts)
            assertEquals(0, database.deadLetteredCount())

            database.projectionOperationsQueries.markDeadLettered("op-1")
            assertEquals(1, database.deadLetteredCount())
        } finally {
            driver.close()
        }
    }

    private fun TansekiDatabase.deadLetteredCount(): Int =
        projectionOperationsQueries
            .countDeadLettered()
            .executeAsOne()
            .toInt()

    @Test
    fun `downgrade requests are rejected without mutating the database`() {
        val (driver, db) = freshDatabase()
        try {
            db.documentsQueries.insertOrIgnore(
                id = "a",
                collection = "vault",
                path = "a.md",
                frontmatter = "{}",
                content = "x",
                content_hash = "h",
                revision = "r",
                updated_at = 1L,
                deleted = 0L,
                frontmatter_raw = null
            )

            assertThrows(IllegalArgumentException::class.java) {
                SqliteSchema.migrate(driver, from = SqliteSchema.version, to = SqliteSchema.version - 1)
            }
            assertEquals(
                1,
                db.documentsQueries
                    .selectAll()
                    .executeAsList()
                    .size
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun `ensure rejects a database from a newer schema`() {
        val (driver, _) = freshDatabase()
        try {
            execute(driver, "PRAGMA user_version = ${SqliteSchema.version + 1}")

            assertThrows(IllegalStateException::class.java) {
                SqliteSchema.ensure(driver)
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun `migrating an up-to-date database is a no-op`() {
        val (driver, db) = freshDatabase()
        try {
            db.documentsQueries.insertOrIgnore(
                id = "a",
                collection = "vault",
                path = "a.md",
                frontmatter = "{}",
                content = "x",
                content_hash = "h",
                revision = "r",
                updated_at = 1L,
                deleted = 0L,
                frontmatter_raw = null
            )
            val version = SqliteSchema.version

            val migrated = SqliteSchema.migrate(driver, from = version, to = version)

            assertEquals(
                1,
                migrated.documentsQueries
                    .selectAll()
                    .executeAsList()
                    .size
            )
        } finally {
            driver.close()
        }
    }
}
