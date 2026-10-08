package gokorei.tanseki.adapters.context.sqlite

import gokorei.tanseki.adapters.context.sqlite.db.TansekiDatabase
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.StorePage
import kotlin.time.Instant

/**
 * The revision ledger: whole history, paged history, owner lookup.
 *
 * Kept out of [SqliteContextStore] so the store is a facade and one query
 * family (the append-only revisions table) lives here with its row mapping.
 */
internal class SqliteHistory(private val database: TansekiDatabase) {
    fun history(id: DocId): List<Revision> =
        database.revisionsQueries.selectForDoc(id.value).executeAsList().map { row ->
            Revision(
                docId = DocId(row.doc_id),
                revision = RevisionId(row.revision),
                author = row.author,
                message = row.message,
                contentHash = row.content_hash,
                createdAt = Instant.fromEpochMilliseconds(row.created_at),
                deps =
                    row.deps
                        .split(",")
                        .filter { it.isNotBlank() }
                        .map { RevisionId(it) },
                content = row.content
            )
        }

    fun historyPage(id: DocId, limit: Int, offset: Int): StorePage<Revision> {
        require(limit > 0) { "limit must be positive" }
        require(offset >= 0) { "offset must not be negative" }
        val total =
            database.revisionsQueries
                .countForDoc(id.value)
                .executeAsOne()
                .toInt()
        val items =
            database.revisionsQueries
                .selectForDocPage(id.value, limit.toLong(), offset.toLong())
                .executeAsList()
                .map { row ->
                    Revision(
                        docId = DocId(row.doc_id),
                        revision = RevisionId(row.revision),
                        author = row.author,
                        message = row.message,
                        contentHash = row.content_hash,
                        createdAt = Instant.fromEpochMilliseconds(row.created_at),
                        deps =
                            row.deps
                                .split(",")
                                .filter { it.isNotBlank() }
                                .map { RevisionId(it) },
                        content = row.content
                    )
                }
        return StorePage(items, total, offset.toLong() + items.size < total)
    }

    fun historyOwner(id: DocId): DocRef? =
        database.documentsQueries
            .selectByIdIncludingDeleted(id.value)
            .executeAsOneOrNull()
            ?.let {
                DocRef(
                    id = DocId(it.id),
                    collection = Collection(it.collection),
                    path = it.path,
                    contentHash = it.content_hash,
                    revision = RevisionId(it.revision),
                    updatedAt = Instant.fromEpochMilliseconds(it.updated_at)
                )
            }

    fun latestRevision(id: DocId): Revision? = history(id).lastOrNull()
}
