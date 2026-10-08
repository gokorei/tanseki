package gokorei.tanseki.adapters.context.sqlite

import gokorei.tanseki.adapters.context.sqlite.db.TansekiDatabase
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.StorePage
import kotlin.time.Instant

/**
 * The document listing half of the SQLite store: reads and the four listing
 * shapes (flat, tombstoned, paged, cursor).
 *
 * Kept out of [SqliteContextStore] so the store is a facade and the row-to-DocRef
 * mapping and the LIKE-prefix handling live once, next to the queries they serve.
 */
internal class SqliteDocuments(private val database: TansekiDatabase) {
    fun read(id: DocId): Document? =
        database.documentsQueries
            .selectById(id.value)
            .executeAsOneOrNull()
            ?.toDomain()

    fun readIncludingDeleted(id: DocId): Document? =
        database.documentsQueries
            .selectByIdIncludingDeleted(id.value)
            .executeAsOneOrNull()
            ?.toDomain()

    fun readManyById(ids: List<DocId>): List<Document> {
        if (ids.isEmpty()) return emptyList()
        // Chunked: SQLite binds one variable per id, and a single IN ? list above
        // the host's variable limit fails outright rather than degrading.
        val rows =
            ids.chunked(ID_READ_CHUNK).flatMap { chunk ->
                database.documentsQueries
                    .selectByIds(chunk.map { it.value })
                    .executeAsList()
                    .map { it.toDomain() }
            }
        val found = rows.associateBy { it.id }
        return ids.mapNotNull(found::get)
    }

    fun list(collection: Collection?): List<DocRef> {
        val rows =
            if (collection == null) {
                database.documentsQueries.selectAll().executeAsList()
            } else {
                database.documentsQueries.selectCollection(collection.value).executeAsList()
            }
        return rows.map {
            DocRef(
                DocId(it.id),
                Collection(it.collection),
                it.path,
                it.content_hash,
                RevisionId(it.revision),
                Instant.fromEpochMilliseconds(it.updated_at)
            )
        }
    }

    fun listDeleted(collection: Collection?): List<DocRef> {
        val rows =
            if (collection == null) {
                database.documentsQueries.selectDeleted().executeAsList()
            } else {
                database.documentsQueries.selectDeletedInCollection(collection.value).executeAsList()
            }
        return rows.map(::toDocRef)
    }

    fun listAll(): List<DocRef> =
        database.documentsQueries
            .selectAllIncludingDeleted()
            .executeAsList()
            .map {
                DocRef(
                    DocId(it.id),
                    Collection(it.collection),
                    it.path,
                    it.content_hash,
                    RevisionId(it.revision),
                    Instant.fromEpochMilliseconds(it.updated_at)
                )
            }

    fun listPage(
        collection: Collection?,
        limit: Int,
        offset: Int,
        pathPrefix: String?
    ): StorePage<DocRef> {
        if (pathPrefix.isNullOrEmpty()) return listPageUnfiltered(collection, limit, offset)
        require(limit > 0) { "limit must be positive" }
        require(offset >= 0) { "offset must not be negative" }
        val pattern = likePrefix(pathPrefix)
        val total =
            if (collection == null) {
                database.documentsQueries
                    .countAllByPathPrefix(pattern)
                    .executeAsOne()
                    .toInt()
            } else {
                database.documentsQueries
                    .countCollectionByPathPrefix(collection.value, pattern)
                    .executeAsOne()
                    .toInt()
            }
        val rows =
            if (collection == null) {
                database.documentsQueries
                    .selectAllPageByPathPrefix(pattern, limit.toLong(), offset.toLong())
                    .executeAsList()
            } else {
                database.documentsQueries
                    .selectCollectionPageByPathPrefix(collection.value, pattern, limit.toLong(), offset.toLong())
                    .executeAsList()
            }
        return StorePage(rows.map(::toDocRef), total, offset.toLong() + rows.size < total)
    }

    fun listPageAfter(
        collection: Collection?,
        pathPrefix: String?,
        after: DocId?,
        limit: Int
    ): StorePage<DocRef> {
        require(limit > 0) { "limit must be positive" }
        val afterId = after?.value ?: ""
        // One row past the limit is the existence check: it costs nothing and
        // avoids a second COUNT scan on every page.
        val rows =
            if (pathPrefix.isNullOrEmpty()) {
                if (collection == null) {
                    database.documentsQueries.listAfterAll(afterId, (limit + 1).toLong()).executeAsList()
                } else {
                    database.documentsQueries
                        .listAfterCollection(collection.value, afterId, (limit + 1).toLong())
                        .executeAsList()
                }
            } else {
                val pattern = likePrefix(pathPrefix)
                if (collection == null) {
                    database.documentsQueries
                        .listAfterAllByPathPrefix(afterId, pattern, (limit + 1).toLong())
                        .executeAsList()
                } else {
                    database.documentsQueries
                        .listAfterCollectionByPathPrefix(collection.value, afterId, pattern, (limit + 1).toLong())
                        .executeAsList()
                }
            }
        val hasMore = rows.size > limit
        val total =
            if (pathPrefix.isNullOrEmpty()) {
                if (collection == null) {
                    database.documentsQueries
                        .countAll()
                        .executeAsOne()
                        .toInt()
                } else {
                    database.documentsQueries
                        .countCollection(collection.value)
                        .executeAsOne()
                        .toInt()
                }
            } else {
                val pattern = likePrefix(pathPrefix)
                if (collection == null) {
                    database.documentsQueries
                        .countAllByPathPrefix(pattern)
                        .executeAsOne()
                        .toInt()
                } else {
                    database.documentsQueries
                        .countCollectionByPathPrefix(collection.value, pattern)
                        .executeAsOne()
                        .toInt()
                }
            }
        return StorePage(rows.take(limit).map(::toDocRef), total, hasMore)
    }

    private fun listPageUnfiltered(collection: Collection?, limit: Int, offset: Int): StorePage<DocRef> {
        require(limit > 0) { "limit must be positive" }
        require(offset >= 0) { "offset must not be negative" }
        val total =
            if (collection == null) {
                database.documentsQueries
                    .countAll()
                    .executeAsOne()
                    .toInt()
            } else {
                database.documentsQueries
                    .countCollection(collection.value)
                    .executeAsOne()
                    .toInt()
            }
        val rows =
            if (collection == null) {
                database.documentsQueries.selectAllPage(limit.toLong(), offset.toLong()).executeAsList()
            } else {
                database.documentsQueries
                    .selectCollectionPage(collection.value, limit.toLong(), offset.toLong())
                    .executeAsList()
            }
        val items =
            rows.map { row ->
                DocRef(
                    DocId(row.id),
                    Collection(row.collection),
                    row.path,
                    row.content_hash,
                    RevisionId(row.revision),
                    Instant.fromEpochMilliseconds(row.updated_at)
                )
            }
        return StorePage(items, total, offset.toLong() + items.size < total)
    }
}

/** SQLite's historical host-parameter limit is 999; stay under it. */
private const val ID_READ_CHUNK = 900
