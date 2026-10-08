package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.SyncBatch
import gokorei.tanseki.core.ports.SyncTransport
import gokorei.tanseki.core.ports.TransferState
import gokorei.tanseki.testkit.InMemoryContextStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The sync contract both transports share: two replicas converge, conflicts
 * follow the chosen model, and the passes tolerate offline replicas.
 *
 * These tests run against [InMemoryContextStore] on both sides, so they pin the
 * decision-independent properties, not any one adapter: disjoint writes meet,
 * concurrent edits pick one deterministic winner and say so in the report,
 * deletes replicate as tombstones, and a partitioned link heals without loss.
 * The conflict rules under test are ADR 0001's (object-store push-pull, CRDT
 * deferred): tombstones beat concurrent edits, otherwise last writer wins.
 */
class SyncServiceTest {
    private val t0 = Instant.fromEpochSeconds(1_700_000_000)

    /**
     * A bidirectional link with two endpoints and a partition switch.
     *
     * Pushes always buffer locally; pulls deliver only while healed. That is the
     * offline model: a partitioned replica keeps working, its batches wait, and
     * healing delivers them in order.
     */
    private class PartitionableLink {
        var partitioned = false
        private val aToB = ArrayDeque<TransferState>()
        private val bToA = ArrayDeque<TransferState>()
        val endpointA = Endpoint(aToB, bToA)
        val endpointB = Endpoint(bToA, aToB)

        inner class Endpoint(
            private val outbox: ArrayDeque<TransferState>,
            private val inbox: ArrayDeque<TransferState>
        ) : SyncTransport {
            override fun push(batch: SyncBatch) {
                synchronized(this@PartitionableLink) { outbox.addAll(batch.states) }
            }

            override fun pull(): SyncBatch =
                synchronized(this@PartitionableLink) {
                    if (partitioned) return SyncBatch()
                    val drained = inbox.toList()
                    inbox.clear()
                    SyncBatch(drained)
                }
        }
    }

    private fun replica() = InMemoryContextStore(clock = { t0 })

    private fun doc(id: String, content: String, at: Instant): Document {
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = hash,
            revision = RevisionId("seed"),
            updatedAt = at
        )
    }

    private fun write(store: InMemoryContextStore, id: String, content: String, at: Instant = t0) {
        store.write(doc(id, content, at), "test", "test", null)
    }

    private fun content(store: InMemoryContextStore, id: String): String? = store.read(DocId(id))?.content

    @Test
    fun `disjoint writes converge both ways and the second pass is a no-op`() {
        val a = replica()
        val b = replica()
        write(a, "notes/a", "alpha")
        write(b, "notes/b", "beta")
        val service = SyncService()

        val first = service.sync(a, b)

        assertEquals("alpha", content(a, "notes/a"))
        assertEquals("beta", content(a, "notes/b"))
        assertEquals("alpha", content(b, "notes/a"))
        assertEquals("beta", content(b, "notes/b"))
        assertEquals(1, first.appliedToLocal)
        assertEquals(1, first.appliedToRemote)
        assertTrue(first.conflicts.isEmpty())

        val second = service.sync(a, b)

        assertEquals(0, second.appliedToLocal)
        assertEquals(0, second.appliedToRemote)
        assertEquals(0, second.skippedStale)
        assertTrue(second.conflicts.isEmpty())
    }

    @Test
    fun `concurrent edits converge on the last writer and record the conflict`() {
        val a = replica()
        val b = replica()
        val service = SyncService()
        write(a, "notes/a", "base")
        service.sync(a, b)

        write(a, "notes/a", "from-a", t0 + 10.seconds)
        write(b, "notes/a", "from-b", t0 + 20.seconds)

        val report = service.sync(a, b)

        assertEquals("from-b", content(a, "notes/a"))
        assertEquals("from-b", content(b, "notes/a"))
        assertEquals(1, report.conflicts.size)
        assertEquals(DocId("notes/a"), report.conflicts.single().documentId)
        assertEquals(SyncSide.REMOTE, report.conflicts.single().winner)
    }

    @Test
    fun `edit versus delete converges on the tombstone and records the conflict`() {
        val a = replica()
        val b = replica()
        val service = SyncService()
        write(a, "notes/a", "base")
        service.sync(a, b)

        write(a, "notes/a", "edited after delete", t0 + 60.seconds)
        b.delete(DocId("notes/a"), "remove", "test", null)

        val report = service.sync(a, b)

        assertNull(content(a, "notes/a"))
        assertNull(content(b, "notes/a"))
        assertTrue(a.readIncludingDeleted(DocId("notes/a"))?.deleted == true)
        assertTrue(b.readIncludingDeleted(DocId("notes/a"))?.deleted == true)
        assertEquals(1, report.conflicts.size)
    }

    @Test
    fun `transport rounds converge like a direct sync`() {
        val a = replica()
        val b = replica()
        val link = PartitionableLink()
        val service = SyncService()
        write(a, "notes/a", "aaa")
        write(b, "notes/b", "bbb")
        write(a, "notes/c", "ca", t0 + 10.seconds)
        write(b, "notes/c", "cb", t0 + 20.seconds)

        service.pushFrom(a, link.endpointA)
        service.pushFrom(b, link.endpointB)
        val pullB = service.pullInto(b, link.endpointB)
        val pullA = service.pullInto(a, link.endpointA)
        service.pullInto(a, link.endpointA)
        service.pullInto(b, link.endpointB)

        assertEquals("aaa", content(a, "notes/a"))
        assertEquals("bbb", content(a, "notes/b"))
        assertEquals("cb", content(a, "notes/c"))
        assertEquals("aaa", content(b, "notes/a"))
        assertEquals("bbb", content(b, "notes/b"))
        assertEquals("cb", content(b, "notes/c"))
        assertEquals(1, pullB.conflicts.size)
        assertEquals(SyncSide.LOCAL, pullB.conflicts.single().winner)
        assertEquals(1, pullA.conflicts.size)
        assertEquals(SyncSide.REMOTE, pullA.conflicts.single().winner)

        val quiet = service.sync(a, b)
        assertEquals(0, quiet.appliedToLocal)
        assertEquals(0, quiet.appliedToRemote)
    }

    @Test
    fun `a partitioned link heals without loss`() {
        val a = replica()
        val b = replica()
        val link = PartitionableLink()
        val service = SyncService()
        write(a, "notes/shared", "base")
        service.pushFrom(a, link.endpointA)
        service.pullInto(b, link.endpointB)

        link.partitioned = true
        write(a, "notes/a-only", "aaa")
        b.delete(DocId("notes/shared"), "remove", "test", null)
        service.pushFrom(a, link.endpointA)
        val duringOutage = service.pullInto(b, link.endpointB)

        assertEquals(0, duringOutage.appliedToLocal)
        assertNull(content(b, "notes/a-only"))
        assertEquals("base", content(a, "notes/shared"))

        link.partitioned = false
        service.pushFrom(b, link.endpointB)
        service.pullInto(a, link.endpointA)
        service.pushFrom(a, link.endpointA)
        service.pullInto(b, link.endpointB)

        assertEquals("aaa", content(a, "notes/a-only"))
        assertEquals("aaa", content(b, "notes/a-only"))
        assertNull(content(a, "notes/shared"))
        assertNull(content(b, "notes/shared"))

        val quiet = service.sync(a, b)
        assertEquals(0, quiet.appliedToLocal)
        assertEquals(0, quiet.appliedToRemote)
    }
}
