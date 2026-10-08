package gokorei.tanseki.adapters.context.sqlite

import app.cash.sqldelight.db.SqlDriver
import gokorei.tanseki.adapters.context.sqlite.db.TansekiDatabase
import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.StoreCapabilities
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.ProjectionBacklog
import gokorei.tanseki.core.ports.ProjectionClaim
import gokorei.tanseki.core.ports.ProjectionOperationStore
import gokorei.tanseki.core.ports.StorePage
import gokorei.tanseki.core.ports.TransferState
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Library-mode [ContextStore] over SQLite (SQLDelight). Documents are rows (the
 * canonical layer); revisions are append-only; edges are relations; blobs are
 * content-addressed. Content hashes give idempotency and `ifRevision` gives
 * compare-and-swap.
 */
class SqliteContextStore(
    private val driver: SqlDriver,
    private val clock: Clock,
    private val database: TansekiDatabase = TansekiDatabase(driver)
) : ContextStore, ProjectionOperationStore {
    private val documents = SqliteDocuments(database)
    private val history = SqliteHistory(database)
    private val edges = SqliteEdges(database)
    private val outbox = SqliteOutbox(database)
    private val leases = SqliteLeases(database, clock)
    private val mutations = SqliteMutations(database, clock, documents, history, outbox)
    private val transfer = SqliteTransfer(database, this, documents, history)
    private val blobs = SqliteBlobs(database)

    override fun read(id: DocId): Document? = documents.read(id)

    override fun readIncludingDeleted(id: DocId): Document? = documents.readIncludingDeleted(id)

    override fun readManyById(ids: List<DocId>): List<Document> = documents.readManyById(ids)

    @Synchronized
    override fun write(
        doc: Document,
        message: String,
        author: String,
        ifRevision: RevisionId?
    ): Revision = mutations.write(doc, message, author, ifRevision)

    @Synchronized
    override fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
        mutations.delete(id, message, author, ifRevision)

    override fun list(collection: Collection?): List<DocRef> = documents.list(collection)

    override fun listDeleted(collection: Collection?): List<DocRef> = documents.listDeleted(collection)

    @Synchronized
    override fun restore(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
        mutations.restore(id, message, author, ifRevision)

    override fun rename(
        from: DocId,
        to: DocId,
        message: String,
        author: String,
        ifRevision: RevisionId?
    ): Revision = mutations.rename(from, to, message, author, ifRevision)

    override fun listAll(): List<DocRef> = documents.listAll()

    override fun listPage(
        collection: Collection?,
        limit: Int,
        offset: Int,
        pathPrefix: String?
    ): StorePage<DocRef> = documents.listPage(collection, limit, offset, pathPrefix)

    override fun listPageAfter(
        collection: Collection?,
        pathPrefix: String?,
        after: DocId?,
        limit: Int
    ): StorePage<DocRef> = documents.listPageAfter(collection, pathPrefix, after, limit)

    override fun projectionOperations(): ProjectionOperationStore = this

    override fun enqueue(operation: ProjectionOperation) = outbox.enqueue(operation)

    override fun pending(limit: Int): List<ProjectionOperation> = outbox.pending(limit)

    override fun deadLetter(operation: ProjectionOperation, error: Throwable?) = outbox.deadLetter(operation)

    override fun deadLettered(limit: Int): List<ProjectionOperation> = outbox.deadLettered(limit)

    override fun pendingLive(limit: Int): List<ProjectionOperation> = outbox.pendingLive(limit)

    override fun complete(operation: ProjectionOperation) = outbox.complete(operation)

    @Synchronized
    override fun recordFailure(operation: ProjectionOperation, error: Throwable?): Int =
        outbox.recordFailure(operation)

    @Synchronized
    override fun claim(
        operationId: String,
        owner: String,
        lease: Duration,
        now: Instant
    ): ProjectionClaim? = leases.claim(operationId, owner, lease, now)

    @Synchronized
    override fun completeClaim(claim: ProjectionClaim): Boolean = leases.completeClaim(claim)

    @Synchronized
    override fun releaseClaim(claim: ProjectionClaim) = leases.releaseClaim(claim)

    override fun leasedOperations(now: Instant): List<ProjectionClaim> = leases.leasedOperations(now)

    override fun quarantineCorrupt(): Int = 0

    override fun backlog(): ProjectionBacklog = outbox.backlog()

    override fun upsertEdge(edge: Edge) = edges.upsertEdge(edge)

    override fun removeEdges(src: DocId, rel: RelType?) = edges.removeEdges(src, rel)

    override fun removeIncomingEdges(dst: DocId, rel: RelType?) = edges.removeIncomingEdges(dst, rel)

    @Synchronized
    override fun clearEdges() = edges.clearEdges()

    @Synchronized
    override fun replaceEdges(src: DocId, edgesList: List<Edge>) = edges.replaceEdges(src, edgesList)

    @Synchronized
    override fun neighbors(id: DocId, rel: RelType?): List<Edge> = edges.neighbors(id, rel)

    override fun incomingNeighbors(id: DocId, rel: RelType?): List<Edge> = edges.incomingNeighbors(id, rel)

    override fun history(id: DocId): List<Revision> = history.history(id)

    override fun historyPage(id: DocId, limit: Int, offset: Int): StorePage<Revision> =
        history.historyPage(id, limit, offset)

    override fun historyOwner(id: DocId): DocRef? = history.historyOwner(id)

    @Synchronized
    override fun importTransferState(state: TransferState): Boolean = transfer.importTransferState(state)

    override fun appendHistory(id: DocId, revisions: List<Revision>) = transfer.appendHistory(id, revisions)

    override fun putBlob(bytes: ByteArray): BlobRef = blobs.putBlob(bytes)

    override fun getBlob(ref: BlobRef): ByteArray = blobs.getBlob(ref)

    override fun capabilities() =
        StoreCapabilities(
            supportsHistory = true,
            supportsPatchGraph = false,
            supportsTransactions = true,
            consistency = Consistency.STRONG,
            supportsHistoryImport = true,
            supportsTombstoneEnumeration = true,
            supportsTombstoneImport = true
        )
}
