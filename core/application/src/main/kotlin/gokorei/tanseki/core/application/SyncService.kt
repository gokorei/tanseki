package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.SyncBatch
import gokorei.tanseki.core.ports.SyncTransport
import gokorei.tanseki.core.ports.TransferState

/** Which replica's copy wins a sync conflict. */
enum class SyncSide {
    LOCAL,
    REMOTE
}

/**
 * One document that diverged between two replicas.
 *
 * [winner] is the side whose copy both replicas hold after convergence. [LOCAL]
 * means the receiving replica kept its own copy (the peer converges when the
 * echoed copy arrives); [REMOTE] means the incoming copy was applied.
 * Conflicts are recorded, never silently merged: overlapping text edits do not
 * auto-merge at this layer.
 */
data class SyncConflict(
    val documentId: DocId,
    val local: Document?,
    val remote: Document?,
    val winner: SyncSide
)

/** Decides the winner for a diverged document. Pluggable for tests and for a future merge policy. */
fun interface SyncConflictResolver {
    fun resolve(local: Document?, remote: Document?): SyncSide
}

/**
 * Deterministic conflict rules matching ADR 0001 (object-store push-pull, CRDT deferred).
 *
 * - A tombstone beats a live copy: edit-vs-delete converges on deleted and the
 *   editor restores explicitly. Deletes replicate; they never resurrect.
 * - Otherwise last writer wins by [Document.updatedAt], which mirrors the
 *   `isStaleTransfer` skip-stale guard. Ties break on `contentHash` with a
 *   strict order so both replicas pick the same winner without talking.
 */
object SyncResolvers {
    val LastWriterWinsTombstoneFirst =
        SyncConflictResolver { local, remote ->
            when {
                local == null -> {
                    SyncSide.REMOTE
                }

                remote == null -> {
                    SyncSide.LOCAL
                }

                local.deleted != remote.deleted -> {
                    if (local.deleted) SyncSide.LOCAL else SyncSide.REMOTE
                }

                local.updatedAt != remote.updatedAt -> {
                    if (local.updatedAt > remote.updatedAt) SyncSide.LOCAL else SyncSide.REMOTE
                }

                else -> {
                    if (local.contentHash > remote.contentHash) SyncSide.LOCAL else SyncSide.REMOTE
                }
            }
        }
}

/** What one sync pass did. */
data class SyncReport(
    val appliedToLocal: Int = 0,
    val appliedToRemote: Int = 0,
    val skippedStale: Int = 0,
    val conflicts: List<SyncConflict> = emptyList(),
    /** Local-winning conflicts staged back onto the transport for the peer. */
    val echoedBack: Int = 0
)

/**
 * Bidirectional replica convergence over the existing [ContextStore] port.
 *
 * No new store operations, no new document format (ADR 0001). Two shapes:
 * - [sync] converges two stores in-process (the degenerate zero-hop transport);
 * - [pushFrom]/[pullInto] move [TransferState] batches across a [SyncTransport]
 *   (vault: Pijul patches translated at the boundary; server/DB: batches over
 *   HTTP). [pullInto] echoes local-winning conflicts back onto the transport, so
 *   repeated push/pull rounds converge exactly like [sync] does.
 *
 * Applying reuses `importTransferState` (history and tombstones travel the way
 * mode transfer already does), falling back to a direct `write`/`delete` only
 * when the import cannot converge the policy's winner: its skip-stale guard
 * refusing an older-but-winning copy (tombstone-wins), or a revision-id
 * collision between independently minted replica histories.
 *
 * Properties the harness pins down:
 * - disjoint writes converge both ways;
 * - concurrent edits converge on one winner and are reported in [SyncReport.conflicts];
 * - passes are idempotent: a second pass right after the first applies nothing;
 * - there is no sequence tracking, so a partitioned (offline) replica simply
 *   syncs when reachable again.
 *
 * Out of scope for this seam: blob bytes (the port has no blob listing, so bytes
 * travel with the transport implementation), and derived state (edges,
 * projection outbox, indexes) which each node rebuilds locally.
 */
class SyncService(
    private val resolver: SyncConflictResolver = SyncResolvers.LastWriterWinsTombstoneFirst
) {
    fun sync(local: ContextStore, remote: ContextStore): SyncReport {
        val ids =
            (allIds(local) + allIds(remote))
                .distinctBy { it.value }
        var appliedToLocal = 0
        var appliedToRemote = 0
        var skippedStale = 0
        val conflicts = mutableListOf<SyncConflict>()

        ids.forEach { id ->
            val localDoc = local.readIncludingDeleted(id)
            val remoteDoc = remote.readIncludingDeleted(id)
            if (localDoc == null && remoteDoc == null) return@forEach
            if (equalState(localDoc, remoteDoc)) {
                mergeHistories(local, id, remote.history(id))
                mergeHistories(remote, id, local.history(id))
                return@forEach
            }
            val winner = resolver.resolve(localDoc, remoteDoc)
            if (localDoc != null && remoteDoc != null) {
                conflicts += SyncConflict(documentId = id, local = localDoc, remote = remoteDoc, winner = winner)
            }
            if (winner == SyncSide.LOCAL) {
                checkNotNull(localDoc)
                if (applyState(remote, localDoc, local.history(id))) appliedToRemote++ else skippedStale++
                mergeHistories(local, id, remote.history(id))
            } else {
                checkNotNull(remoteDoc)
                if (applyState(local, remoteDoc, remote.history(id))) appliedToLocal++ else skippedStale++
                mergeHistories(remote, id, local.history(id))
            }
        }
        return SyncReport(
            appliedToLocal = appliedToLocal,
            appliedToRemote = appliedToRemote,
            skippedStale = skippedStale,
            conflicts = conflicts
        )
    }

    /** Stages every local state, tombstones included, for the peer. */
    fun pushFrom(store: ContextStore, transport: SyncTransport) {
        transport.push(SyncBatch(exportAll(store)))
    }

    /**
     * Applies what the peer staged. Incoming-winning documents are applied;
     * local-winning conflicts are recorded and echoed back onto [transport] so
     * the peer converges on a later pull.
     */
    fun pullInto(store: ContextStore, transport: SyncTransport): SyncReport {
        val incoming = transport.pull().states
        var applied = 0
        var skipped = 0
        val conflicts = mutableListOf<SyncConflict>()
        val echo = mutableListOf<TransferState>()
        incoming.forEach { state ->
            val id = state.document.id
            val localDoc = store.readIncludingDeleted(id)
            if (localDoc == null) {
                if (applyState(store, state.document, state.history)) applied++ else skipped++
                return@forEach
            }
            if (equalState(localDoc, state.document)) {
                mergeHistories(store, id, state.history)
                return@forEach
            }
            val winner = resolver.resolve(localDoc, state.document)
            conflicts += SyncConflict(documentId = id, local = localDoc, remote = state.document, winner = winner)
            if (winner == SyncSide.REMOTE) {
                if (applyState(store, state.document, state.history)) applied++ else skipped++
            } else {
                skipped++
                echo += TransferState(document = localDoc, history = store.history(id))
            }
        }
        if (echo.isNotEmpty()) transport.push(SyncBatch(echo))
        return SyncReport(
            appliedToLocal = applied,
            skippedStale = skipped,
            conflicts = conflicts,
            echoedBack = echo.size
        )
    }

    private fun allIds(store: ContextStore): List<DocId> {
        val live = store.listAll().map { it.id }
        val deleted = runCatching { store.listDeleted() }.getOrDefault(emptyList()).map { it.id }
        return live + deleted
    }

    /**
     * Every state the peer needs, including tombstones. `exportTransferStates`
     * alone is not enough: adapters whose `listAll` hides tombstones would never
     * propagate a delete.
     */
    private fun exportAll(store: ContextStore): List<TransferState> =
        allIds(store).distinctBy { it.value }.mapNotNull { id ->
            val doc = store.readIncludingDeleted(id) ?: return@mapNotNull null
            TransferState(document = doc, history = store.history(id))
        }

    /**
     * Converged when content, tombstone state, path and collection agree.
     * Revisions are deliberately excluded: independent replicas mint their own
     * revision ids, so equal content never shares a revision.
     */
    private fun equalState(first: Document?, second: Document?): Boolean {
        if (first == null || second == null) return false
        return first.contentHash == second.contentHash &&
            first.deleted == second.deleted &&
            first.path == second.path &&
            first.collection == second.collection
    }

    /**
     * Applies [winner] to [target], returning true when the target changed.
     *
     * The standard path is `importTransferState`, which carries history and
     * tombstones the way mode transfer already does. It is bypassed only when it
     * cannot converge: its skip-stale guard refusing the policy's winner
     * (returns false), or a revision-id collision between independently minted
     * replica histories ([ConflictException]). In both cases a direct
     * `write`/`delete` enforces the document winner and history merges
     * best-effort via idempotent appends. Anything else propagates.
     */
    private fun applyState(target: ContextStore, winner: Document, history: List<Revision>): Boolean {
        val existing = target.readIncludingDeleted(winner.id)
        if (existing != null && equalState(existing, winner)) {
            mergeHistories(target, winner.id, history)
            return false
        }
        val imported =
            runCatching { target.importTransferState(TransferState(document = winner, history = history)) }
        if (imported.getOrNull() == true) return true
        val failure = imported.exceptionOrNull()
        if (failure != null && failure !is ConflictException) throw failure
        return forceApply(target, winner, history)
    }

    private fun forceApply(target: ContextStore, winner: Document, history: List<Revision>): Boolean {
        val existing = target.readIncludingDeleted(winner.id)
        if (existing != null && equalState(existing, winner)) return false
        if (winner.deleted) {
            if (existing == null || existing.deleted) {
                mergeHistories(target, winner.id, history)
                return existing == null
            }
            target.delete(winner.id, "sync: tombstone wins", "tanseki-sync")
        } else {
            target.write(winner, "sync: converge", "tanseki-sync")
        }
        mergeHistories(target, winner.id, history)
        return true
    }

    private fun mergeHistories(target: ContextStore, id: DocId, history: List<Revision>) {
        if (history.isEmpty()) return
        runCatching { target.appendHistory(id, history) }
    }
}
