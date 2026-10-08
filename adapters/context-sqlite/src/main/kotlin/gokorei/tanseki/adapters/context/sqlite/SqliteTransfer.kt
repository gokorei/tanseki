@file:Suppress("CyclomaticComplexMethod")

package gokorei.tanseki.adapters.context.sqlite

import gokorei.tanseki.adapters.context.sqlite.db.TansekiDatabase
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.TransferState
import gokorei.tanseki.core.ports.importDocument
import gokorei.tanseki.core.ports.isStaleTransfer
import gokorei.tanseki.core.text.FrontmatterCodec

/**
 * History and document transfer: reconciliation `importTransferState` and the
 * append-only `appendHistory`. Kept out of [SqliteContextStore] so the store is a
 * facade and the reconciliation rules live once next to the queries they drive.
 */
internal class SqliteTransfer(
    private val database: TansekiDatabase,
    private val store: SqliteContextStore,
    private val documents: SqliteDocuments,
    private val history: SqliteHistory
) {
    @Synchronized
    fun importTransferState(state: TransferState): Boolean =
        withBusyRetry {
            database.transactionWithResult {
                val document = importDocument(store, state.document)
                requireDerivedPath(document)
                val existing = documents.readIncludingDeleted(document.id)
                val existingHistory = history.history(document.id)
                if (existing != null && existing.collection != document.collection) {
                    throw collectionConflict(document, existing.id.value)
                }
                if (existing != null && isStaleTransfer(existing, document)) return@transactionWithResult false
                val documentChanged =
                    existing == null ||
                        existing.contentHash != document.contentHash ||
                        existing.revision != document.revision ||
                        existing.deleted != document.deleted
                val changed =
                    documentChanged ||
                        state.history.any { revision -> existingHistory.none { it.revision == revision.revision } }
                if (!document.deleted) {
                    val owner =
                        database.documentsQueries
                            .selectByCollectionAndPath(document.collection.value, document.path)
                            .executeAsOneOrNull()
                    if (owner != null && owner.id != document.id.value) throw pathConflict(document, owner.id)
                }
                state.history.forEach { revision ->
                    val row =
                        database.revisionsQueries
                            .selectOne(
                                document.id.value,
                                revision.revision.value
                            ).executeAsOneOrNull()
                    if (row != null && !sameRevisionContent(row.content_hash, row.content, revision)) {
                        throw revisionConflict(document.id, revision.revision)
                    }
                }
                database.documentsQueries.insertOrIgnore(
                    id = document.id.value,
                    collection = document.collection.value,
                    path = document.path,
                    frontmatter = FrontmatterCodec.serialize(document.frontmatter),
                    content = document.content,
                    content_hash = document.contentHash,
                    revision = document.revision.value,
                    updated_at = document.updatedAt.toEpochMilliseconds(),
                    deleted = if (document.deleted) 1L else 0L,
                    frontmatter_raw = document.frontmatter.rawFrontmatter
                )
                database.documentsQueries.update(
                    collection = document.collection.value,
                    path = document.path,
                    frontmatter = FrontmatterCodec.serialize(document.frontmatter),
                    content = document.content,
                    content_hash = document.contentHash,
                    revision = document.revision.value,
                    updated_at = document.updatedAt.toEpochMilliseconds(),
                    deleted = if (document.deleted) 1L else 0L,
                    frontmatter_raw = document.frontmatter.rawFrontmatter,
                    id = document.id.value
                )
                state.history.forEach { revision ->
                    database.revisionsQueries.insertOrIgnore(
                        doc_id = document.id.value,
                        revision = revision.revision.value,
                        author = revision.author,
                        message = revision.message,
                        content_hash = revision.contentHash,
                        content = revision.content,
                        created_at = revision.createdAt.toEpochMilliseconds(),
                        deps = revision.deps.joinToString(",") { it.value }
                    )
                }
                val operation =
                    if (document.deleted) {
                        gokorei.tanseki.core.domain.ProjectionOperation.delete(
                            document.id,
                            document.revision,
                            document.contentHash,
                            document.updatedAt
                        )
                    } else {
                        gokorei.tanseki.core.domain.ProjectionOperation.upsert(
                            document,
                            document.revision,
                            document.updatedAt
                        )
                    }
                database.projectionOperationsQueries.upsertOperation(operation)
                // A tombstone must not leave the target pointing at a deleted id.
                if (document.deleted) {
                    database.edgesQueries.deleteEdgesTo(document.id.value)
                }
                changed
            }
        }

    fun appendHistory(id: DocId, revisions: List<Revision>) {
        withBusyRetry {
            database.transaction {
                revisions.forEach { revision ->
                    val existing =
                        database.revisionsQueries
                            .selectOne(
                                id.value,
                                revision.revision.value
                            ).executeAsOneOrNull()
                    if (existing != null && !sameRevisionContent(existing.content_hash, existing.content, revision)) {
                        throw revisionConflict(id, revision.revision)
                    }
                    database.revisionsQueries.insertOrIgnore(
                        doc_id = id.value,
                        revision = revision.revision.value,
                        author = revision.author,
                        message = revision.message,
                        content_hash = revision.contentHash,
                        content = revision.content,
                        created_at = revision.createdAt.toEpochMilliseconds(),
                        deps = revision.deps.joinToString(",") { it.value }
                    )
                }
            }
        }
    }
}
