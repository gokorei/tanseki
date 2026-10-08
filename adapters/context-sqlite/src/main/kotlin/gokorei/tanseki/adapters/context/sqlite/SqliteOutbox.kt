package gokorei.tanseki.adapters.context.sqlite

import gokorei.tanseki.adapters.context.sqlite.db.ProjectionOperationsQueries
import gokorei.tanseki.adapters.context.sqlite.db.TansekiDatabase
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ProjectionBacklog
import gokorei.tanseki.core.ports.ProjectionClaim
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The projection outbox rows and their lifecycle.
 *
 * Kept out of [SqliteContextStore] so the outbox delivery path — enqueue,
 * pending, completion, failure accounting — is one place next to its lease
 * logic ([SqliteLeases]) instead of the second half of the store.
 */
internal class SqliteOutbox(private val database: TansekiDatabase) {
    fun enqueue(operation: ProjectionOperation) {
        withBusyRetry {
            database.projectionOperationsQueries.upsertOperation(operation)
            // A re-enqueue of the same id is a newer intent for the same operation,
            // so any lease left by the previous attempt is void. Without this the
            // fresh record would sit un-claimable until the old lease lapsed, which
            // is exactly the delay the new enqueue was meant to end.
            database.projectionOperationsQueries.clearLeaseOnEnqueue(operation.id)
        }
    }

    fun pending(limit: Int): List<ProjectionOperation> =
        database.projectionOperationsQueries
            .selectPending(limit.coerceAtLeast(0).toLong())
            .executeAsList()
            .map { it.toDomain() }

    /** Kept in the outbox, flagged out of the delivery path so an operator can see it. */
    fun deadLetter(operation: ProjectionOperation) {
        withBusyRetry { database.projectionOperationsQueries.markDeadLettered(operation.id) }
    }

    fun deadLettered(limit: Int): List<ProjectionOperation> =
        database.projectionOperationsQueries
            .selectDeadLettered(limit.coerceAtLeast(0).toLong())
            .executeAsList()
            .map { it.toDomain() }

    /**
     * Dead letters are filtered in the query, not after the limit: once an outbox
     * has accumulated more dead letters than the drain window, filtering the first
     * `limit` rows would starve every live operation behind them.
     */
    fun pendingLive(limit: Int): List<ProjectionOperation> =
        database.projectionOperationsQueries
            .selectPendingLive(limit.coerceAtLeast(0).toLong())
            .executeAsList()
            .map { it.toDomain() }

    fun complete(operation: ProjectionOperation) {
        withBusyRetry { database.projectionOperationsQueries.delete(operation.id) }
    }

    /**
     * The read-back stays inside the retried block: a busy error means the
     * increment never landed, so the replay counts exactly one failure.
     */
    fun recordFailure(operation: ProjectionOperation): Int =
        withBusyRetry {
            database.projectionOperationsQueries.incrementFailure(operation.id)
            database.projectionOperationsQueries
                .selectById(operation.id)
                .executeAsOneOrNull()
                ?.attempts
                ?.toInt()
                ?: operation.attempts + 1
        }

    fun backlog(): ProjectionBacklog {
        val records = pending(Int.MAX_VALUE)
        val live = records.filterNot { it.deadLettered }
        val stuck =
            database.projectionOperationsQueries
                .countStuck()
                .executeAsOne()
                .toInt()
        val oldestStuck =
            database.projectionOperationsQueries
                .oldestStuck()
                .executeAsOne()
                .min
        return ProjectionBacklog(
            pending = live.size,
            oldestCreatedAt = live.firstOrNull()?.createdAt,
            stuck = stuck,
            oldestStuckAt = oldestStuck?.let { Instant.fromEpochMilliseconds(it) },
            deadLettered =
                database.projectionOperationsQueries
                    .countDeadLettered()
                    .executeAsOne()
                    .toInt()
        )
    }
}

/**
 * The claim-lock columns of the outbox.
 *
 * Kept beside [SqliteOutbox]: one conditional UPDATE decides ownership — the row
 * count is the whole mechanism, decided by the database rather than by a
 * read-then-write that two workers could both pass.
 */
internal class SqliteLeases(
    private val database: TansekiDatabase,
    private val clock: Clock
) {
    /**
     * One conditional UPDATE decides ownership.
     *
     * The row count is the whole mechanism: it is 1 for the single worker that
     * won the race and 0 for everyone else, decided by the database rather than by
     * a read-then-write that two workers could both pass.
     */
    fun claim(
        operationId: String,
        owner: String,
        lease: Duration,
        now: Instant
    ): ProjectionClaim? =
        withBusyRetry {
            val token = newToken()
            val expiresAt = now + lease
            val updated =
                database.projectionOperationsQueries
                    .claimOperation(
                        lease_owner = owner,
                        lease_token = token,
                        lease_expires_at = expiresAt.toEpochMilliseconds(),
                        id = operationId,
                        lease_expires_at_ = now.toEpochMilliseconds()
                    ).value
            if (updated == 0L) {
                null
            } else {
                ProjectionClaim(operationId, owner, token, expiresAt)
            }
        }

    /**
     * Deletes only while the fencing token still matches and the lease is live.
     *
     * Both conditions are load-bearing. The token is what stops a worker whose
     * lease lapsed and was re-claimed from completing on behalf of the new owner;
     * the expiry check stops a record being deleted by a lease that ran out
     * mid-delivery without anyone taking over.
     */
    fun completeClaim(claim: ProjectionClaim): Boolean =
        withBusyRetry {
            database.projectionOperationsQueries
                .completeClaimed(
                    id = claim.operationId,
                    lease_token = claim.token,
                    lease_expires_at = nowMillis()
                ).value == 1L
        }

    fun releaseClaim(claim: ProjectionClaim) {
        withBusyRetry {
            database.projectionOperationsQueries.releaseClaim(claim.operationId, claim.token)
        }
    }

    fun leasedOperations(now: Instant): List<ProjectionClaim> =
        database.projectionOperationsQueries
            .selectLeased(now.toEpochMilliseconds())
            .executeAsList()
            .mapNotNull { row ->
                val expires = row.lease_expires_at
                val leaseOwner = row.lease_owner
                val leaseToken = row.lease_token
                if (expires == null || leaseOwner == null || leaseToken == null) {
                    null
                } else {
                    ProjectionClaim(
                        operationId = row.id,
                        owner = leaseOwner,
                        token = leaseToken,
                        expiresAt = Instant.fromEpochMilliseconds(expires)
                    )
                }
            }

    private fun newToken(): String =
        java.util.UUID
            .randomUUID()
            .toString()

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()
}

/**
 * Writes the whole outbox row, including the dead-letter flag, so the three
 * enqueue sites cannot drift apart and silently reset a flag.
 */
internal fun ProjectionOperationsQueries.upsertOperation(operation: ProjectionOperation) {
    upsert(
        id = operation.id,
        document_id = operation.documentId.value,
        revision = operation.revision.value,
        content_hash = operation.contentHash,
        kind = operation.kind.name,
        edge_version = operation.edgeVersion,
        projection_version = operation.projectionVersion,
        created_at = operation.createdAt.toEpochMilliseconds(),
        attempts = operation.attempts.toLong(),
        dead_lettered = if (operation.deadLettered) 1L else 0L
    )
}
