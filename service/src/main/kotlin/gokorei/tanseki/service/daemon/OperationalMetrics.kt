package gokorei.tanseki.service.daemon

import gokorei.tanseki.core.application.IndexObserver
import gokorei.tanseki.core.application.PendingOverlay
import gokorei.tanseki.core.application.ReconcileReport
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.service.api.ApiPrincipal
import gokorei.tanseki.service.api.CollectionMetrics
import gokorei.tanseki.service.api.DependencyMetrics
import gokorei.tanseki.service.api.MetricsRecorder
import gokorei.tanseki.service.api.MetricsResponse
import gokorei.tanseki.service.api.MetricsScope
import gokorei.tanseki.service.api.OutboxMetrics
import gokorei.tanseki.service.api.PendingOverlayMetrics
import gokorei.tanseki.service.api.ProjectionMetrics
import gokorei.tanseki.service.api.ReadinessResponse
import gokorei.tanseki.service.api.ReconcileMetrics
import gokorei.tanseki.service.api.ScopedMetricsProvider
import gokorei.tanseki.service.api.ScopedMetricsRequest
import gokorei.tanseki.service.api.WatcherMetrics
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The daemon's own metrics provider.
 *
 * A collection filter is applied to everything the canonical store can answer
 * per collection: document and edge counts, the per-collection breakdown, the
 * outbox backlog, and the write-behind overlay. `projection.state` follows from
 * the scoped backlog, so it answers for the requested collections too.
 *
 * What a filter cannot touch is reported as what it is: one projection pipeline,
 * one overlay, one reconcile sweep and one request log serve every collection,
 * so their timestamps and run counters are daemon-wide by construction. See
 * the `/v1/metrics` contract in `docs/openapi.json` for what is published.
 */
class OperationalMetrics(
    private val store: ContextStore,
    private val overlay: PendingOverlay,
    private val config: DaemonConfig,
    private val lookup: Lookup? = null,
    private val clock: () -> Instant = { Clock.System.now() }
) : MetricsRecorder, ScopedMetricsProvider, IndexObserver {
    private val metricsCache = MetricsCache()
    private val latency = LatencyRecorder()
    private val status = DaemonStatus(clock)

    override fun metrics(): MetricsResponse = cachedMetrics(null, null)

    override fun metrics(principal: ApiPrincipal): MetricsResponse =
        cachedMetrics(principal.credentialId, principal.collections)

    /**
     * The collection filter the caller actually asked for, which is narrower than
     * the credential's scope whenever `?collection=` was supplied: the request
     * is scoped to exactly [ScopedMetricsRequest.collections].
     */
    override fun scopedMetrics(request: ScopedMetricsRequest): MetricsResponse =
        cachedMetrics(cacheKey(request.credentialId, request.collections), request.collections)

    private fun cacheKey(credentialId: String?, collections: Set<String>?): String? =
        when {
            collections == null -> credentialId ?: "global"
            credentialId == null -> collections.sorted().joinToString(",")
            else -> "$credentialId:${collections.sorted().joinToString(",")}"
        }

    private fun cachedMetrics(scopeKey: String?, collections: Set<String>?): MetricsResponse {
        val now = clock()
        val key = scopeKey ?: "global"
        metricsCache.get(key, now)?.let { return it }
        val snapshot = collectMetrics(collections?.take(MAX_METRIC_COLLECTIONS)?.toSet())
        metricsCache.put(key, now, snapshot)
        return snapshot
    }

    /** One collection's canonical references, for the scoped breakdown. */
    private fun collectMetrics(allowedCollections: Set<String>?): MetricsResponse {
        val now = clock()
        val result =
            MetricsCollector.collect(
                store = store,
                lookup = lookup,
                overlay = overlay,
                staleAfterMillis = config.projectionStaleAfter.inWholeMilliseconds,
                now = now,
                snapshot = status.snapshot(),
                latency = latency,
                allowedCollections = allowedCollections,
                onDegraded = ::markDegraded
            )
        status.recordOutbox(result.outbox.stuck, result.outbox.corrupt, result.outbox)
        return result.response
    }

    override fun onIndexFinished(durationNanos: Long, failure: Throwable?) {
        latency.recordIndex(durationNanos, failed = failure != null)
        status.onIndexFinished(failure)
    }

    fun markProjectionCompleted() {
        status.onIndexFinished(null)
    }

    fun recordReconcile(trigger: String, report: ReconcileReport, successful: Boolean) {
        status.recordReconcile(trigger, report, successful)
    }

    fun recordReconcileFailure(trigger: String, failure: Throwable) {
        status.recordReconcileFailure(trigger, failure)
    }

    fun recordEventFailure() {
        status.recordEventFailure()
    }

    /**
     * Counts a write whose frontmatter did not read.
     *
     * Marked degraded rather than only counted, because the consequence is not
     * merely that a metric moved: the document is in the store with none of its
     * properties indexed, and a readiness probe that stays green over that is
     * reporting on the wrong thing.
     */
    override fun recordFrontmatterDegraded(reason: String) {
        status.markDegraded("frontmatter_unreadable")
        metricsCache.clear()
    }

    fun markDegraded(reason: String) {
        status.markDegraded(reason)
        metricsCache.clear()
    }

    fun clearDegraded(reason: String) {
        status.clearDegraded(reason)
        metricsCache.clear()
    }

    fun clearEventDegraded() {
        status.clearEventDegraded()
    }

    fun markWatcherStarting() {
        status.markWatcher(WatcherState.STARTING)
    }

    fun markWatcherRunning() {
        status.markWatcher(WatcherState.RUNNING)
    }

    fun markWatcherRecovering(reason: String) {
        status.markWatcher(WatcherState.RECOVERING, reason)
    }

    fun markWatcherStopped() {
        status.markWatcher(WatcherState.STOPPED)
    }

    fun markWatcherNotApplicable() {
        status.markWatcher(WatcherState.NOT_APPLICABLE)
    }

    override fun recordRequest(durationNanos: Long, statusCode: Int, error: String?) =
        latency.recordRequest(durationNanos, statusCode, error)

    override fun readiness(): ReadinessResponse {
        val snapshot = metrics()
        val unavailable =
            snapshot.dependencies.contextStore == DependencyState.UNAVAILABLE.wire ||
                snapshot.dependencies.lookup == DependencyState.UNAVAILABLE.wire ||
                snapshot.dependencies.projection == DependencyState.UNAVAILABLE.wire
        val status =
            when {
                snapshot.ready -> ReadinessStatus.READY
                unavailable -> ReadinessStatus.UNAVAILABLE
                else -> ReadinessStatus.DEGRADED
            }
        return ReadinessResponse(
            requestId = null,
            status = status.wire,
            ready = snapshot.ready,
            collectedAt = snapshot.collectedAt,
            currentDocuments = snapshot.currentDocuments,
            currentEdges = snapshot.currentEdges,
            dependencies = snapshot.dependencies,
            projection = snapshot.projection,
            pendingOverlay = snapshot.pendingOverlay,
            outbox = snapshot.outbox,
            reconcile = snapshot.reconcile,
            watcher = snapshot.watcher,
            failedEvents = snapshot.failedEvents,
            degradedReason = snapshot.degradedReason,
            failureReasons = snapshot.failureReasons
        )
    }

    /**
     * Readiness for unauthenticated callers: no store scan, no counts.
     *
     * Counts require reading the store, so an anonymous `/v1/ready` probe must
     * not make the daemon scan it. The verdict is built from in-memory flags the
     * daemon's own reconciliation keeps updated; the full snapshot, including
     * counts, is served to authenticated callers via `/v1/metrics`.
     */
    override fun readinessCheap(): ReadinessResponse {
        val now = clock()
        val state = status.snapshot()
        val unavailableReasons =
            listOf("context_store_unavailable", "lookup_unavailable", "projection_unavailable")
        val unavailable = state.reasons.any { it in unavailableReasons }
        // Any degraded dependency means the daemon is not ready; only a clean,
        // projection-completed state is ready.
        val ready = state.reasons.isEmpty() && state.projectionCompletedAt != null
        val status =
            when {
                ready -> ReadinessStatus.READY
                unavailable -> ReadinessStatus.UNAVAILABLE
                else -> ReadinessStatus.DEGRADED
            }
        return ReadinessResponse(
            requestId = null,
            status = status.wire,
            ready = ready,
            collectedAt = now.toString(),
            currentDocuments = 0,
            currentEdges = 0,
            dependencies =
                DependencyMetrics(
                    contextStore =
                        if ("context_store_unavailable" in state.reasons) {
                            DependencyState.UNAVAILABLE.wire
                        } else {
                            DependencyState.AVAILABLE.wire
                        },
                    lookup =
                        if ("lookup_unavailable" in state.reasons) {
                            DependencyState.UNAVAILABLE.wire
                        } else {
                            DependencyState.AVAILABLE.wire
                        },
                    projection =
                        if ("projection_unavailable" in state.reasons) {
                            DependencyState.UNAVAILABLE.wire
                        } else {
                            DependencyState.AVAILABLE.wire
                        }
                ),
            projection = ProjectionMetrics(state = if (state.projectionCompletedAt != null) "ready" else "pending"),
            pendingOverlay = PendingOverlayMetrics(size = overlay.unprojectedSize(), lag = 0),
            outbox = OutboxMetrics(pending = 0, stuck = state.outboxStuck, corrupt = state.outboxCorrupt),
            reconcile =
                ReconcileMetrics(
                    runs = 0,
                    successfulRuns = 0,
                    degradedRuns = 0,
                    lastStatus = "not_run",
                    readFailures = 0,
                    projectionAttempted = 0,
                    projectionCompleted = 0,
                    projectionFailed = 0
                ),
            watcher = WatcherMetrics(state = state.watcherState.wire, restarts = state.watcherRestarts),
            failedEvents = state.failedEvents,
            degradedReason = state.reasons.firstOrNull(),
            failureReasons = state.reasons
        )
    }

    fun health(): Health {
        val snapshot = metrics()
        val statusSnapshot = status.snapshot()
        return Health(
            pendingSize = snapshot.pendingOverlay.size,
            indexingLag = snapshot.pendingOverlay.lag,
            indexedDocuments = snapshot.currentDocuments,
            timestamp = clock(),
            ready = snapshot.ready,
            status =
                (
                    when {
                        snapshot.ready -> ReadinessStatus.READY

                        snapshot.failureReasons.any {
                            it == "context_store_unavailable" ||
                                it == "lookup_unavailable" ||
                                it == "projection_unavailable"
                        } -> ReadinessStatus.UNAVAILABLE

                        else -> ReadinessStatus.DEGRADED
                    }
                ).wire,
            degraded = snapshot.degraded,
            degradedReason = snapshot.degradedReason,
            failureReasons = snapshot.failureReasons,
            failedEvents = snapshot.failedEvents,
            projectionBacklog = snapshot.outbox.pending,
            lastReconciledAt = snapshot.reconcile.lastReconciledAt?.let(Instant::parse),
            currentDocuments = snapshot.currentDocuments,
            currentEdges = snapshot.currentEdges,
            projectionAgeMillis = snapshot.projection.ageMillis,
            projectionState = snapshot.projection.state,
            watcherState = snapshot.watcher.state,
            unprojected = overlay.unprojectedSize(),
            stuckProjections = statusSnapshot.outboxStuck,
            corruptProjections = statusSnapshot.outboxCorrupt,
            deadLetteredProjections = snapshot.outbox.deadLettered,
            expiredUnprojected = snapshot.pendingOverlay.expiredUnprojected
        )
    }

    private companion object {
        const val MAX_METRIC_COLLECTIONS = 64
    }
}
