package gokorei.tanseki.service.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("MetricsResponse")
data class MetricsResponse(
    val schemaVersion: Int = 1,
    val collectedAt: String,
    val currentDocuments: Int,
    val currentEdges: Int,
    val dependencies: DependencyMetrics,
    val projection: ProjectionMetrics,
    val pendingOverlay: PendingOverlayMetrics,
    val outbox: OutboxMetrics,
    val reconcile: ReconcileMetrics,
    val watcher: WatcherMetrics,
    val failedEvents: Long,
    val requests: LatencyMetrics,
    val indexing: LatencyMetrics,
    val ready: Boolean,
    val degraded: Boolean,
    val degradedReason: String? = null,
    val failureReasons: List<String> = emptyList(),
    val scope: MetricsScope? = null,
    val collections: List<CollectionMetrics> = emptyList()
)

@Serializable
@SerialName("MetricsScope")
data class MetricsScope(
    val collections: List<String>?,
    val collectionFiltered: Boolean
)

@Serializable
@SerialName("CollectionMetrics")
data class CollectionMetrics(
    val collection: String,
    val currentDocuments: Int? = null,
    val currentEdges: Int? = null
)

@Serializable
data class DependencyMetrics(
    val contextStore: String,
    val lookup: String,
    val projection: String
)

@Serializable
@SerialName("ReadinessResponse")
data class ReadinessResponse(
    val requestId: String? = null,
    val status: String,
    val ready: Boolean,
    val collectedAt: String,
    val currentDocuments: Int,
    val currentEdges: Int,
    val dependencies: DependencyMetrics,
    val projection: ProjectionMetrics,
    val pendingOverlay: PendingOverlayMetrics,
    val outbox: OutboxMetrics,
    val reconcile: ReconcileMetrics,
    val watcher: WatcherMetrics,
    val failedEvents: Long,
    val degradedReason: String? = null,
    val failureReasons: List<String> = emptyList()
)

@Serializable
data class ProjectionMetrics(
    val lastSuccessfulAt: String? = null,
    val ageMillis: Long? = null,
    val state: String,
    /**
     * Nothing is outstanding and the projection is accurate.
     *
     * An old `ageMillis` on its own is not a fault: the projection runs when
     * there is work, so a daemon nobody has written to is *idle*, not behind.
     * This flag separates the two so a reader does not have to infer it — and so
     * `/v1/ready` can stop reporting a healthy idle process as degraded.
     */
    val idle: Boolean = false
)

@Serializable
data class PendingOverlayMetrics(
    val size: Int,
    val lag: Long,
    /**
     * Evicted entries whose projection never landed and whose unprojected TTL has
     * elapsed. A count, never the ids: this is published to any credential that may
     * read metrics, and a document id would leak a document the reader is not
     * scoped to.
     */
    val expiredUnprojected: Int = 0
)

@Serializable
data class OutboxMetrics(
    val pending: Int,
    val oldestAgeMillis: Long? = null,
    val stuck: Int = 0,
    val corrupt: Int = 0,
    /** Outbox entries whose delivery budget is spent; never retried, never delivered. */
    val deadLettered: Int = 0
)

@Serializable
data class ReconcileMetrics(
    val runs: Long,
    val successfulRuns: Long,
    val degradedRuns: Long,
    val lastStatus: String,
    val lastTrigger: String? = null,
    val lastReconciledAt: String? = null,
    val readFailures: Long,
    val projectionAttempted: Long,
    val projectionCompleted: Long,
    val projectionFailed: Long,
    val lastError: String? = null
)

@Serializable
data class WatcherMetrics(
    val state: String,
    val restarts: Long = 0,
    val lastError: String? = null
)

@Serializable
data class LatencyMetrics(
    val attempts: Long,
    val completed: Long,
    val errors: Long,
    val totalLatencyMillis: Long,
    val lastLatencyMillis: Long? = null,
    val maxLatencyMillis: Long,
    val lastError: String? = null
)

data class ScopedMetricsRequest(
    val credentialId: String,
    val collections: Set<String>?,
    val principal: ApiPrincipal
)

interface MetricsProvider {
    fun metrics(): MetricsResponse

    fun metrics(principal: ApiPrincipal): MetricsResponse = metrics()

    fun metrics(request: ScopedMetricsRequest): MetricsResponse = metrics(request.principal)
}

interface ScopedMetricsProvider {
    fun scopedMetrics(request: ScopedMetricsRequest): MetricsResponse
}

interface MetricsRecorder : MetricsProvider {
    fun recordRequest(durationNanos: Long, statusCode: Int, error: String? = null)

    /**
     * A document was written whose frontmatter block could not be read.
     *
     * The write succeeded and the block is preserved, but nothing behind the
     * typed view was indexed, so this is the only place the loss becomes
     * countable. Defaults to doing nothing so a recorder that does not care
     * about frontmatter need not implement it.
     */
    fun recordFrontmatterDegraded(reason: String) = Unit

    override fun metrics(principal: ApiPrincipal): MetricsResponse = metrics()

    override fun metrics(request: ScopedMetricsRequest): MetricsResponse = metrics(request.principal)

    fun readiness(): ReadinessResponse

    /**
     * Readiness for unauthenticated probes, which must not make the daemon scan
     * the store to count documents. Recorders without a cheaper path fall back to
     * the full [readiness].
     */
    fun readinessCheap(): ReadinessResponse = readiness()
}
