package gokorei.tanseki.adapters.context.sqlite

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import gokorei.tanseki.adapters.context.sqlite.db.TansekiDatabase

object SqliteSchema {
    val version: Long get() = TansekiDatabase.Schema.version

    fun create(driver: SqlDriver): TansekiDatabase {
        TansekiDatabase(driver).transaction {
            TansekiDatabase.Schema.create(driver)
            setUserVersion(driver, version)
        }
        return TansekiDatabase(driver)
    }

    fun migrate(driver: SqlDriver, from: Long, to: Long): TansekiDatabase {
        require(from >= 0) { "schema version must not be negative" }
        require(to <= version) { "schema version $to is newer than supported version $version" }
        require(to >= from) { "schema version cannot move backwards from $from to $to" }
        if (from < to) {
            TansekiDatabase(driver).transaction {
                TansekiDatabase.Schema.migrate(driver, from, to)
                setUserVersion(driver, to)
            }
        }
        return TansekiDatabase(driver)
    }

    /**
     * Opens an existing database at its current shape.
     *
     * A database stamped by an older release is upgraded **before** its objects
     * are inspected: the missing-table check must not reject a schema that the
     * pending migrations (or [UNMIGRATED_TABLES]) are able to complete. A
     * database that carries tables but no version stamp at all is a legacy
     * database from before stamping existed, so it gets the same idempotent
     * upgrade instead of being refused. Only a database that is neither empty
     * nor upgradable afterwards is refused, and it is refused without touching
     * its data.
     */
    fun ensure(driver: SqlDriver): TansekiDatabase {
        if (!hasObject(driver, "table", "")) return create(driver)

        val schemaVersion = userVersion(driver)
        check(schemaVersion <= version) {
            "SQLite schema version $schemaVersion is newer than supported version $version"
        }
        if (schemaVersion in 1 until version) {
            migrate(driver, schemaVersion, version)
            createUnmigratedTables(driver)
            ensureDocumentsPathIndex(driver)
        } else if (schemaVersion == 0L) {
            upgradeLegacy(driver)
        }

        val missingTables = REQUIRED_TABLES.filterNot { hasObject(driver, "table", it) }
        check(missingTables.isEmpty()) {
            "SQLite schema is partial; missing tables: ${missingTables.joinToString()}"
        }

        // A table that exists without a column the generated queries read cannot
        // be repaired by adding tables, so it is refused instead of being handed
        // to the store as if it were healthy. Checked before the indexes, whose
        // definitions and partial predicates depend on those columns.
        val missingColumns = missingColumns(driver)
        check(missingColumns.isEmpty()) {
            "SQLite schema is partial; missing columns: ${missingColumns.joinToString()}"
        }

        val missingIndexes = REQUIRED_INDEXES.filterNot { hasObject(driver, "index", it) }
        check(missingIndexes.isEmpty()) {
            "SQLite schema is partial; missing indexes: ${missingIndexes.joinToString()}"
        }
        return TansekiDatabase(driver)
    }

    /**
     * Completes a database written before the schema was version-stamped. Every
     * statement is idempotent, so re-running this on a healthy database is a
     * no-op. The stamp is written last, and only once every table, column and
     * index the current schema declares is present, so a database that cannot be
     * completed stays unstamped and is refused by the checks in [ensure] without
     * its data being touched.
     *
     * A table that already exists but is missing a column the current schema
     * reads is left untouched: the partial indexes and queries that schema
     * declares cannot be applied to it, and [ensure] reports the missing column
     * instead of failing on a raw SQLite error.
     */
    private fun upgradeLegacy(driver: SqlDriver) {
        // Pure-additive provenance backfill, applied before the missing-column
        // gate below: a table that predates `frontmatter_raw` is still a healthy
        // table, so an unstamped database keeps upgrading instead of being
        // refused for a column whose absence changes no other behaviour (reads
        // see NULL, i.e. "synthesized frontmatter", exactly as before).
        addColumnIfMissing(
            driver,
            table = "documents",
            column = "frontmatter_raw",
            ddl = "ALTER TABLE documents ADD COLUMN frontmatter_raw TEXT"
        )
        if (missingColumns(driver).isNotEmpty()) return
        TansekiDatabase(driver).transaction {
            LEGACY_TABLES.forEach { ddl -> execute(driver, ddl) }
            LEGACY_UPGRADES.forEach { statement -> execute(driver, statement) }
            addColumnIfMissing(
                driver,
                table = "projection_operations",
                column = "attempts",
                ddl = "ALTER TABLE projection_operations ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0"
            )
            addColumnIfMissing(
                driver,
                table = "projection_operations",
                column = "dead_lettered",
                ddl = "ALTER TABLE projection_operations ADD COLUMN dead_lettered INTEGER NOT NULL DEFAULT 0"
            )
            // The lease columns too. `setUserVersion` below stamps the database as
            // current, and `ensure` then stops looking — so a column missing here is
            // a column a stamped-but-broken database will fail on later, with a raw
            // SQL error from `SELECT *` instead of the clean refusal `ensure` exists
            // to give. Every column the generated queries read has to be listed here.
            LEASE_COLUMNS.forEach { (column, type) ->
                addColumnIfMissing(
                    driver,
                    table = "projection_operations",
                    column = column,
                    ddl = "ALTER TABLE projection_operations ADD COLUMN $column $type"
                )
            }
            // Only after the lease column exists: an index on a column the table
            // does not have yet fails, and this runs before the stamp is written.
            execute(
                driver,
                "CREATE INDEX IF NOT EXISTS projection_operations_lease_idx " +
                    "ON projection_operations(lease_expires_at)"
            )
            ensureDocumentsPathIndex(driver)
            if (missingObjects(driver).isEmpty()) setUserVersion(driver, version)
        }
    }

    private fun addColumnIfMissing(driver: SqlDriver, table: String, column: String, ddl: String) {
        if (hasObject(driver, "table", table) && column !in columns(driver, table)) execute(driver, ddl)
    }

    /** Columns the current schema needs that an existing table does not have. */
    private fun missingColumns(driver: SqlDriver): List<String> =
        REQUIRED_TABLES.flatMap { table ->
            if (!hasObject(driver, "table", table)) {
                emptyList()
            } else {
                REQUIRED_COLUMNS
                    .getValue(table)
                    .minus(columns(driver, table))
                    .map { column -> "$table.$column" }
            }
        }

    /** Objects the current schema needs that the database does not have yet. */
    private fun missingObjects(driver: SqlDriver): List<String> =
        REQUIRED_TABLES.filterNot { hasObject(driver, "table", it) } +
            missingColumns(driver) +
            REQUIRED_INDEXES.filterNot { hasObject(driver, "index", it) }

    private fun columns(driver: SqlDriver, table: String): Set<String> =
        driver
            .executeQuery(
                identifier = null,
                sql = "PRAGMA table_info($table)",
                mapper = { cursor ->
                    QueryResult.Value(
                        buildSet {
                            while (cursor.next().value) add(cursor.getString(1).orEmpty())
                        }
                    )
                },
                parameters = 0
            ).value ?: emptySet()

    /**
     * Applies the concurrency pragmas a multi-writer library database needs:
     * WAL so readers never block the writer, and a busy timeout so a second
     * connection waits for the lock instead of failing with SQLITE_BUSY.
     *
     * An in-memory database has no journal file and no second process to
     * coordinate with, so SQLite cannot put it in WAL; the busy timeout and
     * foreign-key enforcement still apply.
     */
    fun configure(driver: SqlDriver, busyTimeoutMillis: Int = DEFAULT_BUSY_TIMEOUT_MILLIS) {
        require(busyTimeoutMillis >= 0) { "busyTimeoutMillis must not be negative" }
        execute(driver, "PRAGMA busy_timeout = $busyTimeoutMillis")
        execute(driver, "PRAGMA foreign_keys = ON")
        val journal =
            driver
                .executeQuery(
                    identifier = null,
                    sql = "PRAGMA journal_mode = WAL",
                    mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
                    parameters = 0
                ).value
        val inMemory = isInMemory(driver)
        require(
            journal == null ||
                inMemory ||
                journal.equals("wal", ignoreCase = true) ||
                journal.equals("memory", ignoreCase = true)
        ) {
            "SQLite journal mode is '$journal'; WAL is required for concurrent readers and writers"
        }
    }

    /** True when the main database has no backing file, i.e. it lives in memory. */
    private fun isInMemory(driver: SqlDriver): Boolean =
        driver
            .executeQuery(
                identifier = null,
                sql = "PRAGMA database_list",
                mapper = { cursor ->
                    QueryResult.Value(
                        buildList {
                            while (cursor.next().value) {
                                if (cursor.getString(1) == "main") add(cursor.getString(2).orEmpty())
                            }
                        }.firstOrNull()
                    )
                },
                parameters = 0
            ).value
            ?.isBlank() != false

    private fun createUnmigratedTables(driver: SqlDriver) {
        UNMIGRATED_TABLES.forEach { ddl -> execute(driver, ddl) }
    }

    /**
     * `documents_path_idx` is declared in Documents.sq, so fresh databases get it
     * from `create`; databases upgraded from a stamped or unstamped legacy schema
     * would otherwise hit the REQUIRED_INDEXES check without it.
     */
    private fun ensureDocumentsPathIndex(driver: SqlDriver) {
        execute(driver, "CREATE INDEX IF NOT EXISTS documents_path_idx ON documents(path)")
    }

    private fun execute(driver: SqlDriver, sql: String) {
        driver.execute(null, sql, 0) {}
    }

    private fun hasObject(driver: SqlDriver, type: String, name: String): Boolean {
        val sql =
            if (name.isBlank()) {
                "SELECT 1 FROM sqlite_master LIMIT 1"
            } else {
                "SELECT 1 FROM sqlite_master WHERE type = '$type' AND name = '$name' LIMIT 1"
            }
        return driver
            .executeQuery(
                identifier = null,
                sql = sql,
                mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                parameters = 0
            ).value
    }

    private fun userVersion(driver: SqlDriver): Long =
        driver
            .executeQuery(
                identifier = null,
                sql = "PRAGMA user_version",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0))
                },
                parameters = 0
            ).value
            ?: 0L

    private fun setUserVersion(driver: SqlDriver, value: Long) {
        require(value >= 0) { "schema version must not be negative" }
        execute(driver, "PRAGMA user_version = $value")
    }

    private const val DEFAULT_BUSY_TIMEOUT_MILLIS = 5_000

    /** Lease columns, and the type each one is added back with. */
    private val LEASE_COLUMNS =
        listOf(
            "lease_owner" to "TEXT",
            "lease_token" to "TEXT",
            "lease_expires_at" to "INTEGER"
        )

    private val REQUIRED_TABLES =
        listOf("documents", "revisions", "edges", "projection_operations", "blobs", "blob_content")

    private val REQUIRED_INDEXES =
        listOf(
            "documents_active_collection_path_key",
            "documents_collection_idx",
            // Created on fresh databases (Documents.sq); REQUIRED_INDEXES is what
            // stops a partial database missing it from passing ensure() silently.
            "documents_path_idx",
            "edges_dst_idx",
            "edges_src_idx",
            "projection_operations_pending_idx",
            "projection_operations_dead_lettered_idx",
            "projection_operations_lease_idx"
        )

    /**
     * Columns the generated queries read. A table that exists without one of them
     * cannot be repaired by adding tables, so the database is refused instead.
     */
    private val REQUIRED_COLUMNS =
        mapOf(
            "documents" to
                setOf(
                    "id",
                    "collection",
                    "path",
                    "frontmatter",
                    "content",
                    "content_hash",
                    "revision",
                    "updated_at",
                    "deleted",
                    "frontmatter_raw"
                ),
            "revisions" to
                setOf(
                    "doc_id",
                    "revision",
                    "author",
                    "message",
                    "content_hash",
                    "content",
                    "created_at",
                    "deps"
                ),
            "edges" to setOf("src", "dst", "rel", "props"),
            "projection_operations" to
                setOf(
                    "id",
                    "document_id",
                    "revision",
                    "content_hash",
                    "kind",
                    "edge_version",
                    "projection_version",
                    "created_at",
                    "attempts",
                    "dead_lettered"
                ),
            "blobs" to setOf("hash", "algorithm", "size"),
            "blob_content" to setOf("hash", "bytes")
        )

    /**
     * Tables the current schema declares but no `.sqm` migration creates, so a
     * database migrated from an older version would otherwise stay incomplete.
     * Each statement is idempotent.
     */
    private val UNMIGRATED_TABLES =
        listOf(
            """
            CREATE TABLE IF NOT EXISTS blobs (
                hash      TEXT    NOT NULL PRIMARY KEY,
                algorithm TEXT    NOT NULL DEFAULT 'sha256',
                size      INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS blob_content (
                hash  TEXT NOT NULL PRIMARY KEY,
                bytes BLOB NOT NULL
            )
            """.trimIndent()
        )

    /**
     * Idempotent DDL for a database that predates the version stamp: the tables
     * and indexes the current schema declares, so an unstamped database is
     * completed instead of refused.
     */
    private val LEGACY_TABLES =
        UNMIGRATED_TABLES +
            listOf(
                """
                CREATE TABLE IF NOT EXISTS documents (
                    id           TEXT    NOT NULL PRIMARY KEY,
                    collection   TEXT    NOT NULL,
                    path         TEXT    NOT NULL,
                    frontmatter  TEXT    NOT NULL DEFAULT '{}',
                    content      TEXT    NOT NULL,
                    content_hash TEXT    NOT NULL,
                    revision     TEXT    NOT NULL,
                    updated_at   INTEGER NOT NULL,
                    deleted      INTEGER NOT NULL DEFAULT 0,
                    frontmatter_raw TEXT
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS revisions (
                    doc_id       TEXT    NOT NULL,
                    revision     TEXT    NOT NULL,
                    author       TEXT    NOT NULL,
                    message      TEXT    NOT NULL,
                    content_hash TEXT    NOT NULL,
                    content      TEXT,
                    created_at   INTEGER NOT NULL,
                    deps         TEXT    NOT NULL DEFAULT '[]',
                    PRIMARY KEY (doc_id, revision)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS edges (
                    src   TEXT NOT NULL,
                    dst   TEXT NOT NULL,
                    rel   TEXT NOT NULL,
                    props TEXT NOT NULL DEFAULT '{}',
                    PRIMARY KEY (src, rel, dst)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS projection_operations (
                    id                 TEXT    PRIMARY KEY,
                    document_id        TEXT    NOT NULL,
                    revision           TEXT    NOT NULL,
                    content_hash       TEXT    NOT NULL,
                    kind               TEXT    NOT NULL,
                    edge_version       TEXT    NOT NULL,
                    projection_version TEXT    NOT NULL,
                    created_at         INTEGER NOT NULL,
                    attempts           INTEGER NOT NULL DEFAULT 0,
                    dead_lettered      INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )

    /** Indexes and columns a legacy database may be missing. */
    private val LEGACY_UPGRADES =
        listOf(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS documents_active_collection_path_key
            ON documents(collection, path) WHERE deleted = 0
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS documents_collection_idx ON documents(collection)",
            "CREATE INDEX IF NOT EXISTS edges_dst_idx ON edges(dst, rel)",
            "CREATE INDEX IF NOT EXISTS edges_src_idx ON edges(src, rel)",
            """
            CREATE INDEX IF NOT EXISTS projection_operations_pending_idx
            ON projection_operations(created_at, id)
            """.trimIndent(),
            """
            CREATE INDEX IF NOT EXISTS projection_operations_dead_lettered_idx
            ON projection_operations(dead_lettered, created_at, id)
            """.trimIndent()
        )
}
