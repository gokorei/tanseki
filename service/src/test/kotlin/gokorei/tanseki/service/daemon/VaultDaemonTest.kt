package gokorei.tanseki.service.daemon

import gokorei.tanseki.composition.IpcClient
import gokorei.tanseki.core.application.Indexer
import gokorei.tanseki.core.application.PendingOverlay
import gokorei.tanseki.core.application.Reconciler
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.FsEvent
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.LogLevel
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.core.ports.Watcher
import gokorei.tanseki.service.logging.JsonStructuredLogger
import gokorei.tanseki.service.logging.Redaction
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class VaultDaemonTest {
    @TempDir
    lateinit var tmp: Path

    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private class ControllableWatcher : Watcher {
        val flow = MutableSharedFlow<FsEvent>(extraBufferCapacity = 32)

        override fun events(vault: VaultPath): Flow<FsEvent> = flow
    }

    private class RestartingWatcher : Watcher {
        val attempts = AtomicInteger(0)

        override fun events(vault: VaultPath): Flow<FsEvent> =
            flow {
                if (attempts.incrementAndGet() == 1) {
                    error("watcher stopped unexpectedly")
                }
                awaitCancellation()
            }
    }

    private class FailingContextStore(private val delegate: ContextStore) : ContextStore by delegate {
        override fun list(collection: Collection?): List<DocRef> =
            throw IllegalStateException("secret dependency failure")
    }

    private class ReadFailingContextStore(
        private val delegate: ContextStore,
        private val unreadable: DocId
    ) : ContextStore by delegate {
        override fun read(id: DocId): Document? =
            if (id == unreadable) throw IllegalStateException("read failed") else delegate.read(id)
    }

    private class CountingLookup : Lookup {
        val indexed = CopyOnWriteArrayList<DocId>()
        val removed = CopyOnWriteArrayList<DocId>()
        var failNextIndex = false
        var failIndexCount = 0

        override fun index(doc: Document, edges: List<Edge>) {
            if (failNextIndex || failIndexCount > 0) {
                failNextIndex = false
                if (failIndexCount > 0) failIndexCount--
                error("lookup unavailable")
            }

            indexed += doc.id
        }

        override fun remove(id: DocId) {
            removed += id
        }

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    private fun doc(id: String, content: String = "x") =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = "hash-$content",
            revision = RevisionId("rev"),
            updatedAt = now
        )

    private fun socketPath(): Path = Path.of("/tmp", "tanseki-${UUID.randomUUID().toString().take(8)}.sock")

    private fun daemon(
        store: ContextStore,
        lookup: Lookup,
        watcher: Watcher,
        socket: Path,
        lock: Path = tmp.resolve(".tanseki/daemon.lock"),
        overlay: PendingOverlay = PendingOverlay(),
        config: DaemonConfig =
            DaemonConfig(
                vault = VaultPath(tmp.toString()),
                indexDir = tmp.resolve("index"),
                socketPath = socket,
                lockFile = lock
            ),
        logger: TansekiLogger = TansekiLogger.Noop
    ): VaultDaemon {
        val metrics = OperationalMetrics(store, overlay, config, lookup)
        val indexer = Indexer(store, lookup, observer = metrics)
        val reconciler = Reconciler(store, lookup, indexer, overlay)

        return VaultDaemon(
            config = config,
            store = store,
            lookup = lookup,
            watcher = watcher,
            indexer = indexer,
            reconciler = reconciler,
            overlay = overlay,
            metricsState = metrics,
            logger = logger
        )
    }

    @Test
    fun `serves health over the ipc socket`() {
        val socket = socketPath()
        val daemon = daemon(InMemoryContextStore(), CountingLookup(), ControllableWatcher(), socket)
        daemon.start()
        try {
            val response = IpcClient.request(socket, """{"op":"health"}""")
            val json = Json.parseToJsonElement(response).jsonObject
            assertTrue(json.containsKey("pendingSize"))
            assertTrue(json.containsKey("indexingLag"))
            assertTrue(json.containsKey("indexedDocuments"))
            assertTrue(json.containsKey("failureReasons"))
            assertEquals("ok", daemon.liveness().status)
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `close is idempotent under concurrent callers and never leaks the lock`() {
        val socket = socketPath()
        val daemon = daemon(InMemoryContextStore(), CountingLookup(), ControllableWatcher(), socket)
        daemon.start()

        // A shutdown hook and an owner thread can both call close(); the teardown
        // must run once, and the rest must return without cancelling/joining
        // twice or racing to release the vault lock.
        val failures = CopyOnWriteArrayList<Throwable>()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val calls =
                (1..8).map {
                    executor.submit {
                        runCatching { daemon.close() }.onFailure { failures += it }
                    }
                }
            calls.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertTrue(failures.isEmpty(), failures.toString())
        // The lock must be free again: a follow-up daemon can take it immediately.
        VaultLock(tmp.resolve(".tanseki/daemon.lock")).acquire().release()
    }

    @Test
    fun `ipc request ids correlate successful and rejected operations`() {
        val socket = socketPath()
        val logs = CopyOnWriteArrayList<String>()
        val daemon =
            daemon(
                InMemoryContextStore(),
                CountingLookup(),
                ControllableWatcher(),
                socket,
                logger = JsonStructuredLogger(logs::add, LogLevel.DEBUG)
            )
        daemon.start()
        try {
            val success =
                Json
                    .parseToJsonElement(
                        IpcClient.request(socket, """{"op":"health","requestId":"ipc-123"}""")
                    ).jsonObject
            assertEquals("ipc-123", success["requestId"]!!.jsonPrimitive.content)
            val failed =
                Json
                    .parseToJsonElement(
                        IpcClient.request(socket, """{"op":"unknown","requestId":"ipc-456"}""")
                    ).jsonObject
            assertEquals("ipc-456", failed["requestId"]!!.jsonPrimitive.content)
            val records = logs.map { Json.parseToJsonElement(it).jsonObject }
            assertTrue(
                records.any {
                    it["message"]?.jsonPrimitive?.content == "ipc request completed" &&
                        it["correlationId"]?.jsonPrimitive?.content == "ipc-123"
                }
            )
            assertTrue(
                records.any {
                    it["message"]?.jsonPrimitive?.content == "ipc request rejected" &&
                        it["correlationId"]?.jsonPrimitive?.content == "ipc-456"
                }
            )
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `single writer is enforced by the lock file`() {
        val store = InMemoryContextStore()
        val first = daemon(store, CountingLookup(), ControllableWatcher(), socketPath())
        val second = daemon(store, CountingLookup(), ControllableWatcher(), socketPath())
        first.start()
        try {
            assertThrows(IllegalStateException::class.java) { second.start() }
        } finally {
            first.close()
        }
        // lock released: a new daemon can start
        val third = daemon(store, CountingLookup(), ControllableWatcher(), socketPath())
        third.start()
        third.close()
    }

    @Test
    fun `watcher events drive the indexer`() =
        runBlocking {
            val store = InMemoryContextStore()
            val lookup = CountingLookup()
            val watcher = ControllableWatcher()
            val daemon = daemon(store, lookup, watcher, socketPath())
            daemon.start()
            try {
                store.write(doc("notes/new"), "m", "t")
                delay(200)
                watcher.flow.tryEmit(FsEvent.Created("$tmp/notes/new.md", now))

                withTimeout(5_000) {
                    while (lookup.indexed.none { it == DocId("notes/new") }) delay(20)
                }
                assertTrue(lookup.indexed.contains(DocId("notes/new")))
            } finally {
                daemon.close()
            }
        }

    @Test
    fun `daemon logs identify a vault and a document without echoing them`() =
        runBlocking {
            val store = InMemoryContextStore()
            val watcher = ControllableWatcher()
            val logs = CopyOnWriteArrayList<String>()
            val socket = socketPath()
            val daemon =
                daemon(
                    store,
                    CountingLookup(),
                    watcher,
                    socket,
                    logger = JsonStructuredLogger(logs::add, LogLevel.DEBUG)
                )
            val id = "notes/redacted"
            store.write(doc(id), "m", "t")
            daemon.start()
            try {
                delay(200)
                watcher.flow.tryEmit(FsEvent.Created("$tmp/$id.md", now))
                withTimeout(5_000) {
                    while (logs.none { it.contains("indexed change") }) delay(20)
                }
            } finally {
                daemon.close()
            }

            assertTrue(logs.isNotEmpty())
            assertTrue(logs.none { tmp.toString() in it }, "a vault path reached a log record: ${logs.firstOrNull()}")
            assertTrue(logs.none { socket.toString() in it }, "a socket path reached a log record")
            assertTrue(logs.none { "notes/redacted" in it }, "a document id reached a log record")
            val indexed = logs.first { it.contains("indexed change") }
            val doc =
                Json
                    .parseToJsonElement(indexed)
                    .jsonObject["doc"]!!
                    .jsonPrimitive.content
            assertTrue(doc.isNotEmpty())
            assertFalse("notes" in doc, indexed)
            assertEquals(doc, Redaction.fingerprint(id))
        }

    @Test
    fun `shared overlay remains pending until reconciliation completes projection`() =
        runBlocking {
            val store = InMemoryContextStore()
            val lookup = CountingLookup()
            val watcher = ControllableWatcher()
            val overlay = PendingOverlay()
            val daemon =
                daemon(
                    store,
                    lookup,
                    watcher,
                    socketPath(),
                    overlay = overlay,
                    config =
                        DaemonConfig(
                            vault = VaultPath(tmp.toString()),
                            indexDir = tmp.resolve("index"),
                            socketPath = Path.of("/tmp", "tanseki-${UUID.randomUUID()}.sock"),
                            eventRetryLimit = 0,
                            reconcileInterval = 20.milliseconds
                        )
                )
            daemon.start()
            try {
                store.write(doc("watch", "content"), "m", "t")
                withTimeout(1_000) {
                    while (watcher.flow.subscriptionCount.value == 0) delay(10)
                }
                lookup.failNextIndex = true
                watcher.flow.tryEmit(FsEvent.Modified("$tmp/watch.md", now))

                withTimeout(1_000) {
                    while (overlay.pendingSize() != 1) delay(10)
                }
                assertEquals(1L, overlay.lag())
                assertEquals(
                    1,
                    daemon.metrics
                        .metrics()
                        .pendingOverlay.size
                )

                withTimeout(5_000) {
                    while (overlay.pendingSize() != 0) delay(10)
                }
                assertEquals(0L, overlay.lag())
            } finally {
                daemon.close()
            }
        }

    @Test
    fun `overflow reconciles the current projection`() =
        runBlocking {
            val store = InMemoryContextStore()
            val lookup = CountingLookup()
            val watcher = ControllableWatcher()
            store.write(doc("overflow", "initial"), "m", "t")
            val daemon = daemon(store, lookup, watcher, socketPath())
            daemon.start()
            try {
                store.write(doc("overflow", "updated"), "m", "t")
                delay(100)
                watcher.flow.tryEmit(FsEvent.Overflow(now))

                withTimeout(5_000) {
                    while (lookup.indexed.count { it == DocId("overflow") } < 2) delay(20)
                }
                assertTrue(lookup.indexed.count { it == DocId("overflow") } >= 2)
            } finally {
                daemon.close()
            }
        }

    @Test
    fun `failed projection exposes degraded backlog and actual current state`() {
        val store = InMemoryContextStore()
        val lookup = CountingLookup()
        val overlay = PendingOverlay()
        val document = doc("backlog", "content")
        store.write(document, "m", "t")
        assertEquals(1, store.projectionOperations().backlog().pending)
        overlay.put(document)

        lookup.failNextIndex = true
        lookup.failIndexCount = 1
        val daemon =

            daemon(
                store,
                lookup,
                ControllableWatcher(),
                socketPath(),
                overlay = overlay,
                config =
                    DaemonConfig(
                        vault = VaultPath(tmp.toString()),
                        indexDir = tmp.resolve("index"),
                        socketPath = Path.of("/tmp", "tanseki-${UUID.randomUUID()}.sock"),
                        eventRetryLimit = 0,
                        reconcileInterval = Duration.ZERO
                    )
            )

        daemon.start()
        try {
            val metrics = daemon.metrics.metrics()
            assertEquals(1, metrics.currentDocuments)
            assertEquals("degraded", metrics.projection.state)

            assertEquals(1, metrics.reconcile.degradedRuns)
            assertEquals(1, metrics.indexing.errors)
            assertTrue(metrics.degraded)
            assertEquals("degraded", daemon.readiness().status)
            assertTrue(daemon.readiness().failureReasons.contains("projection_failed"))
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `metrics read durable projection backlog from the store`() {
        val store = InMemoryContextStore()
        store.write(doc("backlog", "content"), "m", "t")
        val metrics =
            OperationalMetrics(
                store,
                PendingOverlay(),
                DaemonConfig(
                    vault = VaultPath(tmp.toString()),
                    indexDir = tmp.resolve("index"),
                    socketPath = Path.of("/tmp", "tanseki-${UUID.randomUUID()}.sock")
                )
            )

        assertEquals(1, metrics.metrics().outbox.pending)
    }

    @Test
    fun `metrics report a dead-lettered outbox entry and degrade on it`() {
        val store = InMemoryContextStore()
        val document = doc("poison", "content")
        store.write(document, "m", "t")
        val outbox = requireNotNull(store.projectionOperations())
        val operation = outbox.pending(10).single { it.documentId == document.id }
        repeat(gokorei.tanseki.core.ports.DEFAULT_PROJECTION_MAX_ATTEMPTS) {
            outbox.recordFailure(operation)
        }
        outbox.deadLetter(operation)
        val metrics =
            OperationalMetrics(
                store,
                PendingOverlay(),
                DaemonConfig(
                    vault = VaultPath(tmp.toString()),
                    indexDir = tmp.resolve("index"),
                    socketPath = Path.of("/tmp", "tanseki-${UUID.randomUUID()}.sock")
                )
            )

        val snapshot = metrics.metrics()

        // Counted apart from `pending`: nothing is going to deliver it, and a
        // backlog that reports 0 pending for it is indistinguishable from "done".
        assertEquals(1, snapshot.outbox.deadLettered)
        assertEquals(0, snapshot.outbox.pending)
        assertEquals(0, snapshot.outbox.stuck)
        assertTrue(snapshot.failureReasons.contains("projection_dead_lettered"), snapshot.failureReasons.toString())
        assertFalse(snapshot.ready)
        assertEquals(1, metrics.health().deadLetteredProjections)
    }

    @Test
    fun `metrics count expired unprojected entries without naming their documents`() {
        var timestamp = Instant.fromEpochSeconds(1_700_000_000)
        // A single-entry overlay evicts the first document into the unprojected tail.
        val overlay = PendingOverlay(maxEntries = 1, unprojectedTtl = 1.minutes, now = { timestamp })
        overlay.put(doc("secret/target", "content"))
        overlay.put(doc("filler", "content"))
        val metrics =
            OperationalMetrics(
                InMemoryContextStore(),
                overlay,
                DaemonConfig(
                    vault = VaultPath(tmp.toString()),
                    indexDir = tmp.resolve("index"),
                    socketPath = Path.of("/tmp", "tanseki-${UUID.randomUUID()}.sock")
                ),
                clock = { timestamp }
            )

        assertEquals(0, metrics.metrics().pendingOverlay.expiredUnprojected)
        timestamp += 2.minutes
        val snapshot = metrics.metrics()

        assertEquals(1, snapshot.pendingOverlay.expiredUnprojected)
        // The count is published; the id is not, or a credential scoped to another
        // collection would learn that the document exists.
        val published = Json.encodeToString(snapshot.pendingOverlay)
        assertFalse(published.contains("secret/target"), published)
    }

    @Test
    fun `liveness stays process-only when a dependency is unavailable`() {
        val store = FailingContextStore(InMemoryContextStore())
        val daemon = daemon(store, CountingLookup(), ControllableWatcher(), socketPath())
        daemon.start()
        try {
            val readiness = daemon.readiness()
            assertEquals("ok", daemon.liveness().status)
            assertEquals("unavailable", readiness.status)
            assertFalse(readiness.ready)
            assertTrue(readiness.failureReasons.contains("context_store_unavailable"))
            assertTrue(readiness.failureReasons.none { it.contains("secret") })
        } finally {
            daemon.close()
        }
    }

    @Test
    fun `transient event failure is retried and cleared from health`() =
        runBlocking {
            val store = InMemoryContextStore()
            val lookup = CountingLookup()
            val watcher = ControllableWatcher()
            val daemon =
                daemon(
                    store,
                    lookup,
                    watcher,
                    socketPath(),
                    config =
                        DaemonConfig(
                            vault = VaultPath(tmp.toString()),
                            indexDir = tmp.resolve("index"),
                            socketPath = Path.of("/tmp", "tanseki-${UUID.randomUUID()}.sock"),
                            eventRetryLimit = 2,
                            eventRetryBackoff = 5.milliseconds,
                            reconcileInterval = Duration.ZERO
                        )
                )
            store.write(doc("retry", "initial"), "m", "t")
            daemon.start()
            try {
                withTimeout(1_000) {
                    while (watcher.flow.subscriptionCount.value == 0) delay(10)
                }
                lookup.failNextIndex = true
                watcher.flow.tryEmit(FsEvent.Modified("$tmp/retry.md", now))

                withTimeout(5_000) {
                    while (lookup.indexed.count { it == DocId("retry") } < 2) delay(20)
                }
                assertEquals(0, daemon.health().failedEvents)
                assertTrue(!daemon.health().degraded)
            } finally {
                daemon.close()
            }
        }

    @Test
    fun `watcher delete removes canonical incoming and outgoing edges`() =
        runBlocking {
            val store = InMemoryContextStore()
            val lookup = CountingLookup()
            val watcher = ControllableWatcher()
            store.write(doc("source", "See [[target]]"), "m", "t")
            store.write(doc("target", "target"), "m", "t")
            val daemon = daemon(store, lookup, watcher, socketPath())
            daemon.start()
            try {
                withTimeout(1_000) {
                    while (store.neighbors(DocId("source")).isEmpty()) delay(10)
                }
                store.delete(DocId("target"), "gone", "t")
                watcher.flow.tryEmit(FsEvent.Deleted("$tmp/target.md", now))

                withTimeout(5_000) {
                    while (DocId("target") !in lookup.removed) delay(20)
                }
                assertTrue(store.neighbors(DocId("source")).isEmpty())
                assertTrue(store.neighbors(DocId("target")).isEmpty())
            } finally {
                daemon.close()
            }
        }

    @Test
    fun `a create event for a document that already disappeared is applied as a delete`() =
        runBlocking {
            val store = InMemoryContextStore()
            val lookup = CountingLookup()
            val watcher = ControllableWatcher()
            val daemon = daemon(store, lookup, watcher, socketPath())
            daemon.start()
            try {
                watcher.flow.tryEmit(FsEvent.Created("$tmp/vanished.md", now))

                withTimeout(5_000) {
                    while (DocId("vanished") !in lookup.removed) delay(20)
                }
                assertTrue(lookup.indexed.none { it == DocId("vanished") })
                assertEquals(0, daemon.health().failedEvents)
                assertTrue(!daemon.health().degraded)
            } finally {
                daemon.close()
            }
        }

    @Test
    fun `relative watcher event paths are resolved against the vault`() =
        runBlocking {
            val store = InMemoryContextStore()
            val lookup = CountingLookup()
            val watcher = ControllableWatcher()
            store.write(doc("notes/relative", "body"), "m", "t")
            val daemon = daemon(store, lookup, watcher, socketPath())
            daemon.start()
            try {
                watcher.flow.tryEmit(FsEvent.Created("notes/relative.md", now))

                withTimeout(5_000) {
                    while (lookup.indexed.none { it == DocId("notes/relative") }) delay(20)
                }
                assertTrue(lookup.indexed.contains(DocId("notes/relative")))
            } finally {
                daemon.close()
            }
        }

    @Test
    fun `exhausted event retries are visible in health`() =
        runBlocking {
            val store = ReadFailingContextStore(InMemoryContextStore(), DocId("unreadable"))
            val lookup = CountingLookup()
            val watcher = ControllableWatcher()
            val socket = Path.of("/tmp", "tanseki-${UUID.randomUUID()}.sock")
            val daemon =
                daemon(
                    store,
                    lookup,
                    watcher,
                    socket,
                    config =
                        DaemonConfig(
                            vault = VaultPath(tmp.toString()),
                            indexDir = tmp.resolve("index"),
                            socketPath = socket,
                            eventRetryLimit = 0,
                            reconcileInterval = Duration.ZERO
                        )
                )
            store.write(doc("unreadable", "body"), "m", "t")
            daemon.start()
            try {
                delay(100)
                watcher.flow.tryEmit(FsEvent.Modified("$tmp/unreadable.md", now))

                withTimeout(5_000) {
                    while (!daemon.health().degraded) delay(20)
                }
                assertTrue(daemon.health().degraded)
                assertTrue(daemon.health().failedEvents > 0)
            } finally {
                daemon.close()
            }
        }

    @Test
    fun `collector restarts after an unexpected failure`() =
        runBlocking {
            val watcher = RestartingWatcher()
            val daemon =
                daemon(
                    InMemoryContextStore(),
                    CountingLookup(),
                    watcher,
                    socketPath(),
                    config =
                        DaemonConfig(
                            vault = VaultPath(tmp.toString()),
                            indexDir = tmp.resolve("index"),
                            socketPath = Path.of("/tmp", "tanseki-${UUID.randomUUID()}.sock"),
                            collectorRestartDelay = 5.milliseconds,
                            reconcileInterval = Duration.ZERO
                        )
                )
            daemon.start()
            try {
                withTimeout(5_000) {
                    while (watcher.attempts.get() < 2) delay(10)
                }
                assertTrue(watcher.attempts.get() >= 2)
                assertTrue(
                    daemon.metrics
                        .metrics()
                        .watcher.restarts >= 1
                )
            } finally {
                daemon.close()
            }
        }

    @Test
    fun `periodic reconciliation repairs a missed delete`() =
        runBlocking {
            val store = InMemoryContextStore()
            val lookup = CountingLookup()
            store.write(doc("delete", "content"), "m", "t")
            val socket = Path.of("/tmp", "tanseki-${UUID.randomUUID()}.sock")
            val daemon =
                daemon(
                    store,
                    lookup,
                    ControllableWatcher(),
                    socket,
                    config =
                        DaemonConfig(
                            vault = VaultPath(tmp.toString()),
                            indexDir = tmp.resolve("index"),
                            socketPath = socket,
                            reconcileInterval = 20.milliseconds
                        )
                )
            daemon.start()
            try {
                store.delete(DocId("delete"), "gone", "t")

                withTimeout(5_000) {
                    while (DocId("delete") !in lookup.removed) delay(20)
                }
                assertEquals(listOf(DocId("delete")), lookup.removed)
            } finally {
                daemon.close()
            }
        }

    @Test
    fun `projection freshness becomes stale after the configured age`() {
        val store = InMemoryContextStore()
        val overlay = PendingOverlay()
        var timestamp = now
        val metrics =
            OperationalMetrics(
                store,
                overlay,
                DaemonConfig(
                    vault = VaultPath(tmp.toString()),
                    indexDir = tmp.resolve("index"),
                    socketPath = Path.of("/tmp", "tanseki-${UUID.randomUUID()}.sock"),
                    projectionStaleAfter = 1.seconds
                ),
                clock = { timestamp }
            )

        metrics.markProjectionCompleted()
        assertEquals("fresh", metrics.metrics().projection.state)
        timestamp += 2.seconds
        // Idle is not stale. This daemon has had no writes, so its projection is
        // accurate and there is nothing to catch up on. Reporting it stale made
        // `/v1/ready` return 503 for a healthy idle process, which restart-loops
        // any orchestrator using it as a readiness probe.
        val idle = metrics.metrics()
        assertEquals("fresh", idle.projection.state)
        assertTrue(idle.projection.idle, "an aged projection with no outstanding work is idle")
        // Precise: this harness has no watcher, so `watcher_not_configured` is
        // present and unrelated. The assertion is that *projection* staleness is
        // not among the reasons.
        assertFalse(
            idle.failureReasons.contains("projection_stale"),
            "age alone must not degrade the projection: ${idle.failureReasons}"
        )
    }

    /**
     * The complementary case: an aged projection with work still outstanding is
     * genuinely stale.
     *
     * Idle is not the same as caught up. The fix for the false positive must not
     * also make real lag invisible, so this pins the half where degraded is
     * correct.
     */
    @Test
    fun `an aged projection with outstanding work is still stale`() {
        val store = InMemoryContextStore()
        val overlay = PendingOverlay()
        var timestamp = now
        val metrics =
            OperationalMetrics(
                store,
                overlay,
                DaemonConfig(
                    vault = VaultPath(tmp.toString()),
                    indexDir = tmp.resolve("index"),
                    socketPath = Path.of("/tmp", "tanseki-${UUID.randomUUID()}.sock"),
                    projectionStaleAfter = 1.seconds
                ),
                clock = { timestamp }
            )

        metrics.markProjectionCompleted()
        overlay.put(doc("pending"))
        timestamp += 2.seconds

        val snapshot = metrics.metrics()
        assertEquals("stale", snapshot.projection.state)
        assertFalse(snapshot.projection.idle)
        assertTrue(
            snapshot.failureReasons.contains("projection_stale"),
            "work outstanding plus an aged projection is real lag: ${snapshot.failureReasons}"
        )
    }

    @Test
    fun `initial reconcile indexes existing documents`() {
        val store = InMemoryContextStore()
        val lookup = CountingLookup()
        store.write(doc("a"), "m", "t")
        store.write(doc("b"), "m", "t")

        val daemon = daemon(store, lookup, ControllableWatcher(), socketPath())
        daemon.start()
        try {
            val metrics = daemon.metrics.metrics()
            assertEquals(2, metrics.currentDocuments)
            assertEquals(0, metrics.outbox.pending)
            assertEquals("fresh", metrics.projection.state)
            assertEquals(1, metrics.reconcile.runs)
            assertTrue(metrics.indexing.completed >= 2)
            runBlocking {
                withTimeout(1_000) {
                    while (!daemon.readiness().ready) delay(10)
                }
            }
            assertTrue(daemon.readiness().ready)
        } finally {
            daemon.close()
        }
        assertTrue(Files.exists(tmp.resolve(".tanseki")))
    }
}
