package gokorei.tanseki.testkit

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Instant

/**
 * The in-memory store is read by tests that assert the ordering a concurrent
 * writer has just produced, so a reader that can observe a half-updated ledger
 * turns a real ordering bug into a phantom one, and the other way round.
 *
 * `history` walks the same `MutableList` that `appendHistory` appends to, so
 * every accessor and mutator shares one monitor; the test pins the invariant
 * that a reader always sees a whole ledger. Which way a missing monitor fails
 * is a matter of timing — a torn page or a `ConcurrentModificationException`
 * during the copy — so the assertion is the invariant, not one of those.
 */
class InMemoryContextStoreConcurrencyTest {
    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)
    private val id = DocId("notes/one")
    private val rounds = 2_000

    private fun seed(store: InMemoryContextStore) {
        store.write(
            Document(
                id = id,
                collection = Collection("vault"),
                path = "notes/one.md",
                content = "first",
                contentHash = "hash-first",
                revision = RevisionId("rev-1"),
                updatedAt = now
            ),
            "seed",
            "tester"
        )
    }

    private fun imported(index: Int) =
        Revision(
            docId = id,
            revision = RevisionId("imported-$index"),
            author = "tester",
            message = "import $index",
            contentHash = "hash-imported-$index",
            createdAt = now
        )

    @Test
    fun `history is never read while revisions are appended`() {
        val store = InMemoryContextStore { now }
        seed(store)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer =
                executor.submit {
                    start.await()
                    (1..rounds).forEach { store.appendHistory(id, listOf(imported(it))) }
                }
            val reader =
                executor.submit {
                    start.await()
                    (1..rounds).forEach {
                        val history = store.history(id)
                        check(history.map { revision -> revision.revision.value }.distinct().size == history.size) {
                            "history reported the same revision twice"
                        }
                    }
                }
            start.countDown()
            writer.get(30, TimeUnit.SECONDS)
            reader.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertEquals(rounds + 1, store.history(id).size)
    }

    @Test
    fun `deletes and outbox reads never race into a partial view`() {
        val store = InMemoryContextStore { now }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val writers =
                (1..4).map { worker ->
                    executor.submit {
                        start.await()
                        repeat(200) { index ->
                            val docId = DocId("notes/w$worker-$index")
                            store.write(document(docId), "add", "tester")
                            store.delete(docId, "gone", "tester")
                        }
                    }
                }
            val readers =
                (1..2).map {
                    executor.submit {
                        start.await()
                        repeat(300) {
                            // listPageAfter and pendingLive are synchronized like every
                            // other accessor, so a concurrent writer cannot leave them
                            // observing a half-mutated ledger.
                            store.listAll()
                            store.projectionOperations().pendingLive(50)
                            store.listPageAfter(limit = 10)
                        }
                    }
                }
            start.countDown()
            (writers + readers).forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        // Everything was written then tombstoned, so the live set must be empty.
        assertTrue(store.list().isEmpty(), "every write was deleted")
    }

    @Test
    fun `restore with a stale ifRevision is a conflict like the sqlite adapter`() {
        val store = InMemoryContextStore { now }
        store.write(document(DocId("notes/a")), "add", "tester")
        store.delete(DocId("notes/a"), "gone", "tester")

        assertThrows(ConflictException::class.java) {
            store.restore(DocId("notes/a"), "back", "tester", RevisionId("not-current"))
        }
        // A refused restore must leave the tombstone intact.
        assertTrue(store.readIncludingDeleted(DocId("notes/a"))!!.deleted)
    }

    private fun document(docId: DocId): Document =
        Document(
            id = docId,
            collection = Collection("vault"),
            path = "${docId.value}.md",
            content = "first",
            contentHash = "hash-first",
            revision = RevisionId("rev-1"),
            updatedAt = now
        )
}
