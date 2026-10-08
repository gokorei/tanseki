package gokorei.tanseki.service.daemon

import gokorei.tanseki.core.application.Indexer
import gokorei.tanseki.core.application.PendingOverlay
import gokorei.tanseki.core.application.Reconciler
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.FsEvent
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.core.ports.Watcher
import gokorei.tanseki.service.api.MetricsRecorder
import gokorei.tanseki.service.api.ReadinessResponse
import gokorei.tanseki.service.api.requestIdOrGenerated
import gokorei.tanseki.service.logging.Redaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

data class Liveness(val status: String = "ok")

/**
 * The per-vault daemon: a composition root that owns the Context Store and
 * Lookup for a vault, watches the vault and drives the indexer, serves local
 * clients over IPC, and enforces single-writer via a lock file.
 */
class VaultDaemon(
    private val config: DaemonConfig,
    private val store: ContextStore,
    /** The Lookup this daemon owns; exposed for the reconciler and diagnostics. */
    val lookup: Lookup,
    private val watcher: Watcher,
    private val indexer: Indexer,
    private val reconciler: Reconciler,
    private val overlay: PendingOverlay,
    private val metricsState: OperationalMetrics,
    private val onSelfWrite: (String) -> Unit = {},
    /** Pre-acquired single-writer lock; acquired here when null. */
    lock: VaultLock? = null,
    private val logger: TansekiLogger = TansekiLogger.Noop
) : DaemonRuntime {
    override val metrics: MetricsRecorder
        get() = metricsState
    private val lock = lock ?: VaultLock(config.lockFile)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val projectionLock = Any()

    private var watchJob: Job? = null
    private var reconcileJob: Job? = null
    private var ipc: IpcServer? = null

    override fun start() {
        check(running.compareAndSet(false, true)) { "daemon already started" }
        metricsState.markWatcherStarting()
        try {
            lock.acquire()
            reconcileSafely("startup")
            watchJob = scope.launch { superviseWatcher() }
            startReconciliationLoop()
            ipc = IpcServer(config.socketPath, ::handle).also { it.start(scope) }
            logger.info(
                "daemon started",
                mapOf(
                    "vault" to Redaction.fingerprint(config.vault.value),
                    "socket" to Redaction.fileName(config.socketPath.toString())
                )
            )
        } catch (error: Exception) {
            running.set(false)
            metricsState.markWatcherStopped()
            watchJob?.cancel()
            reconcileJob?.cancel()
            runCatching { ipc?.close() }
            lock.release()
            throw error
        }
    }

    private suspend fun superviseWatcher() {
        while (currentCoroutineContext().isActive) {
            try {
                clearDegraded("watcher collector")
                clearDegraded("watcher collector ended")
                clearDegraded("watcher collector failure")
                metricsState.markWatcherRunning()
                watcher.events(config.vault).collect { event -> onEvent(event) }
                markDegraded("watcher collector ended")
                metricsState.markWatcherRecovering("collector ended")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                markDegraded("watcher collector failure")
                metricsState.markWatcherRecovering(error::class.simpleName ?: "collector failure")
                logger.error("watcher collector failed", error)
            }
            if (currentCoroutineContext().isActive) {
                delay(config.collectorRestartDelay)
            }
        }
    }

    private fun startReconciliationLoop() {
        if (config.reconcileInterval <= Duration.ZERO) return
        reconcileJob =
            scope.launch {
                while (currentCoroutineContext().isActive) {
                    delay(config.reconcileInterval)
                    if (currentCoroutineContext().isActive) {
                        reconcileSafely("periodic")
                    }
                }
            }
    }

    private suspend fun onEvent(event: FsEvent) {
        if (event is FsEvent.Overflow) {
            processWithRetry("overflow", "overflow") {
                check(reconcileSafely("overflow")) { "overflow reconciliation failed" }
            }
            return
        }
        val path =
            when (event) {
                is FsEvent.Created -> event.path
                is FsEvent.Modified -> event.path
                is FsEvent.Deleted -> event.path
                is FsEvent.Overflow -> return
            }
        val id = idFor(path) ?: return
        if (event is FsEvent.Deleted) {
            processWithRetry("delete", Redaction.fingerprint(id.value)) { processDelete(id) }
        } else {
            processWithRetry("change", Redaction.fingerprint(id.value)) { processChange(id) }
        }
    }

    private suspend fun processWithRetry(
        reason: String,
        doc: String,
        operation: suspend () -> Unit
    ) {
        var retries = 0
        while (true) {
            try {
                operation()
                clearDegraded(reason)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (retries >= config.eventRetryLimit) {
                    metricsState.recordEventFailure()
                    markDegraded(reason)
                    logger.error("watch event processing failed", error, mapOf("reason" to reason, "doc" to doc))
                    return
                }

                retries++
                delay(config.eventRetryBackoff)
            }
        }
    }

    /**
     * A create/modify event for a document the store no longer holds means the
     * file appeared and disappeared again between the event and this read. The
     * canonical store wins, so the event is applied as a delete instead of
     * failing the watcher.
     */
    private fun processChange(id: DocId) {
        if (store.read(id) == null) {
            processDelete(id)
            return
        }
        processIndex(id)
    }

    private fun processIndex(id: DocId) {
        synchronized(projectionLock) {
            val doc = checkNotNull(store.read(id)) { "document listed but unreadable: ${id.value}" }
            overlay.put(doc)
            indexer.index(doc)
            reconciler.completeCurrent(id, doc.contentHash)
            overlay.markProjected(id, doc.contentHash)
            metricsState.markProjectionCompleted()
            logger.debug("indexed change", mapOf("doc" to Redaction.fingerprint(id.value)))
        }
    }

    private fun processDelete(id: DocId) {
        synchronized(projectionLock) {
            overlay.remove(id)
            indexer.remove(id)
            overlay.markDeleted(id)
            metricsState.markProjectionCompleted()
        }
    }

    private fun reconcileSafely(trigger: String): Boolean =
        try {
            val report = synchronized(projectionLock) { reconciler.reconcile() }
            val successful = report.readFailures == 0 && report.projectionFailed == 0
            metricsState.recordReconcile(trigger, report, successful)
            if (successful) {
                clearDegraded("reconciliation")
                clearDegraded("overflow")
                clearEventDegraded()
                true
            } else {
                markDegraded("reconciliation")
                logger.warn(
                    "reconciliation degraded",
                    mapOf(
                        "trigger" to trigger,
                        "readFailures" to report.readFailures,
                        "projectionFailed" to report.projectionFailed
                    )
                )
                false
            }
        } catch (error: Exception) {
            metricsState.recordReconcileFailure(trigger, error)
            markDegraded("reconciliation")
            logger.error("reconciliation failed", error, mapOf("trigger" to trigger))
            false
        }

    private fun markDegraded(reason: String) {
        metricsState.markDegraded(reason)
    }

    private fun clearDegraded(reason: String) {
        metricsState.clearDegraded(reason)
    }

    private fun clearEventDegraded() {
        metricsState.clearEventDegraded()
    }

    fun health(): Health = metricsState.health()

    fun liveness(): Liveness = Liveness()

    fun readiness(): ReadinessResponse = metricsState.readiness()

    /** Write through the daemon, suppressing the resulting watcher event. */
    suspend fun write(
        doc: Document,
        message: String,
        author: String,
        ifRevision: RevisionId? = null
    ): Revision {
        onSelfWrite(doc.path)
        val revision = store.write(doc, message, author, ifRevision)
        val canonical = store.read(doc.id) ?: doc
        synchronized(projectionLock) {
            overlay.put(canonical)
            indexer.index(canonical)
            reconciler.completeCurrent(canonical.id, canonical.contentHash)
            overlay.markProjected(canonical.id, canonical.contentHash)
            metricsState.markProjectionCompleted()
        }

        return revision
    }

    private fun handle(request: String): String {
        val parsed =
            runCatching {
                Json.parseToJsonElement(request).jsonObject
            }.getOrNull()
        // The correlation id is caller-controlled and echoed back, so it is
        // bounded and regenerated rather than trusted.
        val requestId = requestIdOrGenerated(parsed?.get("requestId")?.jsonPrimitive?.contentOrNull)
        val op = parsed?.get("op")?.jsonPrimitive?.contentOrNull
        val parsedOp = IpcOp.parse(op)
        val requestLogger = logger.withCorrelationId(requestId)
        return try {
            when (parsedOp) {
                IpcOp.LIVENESS -> {
                    requestLogger.info(
                        "ipc request completed",
                        mapOf("operation" to op, "result" to "ok")
                    )
                    buildJsonObject {
                        put("requestId", requestId)
                        put("status", "ok")
                    }.toString()
                }

                IpcOp.HEALTH -> {
                    val response = readiness().copy(requestId = requestId)
                    requestLogger.info(
                        "ipc request completed",
                        mapOf("operation" to op, "result" to response.status)
                    )
                    readinessJson(response)
                }

                null -> {
                    requestLogger.warn(
                        "ipc request rejected",
                        mapOf("operation" to (op ?: "unknown"), "errorType" to "unknown_operation")
                    )
                    buildJsonObject {
                        put("requestId", requestId)
                        put("error", "unknown op")
                    }.toString()
                }
            }
        } catch (error: Exception) {
            requestLogger.error(
                "ipc request failed",
                fields = mapOf("operation" to (op ?: "unknown"), "errorType" to (error::class.simpleName ?: "error"))
            )
            buildJsonObject {
                put("requestId", requestId)
                put("error", "request_failed")
            }.toString()
        }
    }

    private fun readinessJson(response: ReadinessResponse): String {
        val fields =
            Json
                .encodeToJsonElement(ReadinessResponse.serializer(), response)
                .jsonObject
        return buildJsonObject {
            fields.forEach { (key, value) -> put(key, value) }
            put("pendingSize", response.pendingOverlay.size)
            put("indexingLag", response.pendingOverlay.lag)
            put("indexedDocuments", response.currentDocuments)
            put("unprojected", health().unprojected)
            put("stuckProjections", health().stuckProjections)
            put("corruptProjections", health().corruptProjections)
            put("deadLetteredProjections", health().deadLetteredProjections)
            put("expiredUnprojected", health().expiredUnprojected)
            put("failureReasons", Json.encodeToJsonElement(response.failureReasons))
        }.toString()
    }

    private fun idFor(path: String): DocId? = vaultDocId(config.vault.value, path)

    override fun close() {
        // One guard, not `running.compareAndSet` plus `lock.isHeld`: a second
        // caller arriving before the first has released the lock would pass that
        // older check and run the whole teardown again (cancelling twice, joining
        // twice, and then racing to release the lock).
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        watchJob?.cancel()
        reconcileJob?.cancel()
        runCatching { ipc?.close() }
        kotlinx.coroutines.runBlocking {
            watchJob?.join()
            reconcileJob?.join()
        }
        scope.cancel()
        metricsState.markWatcherStopped()
        lock.release()
        logger.info("daemon stopped", mapOf("vault" to Redaction.fingerprint(config.vault.value)))
    }
}
