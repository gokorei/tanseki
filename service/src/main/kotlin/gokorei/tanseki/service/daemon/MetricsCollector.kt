package gokorei.tanseki.service.daemon

import gokorei.tanseki.core.application.PendingOverlay
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.ProjectionBacklog
import gokorei.tanseki.core.ports.ProjectionOperationStore
import gokorei.tanseki.service.api.CollectionMetrics
import gokorei.tanseki.service.api.DependencyMetrics
import gokorei.tanseki.service.api.MetricsResponse
import gokorei.tanseki.service.api.MetricsScope
import gokorei.tanseki.service.api.OutboxMetrics
import gokorei.tanseki.service.api.PendingOverlayMetrics
import gokorei.tanseki.service.api.ProjectionMetrics
import gokorei.tanseki.service.api.ReconcileMetrics
import gokorei.tanseki.service.api.WatcherMetrics
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Reads one metrics snapshot from the store and assembles the response.
 *
 * Owned by [OperationalMetrics] but kept outside the class so the daemon's
 * metrics provider stays a thin facade. Every dependency is passed in — the
 * status snapshot, the latency recorder and a callback for degraded
 * dependencies — and the store/outbox reads live in top-level helpers so the
 * object itself stays an assembler.
 */
internal object MetricsCollector {
    /** The assembled response plus the backlog it observed, so the caller can record it. */
    data class CollectionResult(val response: MetricsResponse, val outbox: ProjectionBacklog)

    fun collect(
        store: ContextStore,
        lookup: Lookup?,
        overlay: PendingOverlay,
        staleAfterMillis: Long,
        now: Instant,
        snapshot: StatusSnapshot,
        latency: LatencyRecorder,
        allowedCollections: Set<String>?,
        onDegraded: (String) -> Unit
    ): CollectionResult {
        val storeReads = readStore(store, allowedCollections, onDegraded)
        val outboxRead = readOutbox(store, storeReads.scoped, storeReads.refs, onDegraded)

        val lookupState =
            if (lookup == null) {
                DependencyState.NOT_CONFIGURED
            } else {
                runCatching { lookup.searchText("", Filters(), 1) }
                    .fold(
                        onSuccess = { DependencyState.AVAILABLE },
                        onFailure = {
                            onDegraded("lookup")
                            DependencyState.UNAVAILABLE
                        }
                    )
            }

        val overlayRefs =
            if (!storeReads.scoped) {
                null
            } else {
                storeReads.collectionRefs.flatMap { group ->
                    runCatching { overlay.pendingRefs(Collection(group.name)) }.getOrElse { emptyList() }
                }
            }
        val overlaySize = overlayRefs?.size ?: overlay.pendingSize()
        val overlayLag = if (overlayRefs == null || overlayRefs.isNotEmpty()) overlay.lag() else 0L
        val expiredUnprojected = runCatching { overlay.expiredUnprojectedIds().size }.getOrElse { 0 }

        val reasons = snapshot.reasons.toMutableSet()
        setReason(reasons, "context_store_unavailable", storeReads.storeState == DependencyState.UNAVAILABLE)
        setReason(reasons, "lookup_unavailable", lookupState == DependencyState.UNAVAILABLE)
        setReason(reasons, "projection_unavailable", outboxRead.projectionDependency == DependencyState.UNAVAILABLE)

        val age = snapshot.projectionCompletedAt?.let { elapsed(now, it) }
        val workOutstanding = outboxRead.outbox.pending > 0 || overlaySize > 0 || expiredUnprojected > 0
        val projectionState =
            projectionStateOf(
                reasons,
                snapshot.projectionCompletedAt,
                workOutstanding,
                age,
                staleAfterMillis
            )
        val projectionIdle = projectionState == ProjectionState.FRESH && !workOutstanding
        setReason(reasons, "projection_stale", projectionState == ProjectionState.STALE)
        outboxReasons(reasons, outboxRead.outbox, overlaySize)
        watcherReason(snapshot.watcherState)?.let { reasons += it }

        val ready = reasons.isEmpty()
        val perCollection =
            storeReads.collectionRefs.takeIf { storeReads.scoped }?.map { group ->
                CollectionMetrics(group.name, group.refs.size, storeReads.edgesByCollection?.get(group.name))
            }
        return CollectionResult(
            response =
                MetricsResponse(
                    collectedAt = now.toString(),
                    currentDocuments = storeReads.refs.size,
                    currentEdges = storeReads.edges,
                    dependencies =
                        DependencyMetrics(
                            contextStore = storeReads.storeState.wire,
                            lookup = lookupState.wire,
                            projection = outboxRead.projectionDependency.wire
                        ),
                    projection =
                        ProjectionMetrics(
                            lastSuccessfulAt = snapshot.projectionCompletedAt?.toString(),
                            ageMillis = age,
                            state = projectionState.wire,
                            idle = projectionIdle
                        ),
                    pendingOverlay = PendingOverlayMetrics(overlaySize, overlayLag, expiredUnprojected),
                    outbox =
                        OutboxMetrics(
                            pending = outboxRead.outbox.pending,
                            oldestAgeMillis = outboxRead.outbox.oldestAge(now)?.inWholeMilliseconds,
                            stuck = outboxRead.outbox.stuck,
                            corrupt = outboxRead.outbox.corrupt,
                            deadLettered = outboxRead.outbox.deadLettered
                        ),
                    reconcile =
                        ReconcileMetrics(
                            runs = snapshot.reconcileRuns,
                            successfulRuns = snapshot.reconcileSuccessfulRuns,
                            degradedRuns = snapshot.reconcileDegradedRuns,
                            lastStatus = snapshot.reconcileLastStatus,
                            lastTrigger = snapshot.reconcileLastTrigger,
                            lastReconciledAt = snapshot.reconcileLastReconciledAt?.toString(),
                            readFailures = snapshot.reconcileReadFailures,
                            projectionAttempted = snapshot.reconcileProjectionAttempted,
                            projectionCompleted = snapshot.reconcileProjectionCompleted,
                            projectionFailed = snapshot.reconcileProjectionFailed,
                            lastError = snapshot.reconcileLastError
                        ),
                    watcher =
                        WatcherMetrics(snapshot.watcherState.wire, snapshot.watcherRestarts, snapshot.watcherLastError),
                    failedEvents = snapshot.failedEvents,
                    requests = latency.requests(),
                    indexing = latency.indexing(),
                    ready = ready,
                    degraded = !ready,
                    degradedReason = reasons.firstOrNull(),
                    failureReasons = reasons.toList(),
                    scope = perCollection?.let { scoped -> MetricsScope(scoped.map { it.collection }, true) },
                    collections = perCollection.orEmpty()
                ),
            outbox = outboxRead.outbox
        )
    }
}

private data class CollectionRefs(val name: String, val refs: List<DocRef>)

/** The store reads one collection pass needs, kept together for the assembler. */
private data class StoreReads(
    val scoped: Boolean,
    val collectionRefs: List<CollectionRefs>,
    val refs: List<DocRef>,
    val storeState: DependencyState,
    val edges: Int,
    val edgesByCollection: Map<String, Int>?
)

private data class OutboxRead(
    val outbox: ProjectionBacklog,
    val projectionDependency: DependencyState
)

/**
 * The store reads, one pass per requested collection so the breakdown and the
 * edge counts share it. A scoped read that cannot reach the store reports it
 * unavailable rather than empty.
 */
private fun readStore(
    store: ContextStore,
    allowedCollections: Set<String>?,
    onDegraded: (String) -> Unit
): StoreReads {
    val scopedResult: Result<List<CollectionRefs>>? =
        allowedCollections?.let { names ->
            runCatching {
                names.sorted().map { name -> CollectionRefs(name, store.list(Collection(name))) }
            }
        }
    val collectionRefs: List<CollectionRefs> = scopedResult?.getOrNull().orEmpty()
    val refsResult: Result<List<DocRef>> =
        scopedResult?.map { groups -> groups.flatMap { it.refs } } ?: runCatching { store.list() }
    val refs =
        refsResult.getOrElse {
            onDegraded("metrics:documents")
            emptyList()
        }
    val edgesByCollection =
        scopedResult?.let {
            collectionRefs.associate { group ->
                group.name to edgesOf(store, group.refs, edgeBudget(collectionRefs.size), onDegraded)
            }
        }
    val edges =
        when {
            refsResult.isFailure -> 0
            edgesByCollection != null -> edgesByCollection.values.sum()
            else -> edgesOf(store, refs, edgeBudget(1), onDegraded)
        }
    return StoreReads(
        scoped = scopedResult != null,
        collectionRefs = collectionRefs,
        refs = refs,
        storeState = if (refsResult.isSuccess) DependencyState.AVAILABLE else DependencyState.UNAVAILABLE,
        edges = edges,
        edgesByCollection = edgesByCollection
    )
}

/**
 * The backlog. A collection filter narrows it to the operations that touch the
 * requested documents; an undecodable record has no document to attribute it to.
 */
private fun readOutbox(
    store: ContextStore,
    scoped: Boolean,
    refs: List<DocRef>,
    onDegraded: (String) -> Unit
): OutboxRead {
    val projectionStoreResult = runCatching { store.projectionOperations() }
    val outboxResult: Result<ProjectionBacklog> =
        projectionStoreResult.fold(
            onSuccess = { operations ->
                if (operations == null) {
                    Result.success(ProjectionBacklog())
                } else {
                    runCatching { operations.backlog() }
                }
            },
            onFailure = { error -> Result.failure(error) }
        )
    val globalOutbox =
        outboxResult.getOrElse {
            onDegraded("metrics:outbox")
            ProjectionBacklog()
        }
    val projectionStore = projectionStoreResult.getOrNull()
    val outbox =
        if (!scoped || projectionStore == null) {
            globalOutbox
        } else {
            outboxResult.getOrNull()?.let { backlog ->
                narrowToScope(backlog, projectionStore, refs.mapTo(HashSet()) { it.id }, onDegraded)
            } ?: globalOutbox
        }
    val projectionDependency =
        when {
            projectionStoreResult.isFailure || outboxResult.isFailure -> DependencyState.UNAVAILABLE
            projectionStore == null -> DependencyState.NOT_CONFIGURED
            else -> DependencyState.AVAILABLE
        }
    return OutboxRead(outbox, projectionDependency)
}

/** Edge count for [refs], bounded by the shared neighbour-read budget. */
private fun edgesOf(store: ContextStore, refs: List<DocRef>, budget: Int, onDegraded: (String) -> Unit): Int =
    runCatching { refs.take(budget).sumOf { store.neighbors(it.id).size } }
        .getOrElse {
            onDegraded("metrics:edges")
            0
        }

private fun edgeBudget(collections: Int): Int =
    if (collections <= 1) MAX_METRIC_EDGE_DOCUMENTS else maxOf(1, MAX_METRIC_EDGE_DOCUMENTS / collections)

/** The backlog narrowed to [documentIds]; undecodable records carry no document id. */
private fun narrowToScope(
    backlog: ProjectionBacklog,
    operations: ProjectionOperationStore,
    documentIds: Set<DocId>,
    onDegraded: (String) -> Unit
): ProjectionBacklog {
    val pending =
        runCatching { operations.pending(MAX_METRIC_OUTBOX_OPERATIONS) }
            .getOrElse {
                onDegraded("metrics:outbox")
                return backlog
            }
    val inScope = pending.filter { it.documentId in documentIds }
    val live = inScope.filterNot { it.deadLettered }
    val stuck = live.filter { it.attempts > 0 }
    return ProjectionBacklog(
        pending = live.size,
        oldestCreatedAt = live.minByOrNull { it.createdAt }?.createdAt,
        stuck = stuck.size,
        oldestStuckAt = stuck.minByOrNull { it.createdAt }?.createdAt,
        corrupt = backlog.corrupt,
        deadLettered = inScope.count { it.deadLettered }
    )
}

private fun setReason(reasons: MutableSet<String>, reason: String, present: Boolean) {
    if (present) reasons += reason else reasons -= reason
}

/**
 * Age alone is not staleness: the projection runs when there is work, so a
 * daemon nobody has written to for an hour has an accurate projection and
 * nothing to do. Age is evidence of being behind only when work is outstanding.
 */
private fun projectionStateOf(
    reasons: Set<String>,
    projectionCompletedAt: Instant?,
    workOutstanding: Boolean,
    age: Long?,
    staleAfterMillis: Long
): ProjectionState {
    if (reasons.isNotEmpty()) return ProjectionState.DEGRADED
    if (projectionCompletedAt == null) return ProjectionState.STALE
    if (workOutstanding && age != null && age >= staleAfterMillis) return ProjectionState.STALE
    return ProjectionState.FRESH
}

private fun watcherReason(state: WatcherState): String? =
    when (state) {
        WatcherState.RUNNING, WatcherState.NOT_APPLICABLE -> null
        WatcherState.STARTING -> "watcher_starting"
        WatcherState.RECOVERING -> "watcher_recovering"
        WatcherState.STOPPED -> "watcher_stopped"
        WatcherState.NOT_CONFIGURED -> "watcher_not_configured"
    }

private fun outboxReasons(reasons: MutableSet<String>, outbox: ProjectionBacklog, overlaySize: Int) {
    if (outbox.pending > 0) reasons += "projection_backlog"
    if (outbox.corrupt > 0) reasons += "projection_corrupt"
    // A dead letter is a document the projection will never deliver, so it is
    // the same class of signal as a corrupt record.
    if (outbox.deadLettered > 0) reasons += "projection_dead_lettered"
    if (overlaySize > 0) reasons += "pending_overlay"
}

private fun elapsed(now: Instant, then: Instant): Long =
    (now - then).coerceAtLeast(Duration.ZERO).inWholeMilliseconds

private const val MAX_METRIC_EDGE_DOCUMENTS = 256
private const val MAX_METRIC_OUTBOX_OPERATIONS = 512
