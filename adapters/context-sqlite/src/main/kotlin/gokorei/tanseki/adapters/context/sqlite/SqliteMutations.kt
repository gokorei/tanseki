package gokorei.tanseki.adapters.context.sqlite

import gokorei.tanseki.adapters.context.sqlite.db.TansekiDatabase
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.documentPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.text.FrontmatterCodec
import gokorei.tanseki.core.text.LinkResolver
import gokorei.tanseki.core.text.WikilinkRewriter
import kotlin.time.Instant

/**
 * The document mutation paths: write, delete, restore and rename, each in one
 * SQLite transaction with a projection outbox row to follow.
 *
 * Kept out of [SqliteContextStore] so the four pipelines — which share the same
 * shape (validate, stamp a history timestamp, mutate the row, enqueue the
 * projection work, return the revision) — are one place instead of half the
 * store. A rename re-points inbound `[[wikilinks]]` through [relinkInbound]
 * before the row moves, because the referrers can only be recognised by
 * resolving them against the id they currently point at.
 */
internal class SqliteMutations(
    private val database: TansekiDatabase,
    private val clock: Clock,
    private val documents: SqliteDocuments,
    private val history: SqliteHistory,
    private val outbox: SqliteOutbox
) {
    fun write(
        doc: Document,
        message: String,
        author: String,
        ifRevision: RevisionId?
    ): Revision {
        if (doc.deleted) throw InvalidInputException("write cannot create a deleted document")
        requireDerivedPath(doc)
        return withBusyRetry {
            database.transactionWithResult {
                val existing = validateWriteTarget(doc, ifRevision)
                if (existing?.contentHash == doc.contentHash) {
                    return@transactionWithResult history.latestRevision(doc.id)
                        ?: synthesizeRevision(doc, author, message)
                }
                ensureRevisionAvailable(doc)
                ensurePathAvailable(doc)

                val historyCreatedAt = nextHistoryTimestamp(clock, doc.updatedAt, history.latestRevision(doc.id))
                database.documentsQueries.insertOrIgnore(
                    id = doc.id.value,
                    collection = doc.collection.value,
                    path = doc.path,
                    frontmatter = FrontmatterCodec.serialize(doc.frontmatter),
                    content = doc.content,
                    content_hash = doc.contentHash,
                    revision = doc.revision.value,
                    updated_at = doc.updatedAt.toEpochMilliseconds(),
                    deleted = 0L,
                    frontmatter_raw = doc.frontmatter.rawFrontmatter
                )
                if (
                    database.documentsQueries
                        .selectByIdIncludingDeleted(doc.id.value)
                        .executeAsOneOrNull() == null
                ) {
                    throw pathConflict(doc)
                }
                database.documentsQueries.update(
                    collection = doc.collection.value,
                    path = doc.path,
                    frontmatter = FrontmatterCodec.serialize(doc.frontmatter),
                    content = doc.content,
                    content_hash = doc.contentHash,
                    revision = doc.revision.value,
                    updated_at = doc.updatedAt.toEpochMilliseconds(),
                    deleted = 0L,
                    frontmatter_raw = doc.frontmatter.rawFrontmatter,
                    id = doc.id.value
                )
                database.revisionsQueries.insert(
                    doc_id = doc.id.value,
                    revision = doc.revision.value,
                    author = author,
                    message = message,
                    content_hash = doc.contentHash,
                    content = doc.content,
                    created_at = historyCreatedAt.toEpochMilliseconds(),
                    deps = doc.frontmatter.text("deps") ?: ""
                )

                val revision =
                    Revision(
                        docId = doc.id,
                        revision = doc.revision,
                        author = author,
                        message = message,
                        contentHash = doc.contentHash,
                        createdAt = historyCreatedAt
                    )
                val operation = ProjectionOperation.upsert(doc, revision.revision, historyCreatedAt, doc.contentHash)
                database.projectionOperationsQueries.upsertOperation(operation)
                revision
            }
        }
    }

    fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
        withBusyRetry {
            database.transactionWithResult {
                val existing =
                    documents.read(id) ?: throw NotFoundException(id)
                if (ifRevision != null && existing.revision != ifRevision) {
                    throw ConflictException(
                        id,
                        "revision mismatch: expected ${ifRevision.value}, found ${existing.revision.value}"
                    )
                }
                val createdAt = nextHistoryTimestamp(clock, existing.updatedAt, history.latestRevision(id))
                val revision =
                    Revision(
                        docId = id,
                        revision = RevisionId("deleted-${existing.revision.value}"),
                        author = author,
                        message = message,
                        contentHash = existing.contentHash,
                        createdAt = createdAt
                    )
                if (database.revisionsQueries.selectOne(id.value, revision.revision.value).executeAsOneOrNull() !=
                    null
                ) {
                    throw revisionConflict(id, revision.revision)
                }
                database.documentsQueries.markDeleted(
                    revision = revision.revision.value,
                    updated_at = createdAt.toEpochMilliseconds(),
                    id = id.value
                )
                database.revisionsQueries.insert(
                    doc_id = id.value,
                    revision = revision.revision.value,
                    author = author,
                    message = message,
                    content_hash = existing.contentHash,
                    content = existing.content,
                    created_at = createdAt.toEpochMilliseconds(),
                    deps = ""
                )
                outbox.enqueue(ProjectionOperation.delete(id, revision.revision, existing.contentHash, createdAt))
                revision
            }
        }

    fun restore(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
        withBusyRetry {
            database.transactionWithResult {
                val tombstone =
                    database.documentsQueries
                        .selectByIdIncludingDeleted(id.value)
                        .executeAsOneOrNull()
                        ?: throw NotFoundException(id)
                val currentRevision = RevisionId(tombstone.revision)
                if (ifRevision != null && currentRevision != ifRevision) {
                    throw ConflictException(
                        id,
                        "revision mismatch: expected ${ifRevision.value}, found ${currentRevision.value}"
                    )
                }
                if (tombstone.deleted == 0L) {
                    // Already live: idempotent, no new revision.
                    return@transactionWithResult history.latestRevision(id) ?: throw NotFoundException(id)
                }
                // A tombstone released its path, so another document may hold it
                // now. Restoring over that document would destroy it, which is the
                // one outcome a trash view must never produce silently.
                val holder =
                    database.documentsQueries
                        .selectByCollectionAndPath(tombstone.collection, tombstone.path)
                        .executeAsOneOrNull()
                if (holder != null && holder.id != id.value) {
                    throw ConflictException(
                        id,
                        "path ${tombstone.path} is held by ${holder.id}; resolve the collision before restoring"
                    )
                }
                val createdAt =
                    nextHistoryTimestamp(
                        clock,
                        Instant.fromEpochMilliseconds(tombstone.updated_at),
                        history.latestRevision(id)
                    )
                val revision =
                    Revision(
                        docId = id,
                        revision = RevisionId("restored-${currentRevision.value}"),
                        author = author,
                        message = message,
                        contentHash = tombstone.content_hash,
                        createdAt = createdAt
                    )
                database.documentsQueries.markRestored(
                    revision = revision.revision.value,
                    updated_at = createdAt.toEpochMilliseconds(),
                    id = id.value
                )
                if (database.revisionsQueries.selectOne(id.value, revision.revision.value).executeAsOneOrNull() !=
                    null
                ) {
                    throw revisionConflict(id, revision.revision)
                }
                database.revisionsQueries.insert(
                    doc_id = id.value,
                    revision = revision.revision.value,
                    author = author,
                    message = message,
                    content_hash = tombstone.content_hash,
                    content = tombstone.content,
                    created_at = createdAt.toEpochMilliseconds(),
                    deps = ""
                )
                // Restore is an upsert to the projection: bringing the document back means
                // re-indexing it, and the indexer re-derives its edges. A new
                // ProjectionKind would ripple through the worker, the MCP surface
                // and the schema for no behavioural difference.
                outbox.enqueue(
                    ProjectionOperation.upsert(
                        document = tombstone.toDomain().copy(deleted = false, revision = revision.revision),
                        revision = revision.revision,
                        createdAt = createdAt
                    )
                )
                revision
            }
        }

    fun rename(
        from: DocId,
        to: DocId,
        message: String,
        author: String,
        ifRevision: RevisionId?
    ): Revision =
        withBusyRetry {
            database.transactionWithResult {
                val source = database.documentsQueries.selectByIdIncludingDeleted(from.value).executeAsOneOrNull()
                if (source == null || source.deleted != 0L) throw NotFoundException(from)
                val currentRevision = RevisionId(source.revision)
                if (ifRevision != null && currentRevision != ifRevision) {
                    throw ConflictException(
                        from,
                        "revision mismatch: expected ${ifRevision.value}, found ${currentRevision.value}"
                    )
                }
                val targetPath = to.documentPath()
                val holder =
                    database.documentsQueries
                        .selectByCollectionAndPath(source.collection, targetPath)
                        .executeAsOneOrNull()
                if (holder != null && holder.id != from.value) {
                    throw pathHeldConflict(from, targetPath, holder.id)
                }
                reclaimTombstone(from, to, targetPath)

                val createdAt =
                    nextHistoryTimestamp(
                        clock,
                        Instant.fromEpochMilliseconds(source.updated_at),
                        history.latestRevision(from)
                    )
                // Before the row moves, while `from` is still what a link resolves
                // to: the referring documents can only be recognised by resolving
                // them against the id they currently point at.
                relinkInbound(from, to, author, createdAt)

                val renameRevision = RevisionId("renamed-${currentRevision.value}")
                database.documentsQueries.renameDocument(
                    newId = to.value,
                    newPath = targetPath,
                    newRevision = renameRevision.value,
                    newUpdatedAt = createdAt.toEpochMilliseconds(),
                    oldId = from.value
                )
                // Edges address documents by id, so both endpoints move. This also
                // repairs the edges the relink above re-pointed in the documents'
                // own text, without deriving anything twice.
                database.edgesQueries.retargetEdgeSrc(to.value, from.value)
                database.edgesQueries.retargetEdgeDst(to.value, from.value)
                database.revisionsQueries.retargetDoc(to.value, from.value)
                database.revisionsQueries.insert(
                    doc_id = to.value,
                    revision = renameRevision.value,
                    author = author,
                    message = message,
                    content_hash = source.content_hash,
                    content = source.content,
                    created_at = createdAt.toEpochMilliseconds(),
                    deps = ""
                )
                val moved =
                    database.documentsQueries
                        .selectByIdIncludingDeleted(to.value)
                        .executeAsOneOrNull()
                if (moved == null || moved.path != targetPath || moved.id != to.value) {
                    throw ConflictException(from, "rename to $targetPath did not take effect")
                }
                val revision =
                    Revision(
                        docId = to,
                        revision = renameRevision,
                        author = author,
                        message = message,
                        contentHash = source.content_hash,
                        createdAt = createdAt
                    )
                // The document's projection is keyed by path, so the lookup side
                // has to learn the new one or searches keep answering from the old.
                outbox.enqueue(
                    ProjectionOperation.upsert(
                        document =
                            source.toDomain().copy(
                                id = to,
                                path = targetPath,
                                revision = renameRevision,
                                updatedAt = createdAt
                            ),
                        revision = renameRevision,
                        createdAt = createdAt,
                        contentHash = source.content_hash
                    )
                )
                revision
            }
        }

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
    private fun reclaimTombstone(from: DocId, to: DocId, targetPath: String) {
        val reclaimed = database.documentsQueries.selectByIdIncludingDeleted(to.value).executeAsOneOrNull()
        if (reclaimed == null || reclaimed.id == from.value) return
        if (reclaimed.deleted == 0L) throw pathHeldConflict(from, targetPath, reclaimed.id)
        database.edgesQueries.deleteEdgesFrom(reclaimed.id)
        database.edgesQueries.deleteEdgesTo(reclaimed.id)
        database.revisionsQueries.deleteForDoc(reclaimed.id)
        database.documentsQueries.deleteByIdIncludingDeleted(reclaimed.id)
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
    private fun relinkInbound(from: DocId, to: DocId, author: String, at: Instant) {
        val referrers =
            database.edgesQueries
                .neighborsIn(from.value)
                .executeAsList()
                .map { it.src }
                .distinct()
                .filter { it != from.value }
        if (referrers.isEmpty()) return
        val refs = documents.list(null)
        val resolve = { target: String ->
            LinkResolver.resolve(
                target,
                exists = { id -> database.documentsQueries.selectById(id.value).executeAsOneOrNull() != null },
                refs = refs
            )
        }
        referrers.forEach { srcId ->
            val row = database.documentsQueries.selectById(srcId).executeAsOneOrNull() ?: return@forEach
            val rewritten = WikilinkRewriter.retarget(row.content, resolve, from, to)
            if (rewritten == row.content) return@forEach
            val hash = sha256(rewritten.toByteArray(Charsets.UTF_8))
            val relinkRevision = RevisionId("relinked-${row.revision}")
            database.documentsQueries.update(
                collection = row.collection,
                path = row.path,
                frontmatter = row.frontmatter,
                content = rewritten,
                content_hash = hash,
                revision = relinkRevision.value,
                updated_at = at.toEpochMilliseconds(),
                deleted = 0L,
                frontmatter_raw = row.frontmatter_raw,
                id = row.id
            )
            database.revisionsQueries.insert(
                doc_id = row.id,
                revision = relinkRevision.value,
                author = author,
                message = "relinked after rename",
                content_hash = hash,
                content = rewritten,
                created_at = at.toEpochMilliseconds(),
                deps = ""
            )
            // Queued rather than derived here: the index owns edge derivation, and
            // a store that wrote edges behind the indexer's back would let a failed
            // delivery look projected.
            outbox.enqueue(
                ProjectionOperation.upsert(
                    document =
                        row.toDomain().copy(
                            content = rewritten,
                            contentHash = hash,
                            revision = relinkRevision,
                            updatedAt = at
                        ),
                    revision = relinkRevision,
                    createdAt = at,
                    contentHash = hash
                )
            )
        }
    }

    private fun validateWriteTarget(doc: Document, ifRevision: RevisionId?): Document? {
        val stored = documents.readIncludingDeleted(doc.id)
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

    private fun ensureRevisionAvailable(doc: Document) {
        val existing =
            database.revisionsQueries
                .selectOne(doc.id.value, doc.revision.value)
                .executeAsOneOrNull()
        if (existing != null) throw revisionConflict(doc)
    }

    private fun ensurePathAvailable(doc: Document) {
        val owner =
            database.documentsQueries
                .selectByCollectionAndPath(doc.collection.value, doc.path)
                .executeAsOneOrNull()
        if (owner != null && owner.id != doc.id.value) throw pathConflict(doc, owner.id)
    }
}
