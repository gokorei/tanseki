package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.ProjectionKind
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.DEFAULT_PROJECTION_LEASE
import gokorei.tanseki.core.ports.DEFAULT_PROJECTION_MAX_ATTEMPTS
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.ProjectionBacklog
import gokorei.tanseki.core.ports.ProjectionClaim
import gokorei.tanseki.core.ports.ProjectionOperationStore
import gokorei.tanseki.core.ports.TansekiLogger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class ProjectionWorkerReport(
    val attempted: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    /** Operations another worker holds a live lease on, so this drain skipped them. */
    val skipped: Int = 0,
    /** Deliveries whose index write landed but whose claim lapsed before completion. */
    val lostOwnership: Int = 0,
    val indexed: Map<DocId, String> = emptyMap(),
    val removed: Set<DocId> = emptySet(),
    /** Operations abandoned after exhausting [ProjectionWorker.maxAttempts]. */
    val deadLettered: List<ProjectionOperation> = emptyList()
)

class ProjectionWorker(
    private val store: ContextStore,
    private val indexer: Indexer,
    private val logger: TansekiLogger = TansekiLogger.Noop,
    private val overlay: PendingOverlay? = null,
    /**
     * Delivery budget per operation. A failing operation is retried on every
     * drain but never blocks the operations behind it, and is dead-lettered
     * (taken out of the delivery path and reported) once this many attempts have
     * been durably recorded.
     */
    private val maxAttempts: Int = DEFAULT_PROJECTION_MAX_ATTEMPTS,
    /**
     * First retry delay after a failed delivery; each further failure doubles it
     * up to [maxRetryBackoff]. Zero — the default — means retry on the next drain.
     *
     * Zero is the default because every existing caller already bounds its own
     * retries: `Indexer.drainProjectionOutbox` stops on the first failure, and the
     * periodic recovery loop runs once per `reconcileInterval`. So a default delay
     * would change recovery timing for every profile without preventing a loop
     * that exists. It is a knob for deployments that tune `reconcileInterval` low
     * or take a write-heavy load, where a failing dependency would otherwise be
     * retried on every write.
     */
    private val retryBackoff: Duration = Duration.ZERO,
    private val maxRetryBackoff: Duration = MAX_RETRY_BACKOFF,
    private val now: () -> Instant = { Clock.System.now() },
    /**
     * How long a claim is honoured, and the window in which another worker may take
     * the operation over. Long enough to cover one delivery comfortably; a lease
     * that lapsed mid-delivery is why [completeClaim] re-checks ownership.
     */
    private val lease: Duration = DEFAULT_PROJECTION_LEASE,
    /**
     * Identifies this worker in the outbox. Only used for diagnosis — the fencing
     * token minted per claim is what actually decides ownership.
     */
    private val workerId: String = defaultWorkerId()
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(!retryBackoff.isNegative()) { "retryBackoff must not be negative" }
        require(!maxRetryBackoff.isNegative()) { "maxRetryBackoff must not be negative" }
        require(lease > Duration.ZERO) { "lease must be positive" }
    }

    /**
     * When each operation may next be attempted, in memory.
     *
     * The durable record already counts attempts and dead-letters an exhausted
     * budget; what it does not carry is *when* to try again. Without that, a
     * failing operation is retried on every single drain — and once recovery runs
     * on a timer rather than on writes, "every drain" becomes an unbounded hot
     * retry against a dependency that is already known to be down.
     *
     * In memory, per process, on purpose: the cap is what matters for the hot-loop
     * failure, and a restart resetting the delay is correct rather than lossy —
     * the durable attempt count survives, so a poison operation still exhausts
     * its budget across restarts instead of getting a free retry every time.
     */
    private val retryAfter = LinkedHashMap<String, Instant>()
    private val drainLock = Any()

    fun drain(limit: Int = 100): ProjectionWorkerReport =
        synchronized(drainLock) {
            val instant = now()
            val outbox = store.projectionOperations() ?: return ProjectionWorkerReport()
            val fetchLimit = (limit.coerceAtLeast(0) + retryAfter.size).coerceAtLeast(100)
            val operations =
                outbox
                    .pendingLive(fetchLimit)
                    .filterNot { retryAfter[it.id]?.let { ready -> ready > instant } == true }
                    .take(limit.coerceAtLeast(0))
            var attempted = 0
            var completed = 0
            var failed = 0
            var skipped = 0
            var lostOwnership = 0
            val indexed = linkedMapOf<DocId, String>()
            val removed = linkedSetOf<DocId>()
            val deadLettered = mutableListOf<ProjectionOperation>()

            operations.forEach { operation ->
                // Ownership is taken before any work. Skipping an operation another
                // worker holds is the point: write-time draining, reconciliation and the
                // watcher each own a ProjectionWorker, and without a claim two of them
                // deliver the same operation and finish out of order.
                val claim = outbox.claim(operation.id, workerId, lease, instant)
                if (claim == null) {
                    skipped++
                    return@forEach
                }
                attempted++
                val attempt = processSafely(operation, claim)
                val error = attempt.error
                if (error == null) {
                    if (recordSuccess(operation, attempt.outcome, claim, indexed, removed)) {
                        retryAfter.remove(operation.id)
                        completed++
                    } else {
                        // The lease lapsed or was taken over mid-delivery. The index
                        // write already happened, so this is not a lost delivery — but
                        // the record is not ours to delete, and reporting success would
                        // be a claim we cannot back up.
                        lostOwnership++
                    }
                } else {
                    failed++
                    logFailure(operation, error)
                    // Hand the operation back immediately rather than making the next
                    // worker wait out the lease for a failure we already know about.
                    outbox.releaseClaim(claim)
                    if (recordFailure(operation, error)) {
                        deadLettered += operation
                        retryAfter.remove(operation.id)
                    } else {
                        retryAfter[operation.id] = instant + backoffFor(operation)
                        trimRetryAfter()
                    }
                }
            }

            return ProjectionWorkerReport(
                attempted = attempted,
                completed = completed,
                failed = failed,
                skipped = skipped,
                lostOwnership = lostOwnership,
                indexed = indexed,
                removed = removed,
                deadLettered = deadLettered
            )
        }

    /**
     * Exponential backoff from the operation's durable attempt count, capped.
     *
     * Keyed off the durable count rather than an in-memory tally so the delay
     * grows across drains and across restarts, and so it cannot be reset by
     * restarting the process.
     */
    private fun backoffFor(operation: ProjectionOperation): Duration {
        if (retryBackoff == Duration.ZERO) return Duration.ZERO
        val shift = (operation.attempts - 1).coerceIn(0, BACKOFF_SHIFT_CAP)
        val scaled = retryBackoff * (1 shl shift)
        return if (scaled > maxRetryBackoff) maxRetryBackoff else scaled
    }

    /** Keeps the delay table from growing without bound on a long-lived outbox. */
    private fun trimRetryAfter() {
        while (retryAfter.size > MAX_TRACKED_RETRIES) {
            val oldest = retryAfter.keys.first()
            retryAfter.remove(oldest)
        }
    }

    /** Operations currently waiting out a backoff delay, for metrics and tests. */
    fun backingOff(): Set<String> = retryAfter.keys.toSet()

    fun completeCurrent(documentId: DocId, contentHash: String): Int {
        val operations = store.projectionOperations() ?: return 0
        val matching =
            operations.pending(Int.MAX_VALUE).filter {
                it.documentId == documentId &&
                    it.kind == ProjectionKind.UPSERT &&
                    it.contentHash == contentHash
            }
        matching.forEach(operations::complete)
        return matching.size
    }

    fun backlog(): ProjectionBacklog = store.projectionOperations()?.backlog() ?: ProjectionBacklog()

    /** Raised when a lease lapses mid-delivery, so the attempt counts as failed. */
    private class LeaseLostException(operationId: String) :
        Exception("projection lease lost for $operationId")

    private companion object {
        val MAX_RETRY_BACKOFF: Duration = 60.seconds

        fun defaultWorkerId(): String =
            "pid-" + ProcessHandle.current().pid()

        /** Caps the shift so the multiplication cannot overflow a long. */
        const val BACKOFF_SHIFT_CAP = 20

        /** An outbox larger than this is not having its delays tracked. */
        const val MAX_TRACKED_RETRIES = 10_000
    }

    /**
     * Re-checks the claim immediately before the delivery that mutates the index.
     *
     * The lease can lapse while a slow dependency is being called. Re-checking here
     * narrows the window in which a lapsed worker could write to the index to the
     * duration of the write itself, rather than the whole delivery. It cannot
     * eliminate it — no lease scheme can — so this is a narrowing, and the comment
     * says so rather than implying the window is closed.
     */
    private fun processSafely(
        operation: ProjectionOperation,
        claim: ProjectionClaim
    ): ProcessAttempt =
        try {
            if (!stillOwns(claim)) {
                ProcessAttempt(error = LeaseLostException(claim.operationId))
            } else {
                ProcessAttempt(outcome = process(operation))
            }
        } catch (error: Exception) {
            ProcessAttempt(error = error)
        }

    private fun stillOwns(claim: ProjectionClaim): Boolean =
        claim.isLive(now()) &&
            store
                .projectionOperations()
                ?.leasedOperations(now())
                ?.any { it.token == claim.token } == true

    private fun processSafelyUnclaimed(operation: ProjectionOperation): ProcessAttempt =
        try {
            ProcessAttempt(outcome = process(operation))
        } catch (error: Exception) {
            ProcessAttempt(ProcessOutcome.STALE, error)
        }

    /**
     * Durably counts the failed attempt. Returns true when the operation has
     * exhausted its budget and was dead-lettered, so the caller can report it
     * instead of leaving it to block the outbox forever. A store that cannot
     * track attempts is still bounded: the worker falls back to the attempt
     * count it read from the outbox and logs the degradation.
     */
    private fun recordFailure(operation: ProjectionOperation, error: Exception): Boolean {
        val operations = store.projectionOperations() ?: return false
        val attempts =
            runCatching { operations.recordFailure(operation, error) }
                .onFailure { logger.error("projection attempt tracking failed", it) }
                .getOrNull()
                ?.takeIf { it > 0 }
                ?: operation.attempts + 1
        if (attempts < maxAttempts) return false
        runCatching { operations.deadLetter(operation, error) }
            .onFailure { logger.error("projection dead-lettering failed", it) }
        logger.warn(
            "projection operation dead-lettered",
            mapOf(
                "doc" to operation.documentId.value,
                "operation" to operation.id,
                "attempts" to attempts
            )
        )
        return true
    }

    /**
     * Completes the operation under [claim], reporting whether the store accepted it.
     *
     * Returns false when the claim was lost, which is not a success: the record is
     * still there and its new owner will finish it. The overlay is only told the
     * document is projected once the outbox agrees it is.
     */
    private fun recordSuccess(
        operation: ProjectionOperation,
        outcome: ProcessOutcome,
        claim: ProjectionClaim,
        indexed: MutableMap<DocId, String>,
        removed: MutableSet<DocId>
    ): Boolean {
        val outbox = store.projectionOperations()
        val completedDelivery = outbox?.completeClaim(claim) ?: false
        if (!completedDelivery) {
            logger.warn(
                "projection lease lost before completion",
                mapOf(
                    "doc" to operation.documentId.value,
                    "operation" to operation.id,
                    "owner" to claim.owner
                )
            )
            return false
        }
        if (outcome == ProcessOutcome.INDEXED && operation.kind == ProjectionKind.DELETE) {
            removed += operation.documentId
        } else if (outcome == ProcessOutcome.INDEXED) {
            indexed[operation.documentId] = operation.contentHash
        }
        completeOverlay(operation)
        return true
    }

    private fun logFailure(operation: ProjectionOperation, error: Exception) {
        logger.warn(
            "projection operation failed",
            mapOf(
                "doc" to operation.documentId.value,
                "operation" to operation.id,
                "kind" to operation.kind.name,
                "attempt" to (operation.attempts + 1)
            ),
            error
        )
    }

    private fun process(operation: ProjectionOperation): ProcessOutcome {
        val current = store.read(operation.documentId)
        return when (operation.kind) {
            ProjectionKind.UPSERT -> {
                if (current == null || current.contentHash != operation.contentHash) {
                    ProcessOutcome.STALE
                } else {
                    indexer.index(current)
                    ProcessOutcome.INDEXED
                }
            }

            ProjectionKind.DELETE -> {
                if (current != null) {
                    ProcessOutcome.STALE
                } else {
                    indexer.remove(operation.documentId)
                    ProcessOutcome.INDEXED
                }
            }
        }
    }

    /** Tells the overlay a document is durable in the index. */
    private fun completeOverlay(operation: ProjectionOperation) {
        when (operation.kind) {
            ProjectionKind.UPSERT -> {
                overlay?.markProjected(operation.documentId, operation.contentHash)
            }

            ProjectionKind.DELETE -> {
                overlay?.markDeleted(operation.documentId)
            }
        }
    }

    private data class ProcessAttempt(
        val outcome: ProcessOutcome = ProcessOutcome.NOOP,
        val error: Exception? = null
    )

    private enum class ProcessOutcome {
        INDEXED,
        STALE,

        /**
         * The index already matched the operation, so nothing was written. Distinct
         * from a failure: the operation is complete, just with nothing to do.
         */
        NOOP
    }
}
