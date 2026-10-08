package gokorei.tanseki.core.ports

import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.StoreCapabilities
import gokorei.tanseki.core.domain.UnsupportedStoreOperationException
import gokorei.tanseki.core.domain.documentIdFromPath
import gokorei.tanseki.core.domain.documentPath
import kotlin.time.Instant

data class StorePage<T>(
    val items: List<T>,
    val total: Int,
    val hasMore: Boolean
)

/**
 * Canonical write model. The only layer clients may write to; the Lookup is
 * derived from it. Implementations: FileStore (Markdown + Pijul), DbStore (SQLite).
 */
interface ContextStore {
    fun read(id: DocId): Document?

    fun readIncludingDeleted(id: DocId): Document? = read(id)

    /** Batch read preserving the caller's id order; duplicate ids collapse. */
    fun readMany(ids: List<DocId>): List<Document> {
        val unique = LinkedHashSet(ids)
        if (unique.isEmpty()) return emptyList()
        val found = readManyById(unique.toList()).associateBy { it.id }
        return unique.mapNotNull(found::get)
    }

    fun readManyById(ids: List<DocId>): List<Document> = ids.mapNotNull(::read)

    fun listPage(
        collection: Collection? = null,
        limit: Int,
        offset: Int = 0,
        pathPrefix: String? = null
    ): StorePage<DocRef> {
        require(limit > 0) { "limit must be positive" }
        require(offset >= 0) { "offset must not be negative" }
        val all = list(collection).filterByPathPrefix(pathPrefix).sortedBy { it.id.value }
        return StorePage(
            items = all.drop(offset).take(limit),
            total = all.size,
            hasMore = offset.toLong() + limit < all.size
        )
    }

    /**
     * Keyset page over documents with an id strictly greater than [after].
     *
     * This is the access pattern that makes a vault of any size listable. Offset
     * pagination re-scans and discards everything before the offset on every
     * call, so the tenth-thousandth document costs as much as the first; this
     * asks the store for "everything past this id" instead, which both adapters
     * can answer from an ordered scan they already keep.
     *
     * [after] is exclusive and the ordering is by id, so a caller resuming from
     * the last item it received cannot see that item twice or skip the next one.
     * Documents that sort before [after] are excluded even if they were created
     * after the page was fetched, which is the one behaviour a cursor gives up
     * relative to offset: the page is stable, not a live view.
     *
     * Adapters able to answer this without materialising the collection should
     * override it. The default is correct but costs a full scan.
     */
    fun listPageAfter(
        collection: Collection? = null,
        pathPrefix: String? = null,
        after: DocId? = null,
        limit: Int
    ): StorePage<DocRef> {
        require(limit > 0) { "limit must be positive" }
        val matched = list(collection).filterByPathPrefix(pathPrefix).sortedBy { it.id.value }
        val remaining = if (after == null) matched else matched.filter { it.id.value > after.value }
        return StorePage(
            items = remaining.take(limit),
            // `total` counts the whole matching set, not what is left after the
            // cursor: a folder tree needs to say "1,234 notes in this folder",
            // and a remainder would drift down as the client pages.
            total = matched.size,
            hasMore = remaining.size > limit
        )
    }

    private fun List<DocRef>.filterByPathPrefix(prefix: String?): List<DocRef> {
        if (prefix.isNullOrEmpty()) return this
        return filter { it.path.startsWith(prefix) }
    }

    fun historyPage(id: DocId, limit: Int, offset: Int = 0): StorePage<Revision> {
        require(limit > 0) { "limit must be positive" }
        require(offset >= 0) { "offset must not be negative" }
        val all = history(id).sortedWith(compareBy<Revision> { it.createdAt }.thenBy { it.revision.value })
        return StorePage(
            items = all.drop(offset).take(limit),
            total = all.size,
            hasMore = offset.toLong() + limit < all.size
        )
    }

    fun listAll(): List<DocRef> = list()

    /**
     * Tombstones only — documents that have been deleted but whose content and
     * history are retained.
     *
     * Deliberately not [listAll]. A trash view wants "what can I bring back",
     * and a union of live plus deleted would make the caller subtract to find
     * it. [DocRef.revision] carries the revision at which the document was
     * deleted and [DocRef.updatedAt] the deletion time, so a client can show
     * both without a second call.
     */
    fun listDeleted(collection: Collection? = null): List<DocRef> =
        listAll()
            .filter { collection == null || it.collection == collection }
            .filter { readIncludingDeleted(it.id)?.deleted == true }
            .sortedBy { it.id.value }

    /**
     * Reverses a delete, returning the document to the live set under its
     * original id and path.
     *
     * A tombstone *releases* its path so another document can claim it.
     * Restoring onto a path that has since been
     * taken is therefore a genuine conflict and must fail with
     * [ConflictException] rather than overwrite. The user resolving that
     * collision is the entire point of a trash view; silently overwriting would
     * destroy the document that currently holds the path.
     *
     * Restoring an already-live document is idempotent and returns the current
     * revision without rewriting anything.
     *
     * This default handles only that idempotent case. Actually reversing a
     * delete needs adapter-specific work — SQLite must clear the tombstone and
     * rewrite the projection in one transaction, the file store must remove its
     * tombstone marker — so an adapter that has not implemented it reports that
     * rather than pretending.
     */
    fun restore(id: DocId, message: String, author: String, ifRevision: RevisionId? = null): Revision {
        val tombstone = readIncludingDeleted(id) ?: throw NotFoundException(id)
        if (tombstone.deleted) throw UnsupportedStoreOperationException("store does not support restore")
        checkRevision(tombstone.revision, ifRevision)
        return latestRevisionOrThrow(id)
    }

    /**
     * Moves [from] to [to], carrying its content, and re-points every inbound
     * `[[wikilink]]` at the new id.
     *
     * This cannot be expressed as a [write] plus a [delete], and an adapter must
     * not try. A document's id *is* its path — [documentPath] is `"$value.md"`
     * and the vault store refuses any document whose two disagree — so the pair
     * moves together or not at all. Splitting it leaves two documents alive
     * between the two calls, and a crash inside that window leaves both on disk.
     *
     * Inbound links are rewritten because edges are derived from `[[link]]` text
     * on every index, not stored as the durable truth. Leaving them pointed at
     * the old id would make the referring documents silently drop their edges on
     * the next index, so the graph would rot without any error being raised.
     *
     * [to] must be free. A tombstone *releases* its path, so a deleted document
     * may hold [to] as a tombstone only, and that case renames cleanly; a *live*
     * document there is a genuine collision and raises [ConflictException]
     * without changing anything, exactly as [restore] refuses a reclaimed path.
     *
     * **What "history continues" can mean.** In the vault, history is the Pijul
     * patch DAG and that DAG is keyed by path: moving the file ends one chain and
     * starts another, and no rename of an identifier merges two paths in it. So
     * this method cannot promise a single unbroken chain, and does not. It
     * promises that the document's history stays reachable as one sequence — an
     * adapter that tracks the document's original key composes the chains across
     * the move and records the move itself, so a caller reading [history] sees one
     * timeline with the rename in it, rather than a truncated past and an
     * unrelated future. Adapters that cannot compose report that here instead of
     * returning a partial history that looks continuous.
     *
     * The three refusals below are three different contracts, not three branches
     * of one: a missing source, a held target, and an adapter that cannot rename
     * reach a caller as 404, 409 and 501, and each needs its own reason before the
     * port gives up. Collapsing them to satisfy a throw-count budget would mean
     * either losing a reason or inventing a wrapper type to hold them, and both
     * cost a caller more than the rule saves.
     */
    @Suppress("ThrowsCount")
    fun rename(
        from: DocId,
        to: DocId,
        message: String,
        author: String,
        ifRevision: RevisionId? = null
    ): Revision {
        val source = read(from) ?: throw NotFoundException(from)
        checkRevision(source.revision, ifRevision)
        val holder = read(to)
        if (holder != null && !holder.deleted) {
            throw ConflictException(
                from,
                "path ${to.documentPath()} is held by ${to.value}; resolve the collision before renaming"
            )
        }
        throw UnsupportedStoreOperationException("store does not support rename")
    }

    private fun latestRevisionOrThrow(id: DocId): Revision =
        history(id).lastOrNull() ?: throw NotFoundException(id)

    /** Fails when [ifRevision] is given and no longer matches [actual]. */
    fun checkRevision(actual: RevisionId, ifRevision: RevisionId?) {
        if (ifRevision != null && actual != ifRevision) {
            throw ConflictException(DocId("<revision>"), "revision mismatch")
        }
    }

    fun exportTransferStates(): List<TransferState> =
        listAll().mapNotNull { ref ->
            val doc = readIncludingDeleted(ref.id) ?: return@mapNotNull null
            TransferState(
                document = doc,
                history = history(ref.id)
            )
        }

    fun importTransferState(state: TransferState): Boolean {
        val document = importDocument(this, state.document)
        readIncludingDeleted(document.id)?.let { existing ->
            if (isStaleTransfer(existing, document)) return false
        }
        val last = state.history.lastOrNull()
        if (document.deleted) {
            if (!capabilities().supportsTombstoneImport) return false
            if (read(document.id) != null) {
                delete(document.id, last?.message ?: "mode transfer delete", last?.author ?: "tanseki-transfer")
            }
            // A tombstone must not leave the target pointing at a deleted id.
            removeIncomingEdges(document.id)
        } else {
            write(document, last?.message ?: "mode transfer", last?.author ?: "tanseki-transfer")
        }
        if (capabilities().supportsHistoryImport) {
            appendHistory(document.id, state.history.map { it.copy(docId = document.id) })
        }
        return true
    }

    fun write(
        doc: Document,
        message: String,
        author: String,
        ifRevision: RevisionId? = null
    ): Revision

    fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId? = null): Revision

    fun list(collection: Collection? = null): List<DocRef>

    fun upsertEdge(edge: Edge)

    /** Remove edges originating at [src], optionally only those of type [rel]. */
    fun removeEdges(src: DocId, rel: RelType? = null)

    fun removeIncomingEdges(dst: DocId, rel: RelType? = null): Unit = throw UnsupportedStoreOperationException(
        "store does not support removing incoming edges"
    )

    fun clearEdges(): Unit = throw UnsupportedStoreOperationException("store does not support clearing edges")

    /**
     * Replaces every outgoing edge of [src] with [edges] atomically, so readers
     * never observe a half-written edge set. [edges] is de-duplicated by
     * (rel, dst) before it is written.
     *
     * Adapters able to swap the whole set in one transaction or one atomic file
     * write must override this; the default keeps [edges] de-duplicated but is
     * only as atomic as the individual calls the target store offers.
     */
    fun replaceEdges(src: DocId, edges: List<Edge>) {
        removeEdges(src)
        edges.distinctBy { edge -> edge.rel to edge.dst }.forEach(::upsertEdge)
    }

    fun neighbors(id: DocId, rel: RelType? = null): List<Edge>

    /**
     * Edges pointing AT [id], optionally narrowed to a single relation. The
     * read counterpart to [removeIncomingEdges]; without it a store can destroy
     * an incoming edge but not report one.
     */
    fun incomingNeighbors(id: DocId, rel: RelType? = null): List<Edge> =
        throw UnsupportedStoreOperationException("store does not support incoming edge reads")

    fun history(id: DocId): List<Revision>

    fun historyOwner(id: DocId): DocRef? =
        read(id)?.let {
            DocRef(it.id, it.collection, it.path, it.contentHash, it.revision, it.updatedAt)
        }

    fun projectionOperations(): ProjectionOperationStore? = null

    /**
     * Import historical revisions (provenance) for [id] without touching the
     * current document. Used by mode transfer/import to preserve history across
     * backends. Must be idempotent: revisions already in the ledger are ignored.
     *
     * Only adapters that advertise
     * [gokorei.tanseki.core.domain.StoreCapabilities.supportsHistoryImport] implement this;
     * vault mode leaves its history to the Pijul patch DAG and does not.
     */
    fun appendHistory(id: DocId, revisions: List<Revision>): Unit =
        throw UnsupportedStoreOperationException("store does not support history import")

    fun putBlob(bytes: ByteArray): BlobRef

    fun getBlob(ref: BlobRef): ByteArray

    fun capabilities(): StoreCapabilities
}

data class TransferState(
    val document: Document,
    val history: List<Revision>
)

/**
 * True when [target] cannot store [document] under its own id, i.e. when the
 * target derives ids from Markdown paths (the vault). Those targets address a
 * document by path alone, so the path is authoritative and the id is re-derived
 * from it; every other backend keeps the pair it was given.
 */
fun importDocument(target: ContextStore, document: Document): Document {
    if (!target.capabilities().supportsPatchGraph) return document
    val id = rederivedId(document.path, document.id)
    if (id == document.id && document.path == id.documentPath()) return document
    return document.copy(id = id, path = id.documentPath())
}

/** Id a document takes when imported into [target]; see [importDocument]. */
fun importTargetId(target: ContextStore, document: Document): DocId = importDocument(target, document).id

/**
 * [edge] rewritten so both endpoints address the ids the target actually
 * stores. [targetId] resolves a source id through the same mapping that was
 * used for the documents, so an edge can never dangle because the edge
 * endpoints were re-derived differently from the document they belong to.
 */
fun importEdge(edge: Edge, targetId: (DocId) -> DocId): Edge =
    edge.copy(src = targetId(edge.src), dst = targetId(edge.dst))

private fun rederivedId(path: String, fallback: DocId): DocId =
    runCatching { documentIdFromPath(path) }.getOrNull() ?: fallback

/**
 * True when [incoming] is an older copy of a document the target already holds:
 * applying it would silently roll back a newer local write, so an import must
 * skip it instead.
 */
fun isStaleTransfer(existing: Document, incoming: Document): Boolean =
    existing.id == incoming.id &&
        existing.contentHash != incoming.contentHash &&
        existing.updatedAt > incoming.updatedAt
