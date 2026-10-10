package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.RequestLimits
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Embedder
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.StorePage
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.core.ports.VectorWriter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A SHA-256 digest in lowercase hex: 64 characters. */
private const val SHA256_HEX_LENGTH = 64

/** Lowercase hex only: an uppercase digest would address a different string. */
private fun isLowerHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'

/**
 * The single entry point for reads and writes. Enforces the one-way rule:
 * writes go to the canonical [ContextStore] **first**, then the derived [Lookup]
 * is updated. Nothing else may write the Lookup.
 */
class QueryFacade(
    private val store: ContextStore,
    private val lookup: Lookup,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val logger: TansekiLogger = TansekiLogger.Noop,
    /** When set, enables [searchHybrid] by embedding the query text. */
    private val embedder: Embedder? = null,
    private val vectorWriter: VectorWriter? = (lookup as? VectorWriter),
    private val overlay: PendingOverlay = PendingOverlay(),
    indexObserver: IndexObserver = IndexObserver.Noop,
    /**
     * Feed to publish committed changes into, when a client is watching.
     *
     * The facade builds its own [Indexer], so the feed has to arrive here rather
     * than only at the daemon: without it an API write commits and projects but
     * never reaches a subscriber, which is the specific case this feed exists for.
     */
    changeFeed: ChangeFeed? = null
) {
    private val indexer = Indexer(store, lookup, embedder, vectorWriter, logger, indexObserver, changeFeed)
    private val projectionWorker = ProjectionWorker(store, indexer, logger, overlay)
    private val mutationLock = Mutex()

    suspend fun get(id: DocId): Document? = withContext(io) { overlay.get(id) ?: store.read(id) }

    suspend fun getMany(ids: List<DocId>): List<Document> =
        withContext(io) {
            val unique = LinkedHashSet(ids)
            if (unique.isEmpty()) return@withContext emptyList()
            val stored = store.readMany(unique.toList()).associateBy { it.id }
            unique.mapNotNull { id -> overlay.get(id) ?: stored[id] }
        }

    suspend fun list(collection: Collection? = null): List<DocRef> =
        withContext(io) { overlay.mergeRefs(store.list(collection), collection) }

    suspend fun listPage(collection: Collection? = null, limit: Int, offset: Int = 0): StorePage<DocRef> =
        withContext(io) { listPageInternal(setOfNotNull(collection), limit, offset, null) }

    /** Offset page narrowed to [pathPrefix]; see [ContextStore.listPage]. */
    suspend fun listPage(
        collection: Collection?,
        limit: Int,
        offset: Int,
        pathPrefix: String?
    ): StorePage<DocRef> = withContext(io) { listPageInternal(setOfNotNull(collection), limit, offset, pathPrefix) }

    /** Keyset page across a single collection, or the whole store when [collection] is null. */
    suspend fun listPageAfter(
        collection: Collection? = null,
        pathPrefix: String? = null,
        after: DocId? = null,
        limit: Int = 50
    ): StorePage<DocRef> =
        withContext(io) { listPageAfterInternal(setOfNotNull(collection), pathPrefix, after, limit) }

    /**
     * Keyset page over documents with an id strictly after [after], optionally
     * restricted to [pathPrefix]. See [ContextStore.listPageAfter] for why a
     * cursor rather than an offset.
     *
     * [after] resumes from the last document the client received. The page is
     * therefore stable against concurrent writes in a way an offset page is not:
     * a document created while the client is paging cannot shift the window and
     * cause a repeat or a skip, because the window is a value rather than a
     * position.
     */
    suspend fun listPageAfter(
        collections: Set<Collection>,
        pathPrefix: String? = null,
        after: DocId? = null,
        limit: Int = 50
    ): StorePage<DocRef> {
        require(collections.isNotEmpty()) { "collections must not be empty" }
        return withContext(io) { listPageAfterInternal(collections, pathPrefix, after, limit) }
    }

    private suspend fun listPageAfterInternal(
        collections: Set<Collection>,
        pathPrefix: String?,
        after: DocId?,
        limit: Int
    ): StorePage<DocRef> {
        require(limit in 1..RequestLimits.MAX_PAGE_LIMIT) { "limit is out of range" }
        // One row past the limit, so hasMore survives the overlay merge below
        // rather than being reported from a window pending refs can eat into.
        val fetchLimit = limit + 1

        fun after(ref: DocRef) = after == null || ref.id.value > after.value

        fun inPrefix(ref: DocRef) = pathPrefix.isNullOrEmpty() || ref.path.startsWith(pathPrefix)

        if (collections.size > 1) {
            val refs =
                collections
                    .flatMap { candidate ->
                        overlay.mergeRefs(
                            store.listPageAfter(candidate, pathPrefix, after, fetchLimit).items,
                            candidate
                        )
                    }.filter { it.collection in collections }
                    .filter(::inPrefix)
                    .filter(::after)
                    .distinctBy { it.id }
                    .sortedBy { it.id.value }
            val pending =
                overlay
                    .pendingRefs(null)
                    .filter { it.collection in collections }
                    .filter(::inPrefix)
                    .filter(::after)
            val combined = (refs + pending).distinctBy { it.id }.sortedBy { it.id.value }
            return StorePage(combined.take(limit), combined.size, combined.size > limit)
        }

        val target = collections.firstOrNull()
        val page = store.listPageAfter(target, pathPrefix, after, fetchLimit)
        val pending = overlay.pendingRefs(target).filter(::inPrefix).filter(::after)
        val refs =
            (overlay.mergeRefs(page.items, target) + pending)
                .distinctBy { it.id }
                .filter { target == null || it.collection == target }
                .sortedBy { it.id.value }
        // hasMore comes from the merged set rather than page.hasMore: a pending
        // ref landing inside the window changes how many items the page really
        // holds, and a cursor that under-reports hasMore strands the client on
        // the last page with documents still behind it.
        return StorePage(refs.take(limit), page.total, refs.size > limit)
    }

    /**
     * Lists across an explicit set of collections. The set must be non-empty:
     * an empty set would fall through to an unfiltered (store-wide) page.
     */
    suspend fun listPage(
        collections: Set<Collection>,
        limit: Int,
        offset: Int = 0,
        pathPrefix: String? = null
    ): StorePage<DocRef> {
        require(collections.isNotEmpty()) { "collections must not be empty" }
        return withContext(io) { listPageInternal(collections, limit, offset, pathPrefix) }
    }

    suspend fun history(id: DocId): List<Revision> = withContext(io) { store.history(id) }

    suspend fun historyPage(id: DocId, limit: Int, offset: Int = 0): StorePage<Revision> =
        withContext(io) { store.historyPage(id, limit, offset) }

    suspend fun historyOwner(id: DocId): DocRef? = withContext(io) { store.historyOwner(id) }

    /**
     * Edges leaving [id], straight from the canonical store.
     *
     * [traverse] answers from the derived Lookup, which is behind pending
     * writes; an edge count that scopes operational metrics to a collection has
     * to be the store's own, so it is read here rather than inferred from a
     * traversal the overlay could still be hiding.
     */
    suspend fun neighbors(id: DocId): List<Edge> = withContext(io) { store.neighbors(id) }

    suspend fun searchText(q: String, filters: Filters = Filters(), limit: Int = 20): List<Hit> =
        searchTextPage(q, filters, limit, 0).take(limit)

    suspend fun searchTextPage(
        q: String,
        filters: Filters = Filters(),
        limit: Int,
        offset: Int
    ): List<Hit> =
        withContext(io) {
            val window = searchWindow(offset, limit)
            val raw = overlay.merge(lookup.searchText(q, filters, window), q, filters, window)
            raw
                .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.id.value })
                .drop(offset)
                .take(limit + 1)
        }

    private suspend fun listPageInternal(
        collections: Set<Collection>,
        limit: Int,
        offset: Int,
        pathPrefix: String?
    ): StorePage<DocRef> {
        pageBounds(limit, offset)
        val prefix = pathPrefix?.takeIf(String::isNotEmpty)
        val fetchLimit = offset + limit + 1
        if (collections.size > 1) {
            val pages =
                collections.sortedBy { it.value }.map { candidate ->
                    val page = store.listPage(candidate, fetchLimit, 0, prefix)
                    val refs =
                        overlay
                            .mergeRefs(page.items, candidate)
                            .filter { prefix == null || it.path.startsWith(prefix) }
                            .sortedBy { it.id.value }
                    refs to page.total
                }
            val pending = overlay.pendingRefs(null).filter { it.collection in collections }
            val combined = (pages.flatMap { it.first } + pending).distinctBy { it.id }.sortedBy { it.id.value }
            val total = pages.sumOf { it.second } - hiddenTotal(collections) + overlayOnly(pending)
            val items = combined.drop(offset).take(limit)
            return StorePage(items, total, offset.toLong() + items.size < total)
        }
        val target = collections.firstOrNull()
        val page = store.listPage(target, fetchLimit, 0, prefix)
        val pending = overlay.pendingRefs(target)
        val refs =
            overlay
                .mergeRefs(page.items, target)
                .filter { prefix == null || it.path.startsWith(prefix) }
                .sortedBy { it.id.value }
        val total = page.total - hiddenTotal(collections) + overlayOnly(pending)
        val items = refs.drop(offset).take(limit)
        return StorePage(items, total, offset.toLong() + items.size < total)
    }

    /**
     * Pending refs the canonical listing does not already account for. Only a
     * document the store has never seen adds to the total: comparing against the
     * fetched page window would count store documents beyond that window twice.
     */
    private fun overlayOnly(pending: List<DocRef>): Int = pending.count { ref -> store.read(ref.id) == null }

    /**
     * Store documents the overlay hides from a listing (a pending delete, or an
     * unprojected tombstone). They are inside the store's total but never in the
     * merged page, so the total has to subtract exactly the ones the store still
     * reports — no more, or an untouched collection would shrink. An empty
     * [collections] set means the unfiltered listing.
     */
    private fun hiddenTotal(collections: Set<Collection>): Int =
        overlay.hiddenIds().count { id ->
            val stored = store.read(id)
            stored != null && (collections.isEmpty() || stored.collection in collections)
        }

    suspend fun searchVector(
        vector: FloatArray,
        filters: Filters = Filters(),
        limit: Int = 20
    ): List<Hit> =
        withContext(io) {
            // Over-fetch by the number of ids the overlay may suppress: those hits
            // are counted against the Lookup's limit, so asking for exactly
            // `limit` would return fewer than the caller asked for. Text search
            // gets this for free from `merge`, which merges pending hits *in*;
            // vector search has no pending side to merge, only hits to remove.
            val window = limit + overlay.suppressionSize()
            overlay.filterSupersededHits(lookup.searchVector(vector, filters, window)).take(limit)
        }

    /**
     * Hybrid search: fuses lexical and vector ranks with [ReciprocalRankFusion].
     * The query is embedded when an [Embedder] is configured; if there is no
     * embedder (or the Lookup has no vectors, e.g. Meili today), this degrades to
     * plain lexical search rather than returning nothing.
     */
    suspend fun searchHybrid(q: String, filters: Filters = Filters(), limit: Int = 20): List<Hit> =
        searchHybridPage(q, filters, limit, 0).take(limit)

    suspend fun searchHybridPage(
        q: String,
        filters: Filters = Filters(),
        limit: Int,
        offset: Int
    ): List<Hit> =
        withContext(io) {
            val window = searchWindow(offset, limit)
            val lexical = lookup.searchText(q, filters, window)
            val vector = queryVector(q)?.let { query -> vectorHits(query, filters, window) }
            val fused =
                if (vector.isNullOrEmpty()) {
                    lexical
                } else {
                    ReciprocalRankFusion.fuse(listOf(lexical, vector), window)
                }
            overlay
                .merge(fused, q, filters, window)
                .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.id.value })
                .drop(offset)
                .take(limit + 1)
        }

    private fun queryVector(q: String): FloatArray? {
        val embedder = this.embedder ?: return null
        if (q.isBlank()) return null
        return runCatching { embedder.embed(listOf(q)).firstOrNull() }.getOrNull()
    }

    private fun vectorHits(query: FloatArray, filters: Filters, limit: Int): List<Hit> =
        runCatching { lookup.searchVector(query, filters, limit) }.getOrDefault(emptyList())

    suspend fun traverse(id: DocId, rel: RelType, depth: Int = 1): List<DocId> =
        withContext(io) {
            if (overlay.isPending(id)) {
                emptyList()
            } else {
                overlay.filterDeleted(lookup.traverse(id, rel, depth))
            }
        }

    /**
     * Documents linking to [id]. Tombstoned sources are filtered the same way
     * [traverse] filters deleted targets, so an unlinked note does not linger
     * in a backlink list.
     */
    suspend fun backlinks(id: DocId, rel: RelType? = null): List<DocId> =
        withContext(io) {
            if (overlay.isPending(id)) {
                emptyList()
            } else {
                overlay.filterDeleted(lookup.backlinks(id, rel))
            }
        }

    /**
     * Persist a document, then reconcile its derived edges and update the index.
     * Returns the revision produced by the store.
     */
    suspend fun write(
        doc: Document,
        message: String,
        author: String,
        ifRevision: RevisionId? = null
    ): Revision =
        withContext(io) {
            val (revision, canonical, operations) =
                mutationLock.withLock {
                    val rev = store.write(doc, message, author, ifRevision)
                    val can = store.read(doc.id) ?: doc
                    overlay.put(can)
                    val ops = store.projectionOperations()
                    ops?.enqueue(
                        ProjectionOperation.upsert(
                            can,
                            rev.revision,
                            clock.now(),
                            can.contentHash
                        )
                    )
                    Triple(rev, can, ops)
                }

            val edges =
                if (operations == null) {
                    indexer.index(canonical)
                    overlay.markProjected(canonical.id, canonical.contentHash)
                    store.neighbors(canonical.id).size
                } else {
                    projectionWorker.drain()
                    store.neighbors(canonical.id).size
                }
            logger.info(
                "write committed",
                mapOf("doc" to canonical.id.value, "revision" to revision.revision.value, "edges" to edges)
            )
            revision
        }

    /**
     * A document including tombstones, or null when the store has never seen it.
     *
     * Distinct from [get], which deliberately hides deleted documents. The
     * restore route needs the tombstone to decide scope and conflict before it
     * can restore; nothing else should use this.
     */
    suspend fun getIncludingDeleted(id: DocId): Document? = withContext(io) { store.readIncludingDeleted(id) }

    // --- Attachments -------------------------------------------------------
    //
    // Blobs are free-standing and content-addressed: identity is the SHA-256 of
    // the bytes, not a path or a document. Nothing here consults a collection,
    // because the port carries no collection on a blob. That is a deliberate
    // consequence of content addressing, and it is why a blob route cannot
    // offer per-collection scoping. The blob routes publish their contract in
    // `docs/openapi.json`.

    /**
     * Stores bytes and returns their content-addressed reference.
     *
     * Uploading the same bytes twice is idempotent by construction: the
     * reference is derived from the content, so the second call addresses the
     * blob that already exists.
     */
    suspend fun putBlob(bytes: ByteArray): BlobRef =
        withContext(io) {
            // The size ceiling is enforced by the HTTP layer, which already has a
            // 413 for it; asserting it again here would need a second exception
            // type for the same condition.
            if (bytes.isEmpty()) throw InvalidInputException("attachment must not be empty")
            store.putBlob(bytes)
        }

    /**
     * Returns the bytes for [ref], having verified them against the digest the
     * reference carries. A store that cannot verify does not implement this, so
     * a caller never receives bytes whose integrity is unconfirmed.
     */
    suspend fun getBlob(ref: BlobRef): ByteArray =
        withContext(io) {
            if (ref.hash.length != SHA256_HEX_LENGTH || !ref.hash.all(::isLowerHex)) {
                throw InvalidInputException("blob hash must be a lowercase SHA-256 digest")
            }
            store.getBlob(ref)
        }

    /** Tombstones only; the feed a trash view renders. */
    suspend fun listDeleted(collection: Collection? = null): List<DocRef> =
        // No overlay filtering here, deliberately. Every other read surface hides
        // what the overlay has not yet observed from the store, but a tombstone
        // IS the deleted state: a delete the overlay knows about before the store
        // confirms it is still a tombstone and belongs in this list. Filtering by
        // the overlay would drop exactly the tombstones a caller most wants to see.
        withContext(io) { store.listDeleted(collection) }

    /**
     * Reverses a delete.
     *
     * The document must re-enter every read surface, not just the store: a
     * delete drops it from the Lookup via `Indexer.remove`, so a restore that
     * only re-inserted the row would leave the note visible in a listing and
     * absent from search. Re-indexing re-derives its edges too, which matters
     * because a delete clears incoming edges.
     */
    suspend fun restore(id: DocId, message: String, author: String, ifRevision: RevisionId? = null): Revision =
        withContext(io) {
            val (revision, restored, operations) =
                mutationLock.withLock {
                    val rev = store.restore(id, message, author, ifRevision)
                    val rest = store.read(id)
                    if (rest != null) {
                        overlay.put(rest)
                    }
                    Triple(rev, rest, store.projectionOperations())
                }

            if (restored != null) {
                if (operations == null) {
                    indexer.index(restored)
                    overlay.markProjected(restored.id, restored.contentHash)
                } else {
                    // The store already enqueued an upsert; let the worker apply it.
                    projectionWorker.drain()
                    overlay.markProjected(restored.id, restored.contentHash)
                }
            }
            logger.info(
                "restore committed",
                mapOf("doc" to id.value, "revision" to revision.revision.value)
            )
            revision
        }

    suspend fun rename(
        from: DocId,
        to: DocId,
        message: String,
        author: String,
        ifRevision: RevisionId? = null
    ): Revision =
        withContext(io) {
            val (revision, moved, operations) =
                mutationLock.withLock {
                    val rev = store.rename(from, to, message, author, ifRevision)
                    overlay.remove(from)
                    val mov = store.read(to)
                    if (mov != null) {
                        overlay.put(mov)
                    }
                    Triple(rev, mov, store.projectionOperations())
                }

            indexer.forget(from)
            if (moved != null) {
                if (operations == null) {
                    indexer.index(moved)
                    overlay.markProjected(moved.id, moved.contentHash)
                } else {
                    // The store enqueued the upserts, including for every document
                    // whose links were rewritten; let the worker apply them.
                    projectionWorker.drain()
                    overlay.markProjected(moved.id, moved.contentHash)
                }
            }
            logger.info(
                "rename committed",
                mapOf(
                    "from" to from.value,
                    "to" to to.value,
                    "revision" to revision.revision.value
                )
            )
            revision
        }

    suspend fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId? = null): Revision =
        withContext(io) {
            val (revision, operations) =
                mutationLock.withLock {
                    val rev = store.delete(id, message, author, ifRevision)
                    overlay.remove(id)
                    rev to store.projectionOperations()
                }

            if (operations == null) {
                indexer.remove(id)
                overlay.markDeleted(id)
            } else {
                projectionWorker.drain()
            }
            logger.info("delete committed", mapOf("doc" to id.value, "revision" to revision.revision.value))
            revision
        }

    fun projectionBacklog() = projectionWorker.backlog()

    suspend fun reconcileProjections(limit: Int = 100): ProjectionWorkerReport =
        withContext(io) { projectionWorker.drain(limit) }

    private fun pageBounds(limit: Int, offset: Int) {
        if (limit !in 1..RequestLimits.MAX_PAGE_LIMIT) {
            throw InvalidInputException("limit must be between 1 and ${RequestLimits.MAX_PAGE_LIMIT}")
        }
        if (offset !in 0..RequestLimits.MAX_PAGE_OFFSET) {
            throw InvalidInputException("offset must be between 0 and ${RequestLimits.MAX_PAGE_OFFSET}")
        }
    }

    private fun searchWindow(offset: Int, limit: Int): Int {
        pageBounds(limit, offset)
        return offset + limit + 1
    }
}
