package gokorei.tanseki.service.daemon

import gokorei.tanseki.core.application.ReconcileReport
import gokorei.tanseki.core.ports.ProjectionBacklog
import kotlin.time.Instant

/**
 * The daemon's mutable metrics/readiness state, under one lock.
 *
 * Extracted from [OperationalMetrics]: the state and its transitions form a small
 * state machine of their own, and [OperationalMetrics] stays a facade that
 * collects from the store, renders DTOs, and delegates every transition here.
 * [snapshot] gives the rest of the facade one consistent read without exposing
 * the lock or the fields. Reconciliation counters live in [ReconcileStatus] and
 * the reason-normalising rules in [ReasonSanitizer], so no one class carries
 * every concern it owns.
 */
internal class DaemonStatus(private val clock: () -> Instant) {
    private val lock = Any()
    private val reconcile = ReconcileStatus()
    private val reasons = LinkedHashSet<String>()
    private var projectionCompletedAt: Instant? = null
    private var watcherState = WatcherState.NOT_CONFIGURED
    private var watcherRestarts = 0L
    private var watcherLastError: String? = null
    private var failedEvents = 0L
    private var outboxStuck = 0
    private var outboxCorrupt = 0

    /** Last observed projection backlog; `Health` reports fields the DTO does not carry. */
    @Volatile
    private var projectionBacklog: ProjectionBacklog = ProjectionBacklog()

    /** One consistent read of every state field, safe to hand to the rendering half. */
    fun snapshot(): StatusSnapshot =
        synchronized(lock) {
            val reconcileSnapshot = reconcile.snapshot()
            StatusSnapshot(
                reasons = reasons.toList(),
                projectionCompletedAt = projectionCompletedAt,
                watcherState = watcherState,
                watcherRestarts = watcherRestarts,
                watcherLastError = watcherLastError,
                failedEvents = failedEvents,
                outboxStuck = outboxStuck,
                outboxCorrupt = outboxCorrupt,
                projectionBacklog = projectionBacklog,
                reconcileRuns = reconcileSnapshot.runs,
                reconcileSuccessfulRuns = reconcileSnapshot.successfulRuns,
                reconcileDegradedRuns = reconcileSnapshot.degradedRuns,
                reconcileLastStatus = reconcileSnapshot.lastStatus,
                reconcileLastTrigger = reconcileSnapshot.lastTrigger,
                reconcileLastReconciledAt = reconcileSnapshot.lastReconciledAt,
                reconcileReadFailures = reconcileSnapshot.readFailures,
                reconcileProjectionAttempted = reconcileSnapshot.projectionAttempted,
                reconcileProjectionCompleted = reconcileSnapshot.projectionCompleted,
                reconcileProjectionFailed = reconcileSnapshot.projectionFailed,
                reconcileLastError = reconcileSnapshot.lastError
            )
        }

    fun recordOutbox(stuck: Int, corrupt: Int, backlog: ProjectionBacklog) {
        synchronized(lock) {
            outboxStuck = stuck
            outboxCorrupt = corrupt
            projectionBacklog = backlog
        }
    }

    fun onIndexFinished(failure: Throwable?) {
        synchronized(lock) {
            if (failure == null) {
                projectionCompletedAt = clock()
                reasons -= "projection_failed"
            } else {
                reasons += "projection_failed"
            }
        }
    }

    fun recordReconcile(trigger: String, report: ReconcileReport, successful: Boolean) {
        synchronized(lock) {
            reconcile.recordReconcile(trigger, report, successful, clock())
            if (successful) {
                projectionCompletedAt = clock()
                reasons -= "reconciliation_degraded"
                reasons -= "projection_failed"
                reasons -= "projection_backlog"
                clearEventDegraded()
            } else {
                reasons += "reconciliation_degraded"
                if (report.projectionFailed > 0) reasons += "projection_failed"
            }
        }
    }

    fun recordReconcileFailure(trigger: String, failure: Throwable) {
        synchronized(lock) {
            reconcile.recordReconcileFailure(trigger, failure, clock())
            reasons += "reconciliation_degraded"
        }
    }

    fun recordEventFailure() {
        synchronized(lock) {
            failedEvents++
            reasons += "watch_event_failed"
        }
    }

    fun markDegraded(reason: String) {
        synchronized(lock) { reasons += ReasonSanitizer.sanitize(reason) }
    }

    fun clearDegraded(reason: String) {
        synchronized(lock) { reasons -= ReasonSanitizer.sanitize(reason) }
    }

    fun clearEventDegraded() {
        synchronized(lock) { reasons.removeAll { it == "watch_event_failed" || it.startsWith("event:") } }
    }

    /** Moves the watcher into [state], recording the recovery reason when supplied. */
    fun markWatcher(state: WatcherState, reason: String? = null) {
        synchronized(lock) {
            watcherState = state
            if (state == WatcherState.RECOVERING) {
                watcherRestarts++
                watcherLastError =
                    if (reason.orEmpty().contains("ended", ignoreCase = true)) {
                        "watcher_ended"
                    } else {
                        "watcher_unavailable"
                    }
            }
        }
    }
}

/** One consistent read of [DaemonStatus], produced under its lock. */
internal data class StatusSnapshot(
    val reasons: List<String>,
    val projectionCompletedAt: Instant?,
    val watcherState: WatcherState,
    val watcherRestarts: Long,
    val watcherLastError: String?,
    val failedEvents: Long,
    val reconcileRuns: Long,
    val reconcileSuccessfulRuns: Long,
    val reconcileDegradedRuns: Long,
    val reconcileLastStatus: String,
    val reconcileLastTrigger: String?,
    val reconcileLastReconciledAt: Instant?,
    val reconcileReadFailures: Long,
    val reconcileProjectionAttempted: Long,
    val reconcileProjectionCompleted: Long,
    val reconcileProjectionFailed: Long,
    val reconcileLastError: String?,
    val outboxStuck: Int,
    val outboxCorrupt: Int,
    val projectionBacklog: ProjectionBacklog
)

/**
 * Reconciliation counters, kept together so they mutate and snapshot in one place.
 *
 * Split out of [DaemonStatus] so the reconcile bookkeeping is one small class
 * with its own shape instead of a pile of fields inside the daemon status.
 */
internal class ReconcileStatus {
    private var runs = 0L
    private var successfulRuns = 0L
    private var degradedRuns = 0L
    private var lastStatus = "not_run"
    private var lastTrigger: String? = null
    private var lastReconciledAt: Instant? = null
    private var readFailures = 0L
    private var projectionAttempted = 0L
    private var projectionCompleted = 0L
    private var projectionFailed = 0L
    private var lastError: String? = null

    fun snapshot(): ReconcileSnapshot =
        ReconcileSnapshot(
            runs = runs,
            successfulRuns = successfulRuns,
            degradedRuns = degradedRuns,
            lastStatus = lastStatus,
            lastTrigger = lastTrigger,
            lastReconciledAt = lastReconciledAt,
            readFailures = readFailures,
            projectionAttempted = projectionAttempted,
            projectionCompleted = projectionCompleted,
            projectionFailed = projectionFailed,
            lastError = lastError
        )

    fun recordReconcile(trigger: String, report: ReconcileReport, successful: Boolean, now: Instant) {
        runs++
        lastStatus = if (successful) "success" else "degraded"
        lastTrigger = ReasonSanitizer.sanitize(trigger)
        lastReconciledAt = now
        readFailures += report.readFailures
        projectionAttempted += report.projectionAttempted
        projectionCompleted += report.projectionCompleted
        projectionFailed += report.projectionFailed
        if (successful) {
            successfulRuns++
            lastError = null
        } else {
            degradedRuns++
            lastError = reconcileError(report)
        }
    }

    fun recordReconcileFailure(trigger: String, failure: Throwable, now: Instant) {
        runs++
        lastStatus = "failed"
        lastTrigger = ReasonSanitizer.sanitize(trigger)
        lastReconciledAt = now
        degradedRuns++
        lastError =
            "reconciliation_failed:${failure::class.simpleName ?: "error"}"
    }

    private fun reconcileError(report: ReconcileReport): String =
        when {
            report.readFailures > 0 -> "reconciliation_read_failure"
            report.projectionFailed > 0 -> "projection_failures"
            else -> "reconciliation_degraded"
        }
}

internal data class ReconcileSnapshot(
    val runs: Long,
    val successfulRuns: Long,
    val degradedRuns: Long,
    val lastStatus: String,
    val lastTrigger: String?,
    val lastReconciledAt: Instant?,
    val readFailures: Long,
    val projectionAttempted: Long,
    val projectionCompleted: Long,
    val projectionFailed: Long,
    val lastError: String?
)

/**
 * Maps free-text reasons onto the closed set published in `failureReasons`.
 *
 * An exact-match table plus a couple of prefix rules and a small watcher-keyword
 * helper, rather than one long `when` cascade. Shared by the degradations and the
 * reconcile trigger, and kept out of the state classes so they own no string
 * normalisation.
 */
internal object ReasonSanitizer {
    private val EXACT_REASONS: Map<String, String> =
        mapOf(
            "overflow" to "projection_backlog",
            "indexing" to "projection_failed",
            "metrics:documents" to "context_store_unavailable",
            "metrics:edges" to "context_store_unavailable",
            "metrics:outbox" to "projection_unavailable",
            "lookup" to "lookup_unavailable"
        )

    fun sanitize(reason: String): String {
        val normalized = reason.trim().lowercase()
        EXACT_REASONS[normalized]?.let { return it }
        if (normalized.startsWith("event:") || normalized.startsWith("watch_event")) return "watch_event_failed"
        if (normalized.startsWith("reconciliation")) return "reconciliation_degraded"
        if (normalized.contains("watcher")) return watcherReason(normalized)
        return "dependency_unavailable"
    }

    private fun watcherReason(normalized: String): String =
        when {
            normalized.contains("collector") && normalized.contains("ended") -> "watcher_ended"
            normalized.contains("collector") -> "watcher_unavailable"
            normalized.contains("recover") -> "watcher_recovering"
            normalized.contains("stop") -> "watcher_stopped"
            normalized.contains("start") -> "watcher_starting"
            else -> "dependency_unavailable"
        }
}
