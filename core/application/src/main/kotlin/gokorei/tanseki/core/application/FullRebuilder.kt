package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.TansekiLogger

enum class RebuildPhase {
    LOADING,
    CLEARING,
    INDEXING,
    OUTBOX,
    COMPLETE,
    FAILED
}

data class RebuildProgress(
    val phase: RebuildPhase,
    val processed: Int = 0,
    val total: Int = 0,
    val documentId: String? = null
)

data class FullRebuildReport(
    val totalDocuments: Int,
    val indexedDocuments: Int,
    val readFailures: Int,
    val indexFailures: Int,
    val projectionAttempted: Int,
    val projectionCompleted: Int,
    val projectionFailed: Int,
    val removedDocuments: Int = 0,
    val failures: List<String> = emptyList()
) {
    val successful: Boolean
        get() = readFailures == 0 && indexFailures == 0 && projectionFailed == 0 && failures.isEmpty()
}

/**
 * Rebuilds the Lookup and the graph from the ContextStore.
 *
 * The rebuild is staged rather than destructive, and it never clears before it
 * can finish:
 *
 * 1. **load** every document and **stage** its derived edges — pure reads, so a
 *    failure here leaves the previous graph and index untouched;
 * 2. **index** every document into the Lookup;
 * 3. only once every document is indexed, **rewrite** the canonical graph from
 *    the staged edges, so the graph ends up holding exactly `f(store)` — edges
 *    of documents the store dropped included;
 * 4. **prune** index entries the store no longer contains, then drain the
 *    projection outbox.
 *
 * A failure in steps 1-2 returns before anything is cleared, so a failed run
 * leaves a usable index; a failure in step 3 is reported and repaired by the
 * next reconcile.
 */
class FullRebuilder(
    private val store: ContextStore,
    private val lookup: Lookup,
    private val indexer: Indexer,
    private val logger: TansekiLogger = TansekiLogger.Noop
) {
    private val projectionWorker = ProjectionWorker(store, indexer, logger)
    private val edgeDeriver = EdgeDeriver(store)

    @Synchronized
    fun rebuild(onProgress: (RebuildProgress) -> Unit = {}): FullRebuildReport {
        val refs =
            try {
                store.list()
            } catch (error: Exception) {
                return failedReport(
                    totalDocuments = 0,
                    indexedDocuments = 0,
                    readFailures = 0,
                    indexFailures = 0,
                    projectionReport = ProjectionWorkerReport(),
                    failures = listOf(errorMessage("listing documents", error)),
                    onProgress = onProgress
                )
            }

        val documents = ArrayList<Document>(refs.size)
        val failures = mutableListOf<String>()
        var readFailures = 0
        refs.forEachIndexed { index, ref ->
            try {
                val document = store.read(ref.id) ?: error("document is unreadable")
                documents += document
            } catch (error: Exception) {
                readFailures++
                failures += errorMessage(ref.id.value, error)
            }
            onProgress(RebuildProgress(RebuildPhase.LOADING, index + 1, refs.size, ref.id.value))
        }

        if (failures.isNotEmpty()) {
            return failedReport(
                totalDocuments = refs.size,
                indexedDocuments = 0,
                readFailures = readFailures,
                indexFailures = 0,
                projectionReport = ProjectionWorkerReport(),
                failures = failures,
                onProgress = onProgress
            )
        }

        val staged: Map<DocId, List<Edge>>
        when (val staging = stageEdges(documents, onProgress)) {
            is Staging.Failed -> {
                return failedReport(
                    totalDocuments = refs.size,
                    indexedDocuments = 0,
                    readFailures = 0,
                    indexFailures = 1,
                    projectionReport = ProjectionWorkerReport(),
                    failures = listOf(staging.message),
                    onProgress = onProgress
                )
            }

            is Staging.Ready -> {
                staged = staging.edges
                logger.info(
                    "staged the canonical graph",
                    mapOf("documents" to staged.size, "rewrites" to staging.rewritten)
                )
            }
        }

        var indexedDocuments = 0
        var indexFailures = 0
        var projectionCompleted = 0
        documents.forEachIndexed { index, document ->
            try {
                indexer.index(document)
                indexedDocuments++
                projectionCompleted += projectionWorker.completeCurrent(document.id, document.contentHash)
            } catch (error: Exception) {
                indexFailures++
                failures += errorMessage(document.id.value, error)
            }
            onProgress(RebuildProgress(RebuildPhase.INDEXING, index + 1, documents.size, document.id.value))
        }

        if (indexFailures > 0) {
            return failedReport(
                totalDocuments = refs.size,
                indexedDocuments = indexedDocuments,
                readFailures = 0,
                indexFailures = indexFailures,
                projectionReport = ProjectionWorkerReport(completed = projectionCompleted),
                failures = failures,
                onProgress = onProgress
            )
        }

        onProgress(RebuildProgress(RebuildPhase.CLEARING, 0, documents.size))
        try {
            rewriteGraph(staged)
        } catch (error: Exception) {
            return failedReport(
                totalDocuments = refs.size,
                indexedDocuments = indexedDocuments,
                readFailures = 0,
                indexFailures = 0,
                projectionReport = ProjectionWorkerReport(completed = projectionCompleted),
                failures = listOf(errorMessage("rewriting the canonical graph", error)),
                onProgress = onProgress
            )
        }

        var removed = 0
        try {
            removed = prune(documents.mapTo(mutableSetOf()) { it.id })
        } catch (error: Exception) {
            return failedReport(
                totalDocuments = refs.size,
                indexedDocuments = indexedDocuments,
                readFailures = 0,
                indexFailures = 0,
                projectionReport = ProjectionWorkerReport(completed = projectionCompleted),
                failures = listOf(errorMessage("pruning stale index entries", error)),
                onProgress = onProgress
            )
        }

        onProgress(RebuildProgress(RebuildPhase.OUTBOX, 0, documents.size))
        val projectionReport = drainProjectionOutbox()
        val allFailures =
            failures + projectionReport.failures()
        return if (allFailures.isEmpty() && projectionReport.failed == 0) {
            val report =
                FullRebuildReport(
                    totalDocuments = refs.size,
                    indexedDocuments = indexedDocuments,
                    readFailures = 0,
                    indexFailures = 0,
                    projectionAttempted = projectionReport.attempted,
                    projectionCompleted = projectionCompleted + projectionReport.completed,
                    projectionFailed = 0,
                    removedDocuments = removed
                )
            onProgress(RebuildProgress(RebuildPhase.COMPLETE, refs.size, refs.size))
            report
        } else {
            failedReport(
                totalDocuments = refs.size,
                indexedDocuments = indexedDocuments,
                readFailures = 0,
                indexFailures = 0,
                projectionReport = projectionReport.copy(completed = projectionCompleted + projectionReport.completed),
                failures = allFailures,
                onProgress = onProgress,
                removedDocuments = removed
            )
        }
    }

    /**
     * Derives every document's edges before a single edge is written, and reads
     * the canonical edge set each document currently holds. Staging is pure
     * reads, so a document that cannot be derived — or a store whose graph
     * cannot be read back — fails the run while the previous index and graph are
     * still intact, instead of leaving a half-rewritten graph behind.
     */
    private fun stageEdges(
        documents: List<Document>,
        onProgress: (RebuildProgress) -> Unit
    ): Staging {
        val staged = LinkedHashMap<DocId, List<Edge>>(documents.size)
        var rewritten = 0
        documents.forEachIndexed { index, document ->
            onProgress(RebuildProgress(RebuildPhase.LOADING, index + 1, documents.size, document.id.value))
            try {
                val current = store.neighbors(document.id)
                val derived = edgeDeriver.derive(document)
                if (current.toSet() != derived.toSet()) rewritten++
                staged[document.id] = derived
            } catch (error: Exception) {
                logger.warn(
                    "staging the canonical graph failed",
                    mapOf("doc" to document.id.value),
                    error
                )
                return Staging.Failed(errorMessage(document.id.value, error))
            }
        }
        return Staging.Ready(staged, rewritten)
    }

    /**
     * Swaps the canonical graph for the staged edge set. Reached only after every
     * document is indexed, so the previous graph survives any earlier failure; a
     * store that cannot clear its edges fails the run loudly instead of
     * silently keeping a graph that is not `f(store)`.
     */
    private fun rewriteGraph(staged: Map<DocId, List<Edge>>) {
        store.clearEdges()
        staged.forEach { (id, edges) -> store.replaceEdges(id, edges) }
    }

    /**
     * Drops graph and index entries the store no longer contains. Only reached
     * after every document has been indexed successfully, so pruning cannot
     * shrink a half-built index.
     */
    private fun prune(known: Set<DocId>): Int {
        val stale = (lookup.indexedIds().orEmpty() - known).toList().sortedBy { it.value }
        stale.forEach(indexer::remove)
        return stale.size
    }

    private fun drainProjectionOutbox(): ProjectionWorkerReport {
        var attempted = 0
        var completed = 0
        var failed = 0
        while (true) {
            val report = projectionWorker.drain()
            attempted += report.attempted
            completed += report.completed
            failed += report.failed
            if (report.attempted == 0 || report.failed > 0 || report.completed == 0) {
                return ProjectionWorkerReport(attempted, completed, failed)
            }
        }
    }

    private fun failedReport(
        totalDocuments: Int,
        indexedDocuments: Int,
        readFailures: Int,
        indexFailures: Int,
        projectionReport: ProjectionWorkerReport,
        failures: List<String>,
        onProgress: (RebuildProgress) -> Unit,
        removedDocuments: Int = 0
    ): FullRebuildReport {
        onProgress(RebuildProgress(RebuildPhase.FAILED, indexedDocuments, totalDocuments))
        return FullRebuildReport(
            totalDocuments = totalDocuments,
            indexedDocuments = indexedDocuments,
            readFailures = readFailures,
            indexFailures = indexFailures,
            projectionAttempted = projectionReport.attempted,
            projectionCompleted = projectionReport.completed,
            projectionFailed = projectionReport.failed,
            removedDocuments = removedDocuments,
            failures = failures
        )
    }

    private fun errorMessage(scope: String, error: Exception): String =
        "$scope: ${error.message ?: error::class.simpleName ?: "unknown error"}"

    private fun ProjectionWorkerReport.failures(): List<String> =
        if (failed == 0) emptyList() else listOf("projection outbox failed for $failed operation(s)")

    private sealed interface Staging {
        val message: String?

        data class Ready(
            val edges: Map<DocId, List<Edge>>,
            /** Documents whose canonical edge set differs from the staged one. */
            val rewritten: Int
        ) : Staging {
            override val message: String? get() = null
        }

        data class Failed(override val message: String) : Staging
    }
}
