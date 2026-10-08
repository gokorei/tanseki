@file:Suppress("ComplexCondition")

package gokorei.tanseki.testkit

import gokorei.tanseki.core.domain.BlobCorruptionException
import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.StoreCapabilities
import gokorei.tanseki.core.domain.documentPath
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.ProjectionBacklog
import gokorei.tanseki.core.ports.ProjectionClaim
import gokorei.tanseki.core.ports.ProjectionOperationStore
import gokorei.tanseki.core.ports.StorePage
import gokorei.tanseki.core.ports.TransferState
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * A complete in-memory [ContextStore] for tests that need a store without an
 * adapter (Lookup rebuild, Reconciler, Overlay, transfer tests).
 *
 * Every accessor and every mutator holds the instance monitor, readers
 * included: a `history` read that raced a concurrent `appendHistory` on the same
 * `MutableList` would otherwise return a torn or half-updated ledger to a test
 * that is asserting exactly the ordering it just produced.
 */
class InMemoryContextStore(
    private val clock: () -> Instant = { Instant.fromEpochSeconds(0) }
) : ContextStore, ProjectionOperationStore {
    private val documents = LinkedHashMap<DocId, Document>()
    private val revisions = LinkedHashMap<DocId, MutableList<Revision>>()
    private val edges = LinkedHashMap<DocId, MutableList<Edge>>()
    private val projectionOperations = LinkedHashMap<String, ProjectionOperation>()
    private val leases = LinkedHashMap<String, ProjectionClaim>()
    private val blobs = LinkedHashMap<String, ByteArray>()
    private var counter = 0
    private var lastHistoryAt: Instant? = null

    @get:Synchronized
    val upsertedEdges: List<Edge> get() = edges.values.flatten()

    @Synchronized
    override fun read(id: DocId): Document? = documents[id]?.takeUnless { it.deleted }

    @Synchronized
    override fun readIncludingDeleted(id: DocId): Document? = documents[id]

    @Synchronized
    override fun readMany(ids: List<DocId>): List<Document> {
        val unique = LinkedHashSet(ids)
        val found = documents.values.associateBy { it.id }
        return unique.mapNotNull { id -> found[id]?.takeUnless { document -> document.deleted } }
    }

    @Synchronized
    override fun write(doc: Document, message: String, author: String, ifRevision: RevisionId?): Revision {
        if (doc.deleted) throw InvalidInputException("deleted documents must be written with delete()")
        if (doc.path != doc.id.documentPath()) {
            throw InvalidInputException("document path must be derived from its id")
        }
        val existing = readIncludingDeleted(doc.id)
        if (existing != null && existing.collection != doc.collection) {
            throw ConflictException(doc.id, "collection is immutable for document '${doc.id.value}'")
        }
        val live = read(doc.id)
        if (ifRevision != null && live?.revision != ifRevision) {
            throw ConflictException(doc.id, "revision mismatch")
        }
        if (live != null && live.contentHash == doc.contentHash) {
            return revisions[doc.id]?.last() ?: error("no revision")
        }
        val revision =
            Revision(
                docId = doc.id,
                revision = RevisionId("rev-${++counter}"),
                author = author,
                message = message,
                contentHash = doc.contentHash,
                createdAt = nextHistoryTimestamp()
            )
        documents[doc.id] = doc.copy(revision = revision.revision)
        revisions.getOrPut(doc.id) { mutableListOf() } += revision
        enqueue(ProjectionOperation.upsert(doc, revision.revision, clock(), doc.contentHash))
        return revision
    }

    @Synchronized
    override fun listDeleted(collection: Collection?): List<DocRef> =
        documents.values
            .filter { it.deleted && (collection == null || it.collection == collection) }
            .map { DocRef(it.id, it.collection, it.path, it.contentHash, it.revision, it.updatedAt) }
            .sortedBy { it.id.value }

    @Synchronized
    override fun restore(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision {
        val tombstone = readIncludingDeleted(id) ?: throw NotFoundException(id)
        if (ifRevision != null && tombstone.revision != ifRevision) {
            throw ConflictException(id, "revision mismatch")
        }
        if (!tombstone.deleted) {
            return history(id).lastOrNull() ?: throw NotFoundException(id)
        }
        // A tombstone released its path; another document may hold it now, and
        // overwriting that document is the outcome a trash view must never
        // produce silently.
        val holder =
            documents.values
                .firstOrNull { !it.deleted && it.collection == tombstone.collection && it.path == tombstone.path }
        if (holder != null && holder.id != id) {
            throw ConflictException(
                id,
                "path ${tombstone.path} is held by ${holder.id.value}; resolve the collision before restoring"
            )
        }
        val createdAt = nextHistoryTimestamp()
        val revision =
            Revision(
                id,
                RevisionId("res-${++counter}"),
                author,
                message,
                tombstone.contentHash,
                createdAt
            )
        val restored =
            tombstone.copy(
                contentHash = tombstone.contentHash,
                revision = revision.revision,
                updatedAt = createdAt,
                deleted = false
            )
        documents[id] = restored
        revisions[id] = (revisions[id].orEmpty() + revision).toMutableList()
        // A restore has to re-enter the index, not just the store: delete enqueued
        // a removal, so without this upsert the document is listed but unsearchable.
        enqueue(ProjectionOperation.upsert(restored, revision.revision, clock(), restored.contentHash))
        return revision
    }

    /**
     * Moves a document, its history and both edge endpoints.
     *
     * The link text of referring documents is not rewritten here. This store backs
     * the route tests, which is the wrong place to grow a second implementation of
     * wikilink rewriting: the adapters own that, and a faithful fake would have to
     * carry the same resolver to agree with them. What the route tests need from here
     * is that the move itself happens and that the edges follow it.
     */
    @Synchronized
    override fun rename(
        from: DocId,
        to: DocId,
        message: String,
        author: String,
        ifRevision: RevisionId?
    ): Revision {
        val source = read(from) ?: throw NotFoundException(from)
        if (ifRevision != null && source.revision != ifRevision) {
            throw ConflictException(from, "revision mismatch")
        }
        if (documents.values.any { !it.deleted && it.id == to }) {
            throw ConflictException(
                from,
                "path ${to.documentPath()} is held by ${to.value}; resolve the collision before renaming"
            )
        }
        val createdAt = nextHistoryTimestamp()
        val revision = Revision(to, RevisionId("mv-${++counter}"), author, message, source.contentHash, createdAt)
        documents.remove(from)
        documents[to] =
            source.copy(id = to, path = to.documentPath(), revision = revision.revision, updatedAt = createdAt)
// History and edges address documents by id, so they move with the document.
        // The carried-over revisions are re-stamped too: leaving them pointing at the
        // old id would make history() answer with rows that disagree with the document
        // they were asked about.
        revisions[to] =
            (revisions.remove(from).orEmpty().map { it.copy(docId = to) } + revision).toMutableList()
        edges[to] = (edges.remove(from).orEmpty().toMutableList())
        edges.keys.toList().forEach { src ->
            val retargeted = edges.getValue(src).map { if (it.dst == from) it.copy(dst = to) else it }
            edges[src] = retargeted.distinctBy { edge -> edge.rel to edge.dst }.toMutableList()
        }
        enqueue(ProjectionOperation.upsert(documents.getValue(to), revision.revision, createdAt, source.contentHash))
        return revision
    }

    @Synchronized
    override fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision {
        val existing = read(id) ?: throw NotFoundException(id)
        if (ifRevision != null && existing.revision != ifRevision) {
            throw ConflictException(id, "revision mismatch")
        }
        val createdAt = nextHistoryTimestamp()
        val revision =
            Revision(
                id,
                RevisionId("del-${++counter}"),
                author,
                message,
                existing.contentHash,
                createdAt
            )
        documents[id] =
            existing.copy(
                revision = revision.revision,
                updatedAt = createdAt,
                deleted = true
            )
        revisions.getOrPut(id) { mutableListOf() } += revision
        enqueue(ProjectionOperation.delete(id, revision.revision, existing.contentHash, createdAt))
        return revision
    }

    @Synchronized
    override fun list(collection: Collection?): List<DocRef> =
        documents.values
            .filter { !it.deleted && (collection == null || it.collection == collection) }
            .map { DocRef(it.id, it.collection, it.path, it.contentHash, it.revision, it.updatedAt) }
            .sortedBy { it.id.value }

    @Synchronized
    override fun listPage(
        collection: Collection?,
        limit: Int,
        offset: Int,
        pathPrefix: String?
    ): StorePage<DocRef> {
        require(limit > 0) { "limit must be positive" }
        require(offset >= 0) { "offset must not be negative" }
        val all = sortedRefs(collection).filter { pathPrefix.isNullOrEmpty() || it.path.startsWith(pathPrefix) }
        val items = all.drop(offset).take(limit)
        return StorePage(items, all.size, offset.toLong() + items.size < all.size)
    }

    @Synchronized
    override fun listPageAfter(
        collection: Collection?,
        pathPrefix: String?,
        after: DocId?,
        limit: Int
    ): StorePage<DocRef> {
        require(limit > 0) { "limit must be positive" }
        val matched = sortedRefs(collection).filter { pathPrefix.isNullOrEmpty() || it.path.startsWith(pathPrefix) }
        val remaining = if (after == null) matched else matched.filter { it.id.value > after.value }
        return StorePage(remaining.take(limit), matched.size, remaining.size > limit)
    }

    private fun sortedRefs(collection: Collection?): List<DocRef> =
        documents.values
            .filter { !it.deleted && (collection == null || it.collection == collection) }
            .map { DocRef(it.id, it.collection, it.path, it.contentHash, it.revision, it.updatedAt) }
            .sortedBy { it.id.value }

    @Synchronized
    override fun importTransferState(state: gokorei.tanseki.core.ports.TransferState): Boolean {
        val document =
            gokorei.tanseki.core.ports
                .importDocument(this, state.document)
        if (document.path != document.id.documentPath()) {
            throw InvalidInputException("document path must be derived from its id")
        }
        val existing = documents[document.id]
        if (existing != null &&
            gokorei.tanseki.core.ports
                .isStaleTransfer(existing, document)
        ) {
            return false
        }
        // New history is a change even when the body is identical, matching the
        // SQL adapters: an import that carries history must not be dropped
        // because the document's current content happens to match.
        val hasNewHistory =
            state.history.any { incoming ->
                revisions[document.id].orEmpty().none { it.revision == incoming.revision }
            }
        if (existing != null && isUnchangedImport(existing, document) && !hasNewHistory) return false
        requireConsistentHistory(document, state.history)
        documents[document.id] = document
        if (document.deleted) removeIncomingEdges(document.id)
        val ledger = revisions.getOrPut(document.id) { mutableListOf() }
        state.history.forEach { revision ->
            // Re-stamped onto the target id, as the SQL adapters do: history() must
            // answer with rows keyed on the document it was asked about.
            if (ledger.none { it.revision == revision.revision }) ledger += revision.copy(docId = document.id)
        }
        enqueue(projectionFor(document))
        return true
    }

    private fun isUnchangedImport(existing: Document, incoming: Document): Boolean =
        existing.contentHash == incoming.contentHash &&
            existing.revision == incoming.revision &&
            existing.deleted == incoming.deleted

    private fun requireConsistentHistory(document: Document, history: List<Revision>) {
        val known = revisions[document.id].orEmpty()
        history.forEach { revision ->
            val recorded = known.firstOrNull { it.revision == revision.revision } ?: return@forEach
            val sameContent =
                recorded.content == null || revision.content == null || recorded.content == revision.content
            if (recorded.contentHash != revision.contentHash || !sameContent) {
                throw ConflictException(document.id, "revision history conflict")
            }
        }
    }

    private fun projectionFor(document: Document): ProjectionOperation =
        if (document.deleted) {
            ProjectionOperation.delete(document.id, document.revision, document.contentHash, document.updatedAt)
        } else {
            ProjectionOperation.upsert(document, document.revision, document.updatedAt)
        }

    @Synchronized
    override fun listAll(): List<DocRef> =
        documents.values
            .map { DocRef(it.id, it.collection, it.path, it.contentHash, it.revision, it.updatedAt) }
            .sortedBy { it.id.value }

    override fun projectionOperations(): ProjectionOperationStore = this

    @Synchronized
    override fun enqueue(operation: ProjectionOperation) {
        val existing = projectionOperations[operation.id]
        projectionOperations[operation.id] =
            if (existing == null) operation else operation.copy(attempts = maxOf(existing.attempts, operation.attempts))
        // A re-enqueue is a newer intent for the same operation, so any lease from
        // the previous attempt is void. Mirrors the SQL backends.
        leases.remove(operation.id)
    }

    @Synchronized
    override fun claim(
        operationId: String,
        owner: String,
        lease: Duration,
        now: Instant
    ): ProjectionClaim? {
        val operation = projectionOperations[operationId] ?: return null
        if (operation.deadLettered) return null
        val held = leases[operationId]
        if (held != null && held.isLive(now)) return null
        val fresh = ProjectionClaim(operationId, owner, nextToken(), now + lease)
        leases[operationId] = fresh
        return fresh
    }

    @Synchronized
    override fun completeClaim(claim: ProjectionClaim): Boolean {
        val held = leases[claim.operationId]
        if (held == null || held.token != claim.token || !held.isLive(clock())) return false
        leases.remove(claim.operationId)
        return projectionOperations.remove(claim.operationId) != null
    }

    @Synchronized
    override fun releaseClaim(claim: ProjectionClaim) {
        val held = leases[claim.operationId]
        if (held == null || held.token == claim.token) leases.remove(claim.operationId)
    }

    @Synchronized
    override fun leasedOperations(now: Instant): List<ProjectionClaim> =
        leases.values.filter { it.isLive(now) }

    /** Forces the stored lease on [operationId] to expire, for crash-recovery tests. */
    @Synchronized
    fun expireLease(operationId: String) {
        val held = leases[operationId] ?: return
        leases[operationId] = held.copy(expiresAt = Instant.fromEpochSeconds(0))
    }

    @Synchronized
    fun heldLease(operationId: String): ProjectionClaim? = leases[operationId]

    private var tokenCounter = 0L

    private fun nextToken(): String = "token-${++tokenCounter}"

    @Synchronized
    override fun pending(limit: Int): List<ProjectionOperation> =
        projectionOperations.values
            .sortedWith(compareBy<ProjectionOperation> { it.createdAt }.thenBy { it.id })
            .take(limit)

    @Synchronized
    override fun pendingLive(limit: Int): List<ProjectionOperation> =
        projectionOperations.values
            .filterNot { it.deadLettered }
            .sortedWith(compareBy<ProjectionOperation> { it.createdAt }.thenBy { it.id })
            .take(limit.coerceAtLeast(0))

    @Synchronized
    override fun complete(operation: ProjectionOperation) {
        projectionOperations.remove(operation.id)
        leases.remove(operation.id)
    }

    @Synchronized
    override fun recordFailure(operation: ProjectionOperation, error: Throwable?): Int {
        val recorded =
            (projectionOperations[operation.id] ?: operation).copy(
                attempts = (projectionOperations[operation.id]?.attempts ?: operation.attempts) + 1
            )
        projectionOperations[operation.id] = recorded
        return recorded.attempts
    }

    @Synchronized
    override fun deadLetter(operation: ProjectionOperation, error: Throwable?) {
        projectionOperations[operation.id] =
            (projectionOperations[operation.id] ?: operation).copy(deadLettered = true)
    }

    @Synchronized
    override fun deadLettered(limit: Int): List<ProjectionOperation> =
        pending(Int.MAX_VALUE).filter { it.deadLettered }.take(limit)

    override fun quarantineCorrupt(): Int = 0

    @Synchronized
    override fun backlog(): ProjectionBacklog {
        val records = pending(Int.MAX_VALUE)
        val live = records.filterNot { it.deadLettered }
        val stuck = live.filter { it.attempts > 0 }
        val dead = records.filter { it.deadLettered }
        return ProjectionBacklog(
            pending = live.size,
            oldestCreatedAt = live.firstOrNull()?.createdAt,
            stuck = stuck.size,
            oldestStuckAt = stuck.firstOrNull()?.createdAt,
            deadLettered = dead.size
        )
    }

    @Synchronized
    override fun upsertEdge(edge: Edge) {
        val outgoing = edges.getOrPut(edge.src) { mutableListOf() }
        val index = outgoing.indexOfFirst { it.rel == edge.rel && it.dst == edge.dst }
        if (index >= 0) outgoing[index] = edge else outgoing += edge
        sortEdges(outgoing)
    }

    @Synchronized
    override fun removeEdges(src: DocId, rel: RelType?) {
        edges[src]?.removeAll { rel == null || it.rel == rel }
        if (edges[src].isNullOrEmpty()) edges.remove(src)
    }

    @Synchronized
    override fun removeIncomingEdges(dst: DocId, rel: RelType?) {
        edges.entries.toList().forEach { (source, outgoing) ->
            outgoing.removeAll { it.dst == dst && (rel == null || it.rel == rel) }
            if (outgoing.isEmpty()) edges.remove(source)
        }
    }

    @Synchronized
    override fun clearEdges() {
        edges.clear()
    }

    @Synchronized
    override fun replaceEdges(src: DocId, edges: List<Edge>) {
        val replacement = edges.distinctBy { edge -> edge.rel to edge.dst }
        if (replacement.isEmpty()) {
            this.edges.remove(src)
        } else {
            this.edges[src] = replacement.toMutableList().also(::sortEdges)
        }
    }

    @Synchronized
    override fun neighbors(id: DocId, rel: RelType?): List<Edge> =
        edges[id].orEmpty().filter { rel == null || it.rel == rel }

    @Synchronized
    override fun incomingNeighbors(id: DocId, rel: RelType?): List<Edge> =
        edges.entries
            .flatMap { (src, list) -> list.map { edge -> src to edge } }
            .filter { (_, edge) -> edge.dst == id && (rel == null || edge.rel == rel) }
            .map { (src, edge) -> edge.copy(src = src) }
            .sortedWith(compareBy({ it.rel.value }, { it.src.value }))

    @Synchronized
    override fun history(id: DocId): List<Revision> =
        revisions[id].orEmpty().sortedWith(compareBy<Revision> { it.createdAt }.thenBy { it.revision.value })

    @Synchronized
    override fun historyPage(id: DocId, limit: Int, offset: Int): StorePage<Revision> {
        require(limit > 0) { "limit must be positive" }
        require(offset >= 0) { "offset must not be negative" }
        val all = history(id)
        val items = all.drop(offset).take(limit)
        return StorePage(items, all.size, offset.toLong() + items.size < all.size)
    }

    @Synchronized
    override fun historyOwner(id: DocId): DocRef? =
        documents[id]?.let {
            DocRef(it.id, it.collection, it.path, it.contentHash, it.revision, it.updatedAt)
        }

    @Synchronized
    override fun appendHistory(id: DocId, revisions: List<Revision>) {
        val ledger = this.revisions.getOrPut(id) { mutableListOf() }
        val known = ledger.mapTo(mutableSetOf()) { it.revision }
        revisions.filterNot { it.revision in known }.forEach { ledger += it }
    }

    @Synchronized
    override fun putBlob(bytes: ByteArray): BlobRef {
        val hash = sha256(bytes)
        blobs[hash] = bytes
        return BlobRef(hash = hash, size = bytes.size.toLong(), algorithm = "sha256")
    }

    /**
     * Verifies on read, like the storage adapters.
     *
     * Without this the test double would accept any bytes under a known hash,
     * which is exactly the bug an adapter can have, so the double cannot be used
     * to catch it.
     */
    @Synchronized
    override fun getBlob(ref: BlobRef): ByteArray {
        val bytes = blobs[ref.hash] ?: throw NotFoundException()
        val sizeMismatch = ref.size >= 0 && bytes.size.toLong() != ref.size
        if (sizeMismatch || sha256(bytes) != ref.hash) {
            throw BlobCorruptionException("blob ${ref.hash} failed size or digest verification")
        }
        return bytes
    }

    /**
     * Rewrites the stored bytes without changing the hash they are addressed by.
     *
     * Only for exercising integrity verification: real corruption is what this
     * simulates, and no other way to produce it exists through the port.
     */
    @Synchronized
    fun corruptBlob(hash: String) {
        blobs[hash] = "tampered".toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Strictly increasing history clock, mirroring the storage adapters: history
     * ordering must not depend on the revision id when the source clock is
     * coarse or frozen.
     */
    @Synchronized
    private fun nextHistoryTimestamp(): Instant {
        val now = clock()
        val previous = lastHistoryAt
        val next = if (previous == null || now > previous) now else previous + 1.milliseconds
        lastHistoryAt = next
        return next
    }

    private fun sortEdges(outgoing: MutableList<Edge>) {
        outgoing.sortWith(compareBy({ it.rel.value }, { it.dst.value }))
    }

    override fun capabilities() =
        StoreCapabilities(
            supportsHistory = true,
            supportsPatchGraph = false,
            supportsTransactions = false,
            consistency = Consistency.STRONG,
            supportsHistoryImport = true,
            supportsTombstoneEnumeration = true,
            supportsTombstoneImport = true,
            supportsSync = true
        )
}
