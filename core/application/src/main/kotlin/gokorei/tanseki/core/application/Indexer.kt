package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.UnsupportedStoreOperationException
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Embedder
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.core.ports.VectorWriter

fun interface IndexObserver {
    fun onIndexFinished(durationNanos: Long, failure: Throwable?)

    companion object {
        val Noop = IndexObserver { _, _ -> }
    }
}

/**
 * Turns a document into Lookup state: derives edges, writes the text/edges via
 * the Lookup port, and (when an [Embedder] is configured) writes vectors through
 * a [VectorWriter]. This is the only component that writes the Lookup.
 */
class Indexer(
    private val store: ContextStore,
    private val lookup: Lookup,
    private val embedder: Embedder? = null,
    private val vectorWriter: VectorWriter? = null,
    private val logger: TansekiLogger = TansekiLogger.Noop,
    private val observer: IndexObserver = IndexObserver.Noop,
    /**
     * Change feed, when a client is watching. Publishing here rather than at each
     * write site is deliberate: this is the only component that writes the
     * Lookup, so an event is published exactly when a change became searchable.
     * A write that failed to project never announces itself.
     */
    private val changeFeed: ChangeFeed? = null
) {
    private val referenceIndex = ReferenceIndex()
    private val edgeDeriver = EdgeDeriver(store, referenceIndex)

    /** Derive without touching the Lookup; used by bulk staging on an already-seeded index. */
    fun derive(doc: Document) = edgeDeriver.derive(doc)

    /** Bulk seed from documents already in hand: no extra store reads. */
    fun seedFrom(documents: List<Document>) = referenceIndex.seedFrom(documents)

    fun index(doc: Document) = indexInternal(doc, repair = true)

    /**
     * Index without repairing referrers. For a bulk load where every document
     * is already in the store, derivation against the seeded resolve map is
     * already correct, so per-document repair is pure overhead. The batch needs
     * no closing repair pass when nothing in it is the link target of anything
     * else in it — the backfill case — and even with intra-batch links the
     * seeded derivation already resolves them.
     */
    fun indexWithoutRepair(doc: Document) = indexInternal(doc, repair = false)

    private fun indexInternal(doc: Document, repair: Boolean) {
        val startedAt = System.nanoTime()
        try {
            referenceIndex.ensureResolveSeeded(store)
            referenceIndex.track(doc)
            val edges = edgeDeriver.reconcile(doc)
            lookup.index(doc, edges)
            if (repair) {
                edgeDeriver.repairTarget(doc.id).forEach { repaired ->
                    lookup.index(repaired.document, repaired.edges)
                }
            }

            var chunks = 0
            if (embedder != null && vectorWriter != null) {
                val vectors = embedder.embed(listOf(doc.content))
                require(vectors.size == 1) { "embedder returned ${vectors.size} vectors for one document" }
                require(vectors.single().size == embedder.dimensions) {
                    "embedder returned dim ${vectors.single().size}, expected ${embedder.dimensions}"
                }
                vectorWriter.writeVectors(doc, embedder.model, vectors)
                chunks = vectors.size
            }
            logger.debug("indexed", mapOf("doc" to doc.id.value, "edges" to edges.size, "chunks" to chunks))
            publishChange(doc)
            runCatching { observer.onIndexFinished(System.nanoTime() - startedAt, null) }
        } catch (error: Exception) {
            runCatching { observer.onIndexFinished(System.nanoTime() - startedAt, error) }
            throw error
        }
    }

    fun remove(id: DocId) {
        referenceIndex.ensureResolveSeeded(store)
        // Evict the target before re-deriving referrers so they unresolve
        // instead of re-resolving to the deleted document.
        referenceIndex.evictResolve(id)
        edgeDeriver.repairTarget(id).forEach { repair ->
            lookup.index(repair.document, repair.edges)
        }
        referenceIndex.evictSource(id)
        // Forget the resolve entry entirely once repair no longer needs the
        // suffixes (evictResolve already dropped them; untrack is belt-and-braces
        // for any path entry left behind).
        // Read ownership before the document is gone: after lookup.remove there is
        // nothing left to scope a delete event by, and an unscoped delete would
        // tell a subscriber about a collection it cannot read.
        val owner = runCatching { store.historyOwner(id) }.getOrNull()
        store.removeEdges(id)
        dropIncomingEdges(id)
        lookup.remove(id)
        changeFeed?.let { feed ->
            runCatching {
                feed.publish(
                    documentId = id,
                    collection = owner?.collection,
                    kind = ChangeKind.DELETE,
                    revision = owner?.revision,
                    contentHash = owner?.contentHash
                )
            }
        }
    }

    /** Forget `id` entirely (rename source): resolve and reverse entries dropped. */
    fun forget(id: DocId) = referenceIndex.untrack(id)

    /**
     * Announces a committed index. Never allowed to fail the write: the document
     * is already searchable, and a subscriber that missed the event is a degraded
     * feed, not a broken store.
     */
    private fun publishChange(doc: Document) {
        val feed = changeFeed ?: return
        runCatching {
            feed.publish(
                documentId = doc.id,
                collection = doc.collection,
                kind = ChangeKind.UPSERT,
                revision = doc.revision,
                contentHash = doc.contentHash
            )
        }.onFailure { error ->
            logger.debug("change feed publish failed", mapOf("doc" to doc.id.value, "error" to (error.message ?: "")))
        }
    }

    /**
     * Drops edges that pointed at [id]. Stores that do not implement incoming
     * edge surgery say so loudly through the port default; that must not fail a
     * delete the store already committed, so the gap is logged instead.
     */
    @Suppress("SwallowedException")
    private fun dropIncomingEdges(id: DocId) {
        try {
            store.removeIncomingEdges(id)
        } catch (error: UnsupportedStoreOperationException) {
            logger.warn(
                "store cannot remove incoming edges; the graph may keep references to the deleted document",
                mapOf("doc" to id.value)
            )
        }
    }
}

/** Outcome of a reconciliation run. */
data class ReconcileReport(
    val added: Int,
    val updated: Int,
    val removed: Int,
    val unchanged: Int,
    val readFailures: Int = 0,
    val projectionAttempted: Int = 0,
    val projectionCompleted: Int = 0,
    val projectionFailed: Int = 0,
    /** Operations abandoned this run after exhausting the delivery budget. */
    val projectionDeadLettered: Int = 0
)

/**
 * Rebuilds and repairs the Lookup from the Context Store: `Lookup = f(store)`.
 *
 * Change detection uses the **listing metadata** ([DocRef]) rather than reading
 * every document: a per-id change token (`content_hash`, or `revision` +
 * `updated_at` for stores whose listing omits the hash, e.g. the vault) is
 * compared against the previous run, and document content is only read for
 * entries that are new or whose token moved. The token includes the vault's file
 * mtime so an external (un-recorded) Markdown edit still triggers a re-read.
 * After the first (full) run only deltas are applied, and deletes propagate.
 */
class Reconciler(
    private val store: ContextStore,
    private val lookup: Lookup,
    private val indexer: Indexer,
    private val overlay: PendingOverlay? = null,
    private val logger: TansekiLogger = TansekiLogger.Noop
) {
    private val projectionWorker = ProjectionWorker(store, indexer, overlay = overlay)

    /** id -> content hash last written to the Lookup. */
    private val hashes = LinkedHashMap<DocId, String>()

    /** id -> change token observed at that write (enables metadata-only skips). */
    private val tokens = LinkedHashMap<DocId, String>()

    @Synchronized
    fun reconcile(): ReconcileReport {
        var added = 0
        var updated = 0
        var unchanged = 0
        var readFailures = 0
        val seen = mutableSetOf<DocId>()
        val abandoned = reportAbandonedOperations()
        val workerReport = drainProjectionOutbox()
        val indexed = workerReport.indexed
        val deadLettered = abandoned + workerReport.deadLettered.size
        if (workerReport.failed + abandoned > 0) {
            return workerReport
                .toReconcileReport(failed = workerReport.failed + abandoned, deadLettered = deadLettered)
        }

        for (ref in store.list()) {
            seen += ref.id
            val refReport = reconcileRef(ref, indexed[ref.id])
            added += refReport.added
            updated += refReport.updated
            unchanged += refReport.unchanged
            readFailures += refReport.readFailures
        }

        val knownLookupIds = lookup.indexedIds().orEmpty()
        val removedByReconcile = (hashes.keys + knownLookupIds).filterNot { it in seen } - workerReport.removed
        removedByReconcile.forEach { id ->
            indexer.remove(id)
            overlay?.markDeleted(id)
            hashes.remove(id)
            tokens.remove(id)
        }
        workerReport.removed.forEach { id ->
            hashes.remove(id)
            tokens.remove(id)
        }

        return ReconcileReport(
            added = added,
            updated = updated,
            removed = removedByReconcile.size + workerReport.removed.size,
            unchanged = unchanged,
            readFailures = readFailures,
            projectionAttempted = workerReport.attempted,
            projectionCompleted = workerReport.completed,
            projectionFailed = workerReport.failed,
            projectionDeadLettered = deadLettered
        )
    }

    @Synchronized
    fun projectionBacklog() = projectionWorker.backlog()

    @Synchronized
    fun completeCurrent(documentId: DocId, contentHash: String) =
        projectionWorker.completeCurrent(documentId, contentHash)

    /**
     * Surfaces operations whose delivery budget was spent: they are reported as
     * projection failures and then dropped from the outbox. Reconciliation is
     * the point where the projection is re-derived from canonical state anyway,
     * so a dead letter must not sit in the outbox forever once it has been seen.
     */
    private fun reportAbandonedOperations(): Int {
        val operations = store.projectionOperations() ?: return 0
        val abandoned =
            runCatching { operations.deadLettered() }
                .onFailure { logger.error("reading dead-lettered projection operations failed", it) }
                .getOrDefault(emptyList())
        abandoned.forEach { operation ->
            runCatching { operations.complete(operation) }
                .onFailure {
                    logger.warn(
                        "dropping a reported dead letter failed",
                        mapOf("operation" to operation.id),
                        it
                    )
                }
        }
        return abandoned.size
    }

    private fun drainProjectionOutbox(): ProjectionWorkerReport {
        var attempted = 0
        var completed = 0
        var failed = 0
        val indexed = linkedMapOf<DocId, String>()
        val removed = linkedSetOf<DocId>()

        while (true) {
            val report = projectionWorker.drain()
            attempted += report.attempted
            completed += report.completed
            failed += report.failed
            report.indexed.forEach { (id, hash) -> indexed[id] = hash }
            report.removed.forEach { removed += it }
            if (report.attempted == 0 || report.failed > 0 || report.completed == 0) {
                return ProjectionWorkerReport(
                    attempted = attempted,
                    completed = completed,
                    failed = failed,
                    indexed = indexed,
                    removed = removed
                )
            }
        }
    }

    private fun reconcileRef(ref: DocRef, indexedHash: String?): RefReport {
        if (indexedHash != null) {
            val report =
                when {
                    hashes[ref.id] == null -> RefReport(added = 1)
                    hashes[ref.id] != indexedHash -> RefReport(updated = 1)
                    else -> RefReport(unchanged = 1)
                }
            hashes[ref.id] = indexedHash
            changeToken(ref)?.let { tokens[ref.id] = it }
            return report
        }
        return try {
            when (reconcileOne(ref)) {
                ReconcileOutcome.ADDED -> RefReport(added = 1)
                ReconcileOutcome.UPDATED -> RefReport(updated = 1)
                ReconcileOutcome.UNCHANGED -> RefReport(unchanged = 1)
                ReconcileOutcome.UNREADABLE -> RefReport(readFailures = 1)
            }
        } catch (_: Exception) {
            RefReport(readFailures = 1)
        }
    }

    private fun reconcileOne(ref: DocRef): ReconcileOutcome {
        val knownHash = hashes[ref.id]
        val token = changeToken(ref)

        // Metadata says nothing moved since the last successful index.
        if (knownHash != null && token != null && token == tokens[ref.id]) {
            return ReconcileOutcome.UNCHANGED
        }

        val doc = store.read(ref.id) ?: return ReconcileOutcome.UNREADABLE
        val outcome =
            when {
                knownHash == null -> ReconcileOutcome.ADDED
                doc.contentHash == knownHash -> ReconcileOutcome.UNCHANGED
                else -> ReconcileOutcome.UPDATED
            }
        if (outcome != ReconcileOutcome.UNCHANGED) {
            indexer.index(doc)
            projectionWorker.completeCurrent(doc.id, doc.contentHash)
            overlay?.markProjected(doc.id, doc.contentHash)
        }
        hashes[ref.id] = doc.contentHash
        token?.let { tokens[ref.id] = it }
        return outcome
    }

    /** Preferred change token from listing metadata; null means "must read". */
    private fun changeToken(ref: DocRef): String? {
        ref.changeToken?.let { return it }
        ref.contentHash?.let { return it }
        val revision = ref.revision?.value
        val updatedAt = ref.updatedAt?.toEpochMilliseconds()
        if (revision == null && updatedAt == null) return null
        return "$revision@$updatedAt"
    }

    private fun ProjectionWorkerReport.toReconcileReport(
        failed: Int = this.failed,
        deadLettered: Int = this.deadLettered.size
    ) =
        ReconcileReport(
            added = 0,
            updated = 0,
            removed = removed.size,
            unchanged = 0,
            projectionAttempted = attempted,
            projectionCompleted = completed,
            projectionFailed = failed,
            projectionDeadLettered = deadLettered
        )

    private data class RefReport(
        val added: Int = 0,
        val updated: Int = 0,
        val unchanged: Int = 0,
        val readFailures: Int = 0
    )

    private enum class ReconcileOutcome { ADDED, UPDATED, UNCHANGED, UNREADABLE }
}
