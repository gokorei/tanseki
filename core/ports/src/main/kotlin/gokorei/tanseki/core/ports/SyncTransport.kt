package gokorei.tanseki.core.ports

/**
 * The boundary a sync batch crosses between two replicas (ADR 0001).
 *
 * This seam moves state; it never decides conflicts. Conflict handling stays in
 * `core/application` (`SyncService` + `SyncConflictResolver`), which applies the
 * chosen model: per-document CAS surfacing 409, skip-stale on older wall-clock
 * copies, tombstones winning over concurrent edits.
 *
 * Transports:
 * - server/DB replicas move [SyncBatch]es of [TransferState] over HTTP;
 * - vault replicas move Pijul patches over a channel (`PijulClient.record` +
 *   `apply`), translating to [TransferState] at this boundary so history still
 *   travels as patches for vaults while the application layer sees one shape.
 *
 * Derived state never crosses: edges, projection outbox/leases, indexes and
 * `.tanseki/` bookkeeping are rebuilt per node. Blob bytes are content-addressed
 * and travel with the transport implementation, not in the batch.
 */
interface SyncTransport {
    /** Stages [batch] for the peer. Buffering here is what makes offline work: */
    fun push(batch: SyncBatch)

    /** Drains what the peer staged. Returns empty when nothing is reachable. */
    fun pull(): SyncBatch
}

/** One shipment of replica state across a [SyncTransport]. */
data class SyncBatch(
    val states: List<TransferState> = emptyList()
)
