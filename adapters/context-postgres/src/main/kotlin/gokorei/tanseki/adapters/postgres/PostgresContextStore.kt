@file:Suppress("LargeClass", "CyclomaticComplexMethod", "ComplexCondition", "NestedBlockDepth", "ThrowsCount")

package gokorei.tanseki.adapters.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import gokorei.tanseki.core.domain.BlobCorruptionException
import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.BlobSupport
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.EdgeProps
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.ProjectionKind
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.StoreCapabilities
import gokorei.tanseki.core.domain.documentPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.ProjectionBacklog
import gokorei.tanseki.core.ports.ProjectionClaim
import gokorei.tanseki.core.ports.ProjectionOperationStore
import gokorei.tanseki.core.ports.StorePage
import gokorei.tanseki.core.ports.TransferState
import gokorei.tanseki.core.ports.importDocument
import gokorei.tanseki.core.ports.isStaleTransfer
import gokorei.tanseki.core.text.FrontmatterCodec
import gokorei.tanseki.core.text.LinkResolver
import gokorei.tanseki.core.text.WikilinkRewriter
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Server-profile [ContextStore] over PostgreSQL (JDBC). Documents/revisions/
 * edges/blob content live in one database; the Lookup is a separate, derived
 * service. Mirrors the SQLite adapter's semantics: content-hash idempotency and
 * `ifRevision` compare-and-swap, inside a transaction.
 */
class PostgresContextStore(
    private val dataSource: DataSource,
    private val clock: Clock,
    private val ownsDataSource: Boolean = false
) : ContextStore, ProjectionOperationStore, AutoCloseable {
    override fun read(id: DocId): Document? =
        dataSource.connection.use { connection -> read(connection, id) }

    override fun readIncludingDeleted(id: DocId): Document? =
        dataSource.connection.use { connection -> read(connection, id, includeDeleted = true) }

    override fun readManyById(ids: List<DocId>): List<Document> {
        if (ids.isEmpty()) return emptyList()
        // Chunked: one placeholder per id, so a very large batch would otherwise
        // exceed the protocol's parameter limit.
        val found = mutableMapOf<DocId, Document>()
        for (chunk in ids.chunked(ID_READ_CHUNK)) {
            dataSource.connection.use { connection ->
                val placeholders = chunk.joinToString(",") { "?" }
                connection
                    .prepareStatement(
                        "SELECT id, collection, path, frontmatter, content, content_hash, revision, updated_at, " +
                            "deleted, frontmatter_raw FROM documents WHERE id IN ($placeholders) AND deleted = FALSE"
                    ).use { statement ->
                        chunk.forEachIndexed { index, id -> statement.setString(index + 1, id.value) }
                        statement.executeQuery().use { rows ->
                            while (rows.next()) {
                                val document = rows.toDocument()
                                found[document.id] = document
                            }
                        }
                    }
            }
        }
        return ids.mapNotNull(found::get)
    }

    override fun write(
        doc: Document,
        message: String,
        author: String,
        ifRevision: RevisionId?
    ): Revision {
        if (doc.deleted) throw InvalidInputException("write cannot create a deleted document")
        requireDerivedPath(doc)
        return try {
            inTransaction { connection ->
                advisoryLock(connection, doc.id)
                val existing = validateWriteTarget(connection, doc, ifRevision)
                if (existing?.contentHash == doc.contentHash) {
                    return@inTransaction latestRevision(connection, doc.id) ?: synthesize(doc, author, message)
                }
                if (revisionExists(connection, doc.id, doc.revision)) throw revisionConflict(doc)
                ensurePathAvailable(connection, doc)

                val historyCreatedAt = nextHistoryTimestamp(doc.updatedAt, latestRevision(connection, doc.id))
                connection
                    .prepareStatement(
                        """
                        INSERT INTO documents (id, collection, path, frontmatter, content, content_hash, revision, updated_at, deleted, frontmatter_raw)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?)
                        ON CONFLICT (id) DO UPDATE SET
                            path = EXCLUDED.path, frontmatter = EXCLUDED.frontmatter,
                            content = EXCLUDED.content, content_hash = EXCLUDED.content_hash, revision = EXCLUDED.revision,
                            updated_at = EXCLUDED.updated_at, deleted = FALSE,
                            frontmatter_raw = EXCLUDED.frontmatter_raw
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, doc.id.value)
                        statement.setString(2, doc.collection.value)
                        statement.setString(3, doc.path)
                        statement.setString(4, FrontmatterCodec.serialize(doc.frontmatter))
                        statement.setString(5, doc.content)
                        statement.setString(6, doc.contentHash)
                        statement.setString(7, doc.revision.value)
                        statement.setLong(8, doc.updatedAt.toEpochMilliseconds())
                        statement.setString(9, doc.frontmatter.rawFrontmatter)
                        statement.executeUpdate()
                    }
                connection
                    .prepareStatement(
                        """
                        INSERT INTO revisions (doc_id, revision, author, message, content_hash, content, created_at, deps)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, doc.id.value)
                        statement.setString(2, doc.revision.value)
                        statement.setString(3, author)
                        statement.setString(4, message)
                        statement.setString(5, doc.contentHash)
                        statement.setString(6, doc.content)
                        statement.setLong(7, historyCreatedAt.toEpochMilliseconds())
                        statement.setString(8, "")
                        statement.executeUpdate()
                    }

                val revision = Revision(doc.id, doc.revision, author, message, doc.contentHash, historyCreatedAt)
                insertOperation(
                    connection,
                    ProjectionOperation.upsert(doc, revision.revision, historyCreatedAt, doc.contentHash)
                )
                revision
            }
        } catch (error: Exception) {
            if (isPathOwnershipConflict(error)) throw pathConflict(doc)
            throw error
        }
    }

    override fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
        inTransaction { connection ->
            advisoryLock(connection, id)
            val existing = read(connection, id, forUpdate = true) ?: throw NotFoundException(id)
            if (ifRevision != null && existing.revision != ifRevision) {
                throw ConflictException(
                    id,
                    "revision mismatch: expected ${ifRevision.value}, found ${existing.revision.value}"
                )
            }
            val createdAt = nextHistoryTimestamp(existing.updatedAt, latestRevision(connection, id))
            val revision =
                Revision(
                    id,
                    RevisionId("deleted-${existing.revision.value}"),
                    author,
                    message,
                    existing.contentHash,
                    createdAt
                )
            if (revisionExists(connection, id, revision.revision)) throw revisionConflict(id, revision.revision)
            connection
                .prepareStatement(
                    "UPDATE documents SET revision = ?, updated_at = ?, deleted = TRUE WHERE id = ? AND deleted = FALSE"
                ).use { statement ->
                    statement.setString(1, revision.revision.value)
                    statement.setLong(2, createdAt.toEpochMilliseconds())
                    statement.setString(3, id.value)
                    statement.executeUpdate()
                }
            insertRevision(
                connection,
                id,
                revision,
                existing.content
            )
            insertOperation(
                connection,
                ProjectionOperation.delete(id, revision.revision, existing.contentHash, createdAt)
            )
            revision
        }

    override fun rename(
        from: DocId,
        to: DocId,
        message: String,
        author: String,
        ifRevision: RevisionId?
    ): Revision =
        try {
            inTransaction { connection ->
                // Sorted so two concurrent renames cannot lock the pair in opposite orders.
                listOf(from.value, to.value).sorted().forEach { advisoryLock(connection, DocId(it)) }
                val source = read(connection, from, forUpdate = true) ?: throw NotFoundException(from)
                val currentRevision = source.revision
                if (ifRevision != null && currentRevision != ifRevision) {
                    throw ConflictException(
                        from,
                        "revision mismatch: expected ${ifRevision.value}, found ${currentRevision.value}"
                    )
                }
                val targetPath = to.documentPath()
                val holder = readPathOwner(connection, source.collection.value, targetPath)
                if (holder != null && holder != from.value) throw pathHeldConflict(from, targetPath, holder)
                reclaimTombstone(connection, from, to, targetPath)

                val createdAt = nextHistoryTimestamp(source.updatedAt, latestRevision(connection, from))
                // Before the row moves, while `from` is still what a link resolves
                // to: the referring documents can only be recognised by resolving
                // them against the id they currently point at.
                relinkInbound(connection, from, to, author, createdAt)

                val renameRevision = RevisionId("renamed-${currentRevision.value}")
                connection
                    .prepareStatement(
                        "UPDATE documents SET id = ?, path = ?, revision = ?, updated_at = ? " +
                            "WHERE id = ? AND deleted = FALSE"
                    ).use { statement ->
                        statement.setString(1, to.value)
                        statement.setString(2, targetPath)
                        statement.setString(3, renameRevision.value)
                        statement.setLong(4, createdAt.toEpochMilliseconds())
                        statement.setString(5, from.value)
                        if (statement.executeUpdate() != 1) throw NotFoundException(from)
                    }
                // Edges address documents by id, so both endpoints move with the
                // renamed document.
                retargetEdgeColumn(connection, "src", to.value, from.value)
                retargetEdgeColumn(connection, "dst", to.value, from.value)
                // History follows the document: the ledger is keyed by doc_id, so
                // a rename that did not move these rows would leave the past
                // behind under an id nothing queries any more.
                retargetRevisions(connection, to.value, from.value)
                connection
                    .prepareStatement(
                        "INSERT INTO revisions (doc_id, revision, author, message, content_hash, content, " +
                            "created_at, deps) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                    ).use { statement ->
                        statement.setString(1, to.value)
                        statement.setString(2, renameRevision.value)
                        statement.setString(3, author)
                        statement.setString(4, message)
                        statement.setString(5, source.contentHash)
                        statement.setString(6, source.content)
                        statement.setLong(7, createdAt.toEpochMilliseconds())
                        statement.setString(8, "")
                        statement.executeUpdate()
                    }
                val moved = read(connection, to, forUpdate = true)
                if (moved == null || moved.path != targetPath || moved.id != to) {
                    throw ConflictException(from, "rename to $targetPath did not take effect")
                }
                val revision = Revision(to, renameRevision, author, message, source.contentHash, createdAt)
                // The document's projection is keyed by path, so the lookup side
                // has to learn the new one or searches keep answering from the old.
                insertOperation(
                    connection,
                    ProjectionOperation.upsert(moved, revision.revision, createdAt, source.contentHash)
                )
                revision
            }
        } catch (error: Exception) {
            if (isPathOwnershipConflict(error)) throw pathHeldConflict(from, to.documentPath(), to.value)
            throw error
        }

    override fun list(collection: Collection?): List<DocRef> =
        dataSource.connection.use { connection ->
            val sql =
                if (collection == null) {
                    "SELECT id, collection, path, content_hash, revision, updated_at FROM documents WHERE deleted = FALSE ORDER BY id"
                } else {
                    "SELECT id, collection, path, content_hash, revision, updated_at FROM documents WHERE deleted = FALSE AND collection = ? ORDER BY id"
                }
            connection.prepareStatement(sql).use { statement ->
                if (collection != null) statement.setString(1, collection.value)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                DocRef(
                                    id = DocId(rows.getString("id")),
                                    collection = Collection(rows.getString("collection")),
                                    path = rows.getString("path"),
                                    contentHash = rows.getString("content_hash"),
                                    revision = RevisionId(rows.getString("revision")),
                                    updatedAt = Instant.fromEpochMilliseconds(rows.getLong("updated_at"))
                                )
                            )
                        }
                    }
                }
            }
        }

    override fun listAll(): List<DocRef> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT id, collection, path, content_hash, revision, updated_at FROM documents ORDER BY id"
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    DocRef(
                                        id = DocId(rows.getString("id")),
                                        collection = Collection(rows.getString("collection")),
                                        path = rows.getString("path"),
                                        contentHash = rows.getString("content_hash"),
                                        revision = RevisionId(rows.getString("revision")),
                                        updatedAt = Instant.fromEpochMilliseconds(rows.getLong("updated_at"))
                                    )
                                )
                            }
                        }
                    }
                }
        }

    override fun listPage(
        collection: Collection?,
        limit: Int,
        offset: Int,
        pathPrefix: String?
    ): StorePage<DocRef> {
        if (pathPrefix.isNullOrEmpty()) return listPageUnfiltered(collection, limit, offset)
        val pattern = likePrefix(pathPrefix)
        return dataSource.connection.use { connection ->
            val scope = if (collection == null) "" else " AND collection = ?"
            val where = "WHERE deleted = FALSE$scope AND path LIKE ? ESCAPE '\\'"
            val total =
                connection.prepareStatement("SELECT count(*) FROM documents $where").use { statement ->
                    bind(statement, collection = collection, pattern = pattern)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
                }
            val pageSql =
                "SELECT id, collection, path, content_hash, revision, updated_at FROM documents " +
                    "$where ORDER BY id LIMIT ? OFFSET ?"
            val items =
                connection.prepareStatement(pageSql).use { statement ->
                    val next = bind(statement, collection = collection, pattern = pattern)
                    statement.setInt(next, limit)
                    statement.setInt(next + 1, offset)
                    readRefs(statement)
                }
            StorePage(items, total, offset.toLong() + items.size < total)
        }
    }

    override fun listPageAfter(
        collection: Collection?,
        pathPrefix: String?,
        after: DocId?,
        limit: Int
    ): StorePage<DocRef> {
        require(limit > 0) { "limit must be positive" }
        val pattern = likePrefix(pathPrefix)
        return dataSource.connection.use { connection ->
            val scope = if (collection == null) "" else " AND collection = ?"
            val afterClause = if (after == null) "" else " AND id > ?"
            val where =
                "WHERE deleted = FALSE$scope$afterClause" +
                    if (pattern == null) "" else " AND path LIKE ? ESCAPE '\\'"
            val total =
                connection.prepareStatement("SELECT count(*) FROM documents $where").use { statement ->
                    bind(statement, collection, after, pattern)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
                }
            // One row past the limit answers hasMore without a second probe.
            val pageSql =
                "SELECT id, collection, path, content_hash, revision, updated_at FROM documents $where ORDER BY id LIMIT ?"
            val rows =
                connection.prepareStatement(pageSql).use { statement ->
                    val next = bind(statement, collection, after, pattern)
                    statement.setInt(next, limit + 1)
                    readRefs(statement)
                }
            StorePage(rows.take(limit), total, rows.size > limit)
        }
    }

    /**
     * Escapes a path prefix into a LIKE pattern so a folder literally named
     * `100%` or `a_b` matches only itself.
     */
    private fun likePrefix(pathPrefix: String?): String? =
        pathPrefix
            ?.takeIf(String::isNotEmpty)
            ?.let { it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }

    /**
     * Binds the optional WHERE-clause parameters **in the order they appear**:
     * collection, then after, then pattern. The SQL is assembled to match, so a
     * reordered bind silently queries the wrong rows rather than failing.
     */
    private fun bind(
        statement: java.sql.PreparedStatement,
        collection: Collection?,
        after: DocId? = null,
        pattern: String? = null
    ): Int {
        var index = 1
        collection?.let { statement.setString(index++, it.value) }
        after?.let { statement.setString(index++, it.value) }
        pattern?.let { statement.setString(index++, it) }
        return index
    }

    private fun readRefs(statement: java.sql.PreparedStatement): List<DocRef> =
        statement.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        DocRef(
                            id = DocId(rows.getString("id")),
                            collection = Collection(rows.getString("collection")),
                            path = rows.getString("path"),
                            contentHash = rows.getString("content_hash"),
                            revision = RevisionId(rows.getString("revision")),
                            updatedAt = Instant.fromEpochMilliseconds(rows.getLong("updated_at"))
                        )
                    )
                }
            }
        }

    private fun listPageUnfiltered(collection: Collection?, limit: Int, offset: Int): StorePage<DocRef> {
        require(limit > 0) { "limit must be positive" }
        require(offset >= 0) { "offset must not be negative" }
        return dataSource.connection.use { connection ->
            val countSql =
                if (collection == null) {
                    "SELECT count(*) FROM documents WHERE deleted = FALSE"
                } else {
                    "SELECT count(*) FROM documents WHERE deleted = FALSE AND collection = ?"
                }
            val total =
                connection.prepareStatement(countSql).use { statement ->
                    if (collection != null) statement.setString(1, collection.value)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
                }
            val pageSql =
                if (collection == null) {
                    "SELECT id, collection, path, content_hash, revision, updated_at FROM documents WHERE deleted = FALSE ORDER BY id LIMIT ? OFFSET ?"
                } else {
                    "SELECT id, collection, path, content_hash, revision, updated_at FROM documents WHERE deleted = FALSE AND collection = ? ORDER BY id LIMIT ? OFFSET ?"
                }
            val items =
                connection.prepareStatement(pageSql).use { statement ->
                    var index = 1
                    if (collection != null) statement.setString(index++, collection.value)
                    statement.setInt(index++, limit)
                    statement.setInt(index, offset)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    DocRef(
                                        id = DocId(rows.getString("id")),
                                        collection = Collection(rows.getString("collection")),
                                        path = rows.getString("path"),
                                        contentHash = rows.getString("content_hash"),
                                        revision = RevisionId(rows.getString("revision")),
                                        updatedAt = Instant.fromEpochMilliseconds(rows.getLong("updated_at"))
                                    )
                                )
                            }
                        }
                    }
                }
            StorePage(items, total, offset.toLong() + items.size < total)
        }
    }

    override fun projectionOperations(): ProjectionOperationStore = this

    override fun enqueue(operation: ProjectionOperation) {
        inTransaction { connection -> insertOperation(connection, operation) }
    }

    override fun pending(limit: Int): List<ProjectionOperation> =
        readOperations(
            """
            SELECT id, document_id, revision, content_hash, kind, edge_version, projection_version, created_at,
                   attempts, dead_lettered
            FROM projection_operations ORDER BY created_at, id LIMIT ?
            """.trimIndent(),
            limit
        )

    /**
     * Dead letters are filtered in the query rather than after the limit: once an
     * outbox holds more dead letters than the drain window, taking the first
     * `limit` rows and dropping the dead ones would starve every live operation
     * queued behind them.
     */
    override fun pendingLive(limit: Int): List<ProjectionOperation> =
        readOperations(
            """
            SELECT id, document_id, revision, content_hash, kind, edge_version, projection_version, created_at,
                   attempts, dead_lettered
            FROM projection_operations WHERE dead_lettered = FALSE ORDER BY created_at, id LIMIT ?
            """.trimIndent(),
            limit
        )

    override fun deadLettered(limit: Int): List<ProjectionOperation> =
        readOperations(
            """
            SELECT id, document_id, revision, content_hash, kind, edge_version, projection_version, created_at,
                   attempts, dead_lettered
            FROM projection_operations WHERE dead_lettered = TRUE ORDER BY created_at, id LIMIT ?
            """.trimIndent(),
            limit
        )

    /** Keeps the record in the outbox, flagged out of the delivery path. */
    @Synchronized
    override fun deadLetter(operation: ProjectionOperation, error: Throwable?) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement("UPDATE projection_operations SET dead_lettered = TRUE WHERE id = ?")
                .use { statement ->
                    statement.setString(1, operation.id)
                    statement.executeUpdate()
                }
        }
    }

    private fun readOperations(sql: String, limit: Int): List<ProjectionOperation> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setInt(1, limit.coerceAtLeast(0))
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                ProjectionOperation(
                                    id = rows.getString("id"),
                                    documentId = DocId(rows.getString("document_id")),
                                    revision = RevisionId(rows.getString("revision")),
                                    contentHash = rows.getString("content_hash"),
                                    kind = ProjectionKind.valueOf(rows.getString("kind")),
                                    edgeVersion = rows.getString("edge_version"),
                                    projectionVersion = rows.getString("projection_version"),
                                    createdAt = Instant.fromEpochMilliseconds(rows.getLong("created_at")),
                                    attempts = rows.getInt("attempts"),
                                    deadLettered = rows.getBoolean("dead_lettered")
                                )
                            )
                        }
                    }
                }
            }
        }

    override fun complete(operation: ProjectionOperation) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM projection_operations WHERE id = ?").use { statement ->
                statement.setString(1, operation.id)
                statement.executeUpdate()
            }
        }
    }

    /**
     * One conditional UPDATE decides ownership.
     *
     * The updated-row count is the mechanism: 1 for the single worker that won the
     * race, 0 for everyone else. Decided by the database, not by a read-then-write
     * that two workers could both pass.
     */
    @Synchronized
    override fun claim(
        operationId: String,
        owner: String,
        lease: Duration,
        now: Instant
    ): ProjectionClaim? =
        dataSource.connection.use { connection ->
            val token =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val expiresAt = now + lease
            val updated =
                connection
                    .prepareStatement(
                        """
                        UPDATE projection_operations
                        SET lease_owner = ?, lease_token = ?, lease_expires_at = ?
                        WHERE id = ?
                          AND dead_lettered = FALSE
                          AND (lease_expires_at IS NULL OR lease_expires_at <= ?)
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, owner)
                        statement.setString(2, token)
                        statement.setLong(3, expiresAt.toEpochMilliseconds())
                        statement.setString(4, operationId)
                        statement.setLong(5, now.toEpochMilliseconds())
                        statement.executeUpdate()
                    }
            if (updated == 0) null else ProjectionClaim(operationId, owner, token, expiresAt)
        }

    /**
     * Deletes only while the fencing token still matches and the lease is live.
     *
     * A worker that lost its lease cannot mark work delivered on behalf of whoever
     * owns it now, which is the whole point of minting a token per claim.
     */
    @Synchronized
    override fun completeClaim(claim: ProjectionClaim): Boolean =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    DELETE FROM projection_operations
                    WHERE id = ? AND lease_token = ? AND lease_expires_at > ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, claim.operationId)
                    statement.setString(2, claim.token)
                    statement.setLong(3, clock.now().toEpochMilliseconds())
                    statement.executeUpdate()
                } == 1
        }

    @Synchronized
    override fun releaseClaim(claim: ProjectionClaim) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE projection_operations
                    SET lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL
                    WHERE id = ? AND lease_token = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, claim.operationId)
                    statement.setString(2, claim.token)
                    statement.executeUpdate()
                }
        }
    }

    @Synchronized
    override fun leasedOperations(now: Instant): List<ProjectionClaim> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT id, lease_owner, lease_token, lease_expires_at
                    FROM projection_operations
                    WHERE lease_expires_at > ?
                    ORDER BY lease_expires_at, id
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, now.toEpochMilliseconds())
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                val expires = rows.getLong(4)
                                // Read wasNull() immediately after the column it
                                // describes: a later getString overwrites the flag,
                                // so checking it after reading owner/token reports
                                // the wrong column's nullness.
                                val expiresMissing = rows.wasNull()
                                val leaseOwner = rows.getString(2)
                                val leaseToken = rows.getString(3)
                                if (!expiresMissing && leaseOwner != null && leaseToken != null) {
                                    add(
                                        ProjectionClaim(
                                            operationId = rows.getString(1),
                                            owner = leaseOwner,
                                            token = leaseToken,
                                            expiresAt = Instant.fromEpochMilliseconds(expires)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
        }

    @Synchronized
    override fun recordFailure(operation: ProjectionOperation, error: Throwable?): Int =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement("UPDATE projection_operations SET attempts = attempts + 1 WHERE id = ?")
                .use { statement ->
                    statement.setString(1, operation.id)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement("SELECT attempts FROM projection_operations WHERE id = ?")
                .use { statement ->
                    statement.setString(1, operation.id)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getInt(1) else operation.attempts + 1
                    }
                }
        }

    override fun quarantineCorrupt(): Int = 0

    override fun backlog(): ProjectionBacklog {
        val records = pending(Int.MAX_VALUE)
        val live = records.filterNot { it.deadLettered }
        val stuck = live.filter { it.attempts > 0 }
        return ProjectionBacklog(
            pending = live.size,
            oldestCreatedAt = live.firstOrNull()?.createdAt,
            stuck = stuck.size,
            oldestStuckAt = stuck.firstOrNull()?.createdAt,
            deadLettered = records.size - live.size
        )
    }

    override fun upsertEdge(edge: Edge) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO edges (src, dst, rel, props) VALUES (?, ?, ?, ?)
                    ON CONFLICT (src, rel, dst) DO UPDATE SET props = EXCLUDED.props
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, edge.src.value)
                    statement.setString(2, edge.dst.value)
                    statement.setString(3, edge.rel.value)
                    statement.setString(4, encodeProps(edge.props))
                    statement.executeUpdate()
                }
        }
    }

    override fun removeEdges(src: DocId, rel: RelType?) {
        dataSource.connection.use { connection ->
            val sql =
                if (rel == null) {
                    "DELETE FROM edges WHERE src = ?"
                } else {
                    "DELETE FROM edges WHERE src = ? AND rel = ?"
                }
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, src.value)
                if (rel != null) statement.setString(2, rel.value)
                statement.executeUpdate()
            }
        }
    }

    override fun removeIncomingEdges(dst: DocId, rel: RelType?) {
        dataSource.connection.use { connection ->
            val sql =
                if (rel == null) {
                    "DELETE FROM edges WHERE dst = ?"
                } else {
                    "DELETE FROM edges WHERE dst = ? AND rel = ?"
                }
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, dst.value)
                if (rel != null) statement.setString(2, rel.value)
                statement.executeUpdate()
            }
        }
    }

    @Synchronized
    override fun clearEdges() {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM edges").use { it.executeUpdate() }
        }
    }

    @Synchronized
    override fun replaceEdges(src: DocId, edges: List<Edge>) {
        val replacement = edges.distinctBy { edge -> edge.rel to edge.dst }
        inTransaction { connection ->
            connection.prepareStatement("DELETE FROM edges WHERE src = ?").use { statement ->
                statement.setString(1, src.value)
                statement.executeUpdate()
            }
            connection
                .prepareStatement("INSERT INTO edges (src, dst, rel, props) VALUES (?, ?, ?, ?)")
                .use { statement ->
                    replacement.forEach { edge ->
                        statement.setString(1, src.value)
                        statement.setString(2, edge.dst.value)
                        statement.setString(3, edge.rel.value)
                        statement.setString(4, encodeProps(edge.props))
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
        }
    }

    /**
     * Synchronized to pair with [replaceEdges]. The transaction protects other
     * connections; a reader on this store's own connection would otherwise observe
     * the DELETE without the inserts.
     */
    @Synchronized
    override fun neighbors(id: DocId, rel: RelType?): List<Edge> =
        dataSource.connection.use { connection ->
            val sql =
                if (rel == null) {
                    "SELECT src, dst, rel, props FROM edges WHERE src = ? ORDER BY rel, dst"
                } else {
                    "SELECT src, dst, rel, props FROM edges WHERE src = ? AND rel = ? ORDER BY rel, dst"
                }
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, id.value)
                if (rel != null) statement.setString(2, rel.value)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                Edge(
                                    src = DocId(rows.getString("src")),
                                    dst = DocId(rows.getString("dst")),
                                    rel = RelType(rows.getString("rel")),
                                    props = decodeProps(rows.getString("props"))
                                )
                            )
                        }
                    }
                }
            }
        }

    /**
     * Mirrors [neighbors] for the inbound direction, ordered by rel then src so
     * repeated reads agree regardless of insertion order, as the contract requires.
     */
    override fun incomingNeighbors(id: DocId, rel: RelType?): List<Edge> =
        dataSource.connection.use { connection ->
            val sql =
                if (rel == null) {
                    "SELECT src, dst, rel, props FROM edges WHERE dst = ? ORDER BY rel, src"
                } else {
                    "SELECT src, dst, rel, props FROM edges WHERE dst = ? AND rel = ? ORDER BY rel, src"
                }
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, id.value)
                if (rel != null) statement.setString(2, rel.value)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                Edge(
                                    src = DocId(rows.getString("src")),
                                    dst = DocId(rows.getString("dst")),
                                    rel = RelType(rows.getString("rel")),
                                    props = decodeProps(rows.getString("props"))
                                )
                            )
                        }
                    }
                }
            }
        }

    override fun history(id: DocId): List<Revision> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT doc_id, revision, author, message, content_hash, content, created_at, deps " +
                        "FROM revisions WHERE doc_id = ? ORDER BY created_at, revision"
                ).use { statement ->
                    statement.setString(1, id.value)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    Revision(
                                        docId = DocId(rows.getString("doc_id")),
                                        revision = RevisionId(rows.getString("revision")),
                                        author = rows.getString("author"),
                                        message = rows.getString("message"),
                                        contentHash = rows.getString("content_hash"),
                                        createdAt = Instant.fromEpochMilliseconds(rows.getLong("created_at")),
                                        deps =
                                            rows
                                                .getString("deps")
                                                .split(",")
                                                .filter { it.isNotBlank() }
                                                .map(::RevisionId),
                                        content = rows.getString("content")
                                    )
                                )
                            }
                        }
                    }
                }
        }

    override fun historyPage(id: DocId, limit: Int, offset: Int): StorePage<Revision> {
        require(limit > 0) { "limit must be positive" }
        require(offset >= 0) { "offset must not be negative" }
        return dataSource.connection.use { connection ->
            val total =
                connection.prepareStatement("SELECT count(*) FROM revisions WHERE doc_id = ?").use { statement ->
                    statement.setString(1, id.value)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
                }
            val items =
                connection
                    .prepareStatement(
                        "SELECT doc_id, revision, author, message, content_hash, content, created_at, deps " +
                            "FROM revisions WHERE doc_id = ? ORDER BY created_at, revision LIMIT ? OFFSET ?"
                    ).use { statement ->
                        statement.setString(1, id.value)
                        statement.setInt(2, limit)
                        statement.setInt(3, offset)
                        statement.executeQuery().use { rows ->
                            buildList {
                                while (rows.next()) {
                                    add(
                                        Revision(
                                            docId = DocId(rows.getString("doc_id")),
                                            revision = RevisionId(rows.getString("revision")),
                                            author = rows.getString("author"),
                                            message = rows.getString("message"),
                                            contentHash = rows.getString("content_hash"),
                                            createdAt = Instant.fromEpochMilliseconds(rows.getLong("created_at")),
                                            deps =
                                                rows
                                                    .getString("deps")
                                                    .split(",")
                                                    .filter { it.isNotBlank() }
                                                    .map(::RevisionId),
                                            content = rows.getString("content")
                                        )
                                    )
                                }
                            }
                        }
                    }
            StorePage(items, total, offset.toLong() + items.size < total)
        }
    }

    override fun historyOwner(id: DocId): DocRef? =
        dataSource.connection.use { connection -> readHistoryOwner(connection, id) }

    override fun importTransferState(state: TransferState): Boolean =
        try {
            inTransaction { connection ->
                val document = importDocument(this, state.document)
                requireDerivedPath(document)
                // Serialize on the id the document will actually occupy, not on
                // the foreign one it was exported under.
                advisoryLock(connection, document.id)
                val existing = read(connection, document.id, forUpdate = true, includeDeleted = true)
                val existingHistory = history(connection, document.id)
                if (existing != null && existing.collection != document.collection) {
                    throw collectionConflict(document, existing.id.value)
                }
                if (existing != null && isStaleTransfer(existing, document)) return@inTransaction false
                val documentChanged =
                    existing == null ||
                        existing.contentHash != document.contentHash ||
                        existing.revision != document.revision ||
                        existing.deleted != document.deleted
                val changed =
                    documentChanged ||
                        state.history.any { revision -> existingHistory.none { it.revision == revision.revision } }
                if (!document.deleted) {
                    val owner = readPathOwner(connection, document.collection.value, document.path)
                    if (owner != null && owner != document.id.value) throw pathConflict(document, owner)
                }
                state.history.forEach { revision ->
                    assertRevisionCompatible(connection, document.id, revision.copy(docId = document.id))
                }
                connection
                    .prepareStatement(
                        """
                        INSERT INTO documents (id, collection, path, frontmatter, content, content_hash, revision, updated_at, deleted, frontmatter_raw)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (id) DO UPDATE SET
                                path = EXCLUDED.path, frontmatter = EXCLUDED.frontmatter,
                                content = EXCLUDED.content, content_hash = EXCLUDED.content_hash, revision = EXCLUDED.revision,
                                updated_at = EXCLUDED.updated_at, deleted = EXCLUDED.deleted,
                                frontmatter_raw = EXCLUDED.frontmatter_raw
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, document.id.value)
                        statement.setString(2, document.collection.value)
                        statement.setString(3, document.path)
                        statement.setString(4, FrontmatterCodec.serialize(document.frontmatter))
                        statement.setString(5, document.content)
                        statement.setString(6, document.contentHash)
                        statement.setString(7, document.revision.value)
                        statement.setLong(8, document.updatedAt.toEpochMilliseconds())
                        statement.setBoolean(9, document.deleted)
                        statement.setString(10, document.frontmatter.rawFrontmatter)
                        statement.executeUpdate()
                    }
                connection
                    .prepareStatement(
                        """
                        INSERT INTO revisions (doc_id, revision, author, message, content_hash, content, created_at, deps)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (doc_id, revision) DO NOTHING
                        """.trimIndent()
                    ).use { statement ->
                        state.history.forEach { revision ->
                            statement.setString(1, document.id.value)
                            statement.setString(2, revision.revision.value)
                            statement.setString(3, revision.author)
                            statement.setString(4, revision.message)
                            statement.setString(5, revision.contentHash)
                            statement.setString(6, revision.content)
                            statement.setLong(7, revision.createdAt.toEpochMilliseconds())
                            statement.setString(8, revision.deps.joinToString(",") { it.value })
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                val operation =
                    if (document.deleted) {
                        ProjectionOperation.delete(
                            document.id,
                            document.revision,
                            document.contentHash,
                            document.updatedAt
                        )
                    } else {
                        ProjectionOperation.upsert(document, document.revision, document.updatedAt)
                    }
                insertOperation(connection, operation)
                // A tombstone must not leave the target pointing at a deleted id.
                if (document.deleted) {
                    connection.prepareStatement("DELETE FROM edges WHERE dst = ?").use { statement ->
                        statement.setString(1, document.id.value)
                        statement.executeUpdate()
                    }
                }
                changed
            }
        } catch (error: Exception) {
            if (isPathOwnershipConflict(error)) throw pathConflict(state.document)
            throw error
        }

    override fun appendHistory(id: DocId, revisions: List<Revision>) {
        if (revisions.isEmpty()) return
        inTransaction { connection ->
            revisions.forEach { revision -> assertRevisionCompatible(connection, id, revision) }
            connection
                .prepareStatement(
                    """
                    INSERT INTO revisions (doc_id, revision, author, message, content_hash, content, created_at, deps)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (doc_id, revision) DO NOTHING
                    """.trimIndent()
                ).use { statement ->
                    revisions.forEach { revision ->
                        statement.setString(1, id.value)
                        statement.setString(2, revision.revision.value)
                        statement.setString(3, revision.author)
                        statement.setString(4, revision.message)
                        statement.setString(5, revision.contentHash)
                        statement.setString(6, revision.content)
                        statement.setLong(7, revision.createdAt.toEpochMilliseconds())
                        statement.setString(8, revision.deps.joinToString(",") { it.value })
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
        }
    }

    override fun putBlob(bytes: ByteArray): BlobRef {
        val hash = BlobSupport.sha256(bytes)
        val ref = BlobRef(hash = hash, size = bytes.size.toLong(), algorithm = BlobSupport.ALGORITHM)
        inTransaction { connection ->
            val metadata =
                connection.prepareStatement("SELECT algorithm, size FROM blobs WHERE hash = ?").use { statement ->
                    statement.setString(1, hash)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getString("algorithm") to rows.getLong("size") else null
                    }
                }
            val content =
                connection.prepareStatement("SELECT bytes FROM blob_content WHERE hash = ?").use { statement ->
                    statement.setString(1, hash)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getBytes("bytes") else null }
                }
            if (metadata != null || content != null) {
                if (metadata == null || content == null || metadata.first != BlobSupport.ALGORITHM ||
                    BlobSupport.sizeDisagrees(ref, metadata.second)
                ) {
                    throw BlobCorruptionException("blob metadata and content are inconsistent for $hash")
                }
                BlobSupport.verify(ref, content)
            } else {
                connection
                    .prepareStatement(
                        "INSERT INTO blobs (hash, algorithm, size) VALUES (?, ?, ?)"
                    ).use { statement ->
                        statement.setString(1, hash)
                        statement.setString(2, BlobSupport.ALGORITHM)
                        statement.setLong(3, ref.size)
                        statement.executeUpdate()
                    }
                connection
                    .prepareStatement("INSERT INTO blob_content (hash, bytes) VALUES (?, ?)")
                    .use { statement ->
                        statement.setString(1, hash)
                        statement.setBytes(2, bytes)
                        statement.executeUpdate()
                    }
            }
        }
        return ref
    }

    override fun getBlob(ref: BlobRef): ByteArray {
        BlobSupport.validateRef(ref)
        return dataSource.connection.use { connection ->
            val metadata =
                connection.prepareStatement("SELECT algorithm, size FROM blobs WHERE hash = ?").use { statement ->
                    statement.setString(1, ref.hash)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getString("algorithm") to rows.getLong("size") else null
                    }
                }
            val bytes =
                connection.prepareStatement("SELECT bytes FROM blob_content WHERE hash = ?").use { statement ->
                    statement.setString(1, ref.hash)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getBytes("bytes") else null }
                }
            if (metadata == null && bytes == null) throw NotFoundException()
            if (metadata == null || bytes == null) {
                throw BlobCorruptionException("blob metadata and content are inconsistent for ${ref.hash}")
            }
            if (metadata.first != BlobSupport.ALGORITHM || BlobSupport.sizeDisagrees(ref, metadata.second)) {
                throw BlobCorruptionException("blob metadata does not match reference ${ref.hash}")
            }
            BlobSupport.verify(ref, bytes)
            bytes
        }
    }

    override fun capabilities() =
        StoreCapabilities(
            supportsHistory = true,
            supportsPatchGraph = false,
            supportsTransactions = true,
            consistency = Consistency.STRONG,
            supportsHistoryImport = true,
            supportsTombstoneEnumeration = true,
            supportsTombstoneImport = true,
            supportsSync = true
        )

    override fun close() {
        if (ownsDataSource) (dataSource as? AutoCloseable)?.close()
    }

    private fun history(connection: Connection, id: DocId): List<Revision> =
        connection
            .prepareStatement(
                "SELECT doc_id, revision, author, message, content_hash, content, created_at, deps " +
                    "FROM revisions WHERE doc_id = ? ORDER BY created_at, revision"
            ).use { statement ->
                statement.setString(1, id.value)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                Revision(
                                    docId = DocId(rows.getString("doc_id")),
                                    revision = RevisionId(rows.getString("revision")),
                                    author = rows.getString("author"),
                                    message = rows.getString("message"),
                                    contentHash = rows.getString("content_hash"),
                                    createdAt = Instant.fromEpochMilliseconds(rows.getLong("created_at")),
                                    deps =
                                        rows
                                            .getString(
                                                "deps"
                                            ).split(",")
                                            .filter { it.isNotBlank() }
                                            .map(::RevisionId),
                                    content = rows.getString("content")
                                )
                            )
                        }
                    }
                }
            }

    private fun assertRevisionCompatible(connection: Connection, id: DocId, revision: Revision) {
        connection
            .prepareStatement("SELECT content_hash, content FROM revisions WHERE doc_id = ? AND revision = ?")
            .use { statement ->
                statement.setString(1, id.value)
                statement.setString(2, revision.revision.value)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        val sameHash = rows.getString("content_hash") == revision.contentHash
                        val existingContent = rows.getString("content")
                        val sameContent =
                            existingContent == null || revision.content == null || existingContent == revision.content
                        if (!sameHash || !sameContent) throw revisionConflict(id, revision.revision)
                    }
                }
            }
    }

    private fun readHistoryOwner(connection: Connection, id: DocId): DocRef? =
        connection
            .prepareStatement(
                "SELECT id, collection, path, content_hash, revision, updated_at FROM documents WHERE id = ?"
            ).use { statement ->
                statement.setString(1, id.value)
                statement.executeQuery().use { rows -> if (rows.next()) rows.toHistoryOwner() else null }
            }

    private fun ResultSet.toHistoryOwner() =
        DocRef(
            id = DocId(getString("id")),
            collection = Collection(getString("collection")),
            path = getString("path"),
            contentHash = getString("content_hash"),
            revision = RevisionId(getString("revision")),
            updatedAt = Instant.fromEpochMilliseconds(getLong("updated_at"))
        )

    private fun validateWriteTarget(
        connection: Connection,
        doc: Document,
        ifRevision: RevisionId?
    ): Document? {
        val stored = read(connection, doc.id, forUpdate = true, includeDeleted = true)
        if (stored != null && stored.collection != doc.collection) {
            throw collectionConflict(doc, stored.id.value)
        }
        if (ifRevision != null && stored?.revision != ifRevision) {
            throw ConflictException(
                doc.id,
                "revision mismatch: expected ${ifRevision.value}, found ${stored?.revision?.value ?: "none"}"
            )
        }
        return stored?.takeUnless { it.deleted }
    }

    private fun ensurePathAvailable(connection: Connection, doc: Document) {
        val pathOwner = readPathOwner(connection, doc.collection.value, doc.path)
        if (pathOwner != null && pathOwner != doc.id.value) throw pathConflict(doc, pathOwner)
    }

    private fun read(
        connection: Connection,
        id: DocId,
        forUpdate: Boolean = false,
        includeDeleted: Boolean = false
    ): Document? {
        val visibility = if (includeDeleted) "" else " AND deleted = FALSE"
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        return connection
            .prepareStatement(
                "SELECT id, collection, path, frontmatter, content, content_hash, revision, updated_at, deleted, " +
                    "frontmatter_raw FROM documents WHERE id = ?$visibility$suffix"
            ).use { statement ->
                statement.setString(1, id.value)
                statement.executeQuery().use { rows -> if (rows.next()) rows.toDocument() else null }
            }
    }

    private fun revisionExists(connection: Connection, id: DocId, revision: RevisionId): Boolean =
        connection
            .prepareStatement("SELECT 1 FROM revisions WHERE doc_id = ? AND revision = ?")
            .use { statement ->
                statement.setString(1, id.value)
                statement.setString(2, revision.value)
                statement.executeQuery().use { it.next() }
            }

    private fun latestRevision(connection: Connection, id: DocId): Revision? =
        connection
            .prepareStatement(
                "SELECT doc_id, revision, author, message, content_hash, created_at, deps " +
                    "FROM revisions WHERE doc_id = ? ORDER BY created_at DESC, revision DESC LIMIT 1"
            ).use { statement ->
                statement.setString(1, id.value)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        Revision(
                            docId = DocId(rows.getString("doc_id")),
                            revision = RevisionId(rows.getString("revision")),
                            author = rows.getString("author"),
                            message = rows.getString("message"),
                            contentHash = rows.getString("content_hash"),
                            createdAt = Instant.fromEpochMilliseconds(rows.getLong("created_at")),
                            deps =
                                rows
                                    .getString("deps")
                                    .split(",")
                                    .filter { it.isNotBlank() }
                                    .map(::RevisionId)
                        )
                    }
                }
            }

    private fun readPathOwner(connection: Connection, collection: String, path: String): String? =
        connection
            .prepareStatement("SELECT id FROM documents WHERE collection = ? AND path = ? AND deleted = FALSE")
            .use { statement ->
                statement.setString(1, collection)
                statement.setString(2, path)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }

    private fun pathHeldConflict(from: DocId, path: String, holderId: String): ConflictException =
        ConflictException(from, "path $path is held by $holderId; resolve the collision before renaming")

    /**
     * Drops a tombstone occupying the target id, if there is one.
     *
     * A tombstone *releases* its path, so a rename onto a path that only a
     * tombstone holds is legal and reclaims it. The row has to go first: it
     * occupies the very primary key the renamed document is moving into, so the
     * update would fail on it. Its history and edges go with it because they
     * belong to a document that will exist under no id afterwards — the user
     * chose this path over that one.
     */
    private fun reclaimTombstone(connection: Connection, from: DocId, to: DocId, targetPath: String) {
        val reclaimed =
            connection.prepareStatement("SELECT id, deleted FROM documents WHERE id = ?").use { statement ->
                statement.setString(1, to.value)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getString("id") to rows.getBoolean("deleted") else null
                }
            } ?: return
        if (reclaimed.first == from.value) return
        if (!reclaimed.second) throw pathHeldConflict(from, targetPath, reclaimed.first)
        connection.prepareStatement("DELETE FROM edges WHERE src = ?").use { statement ->
            statement.setString(1, reclaimed.first)
            statement.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM edges WHERE dst = ?").use { statement ->
            statement.setString(1, reclaimed.first)
            statement.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM revisions WHERE doc_id = ?").use { statement ->
            statement.setString(1, reclaimed.first)
            statement.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM documents WHERE id = ?").use { statement ->
            statement.setString(1, reclaimed.first)
            statement.executeUpdate()
        }
    }

    /**
     * Re-points every `[[wikilink]]` that resolves to [from] at [to], in the
     * documents that hold them.
     *
     * Links are derived from text on every index, so leaving them pointed at the
     * old id would not fail loudly: the next index would simply stop resolving
     * them and the referring documents would quietly lose those edges. Rewriting
     * the text keeps what the author wrote meaning the same document.
     *
     * Each rewritten document gets its own revision. Its content genuinely
     * changed, and reusing the old revision id would hide that from history and
     * let a stale `If-Match` succeed against content that no longer exists.
     */
    private fun relinkInbound(connection: Connection, from: DocId, to: DocId, author: String, at: Instant) {
        val referrers =
            connection.prepareStatement("SELECT DISTINCT src FROM edges WHERE dst = ?").use { statement ->
                statement.setString(1, from.value)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val src = rows.getString(1)
                            if (src != from.value) add(src)
                        }
                    }
                }
            }
        if (referrers.isEmpty()) return
        val refs =
            connection
                .prepareStatement(
                    "SELECT id, collection, path, content_hash, revision, updated_at FROM documents " +
                        "WHERE deleted = FALSE ORDER BY id"
                ).use { statement -> readRefs(statement) }
        val resolve = { target: String ->
            LinkResolver.resolve(
                target,
                exists = { id -> liveExists(connection, id) },
                refs = refs
            )
        }
        referrers.forEach { srcId ->
            val row = read(connection, DocId(srcId), forUpdate = true) ?: return@forEach
            val rewritten = WikilinkRewriter.retarget(row.content, resolve, from, to)
            if (rewritten == row.content) return@forEach
            val hash = sha256(rewritten.toByteArray(Charsets.UTF_8))
            val relinkRevision = RevisionId("relinked-${row.revision.value}")
            connection
                .prepareStatement(
                    "UPDATE documents SET content = ?, content_hash = ?, revision = ?, updated_at = ? " +
                        "WHERE id = ? AND deleted = FALSE"
                ).use { statement ->
                    statement.setString(1, rewritten)
                    statement.setString(2, hash)
                    statement.setString(3, relinkRevision.value)
                    statement.setLong(4, at.toEpochMilliseconds())
                    statement.setString(5, row.id.value)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    "INSERT INTO revisions (doc_id, revision, author, message, content_hash, content, " +
                        "created_at, deps) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, row.id.value)
                    statement.setString(2, relinkRevision.value)
                    statement.setString(3, author)
                    statement.setString(4, "relinked after rename")
                    statement.setString(5, hash)
                    statement.setString(6, rewritten)
                    statement.setLong(7, at.toEpochMilliseconds())
                    statement.setString(8, "")
                    statement.executeUpdate()
                }
            // Queued rather than derived here: the index owns edge derivation, and
            // a store that wrote edges behind the indexer's back would let a failed
            // delivery look projected.
            insertOperation(
                connection,
                ProjectionOperation.upsert(
                    row.copy(content = rewritten, contentHash = hash, revision = relinkRevision, updatedAt = at),
                    relinkRevision,
                    at,
                    hash
                )
            )
        }
    }

    private fun liveExists(connection: Connection, id: DocId): Boolean =
        connection
            .prepareStatement("SELECT 1 FROM documents WHERE id = ? AND deleted = FALSE")
            .use { statement ->
                statement.setString(1, id.value)
                statement.executeQuery().use { it.next() }
            }

    /**
     * Moves one edge endpoint from [from] to [to].
     *
     * [column] is an internal constant (`src` or `dst`), never caller input.
     * The conflicting rows go first: SQLite spells the move `UPDATE OR REPLACE`
     * for the edge that already exists at the target, and PostgreSQL has no
     * equivalent for UPDATE, so the delete makes the same collision resolve the
     * same way.
     */
    private fun retargetEdgeColumn(connection: Connection, column: String, to: String, from: String) {
        require(column == "src" || column == "dst") { "edge endpoint must be src or dst" }
        val other = if (column == "src") "dst" else "src"
        connection
            .prepareStatement(
                "DELETE FROM edges a USING edges b WHERE a.$column = ? AND b.$column = ? " +
                    "AND a.rel = b.rel AND a.$other = b.$other"
            ).use { statement ->
                statement.setString(1, to)
                statement.setString(2, from)
                statement.executeUpdate()
            }
        connection.prepareStatement("UPDATE edges SET $column = ? WHERE $column = ?").use { statement ->
            statement.setString(1, to)
            statement.setString(2, from)
            statement.executeUpdate()
        }
    }

    private fun retargetRevisions(connection: Connection, to: String, from: String) {
        connection
            .prepareStatement(
                "DELETE FROM revisions a USING revisions b " +
                    "WHERE a.doc_id = ? AND b.doc_id = ? AND a.revision = b.revision"
            ).use { statement ->
                statement.setString(1, to)
                statement.setString(2, from)
                statement.executeUpdate()
            }
        connection.prepareStatement("UPDATE revisions SET doc_id = ? WHERE doc_id = ?").use { statement ->
            statement.setString(1, to)
            statement.setString(2, from)
            statement.executeUpdate()
        }
    }

    private fun pathConflict(doc: Document, ownerId: String): ConflictException =
        ConflictException(
            doc.id,
            "path '${doc.path}' is already owned by '$ownerId' in collection '${doc.collection.value}'"
        )

    private fun pathConflict(doc: Document): ConflictException =
        ConflictException(
            doc.id,
            "path '${doc.path}' is already owned in collection '${doc.collection.value}'"
        )

    /**
     * The one path rule every profile shares: a document's path is its id plus
     * the Markdown suffix. The vault stores the file at exactly that relative
     * path, so this adapter — which could physically store anything — refuses
     * divergent paths instead of recording metadata the vault cannot round-trip.
     */
    private fun requireDerivedPath(doc: Document) {
        if (doc.path != doc.id.documentPath()) {
            throw InvalidInputException("document path must be derived from its id")
        }
    }

    private fun collectionConflict(doc: Document, ownerId: String): ConflictException =
        ConflictException(
            doc.id,
            "collection is immutable for document '$ownerId'"
        )

    private fun revisionConflict(doc: Document): ConflictException =
        revisionConflict(doc.id, doc.revision)

    private fun revisionConflict(id: DocId, revision: RevisionId): ConflictException =
        ConflictException(id, "revision '${revision.value}' has already been used with different history")

    private fun isPathOwnershipConflict(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (
                message.contains("documents_active_collection_path_key") ||
                message.contains("documents_collection_path_key")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun insertRevision(connection: Connection, id: DocId, revision: Revision, content: String?) {
        connection
            .prepareStatement(
                "INSERT INTO revisions (doc_id, revision, author, message, content_hash, content, created_at, deps) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, id.value)
                statement.setString(2, revision.revision.value)
                statement.setString(3, revision.author)
                statement.setString(4, revision.message)
                statement.setString(5, revision.contentHash)
                statement.setString(6, content)
                statement.setLong(7, revision.createdAt.toEpochMilliseconds())
                statement.setString(8, revision.deps.joinToString(",") { it.value })
                statement.executeUpdate()
            }
    }

    private fun insertOperation(connection: Connection, operation: ProjectionOperation) {
        connection
            .prepareStatement(
                """
                INSERT INTO projection_operations(
                    id, document_id, revision, content_hash, kind, edge_version, projection_version, created_at,
                    attempts, dead_lettered
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    document_id = EXCLUDED.document_id,
                    revision = EXCLUDED.revision,
                    content_hash = EXCLUDED.content_hash,
                    kind = EXCLUDED.kind,
                    edge_version = EXCLUDED.edge_version,
                    projection_version = EXCLUDED.projection_version,
                    -- A re-enqueued operation is a fresh intent, so any lease the
                    -- previous attempt left must not make it un-claimable until it
                    -- expires. SQLite clears these in clearLeaseOnEnqueue; the
                    -- insert-side upsert has to do the same here.
                    lease_owner = NULL,
                    lease_token = NULL,
                    lease_expires_at = NULL
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, operation.id)
                statement.setString(2, operation.documentId.value)
                statement.setString(3, operation.revision.value)
                statement.setString(4, operation.contentHash)
                statement.setString(5, operation.kind.name)
                statement.setString(6, operation.edgeVersion)
                statement.setString(7, operation.projectionVersion)
                statement.setLong(8, operation.createdAt.toEpochMilliseconds())
                statement.setInt(9, operation.attempts)
                statement.setBoolean(10, operation.deadLettered)
                statement.executeUpdate()
            }
    }

    /** Serializes concurrent writers for one document id, as `write`/`delete` do. */
    private fun advisoryLock(connection: Connection, id: DocId) {
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { statement ->
            statement.setString(1, id.value)
            statement.execute()
        }
    }

    private fun nextHistoryTimestamp(documentTime: Instant, latest: Revision?): Instant =
        maxOf(clock.now(), documentTime, latest?.createdAt ?: documentTime) + 1.milliseconds

    private fun synthesize(doc: Document, author: String, message: String) =
        Revision(doc.id, doc.revision, author, message, doc.contentHash, doc.updatedAt)

    private fun <T> inTransaction(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (error: Exception) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }

    private fun ResultSet.toDocument() =
        Document(
            id = DocId(getString("id")),
            collection = Collection(getString("collection")),
            path = getString("path"),
            content = getString("content"),
            contentHash = getString("content_hash"),
            revision = RevisionId(getString("revision")),
            updatedAt = Instant.fromEpochMilliseconds(getLong("updated_at")),
            // The typed column is the index; the raw column is the provenance.
            // See SqliteMappers.toDomain for why the verbatim block must travel
            // alongside the typed view.
            frontmatter =
                FrontmatterCodec
                    .parse(getString("frontmatter"))
                    .copy(rawFrontmatter = getString("frontmatter_raw")),
            deleted = getBoolean("deleted")
        )

    private fun encodeProps(props: Map<String, String>): String = EdgeProps.encode(props)

    private fun decodeProps(props: String): Map<String, String> = EdgeProps.decode(props)

    private fun sha256(bytes: ByteArray): String = BlobSupport.sha256(bytes)

    companion object {
        /** One placeholder per id; keep a batch well inside PostgreSQL's limit. */
        private const val ID_READ_CHUNK = 900

        /** Opens a pooled store for a JDBC URL, creating the schema if needed. */
        fun open(jdbcUrl: String, user: String, password: String, clock: Clock): PostgresContextStore {
            val config =
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    username = user
                    this.password = password
                    maximumPoolSize = 4
                    // Tolerate slow container/proxy readiness on startup.
                    initializationFailTimeout = 20_000
                }
            val dataSource = HikariDataSource(config)
            return open(dataSource, clock)
        }

        internal fun open(dataSource: HikariDataSource, clock: Clock): PostgresContextStore {
            try {
                PostgresSchema.migrate(dataSource)
                return PostgresContextStore(dataSource, clock, ownsDataSource = true)
            } catch (error: Exception) {
                runCatching { dataSource.close() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
    }
}
