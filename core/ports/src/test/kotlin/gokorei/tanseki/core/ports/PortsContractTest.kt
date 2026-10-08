package gokorei.tanseki.core.ports

import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.StoreCapabilities
import gokorei.tanseki.core.domain.UnsupportedStoreOperationException
import gokorei.tanseki.core.domain.VaultPath
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Contract smoke tests: every port must be implementable without any backend,
 * which is what keeps core hexagonal. Adapters get the full suites in `testkit`.
 */
class PortsContractTest {
    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun doc(id: String, content: String = "body"): Document =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = "hash-$content",
            revision = RevisionId("rev-1"),
            updatedAt = now
        )

    @Test
    fun `ids are value types with validation`() {
        assertEquals(DocId("a"), DocId("a"))
        assertNotEquals(DocId("a"), DocId("b"))
        assertEquals("a", DocId("a").value)
        assertThrows(IllegalArgumentException::class.java) { DocId(" ") }
    }

    @Test
    fun `capabilities advertise honest guarantees`() {
        val caps =
            StoreCapabilities(
                supportsHistory = true,
                supportsPatchGraph = true,
                supportsTransactions = false,
                consistency = Consistency.READ_YOUR_WRITES
            )
        assertTrue(caps.supportsHistory)
        assertTrue(caps.supportsPatchGraph)
        assertFalse(caps.supportsTransactions)
        assertEquals(Consistency.READ_YOUR_WRITES, caps.consistency)
    }

    @Test
    fun `context store is implementable and round-trips`() {
        val store: ContextStore = InMemoryContextStore()
        val written = store.write(doc("notes/a"), message = "add", author = "tester")

        assertEquals(DocId("notes/a"), written.docId)
        assertEquals("add", written.message)

        val read = store.read(DocId("notes/a"))
        assertNotNull(read)
        assertEquals("body", read!!.content)
        assertEquals(1, store.history(DocId("notes/a")).size)
        assertEquals(1, store.list(Collection("vault")).size)
        assertTrue(store.capabilities().supportsHistory)
    }

    @Test
    fun `context store edges and blobs are implementable`() {
        val store: ContextStore = InMemoryContextStore()
        store.write(doc("a"), message = "add", author = "tester")
        store.write(doc("b"), message = "add", author = "tester")
        store.upsertEdge(Edge(DocId("a"), DocId("b"), RelType("links-to")))

        val edges = store.neighbors(DocId("a"))
        assertEquals(1, edges.size)
        assertEquals(DocId("b"), edges.first().dst)

        val ref = store.putBlob("hello".toByteArray())
        assertTrue(store.getBlob(ref).contentEquals("hello".toByteArray()))
    }

    @Test
    fun `lookup is implementable`() {
        val lookup: Lookup = NoopLookup()
        lookup.index(doc("a"), emptyList())
        assertTrue(lookup.searchText("body", Filters(), 10).isEmpty())
        assertTrue(lookup.searchVector(FloatArray(3), Filters(), 10).isEmpty())
        lookup.remove(DocId("a"))
        lookup.rebuild(InMemoryContextStore())
    }

    @Test
    fun `pijul client is implementable`() {
        val pijul: PijulClient =
            object : PijulClient {
                override fun status(vault: VaultPath) = PijulStatus(clean = true)

                override fun log(vault: VaultPath, docId: DocId?, limit: Int) =
                    emptyList<PijulPatch>()

                override fun diff(vault: VaultPath, path: String?) = ""

                override fun record(
                    vault: VaultPath,
                    message: String,
                    author: String,
                    paths: List<String>,
                    operationId: String?
                ) = PijulPatch(RevisionId("patch-1"), author, message, now, operationId = operationId)

                override fun apply(vault: VaultPath, patch: String) = Unit
            }

        assertEquals(RevisionId("patch-1"), pijul.record(VaultPath("/v"), "m", "me", emptyList()).hash)
    }

    @Test
    fun `watcher embedder and clock are implementable`() {
        val watcher: Watcher = Watcher { emptyFlow() }
        assertNotNull(watcher.events(VaultPath("/tmp/vault")))

        val embedder: Embedder =
            object : Embedder {
                override val model = "test-model"
                override val dimensions = 3

                override fun embed(texts: List<String>) = texts.map { FloatArray(dimensions) }
            }
        assertEquals(3, embedder.dimensions)
        assertEquals(1, embedder.embed(listOf("hi")).size)

        val clock = Clock { now }
        assertEquals(now, clock.now())
    }

    @Test
    fun `filter and hit defaults are stable`() {
        val filters = Filters()
        assertTrue(filters.collections.isEmpty())
        assertTrue(filters.tags.isEmpty())
        assertEquals(0.5, Hit(DocId("a"), 0.5).score)
    }

    @Test
    fun `a path derived target re-derives ids and others keep theirs`() {
        val document = doc("a").copy(path = "custom/place.md")
        val vault: ContextStore = InMemoryContextStore().withCapabilities(supportsPatchGraph = true)
        val library: ContextStore = InMemoryContextStore().withCapabilities(supportsPatchGraph = false)

        assertEquals(DocId("custom/place"), importDocument(vault, document).id)
        assertEquals("custom/place.md", importDocument(vault, document).path)
        assertEquals(DocId("a"), importDocument(library, document).id)
        assertEquals("custom/place.md", importDocument(library, document).path)
        assertEquals(importDocument(vault, document).id, importTargetId(vault, document))
        assertEquals(importDocument(library, document).id, importTargetId(library, document))
    }

    @Test
    fun `imported edges follow the document id mapping`() {
        val mapping = mapOf(DocId("a") to DocId("custom/place"))
        val edge = Edge(DocId("a"), DocId("b"), RelType("links-to"))

        val mapped = importEdge(edge) { id -> mapping[id] ?: id }

        assertEquals(DocId("custom/place"), mapped.src)
        assertEquals(DocId("b"), mapped.dst)
    }

    @Test
    fun `the default import drops incoming edges when it stores a tombstone`() {
        val backing = InMemoryContextStore()
        val store = TransferTarget(backing)
        store.write(doc("source"), message = "add", author = "tester")
        store.write(doc("gone"), message = "add", author = "tester")
        store.upsertEdge(Edge(DocId("source"), DocId("gone"), RelType("links-to")))
        assertEquals(1, store.neighbors(DocId("source")).size)

        val applied = store.importTransferState(TransferState(doc("gone").copy(deleted = true), emptyList()))

        assertTrue(applied)
        assertEquals(1, store.incomingEdgeRemovals)
        assertTrue(store.neighbors(DocId("source")).isEmpty())
    }

    @Test
    fun `the default import refuses a stale document`() {
        val store = TransferTarget(InMemoryContextStore())
        store.write(doc("shared", "newer").copy(updatedAt = now), message = "add", author = "tester")

        val applied =
            store.importTransferState(
                TransferState(doc("shared", "older").copy(updatedAt = now.minus(60.seconds)), emptyList())
            )

        assertFalse(applied)
        assertEquals("newer", store.read(DocId("shared"))!!.content)
    }

    @Test
    fun `ports a store cannot support fail loudly`() {
        val store: ContextStore = InMemoryContextStore()

        assertThrows(UnsupportedStoreOperationException::class.java) { store.removeIncomingEdges(DocId("a")) }
        assertThrows(UnsupportedStoreOperationException::class.java) { store.clearEdges() }
        assertThrows(UnsupportedStoreOperationException::class.java) { store.appendHistory(DocId("a"), emptyList()) }
    }

    @Test
    fun `a store that has not implemented rename validates first and then says so`() {
        val store = RenameLessStore()

        // The order matters as much as the outcomes. A client that renamed a
        // document that does not exist should be told that, not told the feature is
        // missing: the two answers mean different things and only one is actionable.
        assertThrows(NotFoundException::class.java) {
            store.rename(DocId("absent"), DocId("target"), "move", "agent")
        }
        store.documents[DocId("a")] = doc("a")
        assertThrows(ConflictException::class.java) {
            store.rename(DocId("a"), DocId("target"), "move", "agent", RevisionId("not-current"))
        }
        store.documents[DocId("taken")] = doc("taken")
        assertThrows(ConflictException::class.java) {
            store.rename(DocId("a"), DocId("taken"), "move", "agent")
        }
        // Only once the request is one the store could actually serve does the gap
        // surface. Reporting 501 for a bad request would send the caller looking
        // for a missing feature instead of fixing their own call.
        assertThrows(UnsupportedStoreOperationException::class.java) {
            store.rename(DocId("a"), DocId("free"), "move", "agent")
        }
    }

    /**
     * The smallest [ContextStore] that does not implement rename.
     *
     * It implements only what the interface requires, so [ContextStore.rename] runs
     * its default rather than an override — which is the whole point: this is what
     * an adapter that has not added the operation yet actually does.
     */
    private class RenameLessStore : ContextStore {
        val documents = mutableMapOf<DocId, Document>()

        override fun read(id: DocId): Document? = documents[id]

        override fun write(doc: Document, message: String, author: String, ifRevision: RevisionId?): Revision =
            error("not needed by this test")

        override fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
            error("not needed by this test")

        override fun list(collection: Collection?): List<DocRef> = emptyList()

        override fun upsertEdge(edge: Edge) = Unit

        override fun removeEdges(src: DocId, rel: RelType?) = Unit

        override fun neighbors(id: DocId, rel: RelType?): List<Edge> = emptyList()

        override fun history(id: DocId): List<Revision> = emptyList()

        override fun putBlob(bytes: ByteArray): BlobRef = error("not needed by this test")

        override fun getBlob(ref: BlobRef): ByteArray = error("not needed by this test")

        override fun capabilities(): StoreCapabilities =
            StoreCapabilities(
                supportsHistory = false,
                supportsPatchGraph = false,
                supportsTransactions = false,
                consistency = Consistency.READ_YOUR_WRITES
            )
    }

    private fun InMemoryContextStore.withCapabilities(
        supportsPatchGraph: Boolean = false,
        supportsTombstoneImport: Boolean = false
    ): ContextStore = CapabilitiesOverride(this, supportsPatchGraph, supportsTombstoneImport)

    private class CapabilitiesOverride(
        private val delegate: ContextStore,
        private val supportsPatchGraph: Boolean,
        private val supportsTombstoneImport: Boolean
    ) : ContextStore by delegate {
        override fun capabilities() =
            delegate
                .capabilities()
                .copy(
                    supportsPatchGraph = supportsPatchGraph,
                    supportsTombstoneImport = supportsTombstoneImport,
                    supportsHistoryImport = true
                )
    }

    /**
     * A store that implements the graph surgery the default import relies on, so
     * the port default itself can be exercised.
     */
    private class TransferTarget(private val backing: InMemoryContextStore) : ContextStore {
        var incomingEdgeRemovals = 0
        val importedHistory = mutableListOf<Pair<DocId, List<Revision>>>()

        override fun read(id: DocId): Document? = backing.read(id)

        override fun readIncludingDeleted(id: DocId): Document? = backing.readIncludingDeleted(id)

        override fun write(
            doc: Document,
            message: String,
            author: String,
            ifRevision: RevisionId?
        ): Revision = backing.write(doc, message, author, ifRevision)

        override fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
            backing.delete(id, message, author, ifRevision)

        override fun list(collection: Collection?): List<DocRef> = backing.list(collection)

        override fun upsertEdge(edge: Edge) = backing.upsertEdge(edge)

        override fun removeEdges(src: DocId, rel: RelType?) = backing.removeEdges(src, rel)

        override fun removeIncomingEdges(dst: DocId, rel: RelType?) {
            incomingEdgeRemovals++
            backing.edges.entries.toList().forEach { (source, outgoing) ->
                outgoing.removeAll { it.dst == dst && (rel == null || it.rel == rel) }
            }
        }

        override fun neighbors(id: DocId, rel: RelType?): List<Edge> = backing.neighbors(id, rel)

        override fun history(id: DocId): List<Revision> = backing.history(id)

        override fun appendHistory(id: DocId, revisions: List<Revision>) {
            importedHistory += id to revisions
        }

        override fun putBlob(bytes: ByteArray): BlobRef = backing.putBlob(bytes)

        override fun getBlob(ref: BlobRef): ByteArray = backing.getBlob(ref)

        override fun capabilities() =
            backing
                .capabilities()
                .copy(supportsTombstoneImport = true, supportsHistoryImport = true, supportsTransactions = true)
    }

    private class InMemoryContextStore : ContextStore {
        private val docs = LinkedHashMap<DocId, Document>()
        private val revisions = LinkedHashMap<DocId, MutableList<Revision>>()
        val edges = LinkedHashMap<DocId, MutableList<Edge>>()
        private val blobs = LinkedHashMap<String, ByteArray>()
        private var counter = 0

        override fun read(id: DocId): Document? = docs[id]

        override fun write(
            doc: Document,
            message: String,
            author: String,
            ifRevision: RevisionId?
        ): Revision {
            docs[doc.id] = doc
            val revision =
                Revision(
                    docId = doc.id,
                    revision = RevisionId("rev-${++counter}"),
                    author = author,
                    message = message,
                    contentHash = doc.contentHash,
                    createdAt = doc.updatedAt
                )
            revisions.getOrPut(doc.id) { mutableListOf() }.add(revision)
            return revision
        }

        override fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
            write(read(id)!!.copy(deleted = true), message, author)

        override fun list(collection: Collection?): List<DocRef> =
            docs.values
                .filter { collection == null || it.collection == collection }
                .map { DocRef(it.id, it.collection, it.path, it.contentHash, it.revision) }

        override fun upsertEdge(edge: Edge) {
            edges.getOrPut(edge.src) { mutableListOf() }.add(edge)
        }

        override fun removeEdges(src: DocId, rel: RelType?) {
            edges[src]?.removeAll { rel == null || it.rel == rel }
        }

        override fun neighbors(id: DocId, rel: RelType?): List<Edge> =
            edges[id].orEmpty().filter { rel == null || it.rel == rel }

        override fun history(id: DocId): List<Revision> = revisions[id].orEmpty()

        override fun putBlob(bytes: ByteArray): BlobRef {
            val ref = BlobRef(hash = bytes.contentHashCode().toString(), size = bytes.size.toLong())
            blobs[ref.hash] = bytes
            return ref
        }

        override fun getBlob(ref: BlobRef): ByteArray = blobs.getValue(ref.hash)

        override fun capabilities() =
            StoreCapabilities(
                supportsHistory = true,
                supportsPatchGraph = false,
                supportsTransactions = false,
                consistency = Consistency.STRONG
            )
    }

    private class NoopLookup : Lookup {
        override fun index(doc: Document, edges: List<Edge>) = Unit

        override fun remove(id: DocId) = Unit

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    /**
     * `ProjectionBacklog` is the only place an operator learns that the projection
     * pipeline is behind, so the distinctions its KDoc draws are load-bearing:
     * `stuck`, `corrupt` and `deadLettered` are counted *apart* from `pending`,
     * because each needs a different response and a combined total cannot tell
     * them apart. A refactor that folded them into one number would look like a
     * simplification and silently remove the operator's ability to act.
     */
    @Test
    fun `projection backlog keeps its counters distinct and its ages honest`() {
        val empty = ProjectionBacklog()
        assertEquals(0, empty.pending)
        assertEquals(0, empty.stuck)
        assertEquals(0, empty.corrupt)
        assertEquals(0, empty.deadLettered)

        val now = Instant.fromEpochSeconds(1_000)
        assertNull(empty.oldestAge(now), "an empty backlog has no oldest entry to age")
        assertNull(empty.oldestStuckAge(now))

        // The four counters are independent fields rather than one derived total,
        // so a backlog can be simultaneously behind, failing, and unrecoverable.
        val mixed =
            ProjectionBacklog(
                pending = 5,
                oldestCreatedAt = Instant.fromEpochSeconds(900),
                stuck = 2,
                oldestStuckAt = Instant.fromEpochSeconds(950),
                corrupt = 1,
                deadLettered = 3
            )
        assertEquals(5, mixed.pending)
        assertEquals(2, mixed.stuck)
        assertEquals(1, mixed.corrupt)
        assertEquals(3, mixed.deadLettered)
        assertEquals(100, mixed.oldestAge(now)?.inWholeSeconds)
        assertEquals(50, mixed.oldestStuckAge(now)?.inWholeSeconds)

        // Clock skew puts the oldest entry in the future. Reporting a negative age
        // would be a lie a monitor alerts on; clamping to zero is the honest floor.
        val skewed = ProjectionBacklog(pending = 1, oldestCreatedAt = Instant.fromEpochSeconds(1_100))
        assertEquals(0, skewed.oldestAge(now)?.inWholeSeconds)
    }
}
