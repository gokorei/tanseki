package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Embedder
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.VectorWriter
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class IndexerReconcilerTest {
    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun doc(id: String, content: String, hash: String = "hash-$content") =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = hash,
            revision = RevisionId("rev"),
            updatedAt = now
        )

    private class CountingLookup : Lookup {
        var indexCalls = 0
        var failNextIndex = false
        val indexed = mutableListOf<DocId>()
        val removed = mutableListOf<DocId>()

        override fun index(doc: Document, edges: List<Edge>) {
            if (failNextIndex) {
                failNextIndex = false
                error("lookup unavailable")
            }
            indexCalls++
            indexed += doc.id
        }

        override fun remove(id: DocId) {
            removed += id
        }

        override fun indexedIds(): Set<DocId> = indexed.toSet()

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    /** Counts [ContextStore.read] calls so we can prove unchanged docs are skipped. */
    private class ReadCountingStore(private val delegate: ContextStore) : ContextStore by delegate {
        var reads = 0

        override fun read(id: DocId): Document? {
            reads++
            return delegate.read(id)
        }
    }

    private class VaultLikeStore(var current: Document) : ContextStore by InMemoryContextStore() {
        override fun list(collection: Collection?): List<DocRef> =
            listOf(
                DocRef(
                    id = current.id,
                    collection = current.collection,
                    path = current.path,
                    contentHash = null,
                    revision = current.revision,
                    updatedAt = current.updatedAt
                )
            )

        override fun read(id: DocId): Document? = current.takeIf { it.id == id }
    }

    private class ToggleReadStore(private val delegate: InMemoryContextStore) : ContextStore by delegate {
        var readable = true

        override fun read(id: DocId): Document? = if (readable) delegate.read(id) else null
    }

    private fun setup(): Triple<InMemoryContextStore, CountingLookup, Reconciler> {
        val store = InMemoryContextStore()
        val lookup = CountingLookup()
        val indexer = Indexer(store, lookup)
        val reconciler = Reconciler(store, lookup, indexer)
        return Triple(store, lookup, reconciler)
    }

    @Test
    fun `a change the reconciler picks up is announced on the change feed`() =
        runBlocking {
            val store = InMemoryContextStore()
            val lookup = CountingLookup()
            val feed = ChangeFeed()
            val indexer = Indexer(store, lookup, changeFeed = feed)
            val reconciler = Reconciler(store, lookup, indexer)

            // This is the external-editor case: bytes land in the store without
            // going through the API, and the reconciler notices on its own sweep.
            store.write(
                Document(
                    id = DocId("external/note"),
                    collection = Collection("vault"),
                    path = "external/note.md",
                    content = "written by an editor",
                    contentHash = "hash-external",
                    revision = RevisionId("rev-external"),
                    updatedAt = Instant.fromEpochSeconds(1_700_000_000)
                ),
                "external",
                "editor"
            )

            // Subscribe first. A fresh subscriber is owed the present, not the
            // retained window, so a reconcile that ran before this would publish
            // to nobody and the assertion would pass for the wrong reason.
            val announced = CompletableDeferred<ChangeEvent>()
            val subscriber = launch { feed.subscribe().collect { announced.complete(it) } }
            // Wait cooperatively until the subscription is actually attached. A
            // single yield is not enough: channelFlow registers in a child
            // coroutine that may need several dispatches to get there.
            withTimeoutOrNull(2.seconds) {
                while (feed.subscriberCount() == 0) yield()
            }

            val registered = feed.subscriberCount()
            reconciler.reconcile()
            val published = feed.currentSequence()

            val event = withTimeoutOrNull(2.seconds) { announced.await() }
            subscriber.cancel()
            assertNotNull(
                event,
                "subscribers=$registered published=$published: a file the reconciler " +
                    "indexed must reach a subscriber, not only the Lookup"
            )
            assertEquals(ChangeKind.UPSERT, event!!.kind)
            assertEquals(DocId("external/note"), event.documentId)
            assertEquals(Collection("vault"), event.collection)
        }

    private class RecordingVectorWriter : VectorWriter {
        val writes = mutableListOf<Triple<DocId, String, List<FloatArray>>>()

        override fun writeVectors(doc: Document, model: String, vectors: List<FloatArray>) {
            writes += Triple(doc.id, model, vectors)
        }
    }

    @Test
    fun `indexer writes configured embeddings through the vector writer`() {
        val store = InMemoryContextStore()
        val lookup = CountingLookup()
        val writer = RecordingVectorWriter()
        val embedder =
            object : Embedder {
                override val model = "fake-model"
                override val dimensions = 2

                override fun embed(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
            }
        val document = doc("a", "one")

        Indexer(store, lookup, embedder, writer).index(document)

        assertEquals(1, writer.writes.size)
        val (id, model, vectors) = writer.writes.single()
        assertEquals(DocId("a"), id)
        assertEquals("fake-model", model)
        assertEquals(2, vectors.single().size)
    }

    @Test
    fun `index observer records completion and failure latency`() {
        val store = InMemoryContextStore()
        val lookup = CountingLookup()
        val observations = mutableListOf<Pair<Long, Throwable?>>()
        val indexer =
            Indexer(
                store,
                lookup,
                observer =
                    IndexObserver { duration, failure ->
                        observations += duration to failure
                    }
            )

        indexer.index(doc("a", "one"))
        lookup.failNextIndex = true
        runCatching { indexer.index(doc("b", "two")) }

        assertEquals(2, observations.size)
        assertEquals(null, observations[0].second)
        assertEquals(true, observations[1].second is IllegalStateException)
        assertTrue(observations.all { it.first >= 0 })
    }

    @Test
    fun `first reconcile indexes the whole store`() {
        val (store, lookup, reconciler) = setup()
        store.write(doc("a", "one"), "m", "t")
        store.write(doc("b", "two"), "m", "t")

        val report = reconciler.reconcile()

        assertEquals(2, report.added)
        assertEquals(2, lookup.indexCalls)
    }

    @Test
    fun `subsequent reconcile applies only deltas`() {
        val (store, lookup, reconciler) = setup()
        store.write(doc("a", "one"), "m", "t")
        store.write(doc("b", "two"), "m", "t")
        reconciler.reconcile()
        val callsAfterFirst = lookup.indexCalls

        store.write(doc("b", "two-changed", hash = "hash-changed"), "m", "t")
        val report = reconciler.reconcile()

        assertEquals(1, report.updated)
        assertEquals(1, report.unchanged)
        assertEquals(callsAfterFirst + 1, lookup.indexCalls)

        // idempotent: a third run is all unchanged
        val third = reconciler.reconcile()
        assertEquals(2, third.unchanged)
        assertEquals(callsAfterFirst + 1, lookup.indexCalls)
    }

    @Test
    fun `reconcile does not re-read unchanged documents`() {
        val store = InMemoryContextStore()
        val counting = ReadCountingStore(store)
        val lookup = CountingLookup()
        val reconciler = Reconciler(counting, lookup, Indexer(counting, lookup))
        store.write(doc("a", "one"), "m", "t")
        store.write(doc("b", "two"), "m", "t")

        reconciler.reconcile()
        val readsAfterFirst = counting.reads
        assertTrue(readsAfterFirst >= 2)

        val report = reconciler.reconcile()

        assertEquals(2, report.unchanged)
        assertEquals(readsAfterFirst, counting.reads)
    }

    @Test
    fun `reconcile detects an external edit that keeps the revision`() {
        val store = VaultLikeStore(doc("a", "one"))
        val lookup = CountingLookup()
        val reconciler = Reconciler(store, lookup, Indexer(store, lookup))
        assertEquals(1, reconciler.reconcile().added)

        // Same revision id, changed content and file mtime (a raw vault edit).
        store.current =
            doc("a", "one-changed").copy(
                revision = RevisionId("rev"),
                updatedAt = Instant.fromEpochSeconds(1_700_000_001)
            )
        val report = reconciler.reconcile()

        assertEquals(1, report.updated)
        assertEquals(2, lookup.indexCalls)
    }

    @Test
    fun `startup reconciliation removes lookup ids absent from the store`() {
        val store = InMemoryContextStore()
        val lookup = CountingLookup()
        val reconciler = Reconciler(store, lookup, Indexer(store, lookup))
        store.write(doc("live", "live"), "m", "t")
        lookup.indexed += DocId("stale")

        val report = reconciler.reconcile()

        assertEquals(1, report.removed)
        assertEquals(listOf(DocId("stale")), lookup.removed)
    }

    @Test
    fun `a failed outbox operation is not bypassed by the same reconciliation pass`() {
        val store = InMemoryContextStore()
        val lookup = CountingLookup().apply { failNextIndex = true }
        val reconciler = Reconciler(store, lookup, Indexer(store, lookup))
        store.write(doc("a", "content"), "m", "t")

        val failed = reconciler.reconcile()

        assertEquals(1, failed.projectionFailed)
        assertEquals(0, lookup.indexCalls)

        val recovered = reconciler.reconcile()

        assertEquals(1, recovered.projectionCompleted)
        assertEquals(1, lookup.indexCalls)
    }

    @Test
    fun `deletes propagate to the lookup`() {
        val (store, lookup, reconciler) = setup()
        store.write(doc("a", "one"), "m", "t")
        store.write(doc("b", "two"), "m", "t")
        reconciler.reconcile()

        store.delete(DocId("a"), "gone", "t")
        val report = reconciler.reconcile()

        assertEquals(1, report.removed)
        assertEquals(listOf(DocId("a")), lookup.removed)
    }

    @Test
    fun `reconcile drains projection operations beyond one batch`() {
        val store = InMemoryContextStore()
        val lookup = CountingLookup()
        val reconciler = Reconciler(store, lookup, Indexer(store, lookup))
        repeat(101) { index -> store.write(doc("doc-$index", "content-$index"), "m", "t") }

        val report = reconciler.reconcile()

        assertEquals(101, report.projectionCompleted)
        assertEquals(0, report.projectionFailed)
        assertEquals(0, store.projectionOperations().backlog().pending)
        assertEquals(101, lookup.indexCalls)
    }

    @Test
    fun `listed but unreadable documents remain indexed until readable`() {
        val backing = InMemoryContextStore()
        val store = ToggleReadStore(backing)
        val lookup = CountingLookup()
        val reconciler = Reconciler(store, lookup, Indexer(store, lookup))
        backing.write(doc("a", "initial"), "m", "t")
        assertEquals(1, reconciler.reconcile().added)

        backing.write(doc("a", "changed"), "m", "t")
        store.readable = false
        val degraded = reconciler.reconcile()

        assertEquals(1, degraded.readFailures)
        assertEquals(listOf(DocId("a")), lookup.indexed)

        store.readable = true
        assertEquals(1, reconciler.reconcile().updated)
        assertEquals(2, lookup.indexCalls)
    }
}
