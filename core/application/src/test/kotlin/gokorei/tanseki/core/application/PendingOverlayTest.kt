package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.NumberValue
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PendingOverlayTest {
    private val at: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun doc(id: String, content: String = "body") =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = "hash-$id-$content",
            revision = RevisionId("rev"),
            updatedAt = Instant.fromEpochSeconds(1_700_000_000)
        )

    @Test
    fun `recently written docs are immediately searchable`() {
        val overlay = PendingOverlay()
        overlay.put(doc("a", "hello world"))
        overlay.put(doc("b", "other"))

        assertEquals(listOf(DocId("a")), overlay.scan("hello"))
    }

    @Test
    fun `a filter-only query reaches a document the index has not seen`() {
        val overlay = PendingOverlay()
        overlay.put(doc("a", "hello world"))

        // The index has not seen this document, so `merge` strips its lookup hit and
        // the overlay is the only place it can be found. If the overlay declined a blank
        // query the document would be in neither result -- invisible precisely while its
        // projection is lagging, which is when someone filters to find what they just wrote.
        assertEquals(
            listOf(DocId("a")),
            overlay
                .merge(emptyList(), "", Filters(collections = setOf("vault")))
                .map { it.id }
        )
    }

    @Test
    fun `a blank query with nothing to filter on still matches nothing`() {
        val overlay = PendingOverlay()
        overlay.put(doc("a", "hello world"))

        assertEquals(emptyList<Hit>(), overlay.merge(emptyList(), "", Filters()))
    }

    @Test
    fun `a filter-only query narrows by a typed frontmatter value`() {
        val overlay = PendingOverlay()
        overlay.put(doc("a").copy(frontmatter = Frontmatter(values = mapOf("pr" to NumberValue("42")))))
        overlay.put(doc("b").copy(frontmatter = Frontmatter(values = mapOf("pr" to NumberValue("7")))))

        assertEquals(
            listOf(DocId("a")),
            overlay
                .merge(emptyList(), "", Filters(frontmatter = mapOf("pr" to NumberValue("42"))))
                .map { it.id }
        )
    }

    @Test
    fun `merge puts pending ahead and dedupes lookup hits`() {
        val overlay = PendingOverlay()
        overlay.put(doc("a", "x"))

        val merged = overlay.merge(listOf(Hit(DocId("a"), 1.0), Hit(DocId("b"), 0.5)), "x")

        assertEquals(listOf(DocId("a"), DocId("b")), merged.map { it.id })
        assertEquals(1, merged.count { it.id == DocId("a") })
    }

    @Test
    fun `eviction follows the watermark`() {
        val overlay = PendingOverlay()
        overlay.put(doc("a", "1"))
        overlay.put(doc("b", "2"))

        overlay.markPersisted(1)

        assertEquals(1, overlay.pendingSize())
        assertNull(overlay.get(DocId("a")))
    }

    @Test
    fun `pending content replaces and suppresses stale lookup hits`() {
        val overlay = PendingOverlay()
        overlay.put(doc("a", "fresh content"))

        val merged = overlay.merge(listOf(Hit(DocId("a"), 1.0, "stale content")), "fresh")
        val suppressed = overlay.merge(listOf(Hit(DocId("a"), 1.0, "stale content")), "stale")

        assertEquals(1, merged.count { it.id == DocId("a") })
        assertEquals("fresh content", merged.single().snippet)
        assertEquals(Double.MAX_VALUE, merged.single().score)
        assertTrue(suppressed.isEmpty())
    }

    @Test
    fun `delete tombstone suppresses stale hits until projection completion`() {
        val overlay = PendingOverlay()
        overlay.remove(DocId("a"))

        assertTrue(overlay.merge(listOf(Hit(DocId("a"), 1.0)), "a").isEmpty())
        assertEquals(1, overlay.pendingSize())
        assertEquals(1L, overlay.lag())

        overlay.markDeleted(DocId("a"))

        assertEquals(0, overlay.pendingSize())
        assertEquals(0L, overlay.lag())
    }

    @Test
    fun `replacement keeps one pending sequence until matching projection completes`() {
        val overlay = PendingOverlay()
        val first = doc("a", "first").copy(revision = RevisionId("rev-1"))
        val second = doc("a", "second").copy(revision = RevisionId("rev-2"))
        overlay.put(first)
        overlay.put(second)

        overlay.markProjected(DocId("a"), first.contentHash)

        assertEquals(1, overlay.pendingSize())
        assertEquals(1L, overlay.lag())

        overlay.markProjected(DocId("a"), second.contentHash)

        assertEquals(0, overlay.pendingSize())
        assertEquals(0L, overlay.lag())
    }

    @Test
    fun `bounded with oldest-first overflow`() {
        val overlay = PendingOverlay(maxEntries = 2)
        overlay.put(doc("a", "1"))
        overlay.put(doc("b", "2"))
        overlay.put(doc("c", "3"))

        assertEquals(2, overlay.pendingSize())
        assertNull(overlay.get(DocId("a")))
        assertEquals(doc("c", "3").id, overlay.get(DocId("c"))!!.id)
    }

    @Test
    fun `overflow suppresses stale hits and does not advance the projection watermark`() {
        val overlay = PendingOverlay(maxEntries = 1)
        val first = doc("a", "old")
        val second = doc("b", "new")
        overlay.put(first)
        overlay.put(second)

        assertTrue(overlay.merge(listOf(Hit(DocId("a"), 1.0)), "old").isEmpty())
        assertEquals(2L, overlay.lag())
        assertNull(overlay.get(DocId("a")))

        overlay.markProjected(DocId("a"), first.contentHash)

        assertEquals(1L, overlay.lag())
        assertEquals(listOf(DocId("b")), overlay.scan("new"))

        overlay.markProjected(DocId("b"), second.contentHash)

        assertEquals(0L, overlay.lag())
    }

    @Test
    fun `lag and pending size are exposed`() {
        val overlay = PendingOverlay()
        overlay.put(doc("a", "1"))
        overlay.put(doc("b", "2"))

        assertEquals(2, overlay.pendingSize())
        assertEquals(2L, overlay.lag())

        overlay.markPersisted(2)
        assertEquals(0, overlay.pendingSize())
        assertEquals(0L, overlay.lag())
    }

    @Test
    fun `evicted upserts stay listed because the store is already durable`() {
        val overlay = PendingOverlay(maxEntries = 1)
        val first = doc("a", "first")
        val second = doc("b", "second")
        overlay.put(first)
        overlay.put(second)

        val canonical =
            listOf(
                DocRef(first.id, first.collection, first.path, first.contentHash, first.revision, first.updatedAt),
                DocRef(second.id, second.collection, second.path, second.contentHash, second.revision, second.updatedAt)
            )

        assertEquals(setOf(DocId("a"), DocId("b")), overlay.mergeRefs(canonical).map { it.id }.toSet())
        assertEquals(1, overlay.unprojectedSize())
        assertEquals(setOf(DocId("a")), overlay.unprojectedIds())
    }

    @Test
    fun `evicted tombstones stay hidden until the delete is projected`() {
        val overlay = PendingOverlay(maxEntries = 1)
        overlay.remove(DocId("a"))
        overlay.put(doc("b", "keep"))

        val canonical =
            listOf(DocRef(DocId("a"), Collection("vault"), "a.md"), DocRef(DocId("b"), Collection("vault"), "b.md"))

        assertEquals(listOf(DocId("b")), overlay.mergeRefs(canonical).map { it.id })
        assertEquals(1, overlay.unprojectedSize())

        overlay.markDeleted(DocId("a"))

        assertEquals(0, overlay.unprojectedSize())
        assertEquals(listOf(DocId("a"), DocId("b")), overlay.mergeRefs(canonical).map { it.id })
    }

    @Test
    fun `merged refs keep a stable id order regardless of which side they came from`() {
        val overlay = PendingOverlay()
        overlay.put(doc("m", "pending"))

        val canonical =
            listOf(
                DocRef(DocId("z"), Collection("vault"), "z.md"),
                DocRef(DocId("a"), Collection("vault"), "a.md")
            )

        assertEquals(listOf(DocId("a"), DocId("m"), DocId("z")), overlay.mergeRefs(canonical).map { it.id })
    }

    @Test
    fun `hidden ids cover pending deletes and unprojected tombstones only`() {
        val overlay = PendingOverlay(maxEntries = 1)
        overlay.put(doc("kept", "content"))
        overlay.put(doc("evicted", "content"))
        overlay.remove(DocId("deleted"))

        val hidden = overlay.hiddenIds()

        assertEquals(setOf(DocId("deleted")), hidden)
        assertTrue(DocId("evicted") !in hidden)
    }

    @Test
    fun `an unprojected tombstone stops hiding the document once its ttl elapses`() {
        var now = Instant.fromEpochSeconds(1_700_000_000)
        val overlay = PendingOverlay(maxEntries = 1, unprojectedTtl = 30.seconds, now = { now })
        overlay.remove(DocId("gone"))
        overlay.put(doc("other", "keep"))
        val canonical = listOf(DocRef(DocId("gone"), Collection("vault"), "gone.md"))

        assertEquals(listOf(DocId("other")), overlay.mergeRefs(canonical).map { it.id })
        assertEquals(setOf(DocId("gone")), overlay.hiddenIds())
        assertEquals(emptySet<DocId>(), overlay.expiredUnprojectedIds())

        now += 31.seconds

        assertEquals(listOf(DocId("gone"), DocId("other")), overlay.mergeRefs(canonical).map { it.id })
        assertEquals(emptySet<DocId>(), overlay.hiddenIds())
        assertEquals(setOf(DocId("gone")), overlay.expiredUnprojectedIds())
        assertEquals(1, overlay.unprojectedSize())
    }

    @Test
    fun `entries are bounded by count and the unprojected tail is bounded by ttl`() {
        var clock = Instant.fromEpochSeconds(1_700_000_000)
        val overlay = PendingOverlay(maxEntries = 2, unprojectedTtl = 1.minutes, now = { clock })
        repeat(6) { index -> overlay.put(doc("doc-$index", "$index")) }

        assertEquals(2, overlay.pendingSize(), "the entry tail is bounded by count")
        assertTrue(overlay.lag() <= 6L)
        // The unprojected tail is deliberately NOT bounded by count: dropping one
        // stops it suppressing anything, immediately. The TTL bounds it instead.
        assertEquals(4, overlay.unprojectedSize())
        assertTrue(overlay.unprojectedIds().contains(DocId("doc-0")))
        assertFalse(overlay.hiddenIds().contains(DocId("doc-0")), "an evicted upsert stays listed")

        clock += 2.minutes
        assertTrue(
            overlay.hiddenIds().isEmpty() || overlay.hiddenIds().none { it.value.startsWith("doc-") },
            "expiry releases the suppression: ${overlay.hiddenIds()}"
        )
    }

    /**
     * Evicting the *suppression* is worse than evicting the document it suppresses.
     *
     * The entry tail is bounded by `maxEntries`, which is fine: an evicted document
     * is still durable in the store, so the store simply answers for it again. The
     * unprojected tail is different — it records that the store is *newer* than the
     * index, and capping it by count dropped that knowledge on the floor, with no
     * recovery and no TTL. A deleted document then reappeared from the index.
     */
    @Test
    fun `overflow keeps suppressing deletes well past the entry limit`() {
        val overlay = PendingOverlay(maxEntries = 8, unprojectedTtl = 1.hours, now = { at })
        overlay.remove(DocId("doomed"))
        repeat(200) { overlay.put(doc("filler/$it")) }

        assertTrue(
            overlay.unprojectedSize() >= 200 - 8,
            "the unprojected tail must not be capped by count: ${overlay.unprojectedSize()}"
        )
        assertTrue(overlay.isDeleted(DocId("doomed")), "the tombstone must stay suppressed")
        assertTrue(
            overlay.hiddenIds().contains(DocId("doomed")),
            "a deleted document must stay out of a canonical listing"
        )
        assertTrue(
            overlay.filterSupersededHits(listOf(Hit(DocId("doomed"), 1.0, "resurrected"))).isEmpty(),
            "a deleted document must not be served from the index"
        )
    }

    @Test
    fun `overflow keeps suppressing stale updated content`() {
        val overlay = PendingOverlay(maxEntries = 8, unprojectedTtl = 1.hours, now = { at })
        overlay.put(doc("edited"))
        repeat(200) { overlay.put(doc("filler/$it")) }

        assertTrue(overlay.isPending(DocId("edited")))
        assertTrue(
            overlay.filterSupersededHits(listOf(Hit(DocId("edited"), 1.0, "old revision"))).isEmpty(),
            "an evicted upsert must not let stale index content be served"
        )
    }

    /**
     * Vector search suppressed only tombstones while text search suppressed
     * everything the overlay knows is superseded, so a document you had just
     * updated was still returned — at its previous revision — from a vector query.
     */
    @Test
    fun `vector-style filtering suppresses pending updates, not only deletes`() {
        val overlay = PendingOverlay(now = { at })
        overlay.put(doc("edited"))

        assertTrue(
            overlay.filterDeletedHits(listOf(Hit(DocId("edited"), 1.0, "old"))).isNotEmpty(),
            "filterDeletedHits hides tombstones only; this is the gap being closed"
        )
        assertTrue(
            overlay.filterSupersededHits(listOf(Hit(DocId("edited"), 1.0, "old"))).isEmpty()
        )
    }

    @Test
    fun `suppression size reports what a search must over-fetch for`() {
        val overlay = PendingOverlay(now = { at })
        assertEquals(0, overlay.suppressionSize())

        overlay.put(doc("a"))
        overlay.remove(DocId("b"))
        assertEquals(2, overlay.suppressionSize())

        overlay.put(doc("c"))
        assertEquals(3, overlay.suppressionSize())
    }

    @Test
    fun `an expired unprojected entry releases its suppression`() {
        var clock = at
        val overlay = PendingOverlay(maxEntries = 1, unprojectedTtl = 1.minutes, now = { clock })
        overlay.remove(DocId("doomed"))
        overlay.put(doc("filler"))

        assertTrue(overlay.hiddenIds().contains(DocId("doomed")))
        assertTrue(overlay.filterSupersededHits(listOf(Hit(DocId("doomed"), 1.0, "x"))).isEmpty())

        clock += 2.minutes
        assertFalse(
            overlay.filterSupersededHits(listOf(Hit(DocId("doomed"), 1.0, "x"))).isEmpty(),
            "the TTL is the real bound and must release the suppression"
        )
        assertTrue(overlay.expiredUnprojectedIds().contains(DocId("doomed")))
    }
}
