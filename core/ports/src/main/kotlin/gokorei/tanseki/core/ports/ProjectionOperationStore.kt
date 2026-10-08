package gokorei.tanseki.core.ports

import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.UnsupportedStoreOperationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class ProjectionBacklog(
    val pending: Int = 0,
    val oldestCreatedAt: Instant? = null,
    /** Pending operations that have already failed at least one delivery. */
    val stuck: Int = 0,
    /** Oldest creation time among stuck operations. */
    val oldestStuckAt: Instant? = null,
    /** Outbox entries that could not be decoded and need operator recovery. */
    val corrupt: Int = 0,
    /**
     * Outbox entries whose delivery budget was exhausted. They are no longer
     * delivered, so they are counted apart from [pending] and [stuck].
     */
    val deadLettered: Int = 0
) {
    fun oldestAge(now: Instant): Duration? {
        val createdAt = oldestCreatedAt ?: return null
        val age = now - createdAt
        return if (age.isNegative()) Duration.ZERO else age
    }

    fun oldestStuckAge(now: Instant): Duration? {
        val createdAt = oldestStuckAt ?: return null
        val age = now - createdAt
        return if (age.isNegative()) Duration.ZERO else age
    }
}

/**
 * Durable outbox between the canonical ContextStore and the derived Lookup.
 *
 * [enqueue] is idempotent per [ProjectionOperation.id]. A delivery that throws
 * must be reported with [recordFailure] (which durably increments
 * [ProjectionOperation.attempts]) so the operation stays visible as stuck
 * instead of silently spinning; once the delivery budget is exhausted the worker
 * dead-letters it with [deadLetter] and reports it.
 *
 * [pending] and [complete] may be called concurrently and must never observe a
 * half-written outbox entry: a delivery that vanishes between the two calls is
 * skipped, and an entry that cannot be decoded is counted in
 * [ProjectionBacklog.corrupt] and recoverable with [quarantineCorrupt] rather
 * than silently dropped.
 */
interface ProjectionOperationStore {
    /**
     * Every outbox record that has not been delivered successfully, oldest
     * first, dead-lettered ones included: those are in the outbox for an
     * operator to see and are filtered out of the delivery path by
     * [ProjectionOperation.deadLettered], not by this listing.
     */
    fun pending(limit: Int = 100): List<ProjectionOperation>

    /**
     * The delivery window: [pending] without its dead-lettered records, oldest
     * first, capped at [limit].
     *
     * A default of "take [limit], then drop the dead letters" is wrong for an
     * outbox that retains them: once the dead letters outnumber the window, every
     * live operation behind them is never delivered. A store that can filter in the
     * query must, and this default exists only for the ones that cannot.
     */
    fun pendingLive(limit: Int = 100): List<ProjectionOperation> =
        pending(limit).filterNot { it.deadLettered }.take(limit)

    fun enqueue(operation: ProjectionOperation)

    fun complete(operation: ProjectionOperation)

    fun backlog(): ProjectionBacklog

    /**
     * Durably records **one more** failed delivery attempt for [operation] and
     * returns the attempt count now stored, so the caller can compare it against
     * its delivery budget without reading the outbox back.
     */
    fun recordFailure(
        operation: ProjectionOperation,
        error: Throwable? = null
    ): Int = throw UnsupportedStoreOperationException("store does not support projection attempt tracking")

    /**
     * Atomically takes ownership of the operation [operationId], or returns null
     * if another worker holds a live lease on it.
     *
     * This is the ownership boundary. Write-time draining, reconciliation and the
     * watcher each build their own [gokorei.tanseki.core.application.ProjectionWorker],
     * so without a claim two of them can take the same operation and deliver it
     * concurrently — finishing out of order, so the index ends up holding the
     * *older* revision while both report success.
     *
     * A lease, not a permanent lock, because the holder can die. An expired lease
     * is claimable by anyone, so a crashed worker's operations are picked up once
     * the lease lapses rather than stalling forever.
     */
    fun claim(
        operationId: String,
        owner: String,
        lease: Duration,
        now: Instant
    ): ProjectionClaim?

    /**
     * Deletes the operation, but **only** while [claim] is still held.
     *
     * Returns false when the lease was lost or a competing claim took over, in
     * which case the record is left exactly as it was. A worker that lost its lease
     * must not be able to mark work delivered on behalf of whoever owns it now.
     */
    fun completeClaim(claim: ProjectionClaim): Boolean

    /**
     * Gives up a claim without delivering, so another worker can take it at once
     * instead of waiting out the lease.
     */
    fun releaseClaim(claim: ProjectionClaim)

    /** Claims currently held and unexpired; for metrics and operator diagnosis. */
    fun leasedOperations(now: Instant): List<ProjectionClaim> = emptyList()

    /**
     * Takes [operation] out of the delivery path because its budget is spent.
     * Outboxes that can retain a dead letter keep it visible in [pending] and
     * [backlog]; stores without that ability drop the record, which loses the
     * operator signal but never leaves a poison operation in the delivery path.
     */
    fun deadLetter(operation: ProjectionOperation, error: Throwable? = null) {
        complete(operation)
    }

    /** Dead-lettered records still held by this outbox, oldest first. */
    fun deadLettered(limit: Int = 100): List<ProjectionOperation> =
        pending(limit).filter { it.deadLettered }

    /**
     * Moves outbox entries that cannot be decoded out of the delivery path and
     * returns how many were quarantined, so one damaged file cannot stall the
     * operations behind it forever. Only file-backed outboxes can hold
     * undecodable entries; stores whose outbox is a table report `0`.
     */
    fun quarantineCorrupt(): Int = throw UnsupportedStoreOperationException(
        "store does not support quarantining corrupt projection operations"
    )
}

/**
 * A time-bounded right to deliver one operation.
 *
 * [token] is a fencing token: it is minted per claim, so a worker whose lease has
 * expired and been taken over by another cannot tell itself apart from the
 * current owner. [completeClaim] requires it, which is what makes "completion
 * cannot succeed after lease loss" true rather than merely likely.
 */
data class ProjectionClaim(
    val operationId: String,
    val owner: String,
    val token: String,
    val expiresAt: Instant
) {
    fun isLive(now: Instant): Boolean = now < expiresAt
}

/** Default delivery budget before an operation is dead-lettered. */
const val DEFAULT_PROJECTION_MAX_ATTEMPTS: Int = 5

/** Default projection lease: long enough to cover one delivery, short enough to recover. */
val DEFAULT_PROJECTION_LEASE: Duration = 30.seconds
