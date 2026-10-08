package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.DEFAULT_PROJECTION_MAX_ATTEMPTS
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ProjectionWorkerTest {
    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun doc(id: String, content: String) =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = "hash-$content",
            revision = RevisionId("rev-$id"),
            updatedAt = now
        )

    private class CountingLookup : Lookup {
        var failNextIndex = false
        var indexCalls = 0
        val indexed = linkedMapOf<DocId, Document>()
        val indexedEdges = mutableMapOf<DocId, List<Edge>>()

        override fun index(doc: Document, edges: List<Edge>) {
            indexCalls++
            if (failNextIndex) {
                failNextIndex = false
                error("lookup unavailable")
            }
            indexed[doc.id] = doc
            indexedEdges[doc.id] = edges
        }

        override fun remove(id: DocId) {
            indexed.remove(id)
            indexedEdges.remove(id)
        }

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> =
            indexedEdges[id].orEmpty().filter { it.src == id && it.rel == rel }.map { it.dst }

        override fun rebuild(store: ContextStore) = Unit
    }

    private class FailingEdgeStore(private val delegate: InMemoryContextStore) : ContextStore by delegate {
        var failNextReplace = true
        var alwaysFailSrc: DocId? = null
        var replaceCalls = 0

        override fun replaceEdges(src: DocId, edges: List<Edge>) {
            replaceCalls++
            if (alwaysFailSrc == src || failNextReplace) {
                failNextReplace = false
                error("edge store unavailable")
            }
            delegate.replaceEdges(src, edges)
        }
    }

    private fun worker(store: ContextStore, lookup: Lookup, maxAttempts: Int = 5): ProjectionWorker =
        ProjectionWorker(store, Indexer(store, lookup), maxAttempts = maxAttempts)

    private fun operations(store: ContextStore) = requireNotNull(store.projectionOperations())

    @Test
    fun `a failing operation no longer blocks the operations behind it`() {
        val store = FailingEdgeStore(InMemoryContextStore())
        val lookup = CountingLookup()
        store.write(doc("poison", "See [[source]]"), "m", "tester")
        store.write(doc("source", "See [[poison]]"), "m", "tester")
        store.write(doc("tail", "tail"), "m", "tester")

        val report = worker(store, lookup).drain()

        assertEquals(1, report.failed)
        assertEquals(2, report.completed)
        assertEquals(3, report.attempted)
        assertTrue(DocId("tail") in lookup.indexed)
        assertEquals(1, operations(store).backlog().pending)
        assertEquals(1, operations(store).backlog().stuck)
    }

    @Test
    fun `an operation is dead-lettered once its attempts are exhausted`() {
        val store = FailingEdgeStore(InMemoryContextStore()).apply { alwaysFailSrc = DocId("poison") }
        val lookup = CountingLookup()
        store.write(doc("poison", "See [[source]]"), "m", "tester")
        store.write(doc("source", "source"), "m", "tester")
        val projectionWorker = worker(store, lookup, maxAttempts = 2)

        val first = projectionWorker.drain()
        val second = projectionWorker.drain()

        assertEquals(1, first.failed)
        assertTrue(first.deadLettered.isEmpty())
        assertEquals(1, second.deadLettered.size)
        assertEquals(listOf(DocId("poison")), operations(store).pending(10).map { it.documentId })
        assertEquals(0, operations(store).backlog().stuck)
        assertEquals(1, operations(store).backlog().deadLettered)

        val third = projectionWorker.drain()

        assertEquals(0, third.attempted)
        assertEquals(1, operations(store).backlog().deadLettered)
    }

    @Test
    fun `a dead-lettered operation does not stall the operations behind it`() {
        val store = FailingEdgeStore(InMemoryContextStore())
        val lookup = CountingLookup()
        store.write(doc("poison", "See [[source]]"), "m", "tester")
        store.write(doc("source", "source"), "m", "tester")
        val projectionWorker = worker(store, lookup, maxAttempts = 1)

        val first = projectionWorker.drain()
        val second = projectionWorker.drain()

        assertEquals(1, first.deadLettered.size)
        assertEquals(0, second.attempted)
        assertEquals(setOf(DocId("source")), lookup.indexed.keys)
    }

    @Test
    fun `dead lettered operations are reported by reconciliation`() {
        val store = FailingEdgeStore(InMemoryContextStore())
        val lookup = CountingLookup()
        store.write(doc("poison", "See [[source]]"), "m", "tester")
        store.write(doc("source", "source"), "m", "tester")
        val indexer = Indexer(store, lookup)
        val reconciler = Reconciler(store, lookup, indexer)
        val worker = ProjectionWorker(store, indexer)

        val degraded = reconciler.reconcile()

        assertEquals(1, degraded.projectionFailed)
        assertEquals(1, operations(store).pending(10).count { it.documentId == DocId("poison") })

        repeat(DEFAULT_PROJECTION_MAX_ATTEMPTS) { worker.drain() }
        val recovered = reconciler.reconcile()

        assertTrue(operations(store).pending(10).none { it.documentId == DocId("poison") })
        assertEquals(0, recovered.projectionFailed)
        assertTrue(DocId("source") in lookup.indexed)
    }

    @Test
    fun `edge failure leaves the operation pending and a retry repairs it`() {
        val store = FailingEdgeStore(InMemoryContextStore())
        val lookup = CountingLookup()
        store.write(doc("target", "target"), "m", "tester")
        store.write(doc("source", "See [[target]]"), "m", "tester")

        val first = worker(store, lookup).drain()

        assertEquals(1, first.failed)
        assertEquals(1, operations(store).backlog().pending)
        assertTrue(first.deadLettered.isEmpty())
        assertEquals(setOf(DocId("target")), lookup.indexed.keys)

        val second = worker(store, lookup).drain()

        assertEquals(1, second.completed)
        assertEquals(0, second.failed)
        assertEquals(1, store.neighbors(DocId("source")).size)
        assertEquals(2, lookup.indexed.size)

        val third = worker(store, lookup).drain()

        assertEquals(0, third.attempted)
        assertEquals(1, store.neighbors(DocId("source")).size)
        assertEquals(2, lookup.indexed.size)
    }

    @Test
    fun `target create and delete repair unresolved source edges`() {
        val store = InMemoryContextStore()
        val lookup = CountingLookup()
        store.write(doc("source", "See [[target]]"), "m", "tester")
        val projectionWorker = worker(store, lookup)

        projectionWorker.drain()
        assertTrue(store.neighbors(DocId("source")).isEmpty())

        store.write(doc("target", "target"), "m", "tester")
        projectionWorker.drain()
        assertEquals(listOf(DocId("target")), store.neighbors(DocId("source")).map { it.dst })
        assertEquals(listOf(DocId("target")), lookup.traverse(DocId("source"), RelType("links-to"), 1))

        store.delete(DocId("target"), "delete", "tester")
        projectionWorker.drain()
        assertTrue(store.neighbors(DocId("source")).isEmpty())
        assertTrue(lookup.traverse(DocId("source"), RelType("links-to"), 1).isEmpty())
    }

    @Test
    fun `lookup failure is recoverable and retries do not duplicate edges`() {
        val store = InMemoryContextStore { now - 10.seconds }
        val lookup = CountingLookup().apply { failNextIndex = true }
        store.write(doc("target", "target"), "m", "tester")
        store.write(doc("source", "See [[target]]"), "m", "tester")

        val projectionWorker = worker(store, lookup)
        val first = projectionWorker.drain()
        assertEquals(1, first.failed)
        assertEquals(1, store.neighbors(DocId("source")).size)
        assertEquals(1, operations(store).backlog().pending)

        val second = projectionWorker.drain()
        assertEquals(1, second.completed)
        assertEquals(setOf(DocId("target"), DocId("source")), lookup.indexed.keys)
        assertEquals(1, store.neighbors(DocId("source")).size)
        assertEquals(0, operations(store).backlog().pending)

        projectionWorker.drain()
        assertEquals(2, lookup.indexed.size)
        assertEquals(1, store.neighbors(DocId("source")).size)
    }

    @Test
    fun `reconciliation repairs an abandoned operation and backlog exposes age`() {
        val store = InMemoryContextStore { now - 10.seconds }
        val lookup = CountingLookup()
        val indexer = Indexer(store, lookup)
        val reconciler = Reconciler(store, lookup, indexer)
        store.write(doc("abandoned", "content"), "m", "tester")
        val operation = operations(store).pending().single()
        operations(store).complete(operation)
        operations(store).enqueue(operation)
        assertEquals(15.seconds, operations(store).backlog().oldestAge(now + 5.seconds))

        val report = reconciler.reconcile()

        assertEquals(1, report.added)
        assertEquals(1, lookup.indexed.size)
        assertEquals(0, operations(store).backlog().pending)
    }

    @Test
    fun `query facade returns canonical success while lookup remains pending`() =
        runBlocking {
            val store = InMemoryContextStore { now - 10.seconds }
            val lookup = CountingLookup().apply { failNextIndex = true }
            val facade =
                QueryFacade(
                    store = store,
                    lookup = lookup,
                    clock = Clock { now },
                    io = Dispatchers.Unconfined
                )

            val revision = facade.write(doc("source", "content"), "m", "tester")

            assertEquals("rev-1", revision.revision.value)
            assertNotNull(store.read(DocId("source")))
            assertEquals(1, facade.projectionBacklog().pending)
            lookup.failNextIndex = false
            assertEquals(1, facade.reconcileProjections().completed)
            assertEquals(0, facade.projectionBacklog().pending)
        }

    /** Fails every delivery until [healthy] is set; the transient-failure case. */
    private class ToggleLookup(private val failing: Boolean = true) : Lookup {
        var healthy: Boolean = !failing
        var attempts = 0

        override fun index(doc: Document, edges: List<Edge>) {
            attempts++
            check(healthy) { "lookup unavailable" }
        }

        override fun remove(id: DocId) = Unit

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    /** A store with one durable projection operation, enqueued the way a write does it. */
    private fun storeWithPendingUpsert(): InMemoryContextStore =
        InMemoryContextStore().also { it.write(doc("a", "body"), "m", "tester") }

    private fun pendingOperation(store: InMemoryContextStore): ProjectionOperation =
        requireNotNull(store.projectionOperations()).pendingLive(1).single()

    private fun workerFor(
        store: InMemoryContextStore,
        lookup: Lookup,
        now: () -> Instant,
        retryBackoff: Duration = Duration.ZERO,
        maxRetryBackoff: Duration = 60.seconds,
        maxAttempts: Int = DEFAULT_PROJECTION_MAX_ATTEMPTS
    ) = ProjectionWorker(
        store = store,
        indexer = Indexer(store, lookup),
        maxAttempts = maxAttempts,
        retryBackoff = retryBackoff,
        maxRetryBackoff = maxRetryBackoff,
        now = now
    )

    /**
     * A failing operation must not be retried on every drain when pacing is on.
     *
     * The default is unpaced because every caller already bounds its own retries,
     * but a deployment that tunes the interval low would otherwise retry a
     * known-bad dependency on every tick. The delay is keyed off the *durable*
     * attempt count, so it grows across drains and cannot be reset by restarting
     * the process.
     */
    @Test
    fun `a failing operation waits out its backoff before being retried`() {
        var clock = Instant.fromEpochSeconds(1_700_000_000)
        val store = storeWithPendingUpsert()
        val operation = pendingOperation(store)
        val worker = workerFor(store, ToggleLookup(), { clock }, retryBackoff = 10.seconds)

        assertEquals(1, worker.drain().failed)
        assertEquals(0, worker.drain().attempted, "the operation must be waiting out its delay")
        assertEquals(setOf(operation.id), worker.backingOff())

        clock += 9.seconds
        assertEquals(0, worker.drain().attempted, "still inside the delay")

        clock += 2.seconds
        assertEquals(1, worker.drain().attempted, "the delay has elapsed")
    }

    @Test
    fun `backoff grows with each further failure`() {
        var clock = Instant.fromEpochSeconds(1_700_000_000)
        val store = storeWithPendingUpsert()
        val operation = pendingOperation(store)
        val worker =
            workerFor(
                store,
                ToggleLookup(),
                { clock },
                retryBackoff = 1.seconds,
                maxRetryBackoff = 1.hours,
                maxAttempts = 100
            )

        // After each failure the next eligible moment moves further out, so a drain
        // at a fixed offset only lands for as many attempts as the delay allows.
        worker.drain()
        clock += 1.seconds
        worker.drain()
        clock += 2.seconds
        worker.drain()
        clock += 4.seconds
        assertEquals(1, worker.drain().attempted, "the fourth failure waits 8s")

        assertEquals(
            4,
            pendingOperation(store).attempts,
            "the delay is keyed off the durable attempt count, so it survives a restart"
        )
    }

    @Test
    fun `a successful delivery clears the backoff`() {
        var clock = Instant.fromEpochSeconds(1_700_000_000)
        val store = storeWithPendingUpsert()
        val operation = pendingOperation(store)
        val lookup = ToggleLookup()
        val worker = workerFor(store, lookup, { clock }, retryBackoff = 10.seconds)

        worker.drain()
        assertEquals(1, worker.backingOff().size)

        lookup.healthy = true
        clock += 11.seconds
        assertEquals(1, worker.drain().completed)
        assertTrue(worker.backingOff().isEmpty(), "a delivered operation must leave the delay table")
    }

    @Test
    fun `an unpaced worker retries on the next drain`() {
        var clock = Instant.fromEpochSeconds(1_700_000_000)
        val store = storeWithPendingUpsert()
        val operation = pendingOperation(store)
        val worker = workerFor(store, ToggleLookup(), { clock })

        assertEquals(1, worker.drain().failed)
        assertEquals(1, worker.drain().attempted, "the default is unpaced, as documented")
    }

    /**
     * Only one worker may own an operation at a time.
     *
     * Write-time draining, reconciliation and the watcher each build their own
     * ProjectionWorker over the same store. Without a claim they race on the same
     * outbox record and can finish out of order, leaving the index holding the
     * older revision while both report success.
     */
    @Test
    fun `a second worker cannot claim an operation the first holds`() {
        var clock = Instant.fromEpochSeconds(1_700_000_000)
        val store = storeWithPendingUpsert()
        val outbox = requireNotNull(store.projectionOperations())
        val operation = pendingOperation(store)
        val first = ProjectionWorker(store, Indexer(store, ToggleLookup()), lease = 30.seconds, now = { clock })
        val second = ProjectionWorker(store, Indexer(store, ToggleLookup()), lease = 30.seconds, now = { clock })

        val claim = requireNotNull(outbox.claim(operation.id, "worker-a", 30.seconds, clock))
        assertEquals(
            null,
            outbox.claim(operation.id, "worker-b", 30.seconds, clock),
            "a live lease must exclude a competing claim"
        )

        clock += 31.seconds
        val takeover = requireNotNull(outbox.claim(operation.id, "worker-b", 30.seconds, clock))
        assertFalse(
            takeover.token == claim.token,
            "a takeover must mint a fresh fencing token, or the old owner could still complete"
        )
    }

    /**
     * Completion must not succeed after the lease was taken over.
     *
     * This is what the fencing token buys: without it, a worker whose lease lapsed
     * mid-delivery would return and delete an operation the new owner is still
     * delivering.
     */
    @Test
    fun `completion fails after the lease is taken over`() {
        var clock = Instant.fromEpochSeconds(1_700_000_000)
        val store = storeWithPendingUpsert()
        val outbox = requireNotNull(store.projectionOperations())
        val operation = pendingOperation(store)

        val stale = requireNotNull(outbox.claim(operation.id, "worker-a", 10.seconds, clock))
        clock += 11.seconds
        outbox.claim(operation.id, "worker-b", 30.seconds, clock)

        assertFalse(
            outbox.completeClaim(stale),
            "a lapsed owner must not be able to mark the operation delivered"
        )
        assertTrue(
            outbox.pendingLive(10).any { it.id == operation.id },
            "the record must survive a stolen completion"
        )
        assertTrue(outbox.completeClaim(requireNotNull(outbox.leasedOperations(clock).firstOrNull())))
    }

    @Test
    fun `an expired lease is recoverable by another worker`() {
        var clock = Instant.fromEpochSeconds(1_700_000_000)
        val store = storeWithPendingUpsert()
        val outbox = requireNotNull(store.projectionOperations())
        val operation = pendingOperation(store)

        // Simulates a worker that died holding a lease: the claim is simply never
        // released or completed.
        requireNotNull(outbox.claim(operation.id, "worker-a", 10.seconds, clock))
        assertEquals(null, outbox.claim(operation.id, "worker-b", 30.seconds, clock))

        clock += 11.seconds
        assertTrue(
            outbox.claim(operation.id, "worker-b", 30.seconds, clock) != null,
            "a crashed holder must not strand its operation"
        )
    }

    @Test
    fun `a worker skips an operation another worker is delivering`() {
        var clock = Instant.fromEpochSeconds(1_700_000_000)
        val store = storeWithPendingUpsert()
        val outbox = requireNotNull(store.projectionOperations())
        val operation = pendingOperation(store)
        requireNotNull(outbox.claim(operation.id, "worker-a", 30.seconds, clock))

        val worker = ProjectionWorker(store, Indexer(store, ToggleLookup()), lease = 30.seconds, now = { clock })
        val report = worker.drain()

        assertEquals(0, report.attempted, "a leased operation must not be attempted")
        assertEquals(1, report.skipped, "and it must be reported as skipped, not silently dropped")
    }

    @Test
    fun `the outbox is drained exactly once when two workers race`() {
        var clock = Instant.fromEpochSeconds(1_700_000_000)

        // Baseline: how many index writes a single worker performs for this document.
        val baselineStore = storeWithPendingUpsert()
        val baselineLookup = ToggleLookup(failing = false)
        ProjectionWorker(baselineStore, Indexer(baselineStore, baselineLookup), lease = 30.seconds, now = { clock })
            .drain()

        val store = storeWithPendingUpsert()
        val lookup = ToggleLookup(failing = false)
        val a = ProjectionWorker(store, Indexer(store, lookup), lease = 30.seconds, now = { clock })
        val b = ProjectionWorker(store, Indexer(store, lookup), lease = 30.seconds, now = { clock })

        val first = a.drain()
        val second = b.drain()

        assertEquals(1, first.completed + second.completed, "exactly one worker delivers it")
        // Sequential drains cannot produce a skip: the winner deletes the record, so
        // the loser finds nothing pending at all. `skipped` only rises when the two
        // read the same pending list before either claims — which is what the
        // explicit claim tests above exercise, since that race is not reproducible
        // by interleaving two drains on one thread.
        assertEquals(0, second.attempted, "the second drain has nothing left to deliver")
        assertEquals(
            baselineLookup.attempts,
            lookup.attempts,
            "two workers must not write the index twice for one operation"
        )
    }

    @Test
    fun `releasing a claim lets the next worker take it immediately`() {
        var clock = Instant.fromEpochSeconds(1_700_000_000)
        val store = storeWithPendingUpsert()
        val outbox = requireNotNull(store.projectionOperations())
        val operation = pendingOperation(store)
        val claim = requireNotNull(outbox.claim(operation.id, "worker-a", 30.seconds, clock))
        assertEquals(null, outbox.claim(operation.id, "worker-b", 30.seconds, clock))

        outbox.releaseClaim(claim)

        assertTrue(outbox.claim(operation.id, "worker-b", 30.seconds, clock) != null)
    }

    @Test
    fun `a re-enqueued operation does not inherit a stale lease`() {
        var clock = Instant.fromEpochSeconds(1_700_000_000)
        val store = storeWithPendingUpsert()
        val outbox = requireNotNull(store.projectionOperations())
        val operation = pendingOperation(store)
        requireNotNull(outbox.claim(operation.id, "worker-a", 60.seconds, clock))

        outbox.enqueue(operation)

        assertTrue(
            outbox.claim(operation.id, "worker-b", 30.seconds, clock) != null,
            "a fresh enqueue must be claimable immediately, not after the old lease lapses"
        )
    }
}
