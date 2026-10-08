package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.UnsupportedStoreOperationException
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Embedder
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.VectorWriter
import gokorei.tanseki.testkit.InMemoryContextStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class FullRebuilderTest {
    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun doc(id: String, content: String) =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = "hash-$id-$content",
            revision = RevisionId("rev-$id"),
            updatedAt = now
        )

    private class RecordingLookup : Lookup {
        val documents = linkedMapOf<DocId, Document>()
        val edges = linkedMapOf<DocId, List<Edge>>()
        var clearCalls = 0

        override fun index(doc: Document, edges: List<Edge>) {
            documents[doc.id] = doc
            this.edges[doc.id] = edges
        }

        override fun remove(id: DocId) {
            documents.remove(id)
            edges.remove(id)
        }

        override fun clear() {
            clearCalls++
            documents.clear()
            edges.clear()
        }

        override fun indexedIds(): Set<DocId> = documents.keys.toSet()

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    private class ReadFailingStore(
        private val delegate: ContextStore,
        private val unreadable: DocId
    ) : ContextStore by delegate {
        override fun read(id: DocId): Document? = if (id == unreadable) null else delegate.read(id)
    }

    private class RecordingVectorWriter : VectorWriter {
        val writes = mutableListOf<Pair<DocId, String>>()

        override fun writeVectors(doc: Document, model: String, vectors: List<FloatArray>) {
            writes += doc.id to model
        }
    }

    private class IndexFailingStore(
        private val delegate: ContextStore,
        private val failing: DocId
    ) : ContextStore by delegate {
        override fun neighbors(id: DocId, rel: RelType?): List<Edge> =
            if (id == failing) throw IllegalStateException("edge store unavailable") else delegate.neighbors(id, rel)
    }

    private class EdgeClearFailingStore(private val delegate: ContextStore) : ContextStore by delegate {
        override fun clearEdges(): Unit =
            throw UnsupportedStoreOperationException("store does not support clearing edges")
    }

    @Test
    fun `full rebuild derives edges and vectors through the shared indexer`() {
        val store = InMemoryContextStore()
        store.write(doc("a", "See [[b]]"), "m", "t")
        store.write(doc("b", "target"), "m", "t")
        store.write(doc("removed", "gone"), "m", "t")
        store.delete(DocId("removed"), "gone", "t")
        val lookup = RecordingLookup()
        val vectors = RecordingVectorWriter()
        val embedder =
            object : Embedder {
                override val model = "test-model"
                override val dimensions = 2

                override fun embed(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
            }

        val report =
            FullRebuilder(
                store = store,
                lookup = lookup,
                indexer = Indexer(store, lookup, embedder, vectors)
            ).rebuild()

        assertTrue(report.successful)
        assertEquals(4, report.projectionCompleted)
        assertEquals(0, lookup.clearCalls)
        assertEquals(setOf(DocId("a"), DocId("b")), lookup.documents.keys)
        assertEquals(
            listOf(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo)),
            lookup.edges[DocId("a")]
        )
        assertEquals(setOf(DocId("a"), DocId("b")), vectors.writes.map { it.first }.toSet())
        assertEquals(0, store.projectionOperations().backlog().pending)
    }

    @Test
    fun `full rebuild prunes index and graph entries the store dropped`() {
        val store = InMemoryContextStore()
        store.write(doc("a", "See [[b]]"), "m", "t")
        store.write(doc("b", "target"), "m", "t")
        val lookup = RecordingLookup()
        val indexer = Indexer(store, lookup)
        indexer.index(doc("stale", "orphaned"))
        store.upsertEdge(Edge(DocId("stale"), DocId("b"), RelTypes.LinksTo))
        indexer.index(store.read(DocId("a"))!!)

        val report = FullRebuilder(store, lookup, indexer).rebuild()

        assertTrue(report.successful)
        assertEquals(1, report.removedDocuments)
        assertEquals(setOf(DocId("a"), DocId("b")), lookup.documents.keys)
        assertEquals(emptyList<Edge>(), store.neighbors(DocId("stale")))
        assertEquals(listOf(RelTypes.LinksTo), store.neighbors(DocId("a")).map { it.rel })
    }

    @Test
    fun `a failing index pass leaves the previous graph and index in place`() {
        val backing = InMemoryContextStore()
        backing.write(doc("a", "usable"), "m", "t")
        backing.write(doc("b", "edge failure"), "m", "t")
        val lookup = RecordingLookup().apply { index(doc("stale", "old"), emptyList()) }
        val store = IndexFailingStore(backing, DocId("b"))
        val indexer = Indexer(store, lookup)
        indexer.index(backing.read(DocId("a"))!!)
        val before = lookup.documents.keys.toSet()

        val report = FullRebuilder(store, lookup, indexer).rebuild()

        assertFalse(report.successful)
        assertEquals(1, report.indexFailures)
        assertTrue(before.containsAll(lookup.documents.keys))
        assertTrue(DocId("stale") in lookup.documents)
        assertEquals(0, lookup.clearCalls)
    }

    @Test
    fun `a store that cannot clear its graph fails the rebuild instead of claiming success`() {
        val backing = InMemoryContextStore()
        backing.write(doc("a", "See [[b]]"), "m", "t")
        backing.write(doc("b", "target"), "m", "t")
        val lookup = RecordingLookup()
        val store = EdgeClearFailingStore(backing)
        val indexer = Indexer(store, lookup)
        indexer.index(backing.read(DocId("b"))!!)

        val report = FullRebuilder(store, lookup, indexer).rebuild()

        assertFalse(report.successful)
        assertTrue(report.failures.any { it.contains("canonical graph") })
        assertTrue(lookup.documents.keys.containsAll(setOf(DocId("a"), DocId("b"))))
    }

    @Test
    fun `a rebuild drops canonical edges the store no longer holds`() {
        val store = InMemoryContextStore()
        store.write(doc("a", "See [[b]]"), "m", "t")
        store.write(doc("b", "target"), "m", "t")
        store.upsertEdge(Edge(DocId("gone"), DocId("b"), RelTypes.LinksTo))
        val lookup = RecordingLookup()

        val report = FullRebuilder(store, lookup, Indexer(store, lookup)).rebuild()

        assertTrue(report.successful, report.toString())
        assertTrue(store.neighbors(DocId("gone")).isEmpty())
        assertEquals(listOf(RelTypes.LinksTo), store.neighbors(DocId("a")).map { it.rel })
        assertEquals(
            store.list().flatMap { ref -> store.neighbors(ref.id) }.toSet(),
            lookup.edges.values
                .flatten()
                .toSet()
        )
    }

    @Test
    fun `full rebuild reports read failure without clearing a usable lookup`() {
        val backing = InMemoryContextStore()
        backing.write(doc("a", "usable"), "m", "t")
        backing.write(doc("b", "unreadable"), "m", "t")
        val lookup = RecordingLookup().apply { index(doc("stale", "old"), emptyList()) }
        val progress = mutableListOf<RebuildProgress>()
        val store = ReadFailingStore(backing, DocId("b"))

        val report =
            FullRebuilder(
                store = store,
                lookup = lookup,
                indexer = Indexer(store, lookup)
            ).rebuild(progress::add)

        assertFalse(report.successful)
        assertEquals(1, report.readFailures)
        assertEquals(0, lookup.clearCalls)
        assertTrue(DocId("stale") in lookup.documents)
        assertEquals(RebuildPhase.FAILED, progress.last().phase)
    }
}
