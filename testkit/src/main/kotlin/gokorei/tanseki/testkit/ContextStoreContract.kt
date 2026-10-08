package gokorei.tanseki.testkit

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.ContextStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Shared [ContextStore] conformance suite. Every adapter must extend this and
 * pass it, so contract violations are caught centrally rather than per adapter.
 */
abstract class ContextStoreContract {
    protected abstract fun newStore(): ContextStore

    protected open fun closeStore(store: ContextStore) = Unit

    private var storeRef: ContextStore? = null
    protected val store: ContextStore get() = storeRef!!

    @BeforeEach
    fun setUpContract() {
        storeRef = newStore()
    }

    @AfterEach
    fun tearDownContract() {
        storeRef?.let(::closeStore)
        storeRef = null
    }

    protected fun document(id: String, content: String, revision: String = "rev-1") =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = "hash-$content",
            revision = RevisionId(revision),
            updatedAt = Instant.fromEpochSeconds(1_700_000_000)
        )

    /**
     * Rename is part of the contract, not an optional extra: an adapter that has
     * not implemented it must say so, and every adapter that has must agree on
     * what a move does to the document, its history, and both edge endpoints.
     */
    @Test
    @Suppress("FunctionNaming")
    fun `rename moves the document, its history and its edges`() {
        val store = newStore()
        store.write(document("notes/old", "the body"), "add", "agent")
        store.upsertEdge(Edge(DocId("notes/old"), DocId("other"), RelTypes.LinksTo))
        store.upsertEdge(Edge(DocId("other"), DocId("notes/old"), RelTypes.LinksTo))

        val revision = store.rename(DocId("notes/old"), DocId("folder/new"), "move", "agent")

        assertNull(store.read(DocId("notes/old")))
        val moved = store.read(DocId("folder/new"))
        assertNotNull(moved)
        assertEquals("the body", moved!!.content)
        assertEquals(DocId("folder/new"), revision.docId)
        // History follows the document, and the move is a step in it rather than a
        // gap: the pre-rename revision must still be reachable under the new id.
        assertTrue(store.history(DocId("folder/new")).isNotEmpty())
        assertTrue(store.history(DocId("folder/new")).all { it.docId == DocId("folder/new") })
        // Both endpoints of both edges move with the ids they name.
        assertEquals(listOf(DocId("other")), store.neighbors(DocId("folder/new")).map { it.dst })
        assertTrue(store.incomingNeighbors(DocId("other")).any { it.src == DocId("folder/new") })
        assertTrue(store.neighbors(DocId("notes/old")).isEmpty())
    }

    @Test
    @Suppress("FunctionNaming")
    fun `renaming onto a live document is refused and changes nothing`() {
        val store = newStore()
        store.write(document("notes/old", "old body"), "add", "agent")
        store.write(document("notes/taken", "holder body"), "add", "agent")

        assertThrows(ConflictException::class.java) {
            store.rename(DocId("notes/old"), DocId("notes/taken"), "move", "agent")
        }

        assertEquals("old body", store.read(DocId("notes/old"))!!.content)
        assertEquals("holder body", store.read(DocId("notes/taken"))!!.content)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `renaming an unknown document is reported as not found`() {
        val store = newStore()

        assertThrows(NotFoundException::class.java) {
            store.rename(DocId("notes/absent"), DocId("notes/new"), "move", "agent")
        }
    }

    @Test
    @Suppress("FunctionNaming")
    fun `rename honours ifRevision`() {
        val store = newStore()
        store.write(document("notes/old", "body"), "add", "agent")
        // Not the revision passed to write: a store assigns its own. The vault
        // records a Pijul patch hash, so asking for the id we supplied would be
        // asserting an implementation detail instead of the contract.
        val current = store.read(DocId("notes/old"))!!.revision

        assertThrows(ConflictException::class.java) {
            store.rename(DocId("notes/old"), DocId("notes/new"), "move", "agent", RevisionId("stale"))
        }
        store.rename(DocId("notes/old"), DocId("notes/new"), "move", "agent", current)
        assertNotNull(store.read(DocId("notes/new")))
    }

    @Test
    @Suppress("FunctionNaming")
    fun `write then read round-trips the document`() {
        store.write(document("a", "hello"), "msg", "tester")

        val read = store.read(DocId("a"))!!
        assertEquals("hello", read.content)
        assertEquals(Collection("vault"), read.collection)
        assertTrue(read.contentHash.isNotBlank())
    }

    @Test
    @Suppress("FunctionNaming")
    fun `write is idempotent for identical content`() {
        store.write(document("a", "same"), "m", "tester")
        store.write(document("a", "same", revision = "rev-2"), "m", "tester")

        assertEquals(1, store.history(DocId("a")).size)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `stale compare and swap is rejected before an idempotent no-op`() {
        store.write(document("a", "same"), "m", "tester")

        assertThrows(ConflictException::class.java) {
            store.write(document("a", "same"), "m", "tester", ifRevision = RevisionId("stale"))
        }
    }

    /**
     * Path identity — derivation, collection immutability, tombstone paths —
     * lives in [ContextStorePathContract]: this class had reached the detekt
     * size limit, and the path rules deserve their own home.
     */

    @Test
    @Suppress("FunctionNaming")
    fun `ifRevision enforces compare and swap`() {
        store.write(document("a", "one"), "m", "tester")
        val current = store.read(DocId("a"))!!.revision

        assertThrows(ConflictException::class.java) {
            store.write(document("a", "two", revision = "rev-2"), "m", "tester", ifRevision = RevisionId("bogus"))
        }
        store.write(document("a", "two", revision = "rev-2"), "m", "tester", ifRevision = current)

        assertEquals("two", store.read(DocId("a"))!!.content)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `concurrent writers with the same expected revision cannot both commit`() {
        store.write(document("a", "one", revision = "rev-1"), "m", "tester")
        val current = store.read(DocId("a"))!!.revision
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val writes =
                listOf("two-a" to "rev-2", "two-b" to "rev-3").map { (content, revision) ->
                    executor.submit<Revision> {
                        start.await()
                        store.write(document("a", content, revision), "edit", "tester", current)
                    }
                }
            start.countDown()
            val outcomes = writes.map { runCatching { it.get(10, TimeUnit.SECONDS) } }

            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(
                1,
                outcomes.count {
                    val error = it.exceptionOrNull()
                    error is ConflictException || error?.cause is ConflictException
                }
            )
            assertTrue(store.read(DocId("a"))!!.content in setOf("two-a", "two-b"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    @Suppress("FunctionNaming")
    fun `batch reads omit missing and deleted documents`() {
        store.write(document("batch/a", "a"), "m", "tester")
        store.write(document("batch/b", "b"), "m", "tester")
        store.delete(DocId("batch/b"), "gone", "tester")

        assertEquals(
            listOf(DocId("batch/a")),
            store.readMany(listOf(DocId("batch/b"), DocId("batch/a"))).map { it.id }
        )
    }

    @Test
    @Suppress("FunctionNaming")
    fun `batch reads return documents in the requested order without duplicates`() {
        store.write(document("order/a", "a"), "m", "tester")
        store.write(document("order/b", "b"), "m", "tester")
        store.write(document("order/c", "c"), "m", "tester")

        val read = store.readMany(listOf(DocId("order/c"), DocId("order/a"), DocId("order/b"), DocId("order/c")))

        assertEquals(listOf(DocId("order/c"), DocId("order/a"), DocId("order/b")), read.map { it.id })
    }

    @Test
    @Suppress("FunctionNaming")
    fun `neighbors are returned in a deterministic order`() {
        val first =
            listOf(
                Edge(DocId("order"), DocId("z"), RelTypes.References),
                Edge(DocId("order"), DocId("a"), RelTypes.LinksTo),
                Edge(DocId("order"), DocId("m"), RelTypes.LinksTo)
            )
        first.forEach(store::upsertEdge)
        val forward = store.neighbors(DocId("order")).map { it.rel to it.dst.value }

        store.clearEdges()
        first.reversed().forEach(store::upsertEdge)
        val reversed = store.neighbors(DocId("order")).map { it.rel to it.dst.value }

        assertEquals(forward, reversed)
        assertEquals(3, forward.size)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `incoming neighbors return the sources of inbound edges`() {
        store.upsertEdge(Edge(DocId("a"), DocId("target"), RelTypes.LinksTo))
        store.upsertEdge(Edge(DocId("b"), DocId("target"), RelTypes.LinksTo))
        store.upsertEdge(Edge(DocId("a"), DocId("other"), RelTypes.LinksTo))

        val incoming = store.incomingNeighbors(DocId("target"))

        assertEquals(setOf(DocId("a"), DocId("b")), incoming.map { it.src }.toSet())
        assertTrue(incoming.all { it.dst == DocId("target") })
    }

    @Test
    @Suppress("FunctionNaming")
    fun `incoming neighbors narrow by relation`() {
        store.upsertEdge(Edge(DocId("a"), DocId("target"), RelTypes.LinksTo))
        store.upsertEdge(Edge(DocId("b"), DocId("target"), RelTypes.References))

        assertEquals(
            listOf(DocId("a")),
            store.incomingNeighbors(DocId("target"), RelTypes.LinksTo).map { it.src }
        )
        assertEquals(
            listOf(DocId("b")),
            store.incomingNeighbors(DocId("target"), RelTypes.References).map { it.src }
        )
    }

    @Test
    @Suppress("FunctionNaming")
    fun `incoming neighbors for a document with no inbound edges are empty`() {
        store.upsertEdge(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo))

        assertTrue(store.incomingNeighbors(DocId("a")).isEmpty())
    }

    @Test
    @Suppress("FunctionNaming")
    fun `incoming neighbors are returned in a deterministic order`() {
        val edges =
            listOf(
                Edge(DocId("order/b"), DocId("target"), RelTypes.References),
                Edge(DocId("order/a"), DocId("target"), RelTypes.LinksTo),
                Edge(DocId("order/c"), DocId("target"), RelTypes.LinksTo)
            )
        edges.forEach(store::upsertEdge)
        val forward = store.incomingNeighbors(DocId("target")).map { it.rel to it.src.value }

        store.clearEdges()
        edges.reversed().forEach(store::upsertEdge)
        val reversed = store.incomingNeighbors(DocId("target")).map { it.rel to it.src.value }

        assertEquals(forward, reversed)
        assertEquals(3, forward.size)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `incoming neighbors agree with removing incoming edges`() {
        store.upsertEdge(Edge(DocId("a"), DocId("target"), RelTypes.LinksTo))
        store.upsertEdge(Edge(DocId("b"), DocId("target"), RelTypes.LinksTo))
        store.removeIncomingEdges(DocId("target"), RelTypes.LinksTo)

        assertTrue(store.incomingNeighbors(DocId("target")).isEmpty())
    }

    @Test
    @Suppress("FunctionNaming")
    fun `upserting an existing edge replaces it instead of duplicating it`() {
        store.upsertEdge(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo, mapOf("label" to "old")))
        store.upsertEdge(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo, mapOf("label" to "new")))

        val edges = store.neighbors(DocId("a"))
        assertEquals(1, edges.size)
        assertEquals("new", edges.single().props["label"])
    }

    @Test
    @Suppress("FunctionNaming")
    fun `replaceEdges swaps the whole outgoing edge set`() {
        store.upsertEdge(Edge(DocId("a"), DocId("stale"), RelTypes.References))
        store.upsertEdge(Edge(DocId("other"), DocId("a"), RelTypes.LinksTo))

        store.replaceEdges(
            DocId("a"),
            listOf(
                Edge(DocId("a"), DocId("b"), RelTypes.LinksTo),
                Edge(DocId("a"), DocId("b"), RelTypes.LinksTo, mapOf("label" to "ignored")),
                Edge(DocId("a"), DocId("c"), RelTypes.References)
            )
        )

        assertEquals(
            listOf(RelTypes.LinksTo to "b", RelTypes.References to "c"),
            store.neighbors(DocId("a")).map { it.rel to it.dst.value }
        )
        assertEquals(1, store.neighbors(DocId("other")).size)

        store.replaceEdges(DocId("a"), emptyList())

        assertTrue(store.neighbors(DocId("a")).isEmpty())
    }

    @Test
    @Suppress("FunctionNaming")
    fun `history pages are ordered and cover the whole history`() {
        store.write(document("a", "one", revision = "rev-1"), "first", "tester")
        store.write(document("a", "two", revision = "rev-2"), "second", "tester")
        store.write(document("a", "three", revision = "rev-3"), "third", "tester")

        val first = store.historyPage(DocId("a"), limit = 2)
        val second = store.historyPage(DocId("a"), limit = 2, offset = 2)

        assertEquals(3, first.total)
        assertTrue(first.hasMore)
        assertEquals(2, first.items.size)
        assertEquals(1, second.items.size)
        assertEquals(false, second.hasMore)
        assertEquals(store.history(DocId("a")).map { it.revision }, (first.items + second.items).map { it.revision })
    }

    @Test
    @Suppress("FunctionNaming")
    fun `failed projection deliveries are reported as stuck`() {
        val operation =
            gokorei.tanseki.core.domain.ProjectionOperation.upsert(
                document("stuck", "content"),
                RevisionId("rev-1"),
                Instant.fromEpochSeconds(1_700_000_000)
            )
        val operations = requireNotNull(store.projectionOperations())
        operations.enqueue(operation)

        val recorded = operations.recordFailure(operation)

        val backlog = operations.backlog()
        assertTrue(backlog.stuck >= 1)
        assertEquals(1, recorded)
        assertEquals(1, operations.pending(10).single { it.id == operation.id }.attempts)
        assertEquals(2, operations.recordFailure(operations.pending(10).single { it.id == operation.id }))
        assertEquals(0, operations.quarantineCorrupt())
    }

    @Test
    @Suppress("FunctionNaming")
    fun `a healthy outbox has nothing to quarantine`() {
        store.write(document("healthy", "content"), "m", "tester")
        val operations = requireNotNull(store.projectionOperations())

        assertEquals(0, operations.quarantineCorrupt())
        assertEquals(1, operations.backlog().pending)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `importing a tombstone drops the edges that pointed at it`() {
        assumeTrue(store.capabilities().supportsTombstoneImport, "adapter does not import tombstones")
        store.write(document("source", "See [[gone]]"), "m", "tester")
        store.write(document("gone", "deleted body"), "m", "tester")
        store.upsertEdge(Edge(DocId("source"), DocId("gone"), RelTypes.LinksTo))
        val tombstone = document("gone", "deleted body").copy(deleted = true)

        val applied =
            store.importTransferState(
                gokorei.tanseki.core.ports
                    .TransferState(tombstone, emptyList())
            )

        assertTrue(applied)
        assertTrue(store.neighbors(DocId("source")).isEmpty())
    }

    @Test
    @Suppress("FunctionNaming")
    fun `a dead-lettered operation leaves the delivery path but stays visible`() {
        val operation =
            gokorei.tanseki.core.domain.ProjectionOperation.upsert(
                document("abandoned", "content"),
                RevisionId("rev-1"),
                Instant.fromEpochSeconds(1_700_000_000)
            )
        val operations = requireNotNull(store.projectionOperations())
        operations.enqueue(operation)
        operations.recordFailure(operation)

        operations.deadLetter(operation)

        val backlog = operations.backlog()
        assertEquals(0, backlog.pending)
        assertEquals(0, backlog.stuck)
        // The operator signal is the point of dead-lettering: dropping the record
        // would report a clean backlog for an operation nothing ever delivered.
        assertEquals(1, backlog.deadLettered)
        assertEquals(listOf(operation.id), operations.deadLettered().map { it.id })
        assertTrue(
            operations.deadLettered().single().deadLettered,
            "a retained dead letter must come back flagged"
        )
    }

    @Test
    @Suppress("FunctionNaming")
    fun `dead letters do not consume the delivery window`() {
        val operations = requireNotNull(store.projectionOperations())
        // Oldest first, so the dead letters sort ahead of the live operation.
        val abandoned =
            (0 until 3).map { index ->
                gokorei.tanseki.core.domain.ProjectionOperation
                    .upsert(
                        document("abandoned/$index", "content-$index"),
                        RevisionId("rev-$index"),
                        Instant.fromEpochSeconds(1_700_000_000L + index)
                    ).also {
                        operations.enqueue(it)
                        operations.deadLetter(it)
                    }
            }
        val live =
            gokorei.tanseki.core.domain.ProjectionOperation.upsert(
                document("live", "content"),
                RevisionId("rev-live"),
                Instant.fromEpochSeconds(1_700_000_100)
            )
        operations.enqueue(live)

        // A window the size of the live backlog must still deliver the live
        // operation: taking `limit` rows first and dropping the dead ones after
        // would leave nothing to deliver.
        val window = operations.pendingLive(1)

        assertEquals(listOf(live.id), window.map { it.id })
        assertEquals(abandoned.size, operations.backlog().deadLettered)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `import does not roll back a newer local document`() {
        store.write(document("stale", "newer"), "m", "tester")
        val current = store.readIncludingDeleted(DocId("stale"))!!
        val older =
            document("stale", "older").copy(
                revision = RevisionId("rev-older"),
                updatedAt = current.updatedAt.minus(1.seconds)
            )

        val applied =
            store.importTransferState(
                gokorei.tanseki.core.ports
                    .TransferState(older, emptyList())
            )

        assertEquals(false, applied)
        assertEquals("newer", store.read(DocId("stale"))!!.content)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `list returns written documents`() {
        store.write(document("a", "a"), "m", "tester")
        store.write(document("b", "b"), "m", "tester")

        assertEquals(setOf(DocId("a"), DocId("b")), store.list(Collection("vault")).map { it.id }.toSet())
    }

    @Test
    @Suppress("FunctionNaming")
    fun `list pages stay deterministic during concurrent writes`() {
        val initial = (0 until 3).map { index -> document("page/$index", "initial-$index") }
        val concurrent = (3 until 9).map { index -> document("page/$index", "concurrent-$index") }
        initial.forEach { store.write(it, "initial", "tester") }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writes = executor.submit { concurrent.forEach { store.write(it, "write", "tester") } }
            val reads = executor.submit { (0 until 6).map { store.listPage(limit = 3, offset = 0) } }
            writes.get(10, TimeUnit.SECONDS)
            reads.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        val ids =
            (0 until 3).flatMap { offset ->
                store
                    .listPage(
                        limit = 3,
                        offset = offset * 3
                    ).items
                    .map { it.id.value }
            }
        assertEquals((0 until 9).map { "page/$it" }, ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `delete tombstones the document and records its returned revision`() {
        val created = store.write(document("a", "a"), "m", "tester")

        val deleted = store.delete(DocId("a"), "gone", "tester")

        assertNull(store.read(DocId("a")))
        assertTrue(store.list().isEmpty())
        assertEquals(listOf(created.revision, deleted.revision), store.history(DocId("a")).map { it.revision })
        val owner = store.historyOwner(DocId("a"))!!
        assertEquals(Collection("vault"), owner.collection)
        assertEquals("a.md", owner.path)
        assertEquals(deleted.revision, owner.revision)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `a deleted id can be recreated without losing deletion history`() {
        val created = store.write(document("a", "one"), "create", "tester")
        val deleted = store.delete(DocId("a"), "gone", "tester")

        val recreated = store.write(document("a", "two", revision = "rev-2"), "recreate", "tester")

        assertEquals("two", store.read(DocId("a"))!!.content)
        assertEquals(
            listOf(created.revision, deleted.revision, recreated.revision),
            store.history(DocId("a")).map { it.revision }
        )
        assertEquals(recreated.revision, store.historyOwner(DocId("a"))!!.revision)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `delete ifRevision enforces compare and swap without recording a failed deletion`() {
        store.write(document("a", "a"), "m", "tester")

        assertThrows(ConflictException::class.java) {
            store.delete(DocId("a"), "stale", "tester", ifRevision = RevisionId("wrong"))
        }

        assertEquals("a", store.read(DocId("a"))!!.content)
        assertEquals(1, store.history(DocId("a")).size)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `history is recorded`() {
        store.write(document("a", "one"), "first", "tester")
        assertTrue(store.history(DocId("a")).isNotEmpty())
    }

    @Test
    @Suppress("FunctionNaming")
    fun `appendHistory imports foreign revisions idempotently`() {
        assumeTrue(store.capabilities().supportsHistoryImport, "adapter does not import history")
        store.write(document("a", "one"), "m", "tester")
        val imported =
            Revision(
                docId = DocId("a"),
                revision = RevisionId("imported-1"),
                author = "importer",
                message = "imported",
                contentHash = "hash-imported",
                createdAt = Instant.fromEpochSeconds(1_600_000_000)
            )

        store.appendHistory(DocId("a"), listOf(imported))
        store.appendHistory(DocId("a"), listOf(imported))

        val revisions = store.history(DocId("a"))
        assertEquals(1, revisions.count { it.revision == RevisionId("imported-1") })
        assertEquals("imported", revisions.first { it.revision == RevisionId("imported-1") }.message)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `edges upsert and neighbors filter by rel`() {
        store.upsertEdge(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo))
        store.upsertEdge(Edge(DocId("a"), DocId("c"), RelTypes.References))

        assertEquals(2, store.neighbors(DocId("a")).size)
        assertEquals(1, store.neighbors(DocId("a"), RelTypes.LinksTo).size)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `removeEdges drops edges from a source`() {
        store.upsertEdge(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo))
        store.upsertEdge(Edge(DocId("a"), DocId("c"), RelTypes.References))

        store.removeEdges(DocId("a"))
        assertTrue(store.neighbors(DocId("a")).isEmpty())

        store.upsertEdge(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo))
        store.upsertEdge(Edge(DocId("a"), DocId("c"), RelTypes.References))
        store.removeEdges(DocId("a"), RelTypes.LinksTo)
        assertEquals(listOf(RelTypes.References), store.neighbors(DocId("a")).map { it.rel })
    }

    @Test
    @Suppress("FunctionNaming")
    fun `removeIncomingEdges drops edges to a target`() {
        store.upsertEdge(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo))
        store.upsertEdge(Edge(DocId("a"), DocId("c"), RelTypes.References))
        store.upsertEdge(Edge(DocId("d"), DocId("b"), RelTypes.References))

        store.removeIncomingEdges(DocId("b"))

        assertEquals(listOf(RelTypes.References), store.neighbors(DocId("a")).map { it.rel })
        assertTrue(store.neighbors(DocId("d")).isEmpty())
    }

    @Test
    @Suppress("FunctionNaming")
    fun `clearEdges removes every canonical edge`() {
        store.upsertEdge(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo))
        store.upsertEdge(Edge(DocId("c"), DocId("d"), RelTypes.References))

        store.clearEdges()

        assertTrue(store.neighbors(DocId("a")).isEmpty())
        assertTrue(store.neighbors(DocId("c")).isEmpty())
    }

    @Test
    @Suppress("FunctionNaming")
    fun `blobs are content addressed`() {
        val ref = store.putBlob("hello".toByteArray())

        assertArrayEquals("hello".toByteArray(), store.getBlob(ref))
        assertEquals(ref, store.putBlob("hello".toByteArray()))
    }
}

/**
 * Listing and cursor-pagination contract.
 *
 * Split out of [ContextStoreContract] rather than appended to it: that class
 * carries the whole write/read/edge surface and had reached the detekt size
 * limit, and listing has enough of its own rules (keyset exclusivity, prefix
 * literalness, total semantics) to deserve a separate home.
 */
abstract class ContextStoreListingContract {
    protected abstract fun newStore(): ContextStore

    protected open fun closeStore(store: ContextStore) = Unit

    private var storeRef: ContextStore? = null
    protected val store: ContextStore get() = storeRef!!

    @BeforeEach
    fun setUpListing() {
        storeRef = newStore()
    }

    @AfterEach
    fun tearDownListing() {
        storeRef?.let(::closeStore)
        storeRef = null
    }

    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun document(id: String, content: String, revision: String = "rev-1") =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = "hash-$content",
            revision = RevisionId(revision),
            updatedAt = now
        )

    private fun documentAtPath(
        id: String,
        path: String,
        content: String,
        collection: String = "vault"
    ) = Document(
        id = DocId(id),
        collection = Collection(collection),
        path = path,
        content = content,
        contentHash = "hash-$content",
        revision = RevisionId("rev-1"),
        updatedAt = now
    )

    @Test
    @Suppress("FunctionNaming")
    fun `listPageAfter walks the whole set without repeating or skipping`() {
        val ids = (0 until 25).map { "notes/%03d".format(it) }
        ids.forEach { store.write(document(it, "body $it"), "msg", "tester") }

        val seen = mutableListOf<DocId>()
        var after: DocId? = null
        // A page size that does not divide the set, so an off-by-one at the
        // boundary shows up rather than landing between pages.
        var exhausted = false
        repeat(40) {
            val page = store.listPageAfter(after = after, limit = 7)
            if (page.items.isEmpty()) return@repeat
            seen += page.items.map { ref -> ref.id }
            after = page.items.last().id
            if (!page.hasMore) {
                exhausted = true
                return@repeat
            }
        }
        assertTrue(exhausted, "cursor walk never reported the last page")

        assertEquals(ids.size, seen.size, "cursor walk visited ${seen.size} documents")
        assertEquals(ids.size, seen.distinct().size, "cursor walk repeated a document")
        assertEquals(ids.map(::DocId).sortedBy { it.value }, seen, "cursor walk skipped or reordered")
    }

    @Test
    @Suppress("FunctionNaming")
    fun `listPageAfter is exclusive of the cursor document`() {
        store.write(document("a", "x"), "msg", "tester")
        store.write(document("b", "y"), "msg", "tester")

        val page = store.listPageAfter(after = DocId("a"), limit = 10)

        assertEquals(listOf(DocId("b")), page.items.map { it.id })
    }

    @Test
    @Suppress("FunctionNaming")
    fun `listPageAfter reports the full match count as total`() {
        val ids = (0 until 5).map { "notes/%02d".format(it) }
        ids.forEach { store.write(document(it, "body"), "msg", "tester") }
        store.write(document("elsewhere/other", "body"), "msg", "tester")

        val first = store.listPageAfter(pathPrefix = "notes/", limit = 2)

        assertEquals(2, first.items.size)
        assertEquals(5, first.total, "total should be the folder size, not the remainder")
        assertTrue(first.hasMore)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `a path prefix selects exactly one subtree`() {
        store.write(documentAtPath("notes/a", "notes/a.md", "x"), "msg", "tester")
        store.write(documentAtPath("notes/deep/b", "notes/deep/b.md", "x"), "msg", "tester")
        store.write(documentAtPath("notesx/c", "notesx/c.md", "x"), "msg", "tester")
        store.write(documentAtPath("other/d", "other/d.md", "x"), "msg", "tester")

        val page = store.listPage(pathPrefix = "notes/", limit = 50)

        assertEquals(setOf(DocId("notes/a"), DocId("notes/deep/b")), page.items.map { it.id }.toSet())
        assertEquals(2, page.total)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `a path prefix intersects with collection`() {
        // Stored paths are collection-qualified, so a collection filter and a path
        // prefix have to agree on the prefix or the intersection is empty.
        store.write(documentAtPath("alpha/notes/a", "alpha/notes/a.md", "x", collection = "alpha"), "msg", "tester")
        store.write(documentAtPath("beta/notes/b", "beta/notes/b.md", "x", collection = "beta"), "msg", "tester")
        store.write(documentAtPath("alpha/other/c", "alpha/other/c.md", "x", collection = "alpha"), "msg", "tester")

        val page = store.listPage(collection = Collection("alpha"), pathPrefix = "alpha/notes/", limit = 50)

        assertEquals(listOf(DocId("alpha/notes/a")), page.items.map { it.id })
    }

    @Test
    @Suppress("FunctionNaming")
    fun `a path prefix containing a percent sign is literal`() {
        // A folder named `100%` must not act as a LIKE wildcard and pull in
        // every other path in the vault.
        store.write(documentAtPath("100%/a", "100%/a.md", "x"), "msg", "tester")
        store.write(documentAtPath("1000/b", "1000/b.md", "x"), "msg", "tester")

        val page = store.listPage(pathPrefix = "100%/", limit = 50)

        assertEquals(listOf(DocId("100%/a")), page.items.map { it.id })
    }

    @Test
    @Suppress("FunctionNaming")
    fun `a path prefix containing an underscore is literal`() {
        store.write(documentAtPath("a_b/c", "a_b/c.md", "x"), "msg", "tester")
        store.write(documentAtPath("axb/d", "axb/d.md", "x"), "msg", "tester")

        val page = store.listPage(pathPrefix = "a_b/", limit = 50)

        assertEquals(listOf(DocId("a_b/c")), page.items.map { it.id })
    }

    @Test
    @Suppress("FunctionNaming")
    fun `listPage offset and cursor agree on ordering`() {
        val ids = (0 until 12).map { "notes/%02d".format(it) }
        ids.forEach { store.write(document(it, "body"), "msg", "tester") }

        val offsetFirst = store.listPage(limit = 5).items.map { it.id }

        assertEquals(ids.take(5).map(::DocId), offsetFirst, "offset page order must match id order")
    }

    @Test
    @Suppress("FunctionNaming")
    fun `offset pagination still honours the offset`() {
        val ids = (0 until 12).map { "notes/%02d".format(it) }
        ids.forEach { store.write(document(it, "body"), "msg", "tester") }

        val third = store.listPage(limit = 5, offset = 10)

        assertEquals(listOf(DocId("notes/10"), DocId("notes/11")), third.items.map { it.id })
        assertFalse(third.hasMore)
    }
}

/**
 * Edge replacement is all-or-nothing, on every adapter.
 *
 * `replaceEdges` removes a document's outgoing edges before inserting the
 * replacements. Done as two steps, a concurrent reader sees a document with no
 * outgoing edges at all — indistinguishable from one whose links were genuinely
 * deleted — and a crash mid-way leaves the document permanently edge-less.
 *
 * The port's default implementation is remove-then-insert and is explicitly not
 * atomic; it exists so a new adapter is not silently broken. This contract is what
 * catches that, because it runs the same assertions against each backend.
 */
abstract class ContextStoreEdgeReplacementContract {
    protected abstract fun newStore(): ContextStore

    protected open fun closeStore(store: ContextStore) = Unit

    private var storeRef: ContextStore? = null
    protected val store: ContextStore get() = storeRef!!

    @BeforeEach
    fun setUpEdges() {
        storeRef = newStore()
    }

    @AfterEach
    fun tearDownEdges() {
        storeRef?.let(::closeStore)
        storeRef = null
    }

    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun document(id: String, revision: String = "rev-1") =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = "body\n",
            contentHash = "hash-$id",
            revision = RevisionId(revision),
            updatedAt = now
        )

    private fun edge(from: DocId, to: String, rel: RelType = RelTypes.LinksTo) =
        Edge(from, DocId(to), rel)

    private fun targets(src: DocId): List<String> = store.neighbors(src).map { it.dst.value }.sorted()

    @Test
    @Suppress("FunctionNaming")
    fun `replacement swaps the whole outgoing set`() {
        store.write(document("a"), "tester", "tester")
        store.write(document("b"), "tester", "tester")
        store.write(document("c"), "tester", "tester")
        store.upsertEdge(edge(DocId("a"), "b"))

        store.replaceEdges(DocId("a"), listOf(edge(DocId("a"), "c")))

        assertEquals(
            listOf("c"),
            targets(DocId("a")),
            "the previous edge must be gone, not merged with the replacement"
        )
    }

    @Test
    @Suppress("FunctionNaming")
    fun `an empty replacement removes every outgoing edge`() {
        store.write(document("a"), "tester", "tester")
        store.write(document("b"), "tester", "tester")
        store.upsertEdge(edge(DocId("a"), "b"))

        store.replaceEdges(DocId("a"), emptyList())

        assertEquals(emptyList<String>(), targets(DocId("a")))
    }

    @Test
    @Suppress("FunctionNaming")
    fun `a replacement for an unknown document is not an error`() {
        store.replaceEdges(DocId("absent"), listOf(edge(DocId("absent"), "b")))
        assertEquals(listOf("b"), targets(DocId("absent")))
    }

    @Test
    @Suppress("FunctionNaming")
    fun `duplicate edges in one replacement collapse`() {
        store.write(document("a"), "tester", "tester")
        store.write(document("b"), "tester", "tester")

        store.replaceEdges(
            DocId("a"),
            listOf(edge(DocId("a"), "b"), edge(DocId("a"), "b"), edge(DocId("a"), "b"))
        )

        assertEquals(1, store.neighbors(DocId("a")).size, "de-duplication is part of the contract")
    }

    @Test
    @Suppress("FunctionNaming")
    fun `distinct relations to the same target are both kept`() {
        store.write(document("a"), "tester", "tester")
        store.write(document("b"), "tester", "tester")

        store.replaceEdges(
            DocId("a"),
            listOf(edge(DocId("a"), "b", RelTypes.LinksTo), edge(DocId("a"), "b", RelTypes.References))
        )

        assertEquals(
            setOf(RelTypes.LinksTo, RelTypes.References),
            store.neighbors(DocId("a")).map { it.rel }.toSet(),
            "de-duplication is by (rel, dst): a different relation is a different edge"
        )
    }

    @Test
    @Suppress("FunctionNaming")
    fun `a reader never observes an empty or partial edge set during replacement`() {
        store.write(document("a"), "tester", "tester")
        repeat(4) { index ->
            store.write(document("old/$index"), "tester", "tester")
            store.write(document("new/$index"), "tester", "tester")
        }
        val before =
            (0 until 4).map { index -> edge(DocId("a"), "old/$index") }
        val after =
            (0 until 4).map { index -> edge(DocId("a"), "new/$index") }
        store.replaceEdges(DocId("a"), before)

        var torn = 0
        val stop =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val reader =
            Thread {
                while (!stop.get()) {
                    val seen = store.neighbors(DocId("a")).map { it.dst.value }.sorted()
                    if (seen != before.map { it.dst.value }.sorted() && seen != after.map { it.dst.value }.sorted()) {
                        torn++
                    }
                }
            }
        reader.start()
        repeat(40) { round ->
            store.replaceEdges(DocId("a"), if (round % 2 == 0) after else before)
        }
        stop.set(true)
        reader.join(5_000)

        assertEquals(
            0,
            torn,
            "a reader saw a partial edge set; replacement is not atomic on this adapter"
        )
    }

    @Test
    @Suppress("FunctionNaming")
    fun `deleting and recreating a document leaves one coherent edge set`() {
        store.write(document("a"), "tester", "tester")
        store.write(document("b"), "tester", "tester")
        store.upsertEdge(edge(DocId("a"), "b"))

        store.delete(DocId("a"), "tester", "tester")
        // A new revision: the store correctly refuses to reuse one with different
        // history, and this test is about edges, not about revision reuse.
        store.write(document("a", revision = "rev-2"), "tester", "tester")
        store.replaceEdges(DocId("a"), listOf(edge(DocId("a"), "b")))

        assertEquals(listOf("b"), targets(DocId("a")))
    }

    @Test
    @Suppress("FunctionNaming")
    fun `a failed replacement leaves the previous set intact`() {
        store.write(document("a"), "tester", "tester")
        store.write(document("b"), "tester", "tester")
        store.upsertEdge(edge(DocId("a"), "b"))

        // A duplicate target violates the store's uniqueness, so the insert fails
        // part-way through the replacement. Whatever the outcome, it must not leave
        // the document with no edges: the previous set is the only safe answer.
        runCatching {
            store.replaceEdges(
                DocId("a"),
                listOf(edge(DocId("a"), "b"), edge(DocId("a"), "b"))
            )
        }

        assertEquals(
            listOf("b"),
            targets(DocId("a")),
            "a failed replacement must not leave the document edge-less"
        )
    }
}
