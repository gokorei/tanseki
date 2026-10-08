package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.StoreCapabilities
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Embedder
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Instant

class QueryFacadeTest {
    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun doc(id: String, content: String, collection: String = "vault") =
        Document(
            id = DocId(id),
            collection = Collection(collection),
            path = "$id.md",
            content = content,
            contentHash = "hash-$id",
            revision = RevisionId("rev"),
            updatedAt = now
        )

    private class FakeStore : ContextStore {
        val documents = LinkedHashMap<DocId, Document>()
        val storedEdges = mutableListOf<Edge>()
        var lastWrite: Document? = null
        var conflictOnWrite = false

        override fun read(id: DocId): Document? = documents[id]

        override fun write(doc: Document, message: String, author: String, ifRevision: RevisionId?): Revision {
            if (conflictOnWrite) throw ConflictException(doc.id, "stale revision")
            lastWrite = doc
            documents[doc.id] = doc
            return Revision(
                doc.id,
                RevisionId("rev-${documents.size}"),
                author,
                message,
                doc.contentHash,
                doc.updatedAt
            )
        }

        override fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision {
            val existing = documents.remove(id)!!
            return Revision(
                id,
                RevisionId("del"),
                author,
                message,
                existing.contentHash,
                Instant.fromEpochSeconds(1_700_000_000)
            )
        }

        override fun list(collection: Collection?): List<DocRef> =
            documents.values
                .filter { collection == null || it.collection == collection }
                .map { DocRef(it.id, it.collection, it.path, it.contentHash, it.revision) }

        override fun upsertEdge(edge: Edge) {
            storedEdges += edge
        }

        override fun removeEdges(src: DocId, rel: RelType?) {
            storedEdges.removeAll { it.src == src && (rel == null || it.rel == rel) }
        }

        override fun neighbors(id: DocId, rel: RelType?): List<Edge> = storedEdges.filter { it.src == id }

        override fun history(id: DocId): List<Revision> = emptyList()

        override fun putBlob(bytes: ByteArray) = BlobRef("h", bytes.size.toLong())

        override fun getBlob(ref: BlobRef) = ByteArray(0)

        override fun capabilities() = StoreCapabilities(true, false, false, Consistency.STRONG)
    }

    private class BlockingReadStore : ContextStore {
        private val documents = LinkedHashMap<DocId, Document>()
        val writeCommitted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)

        override fun read(id: DocId): Document? = documents[id]

        @Synchronized
        override fun write(
            doc: Document,
            message: String,
            author: String,
            ifRevision: RevisionId?
        ): Revision {
            documents[doc.id] = doc
            writeCommitted.countDown()
            check(releaseWrite.await(5, TimeUnit.SECONDS)) { "write release timed out" }
            return Revision(doc.id, RevisionId("write"), author, message, doc.contentHash, doc.updatedAt)
        }

        @Synchronized
        override fun delete(
            id: DocId,
            message: String,
            author: String,
            ifRevision: RevisionId?
        ): Revision {
            val existing = documents.remove(id)!!
            return Revision(id, RevisionId("delete"), author, message, existing.contentHash, existing.updatedAt)
        }

        override fun list(collection: Collection?): List<DocRef> =
            documents.values.map { DocRef(it.id, it.collection, it.path, it.contentHash, it.revision) }

        override fun upsertEdge(edge: Edge) = Unit

        override fun removeEdges(src: DocId, rel: RelType?) = Unit

        override fun neighbors(id: DocId, rel: RelType?): List<Edge> = emptyList()

        override fun history(id: DocId): List<Revision> = emptyList()

        override fun putBlob(bytes: ByteArray): BlobRef = BlobRef("h", bytes.size.toLong())

        override fun getBlob(ref: BlobRef): ByteArray = ByteArray(0)

        override fun capabilities() = StoreCapabilities(true, false, false, Consistency.STRONG)
    }

    private class RecordingLookup : Lookup {
        val indexed = linkedMapOf<DocId, Document>()
        val removed = mutableListOf<DocId>()
        var vectorResults: List<Hit> = emptyList()
        var lastVectorLimit: Int = -1

        override fun index(doc: Document, edges: List<Edge>) {
            indexed[doc.id] = doc
        }

        override fun remove(id: DocId) {
            indexed.remove(id)
            removed += id
        }

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> {
            lastVectorLimit = limit
            return vectorResults.take(limit)
        }

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    private class FakeLookup : Lookup {
        val indexed = mutableListOf<Pair<DocId, List<Edge>>>()
        val removed = mutableListOf<DocId>()

        override fun index(doc: Document, edges: List<Edge>) {
            indexed += doc.id to edges
        }

        override fun remove(id: DocId) {
            removed += id
        }

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> =
            listOf(Hit(DocId("a"), 1.0, q))

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = listOf(Hit(DocId("v"), 0.9))

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = listOf(DocId("b"))

        override fun rebuild(store: ContextStore) = Unit
    }

    /** Lexical and vector disagree, so fusion is observable. */
    private class HybridLookup : Lookup {
        val textFilters = mutableListOf<Filters>()
        val vectorFilters = mutableListOf<Filters>()

        override fun index(doc: Document, edges: List<Edge>) = Unit

        override fun remove(id: DocId) = Unit

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> {
            textFilters += filters
            return listOf(Hit(DocId("a"), 1.0), Hit(DocId("b"), 0.5))
        }

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> {
            vectorFilters += filters
            return listOf(Hit(DocId("b"), 0.9), Hit(DocId("c"), 0.8))
        }

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    private class FakeEmbedder : Embedder {
        override val model = "fake"
        override val dimensions = 2

        override fun embed(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(1f, 0f) }
    }

    private class OverlayLookup : Lookup {
        val documents = linkedMapOf<DocId, Document>()
        val edges = mutableMapOf<DocId, List<Edge>>()
        var failNextIndex = false
        var failNextRemove = false

        override fun index(doc: Document, edges: List<Edge>) {
            if (failNextIndex) {
                failNextIndex = false
                error("lookup unavailable")
            }
            documents[doc.id] = doc
            this.edges[doc.id] = edges
        }

        override fun remove(id: DocId) {
            if (failNextRemove) {
                failNextRemove = false
                error("lookup unavailable")
            }
            documents.remove(id)
            edges.remove(id)
        }

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> =
            documents.values
                .filter { q.isNotBlank() && it.content.contains(q, ignoreCase = true) }
                .take(limit)
                .map { Hit(it.id, 1.0, it.content) }

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = edges[id].orEmpty().map { it.dst }

        override fun rebuild(store: ContextStore) = Unit
    }

    private fun facade(store: FakeStore, lookup: FakeLookup) =
        QueryFacade(
            store = store,
            lookup = lookup,
            clock = Clock { now },
            io = Dispatchers.Unconfined
        )

    private fun hybridFacade(
        lookup: Lookup,
        embedder: Embedder? = null,
        overlay: PendingOverlay = PendingOverlay()
    ) =
        QueryFacade(
            store = FakeStore(),
            lookup = lookup,
            clock = Clock { now },
            io = Dispatchers.Unconfined,
            embedder = embedder,
            overlay = overlay
        )

    @Test
    fun `rename republishes under the new id and forgets the old one`() =
        runBlocking {
            val store = InMemoryContextStore()
            val lookup = OverlayLookup()
            val overlay = PendingOverlay()
            val facade =
                QueryFacade(
                    store = store,
                    lookup = lookup,
                    clock = Clock { now },
                    io = Dispatchers.Unconfined,
                    overlay = overlay
                )
            store.write(doc("notes/old", "the body"), "add", "agent")

            val revision = facade.rename(DocId("notes/old"), DocId("folder/new"), "move", "agent")

            assertEquals(DocId("folder/new"), revision.docId)
            // The old id must stop resolving, and the new one must serve content.
            // Leaving the overlay entry under the old id would let a read keep
            // being answered from a document that has moved.
            assertNull(facade.get(DocId("notes/old")))
            assertEquals("the body", facade.get(DocId("folder/new"))!!.content)
            assertEquals(listOf(DocId("folder/new")), facade.list().map { it.id })
            assertEquals(listOf(DocId("folder/new")), facade.searchText("body").map { it.id })
        }

    @Test
    fun `rename leaves the document alone when the store refuses it`() =
        runBlocking {
            val store = InMemoryContextStore()
            val overlay = PendingOverlay()
            val facade =
                QueryFacade(
                    store = store,
                    lookup = OverlayLookup(),
                    clock = Clock { now },
                    io = Dispatchers.Unconfined,
                    overlay = overlay
                )
            store.write(doc("notes/old", "old body"), "add", "agent")
            store.write(doc("notes/taken", "holder body"), "add", "agent")

            // assertThrows takes a plain lambda, so the suspending call is captured
            // with runCatching first and the outcome asserted afterwards.
            val outcome = runCatching { facade.rename(DocId("notes/old"), DocId("notes/taken"), "move", "agent") }

            assertTrue(
                outcome.exceptionOrNull() is ConflictException,
                "expected a conflict, got ${outcome.exceptionOrNull()}"
            )

            assertEquals("old body", facade.get(DocId("notes/old"))!!.content)
            assertEquals("holder body", facade.get(DocId("notes/taken"))!!.content)
        }

    @Test
    fun `pending documents win every production read until projection completes`() =
        runBlocking {
            val store = InMemoryContextStore()
            val lookup = OverlayLookup()
            val current = doc("a", "fresh content")
            store.write(current, "m", "t")
            val canonical = store.read(DocId("a"))!!
            lookup.index(doc("a", "stale content"), emptyList())
            val overlay = PendingOverlay()
            overlay.put(canonical)
            val facade =
                QueryFacade(
                    store = store,
                    lookup = lookup,
                    clock = Clock { now },
                    io = Dispatchers.Unconfined,
                    overlay = overlay
                )

            assertEquals("fresh content", facade.get(DocId("a"))!!.content)
            assertEquals(current.contentHash, facade.list().single().contentHash)
            assertEquals(listOf(DocId("a")), facade.searchText("fresh").map { it.id })
            assertTrue(facade.searchText("stale").isEmpty())
            assertTrue(facade.traverse(DocId("a"), RelTypes.LinksTo).isEmpty())

            val hybridOverlay = PendingOverlay()
            hybridOverlay.put(current)
            val hybrid = hybridFacade(HybridLookup(), FakeEmbedder(), hybridOverlay).searchHybrid("fresh")
            assertEquals(DocId("a"), hybrid.first().id)
            assertEquals("fresh content", hybrid.first().snippet)
        }

    @Test
    fun `projection failure keeps pending reads and recovery evicts after completion`() =
        runBlocking {
            val store = InMemoryContextStore()
            val lookup = OverlayLookup().apply { failNextIndex = true }
            val overlay = PendingOverlay()
            val facade =
                QueryFacade(
                    store = store,
                    lookup = lookup,
                    clock = Clock { now },
                    io = Dispatchers.Unconfined,
                    overlay = overlay
                )

            facade.write(doc("a", "fresh content"), "m", "t")

            assertEquals("fresh content", facade.get(DocId("a"))!!.content)
            assertEquals(DocId("a"), facade.list().single().id)
            assertEquals(listOf(DocId("a")), facade.searchText("fresh").map { it.id })
            assertEquals(1, facade.projectionBacklog().pending)
            assertEquals(1, overlay.pendingSize())
            assertEquals(1L, overlay.lag())

            val report = facade.reconcileProjections()

            assertEquals(1, report.completed)
            assertEquals(0, facade.projectionBacklog().pending)
            assertEquals(0, overlay.pendingSize())
            assertEquals(0L, overlay.lag())
            assertEquals(listOf(DocId("a")), facade.searchText("fresh").map { it.id })
        }

    @Test
    fun `failed delete keeps stale lookup hidden until tombstone recovery`() =
        runBlocking {
            val store = InMemoryContextStore()
            val lookup = OverlayLookup()
            val overlay = PendingOverlay()
            val facade =
                QueryFacade(
                    store = store,
                    lookup = lookup,
                    clock = Clock { now },
                    io = Dispatchers.Unconfined,
                    overlay = overlay
                )
            facade.write(doc("a", "content"), "m", "t")
            lookup.failNextRemove = true

            facade.delete(DocId("a"), "gone", "t")

            assertNull(facade.get(DocId("a")))
            assertTrue(facade.list().isEmpty())
            assertTrue(facade.searchText("content").isEmpty())
            assertTrue(facade.traverse(DocId("a"), RelTypes.LinksTo).isEmpty())
            assertEquals(1, facade.projectionBacklog().pending)
            assertEquals(1, overlay.pendingSize())

            assertEquals(1, facade.reconcileProjections().completed)
            assertEquals(0, overlay.pendingSize())
        }

    @Test
    fun `write persists to the store first then derives edges and indexes`() =
        runBlocking {
            val store = FakeStore()
            val lookup = FakeLookup()
            store.documents[DocId("notes/b")] = doc("notes/b", "target")

            val revision =
                facade(store, lookup).write(
                    doc("notes/a", "Links to [[notes/b]] and [[ghost]]"),
                    message = "add",
                    author = "agent"
                )

            assertEquals(DocId("notes/a"), store.lastWrite!!.id)
            assertEquals(1, store.storedEdges.size)
            val edge = store.storedEdges.single()
            assertEquals(DocId("notes/b"), edge.dst)
            assertEquals(RelTypes.LinksTo, edge.rel)

            assertEquals(1, lookup.indexed.size)
            assertEquals(DocId("notes/a"), lookup.indexed.single().first)
            assertEquals(
                1,
                lookup.indexed
                    .single()
                    .second.size
            )
            assertEquals("rev-2", revision.revision.value)
        }

    @Test
    fun `link resolution falls back to path matching`() =
        runBlocking {
            val store = FakeStore()
            val lookup = FakeLookup()
            store.documents[DocId("a")] = doc("a", "See [[Some Page]]")

            facade(store, lookup).write(doc("root", "See [[Some Page]]"), "m", "a")

            // no doc named "Some Page", so no edge derived
            assertTrue(store.storedEdges.isEmpty())
        }

    @Test
    fun `reads delegate to store and lookup`() =
        runBlocking {
            val store = FakeStore()
            val lookup = FakeLookup()
            store.documents[DocId("a")] = doc("a", "x")
            val facade = facade(store, lookup)

            assertEquals("x", facade.get(DocId("a"))!!.content)
            assertEquals(1, facade.list().size)
            assertNull(facade.get(DocId("missing")))
            assertEquals(1, facade.searchText("q").size)
            assertEquals(1, facade.searchVector(FloatArray(3)).size)
            assertEquals(listOf(DocId("b")), facade.traverse(DocId("a"), RelTypes.LinksTo))
        }

    @Test
    fun `batch reads use one store operation and preserve request order`() =
        runBlocking {
            val backing = InMemoryContextStore()
            backing.write(doc("a", "alpha"), "m", "tester")
            backing.write(doc("b", "beta"), "m", "tester")
            var batchReads = 0
            val store =
                object : ContextStore by backing {
                    override fun readMany(ids: List<DocId>): List<Document> {
                        batchReads += 1
                        return ids.distinct().mapNotNull { backing.read(it) }
                    }
                }
            val facade =
                QueryFacade(
                    store = store,
                    lookup = RecordingLookup(),
                    clock = Clock { now },
                    io = Dispatchers.Unconfined
                )

            assertEquals(
                listOf("b", "a"),
                facade.getMany(listOf(DocId("b"), DocId("a"), DocId("b"))).map { it.id.value }
            )
            assertEquals(1, batchReads)
        }

    @Test
    fun `list page totals do not double count pending documents the store already has`() =
        runBlocking {
            val store = InMemoryContextStore()
            (1..5).forEach { index -> store.write(doc("doc-$index", "body-$index"), "m", "tester") }
            val overlay = PendingOverlay()
            val facade =
                QueryFacade(
                    store = store,
                    lookup = RecordingLookup(),
                    clock = Clock { now },
                    io = Dispatchers.Unconfined,
                    overlay = overlay
                )
            overlay.put(doc("doc-5", "body-5"))

            val page = facade.listPage(limit = 2)

            assertEquals(5, page.total)
            assertEquals(listOf(DocId("doc-1"), DocId("doc-2")), page.items.map { it.id })
            assertTrue(page.hasMore)
        }

    @Test
    fun `list page totals include pending documents the store has not seen`() =
        runBlocking {
            val store = InMemoryContextStore()
            (1..3).forEach { index -> store.write(doc("doc-$index", "body-$index"), "m", "tester") }
            val overlay = PendingOverlay()
            val facade =
                QueryFacade(
                    store = store,
                    lookup = RecordingLookup(),
                    clock = Clock { now },
                    io = Dispatchers.Unconfined,
                    overlay = overlay
                )
            overlay.put(doc("doc-4", "body-4"))

            val page = facade.listPage(limit = 2)

            assertEquals(4, page.total)
            assertEquals(listOf(DocId("doc-1"), DocId("doc-2")), page.items.map { it.id })
            assertTrue(page.hasMore)
            assertFalse(facade.listPage(limit = 10).hasMore)
            assertEquals(4, facade.listPage(limit = 10).total)
        }

    @Test
    fun `multi collection list pages count each collection and its pending documents once`() =
        runBlocking {
            val store = InMemoryContextStore()
            store.write(doc("a-1", "x", collection = "one"), "m", "tester")
            store.write(doc("a-2", "x", collection = "one"), "m", "tester")
            store.write(doc("b-1", "x", collection = "two"), "m", "tester")
            val overlay = PendingOverlay()
            val facade =
                QueryFacade(
                    store = store,
                    lookup = RecordingLookup(),
                    clock = Clock { now },
                    io = Dispatchers.Unconfined,
                    overlay = overlay
                )
            overlay.put(doc("a-3", "x", collection = "one"))
            overlay.put(doc("c-1", "x", collection = "three"))

            val page = facade.listPage(setOf(Collection("one"), Collection("two")), limit = 10)

            assertEquals(4, page.total)
            assertEquals(
                listOf(DocId("a-1"), DocId("a-2"), DocId("a-3"), DocId("b-1")),
                page.items.map { it.id }
            )
        }

    @Test
    fun `restore returns a deleted document to the live set and reindexes it`() =
        runBlocking {
            val store = InMemoryContextStore { now }
            val lookup = FakeLookup()
            val facade =
                QueryFacade(
                    store = store,
                    lookup = lookup,
                    clock = Clock { now },
                    io = Dispatchers.Unconfined
                )
            val id = DocId("notes/a")
            store.write(doc("notes/a", "body", collection = "notes"), "create", "tester", null)
            facade.delete(id, "bye", "agent")

            assertNull(facade.get(id))

            val revision = facade.restore(id, "back", "agent")

            val restored = facade.get(id)
            assertEquals(id, restored?.id)
            assertEquals(RevisionId(revision.revision.value), restored?.revision)
            assertEquals(listOf(id), lookup.indexed.map { it.first }.distinct())
        }

    @Test
    fun `restore of an unknown id propagates not found`() =
        runBlocking {
            val store = InMemoryContextStore { now }
            val facade =
                QueryFacade(
                    store = store,
                    lookup = FakeLookup(),
                    clock = Clock { now },
                    io = Dispatchers.Unconfined
                )

            assertThrows(NotFoundException::class.java) {
                runBlocking { facade.restore(DocId("missing"), "back", "agent") }
            }
        }

    @Test
    fun `delete removes from the store then the lookup`() =
        runBlocking {
            val store = FakeStore()
            val lookup = FakeLookup()
            store.documents[DocId("a")] = doc("a", "x")

            facade(store, lookup).delete(DocId("a"), "bye", "agent")

            assertTrue(lookup.removed.contains(DocId("a")))
            assertNull(store.read(DocId("a")))
        }

    @Test
    fun `delete cannot be overwritten by an older in-flight write`() =
        runBlocking {
            val store = BlockingReadStore()
            val lookup = RecordingLookup()
            val facade = QueryFacade(store, lookup, Clock { now }, io = Dispatchers.Default)
            val write = async(Dispatchers.Default) { facade.write(doc("a", "old"), "write", "tester") }

            assertTrue(store.writeCommitted.await(5, TimeUnit.SECONDS))
            val delete = async { facade.delete(DocId("a"), "delete", "tester") }
            yield()
            assertFalse(delete.isCompleted)

            store.releaseWrite.countDown()
            write.await()
            delete.await()

            assertNull(store.read(DocId("a")))
            assertFalse(lookup.indexed.containsKey(DocId("a")))
            assertEquals(listOf(DocId("a")), lookup.removed)
        }

    @Test
    fun `typed author frontmatter creates a mentions edge`() =
        runBlocking {
            val store = FakeStore()
            val lookup = FakeLookup()
            val authorDocument = doc("source", "body").copy(frontmatter = Frontmatter(author = "Ada"))

            facade(store, lookup).write(authorDocument, "write", "tester")

            val edge = store.storedEdges.single()
            assertEquals(RelTypes.Mentions, edge.rel)
            assertEquals(DocId("author/Ada"), edge.dst)
            assertEquals("Ada", edge.props["author"])
        }

    @Test
    fun `safe wikilink resolution handles md suffixes and malformed links`() =
        runBlocking {
            val store = FakeStore()
            val lookup = FakeLookup()
            store.documents[DocId("notes/target")] = doc("notes/target", "target")

            facade(store, lookup).write(
                doc("source", "[[notes/target.md]] [[[malformed]] [[unterminated"),
                "write",
                "tester"
            )

            assertEquals(listOf(DocId("notes/target")), store.storedEdges.map { it.dst })
        }

    @Test
    fun `hybrid search fuses lexical and vector rankings`() =
        runBlocking {
            val fused = hybridFacade(HybridLookup(), FakeEmbedder()).searchHybrid("q")

            // b appears in both rankings, so it outranks the single-signal docs.
            assertEquals(DocId("b"), fused.first().id)
            assertEquals(setOf(DocId("a"), DocId("b"), DocId("c")), fused.map { it.id }.toSet())
        }

    @Test
    fun `hybrid search passes filters to lexical and vector lookup`() =
        runBlocking {
            val lookup = HybridLookup()
            val filters = Filters(collections = setOf("vault"), tags = setOf("api"))

            hybridFacade(lookup, FakeEmbedder()).searchHybrid("q", filters)

            assertEquals(listOf(filters), lookup.textFilters)
            assertEquals(listOf(filters), lookup.vectorFilters)
        }

    @Test
    fun `hybrid search degrades to lexical without an embedder`() =
        runBlocking {
            val hits = hybridFacade(HybridLookup()).searchHybrid("q")

            assertEquals(listOf(DocId("a"), DocId("b")), hits.map { it.id })
        }

    @Test
    fun `store conflicts propagate and the lookup is not written`() =
        runBlocking {
            val store = FakeStore()
            val lookup = FakeLookup()
            store.conflictOnWrite = true

            assertThrows(ConflictException::class.java) {
                runBlocking { facade(store, lookup).write(doc("a", "x"), "m", "a") }
            }
            assertTrue(lookup.indexed.isEmpty())
        }

    /**
     * Vector search hid only tombstones, so a document you had just updated was
     * still returned — at its previous revision — by a vector query, while text
     * search correctly suppressed it. The index can only hold content older than
     * the store, so serving it answers with the wrong revision of a document the
     * caller has already changed.
     */
    @Test
    fun `vector search suppresses a document whose update is still pending`() {
        val store = InMemoryContextStore()
        val lookup = RecordingLookup()
        val overlay = PendingOverlay()
        val facade =
            QueryFacade(
                store = store,
                lookup = lookup,
                overlay = overlay,
                clock = Clock { now },
                io = Dispatchers.Unconfined
            )
        val document = doc("edited", "the original body")
        store.write(document, "t", "t")
        overlay.put(document.copy(content = "a newer body the index has never seen"))

        lookup.vectorResults = listOf(Hit(document.id, 1.0, "the original body"))

        val hits = runBlocking { facade.searchVector(floatArrayOf(1f, 0f)) }

        assertTrue(hits.isEmpty(), "a pending update must not be served from the index: $hits")
    }

    @Test
    fun `vector search still returns documents that are not pending`() {
        val store = InMemoryContextStore()
        val lookup = RecordingLookup()
        val facade =
            QueryFacade(
                store = store,
                lookup = lookup,
                overlay = PendingOverlay(),
                clock = Clock { now },
                io = Dispatchers.Unconfined
            )
        val document = doc("settled", "body")
        store.write(document, "t", "t")
        lookup.vectorResults = listOf(Hit(document.id, 1.0, "body"))

        val hits = runBlocking { facade.searchVector(floatArrayOf(1f, 0f)) }

        assertEquals(listOf(document.id), hits.map { it.id })
    }

    /**
     * Suppression happens after the Lookup has applied its limit, so the removed
     * ids count against it. Without over-fetching, suppressing one hit turns a
     * request for five into four — a silent under-return rather than an error.
     */
    @Test
    fun `vector search over-fetches so suppression does not shrink the page`() {
        val store = InMemoryContextStore()
        val lookup = RecordingLookup()
        val overlay = PendingOverlay()
        val facade =
            QueryFacade(
                store = store,
                lookup = lookup,
                overlay = overlay,
                clock = Clock { now },
                io = Dispatchers.Unconfined
            )
        repeat(3) { index ->
            val document = doc("pending/$index", "body $index")
            store.write(document, "t", "t")
            overlay.put(document.copy(content = "newer"))
        }
        lookup.vectorResults =
            listOf(
                Hit(DocId("pending/0"), 1.0, "old"),
                Hit(DocId("pending/1"), 1.0, "old"),
                Hit(DocId("pending/2"), 1.0, "old"),
                Hit(DocId("settled/a"), 1.0, "body"),
                Hit(DocId("settled/b"), 1.0, "body")
            )

        val hits = runBlocking { facade.searchVector(floatArrayOf(1f, 0f), limit = 5) }

        assertEquals(listOf(DocId("settled/a"), DocId("settled/b")), hits.map { it.id })
        assertTrue(lookup.lastVectorLimit >= 5, "the lookup must be asked for more than the limit")
    }
}
