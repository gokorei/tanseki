package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Write-behind read cache for the canonical store.
 *
 * Entries hold documents that are durable in the store but not yet reflected in
 * the Lookup, so reads must see them. An entry that is evicted to stay inside
 * [maxEntries] moves to the *unprojected* tail: the store is authoritative
 * again, so an evicted upsert stays listed while an evicted tombstone stays
 * hidden until its delete is projected.
 *
 * That tail is bounded twice: by count, and by [unprojectedTtl]. A projection
 * that never lands (a dead-lettered operation, a store without an outbox) must
 * not hide a document forever, so an unprojected id stops hiding itself once it
 * is older than the TTL, and [expiredUnprojectedIds] makes that recovery
 * observable.
 */
class PendingOverlay(
    private val maxEntries: Int = 1_000,
    private val unprojectedTtl: Duration = DEFAULT_UNPROJECTED_TTL,
    private val now: () -> Instant = { Clock.System.now() }
) {
    private data class Entry(
        val id: DocId,
        val document: Document?,
        val sequence: Long
    )

    private data class Unprojected(
        val sequence: Long,
        val contentHash: String?,
        val deleted: Boolean,
        val evictedAt: Instant
    )

    private val entries = LinkedHashMap<DocId, Entry>()
    private val unprojected = LinkedHashMap<DocId, Unprojected>()
    private val projectedSequences = sortedSetOf<Long>()
    private var sequence = 0L
    private var watermark = 0L

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(!unprojectedTtl.isNegative()) { "unprojectedTtl must not be negative" }
    }

    @Synchronized
    fun put(doc: Document) {
        replace(Entry(doc.id, doc, ++sequence))
    }

    @Synchronized
    fun get(id: DocId): Document? = entries[id]?.document

    @Synchronized
    fun remove(id: DocId) {
        replace(Entry(id, null, ++sequence))
    }

    @Synchronized
    fun isPending(id: DocId): Boolean = id in entries || id in unprojected

    @Synchronized
    fun isDeleted(id: DocId): Boolean =
        (entries[id]?.document == null && id in entries) || unprojected[id]?.deleted == true

    @Synchronized
    fun scan(
        q: String,
        filters: Filters = Filters()
    ): List<DocId> = matchingDocuments(q, filters).map { it.id }

    /**
     * Canonical refs with the overlay applied, in a stable id order so a page
     * never depends on which side (canonical or pending) a ref came from.
     */
    @Synchronized
    fun mergeRefs(
        canonicalRefs: List<DocRef>,
        collection: Collection? = null
    ): List<DocRef> {
        val pendingRefs =
            entries.values.mapNotNull { entry ->
                entry.document?.let { document ->
                    document.ref().takeIf { collection == null || document.collection == collection }
                }
            }
        return (pendingRefs + canonicalRefs.filterNot { hidesFromListing(it.id) }).sortedBy { it.id.value }
    }

    @Synchronized
    fun pendingRefs(collection: Collection? = null): List<DocRef> =
        entries.values
            .mapNotNull { entry ->
                entry.document?.let { document ->
                    document.ref().takeIf { collection == null || document.collection == collection }
                }
            }.sortedBy { it.id.value }

    @Synchronized
    fun merge(
        lookupHits: List<Hit>,
        q: String,
        filters: Filters = Filters(),
        limit: Int = Int.MAX_VALUE
    ): List<Hit> {
        val pendingHits =
            matchingDocuments(q, filters).map { document ->
                Hit(document.id, Double.MAX_VALUE, document.content.take(SNIPPET_LENGTH))
            }
        return (pendingHits + lookupHits.filterNot { hidesFromIndex(it.id) })
            .take(limit.coerceAtLeast(0))
    }

    @Synchronized
    fun filterDeleted(ids: List<DocId>): List<DocId> = ids.filterNot { isDeleted(it) }

    @Synchronized
    fun filterDeletedHits(hits: List<Hit>): List<Hit> = hits.filterNot { isDeleted(it.id) }

    /**
     * Drops index hits the overlay knows are superseded.
     *
     * Wider than [filterDeletedHits], which only hides tombstones. An id with a
     * pending *update* must be hidden too: the Lookup can only hold content older
     * than what the store already has, so serving it would answer with the
     * previous revision of a document the caller has already changed.
     */
    @Synchronized
    fun filterSupersededHits(hits: List<Hit>): List<Hit> = hits.filterNot { hidesFromIndex(it.id) }

    @Synchronized
    fun markProjected(
        id: DocId,
        contentHash: String
    ) {
        val entry = entries[id]
        val document = entry?.document
        if (document?.contentHash == contentHash) {
            evict(entry)
            unprojected.remove(id)?.let { project(it.sequence) }
            return
        }
        val pending = unprojected[id] ?: return
        if (!pending.deleted && pending.contentHash == contentHash) {
            unprojected.remove(id)
            project(pending.sequence)
        }
    }

    @Synchronized
    fun markDeleted(id: DocId) {
        entries[id]?.takeIf { it.document == null }?.let(::evict)
        unprojected.remove(id)?.let { project(it.sequence) }
    }

    @Synchronized
    fun markPersisted(watermark: Long) {
        if (watermark > this.watermark) this.watermark = watermark
        projectedSequences.removeAll { it <= this.watermark }
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.sequence <= this.watermark) iterator.remove()
        }
        unprojected.entries.removeIf { (_, value) ->
            if (value.sequence <= this.watermark) true else false
        }
    }

    @Synchronized
    fun pendingSize(): Int = entries.size

    /** Evicted entries still awaiting projection; bounded by [maxEntries]. */
    @Synchronized
    fun unprojectedSize(): Int = unprojected.size

    /**
     * How many ids the overlay is currently suppressing from index results.
     *
     * A search that filters after the Lookup has already applied its limit must
     * over-fetch by at least this much, or the ids it removes are counted against
     * the limit and the caller receives fewer hits than it asked for.
     */
    @Synchronized
    fun suppressionSize(): Int = entries.size + unprojected.size

    @Synchronized
    fun unprojectedIds(): Set<DocId> = unprojected.keys.toSet()

    /**
     * Unprojected ids whose TTL has elapsed: the overlay stopped hiding them
     * and the canonical store answers for them again. Non-empty means some
     * projection never completed and is worth reporting.
     */
    @Synchronized
    fun expiredUnprojectedIds(): Set<DocId> = unprojected.filterValues(::isExpired).keys.toSet()

    /**
     * Ids the overlay removes from a canonical listing: pending deletes and
     * unprojected tombstones. A paginated total must subtract the ones the
     * store still lists, otherwise the page promises rows it will not return.
     * The overlay does not know which collection a deleted document belonged to,
     * so the caller resolves that from the store.
     */
    @Synchronized
    fun hiddenIds(): Set<DocId> =
        (
            entries.entries.filter { (_, entry) -> entry.document == null }.map { it.key } +
                unprojected.filterValues { it.deleted && !isExpired(it) }.keys
        ).toSet()

    @Synchronized
    fun currentSequence(): Long = sequence

    @Synchronized
    fun lag(): Long = sequence - watermark

    /**
     * Canonical listing hides an id only while the overlay is the sole source of
     * truth about it: a live pending entry, or an evicted tombstone whose delete
     * has not been projected and has not yet timed out. An evicted *upsert* is
     * already durable in the store, so it must stay listed.
     */
    private fun hidesFromListing(id: DocId): Boolean =
        id in entries || unprojected[id]?.let { it.deleted && !isExpired(it) } == true

    /**
     * Index results stay suppressed for every unprojected id: the Lookup can only
     * hold content older than what the store already has, so it is never the
     * better answer.
     */
    private fun hidesFromIndex(id: DocId): Boolean = id in entries || unprojected[id]?.let(::isHiddenFromIndex) == true

    private fun isHiddenFromIndex(pending: Unprojected): Boolean = !isExpired(pending)

    private fun isExpired(pending: Unprojected): Boolean {
        val age = now() - pending.evictedAt
        return age > unprojectedTtl
    }

    private fun replace(entry: Entry) {
        entries.remove(entry.id)?.let { removed -> project(removed.sequence) }
        entries[entry.id] = entry
        while (entries.size > maxEntries) {
            val oldest = entries.entries.first()
            entries.remove(oldest.key)
            unprojected[oldest.key] =
                Unprojected(
                    sequence = oldest.value.sequence,
                    contentHash = oldest.value.document?.contentHash,
                    deleted = oldest.value.document == null,
                    evictedAt = now()
                )
            // Deliberately NOT bounded by count. Dropping the oldest entry here
            // used to stop it suppressing anything at all, immediately and with no
            // recovery: a tombstone evicted this way let a deleted document
            // reappear from the index the moment it was displaced, and an evicted
            // upsert let stale content be served. Evicting the suppression is
            // strictly worse than evicting the document it was suppressing,
            // because the document is still durable in the store — only the
            // knowledge that the store is *newer* than the index is lost.
            //
            // [unprojectedTtl] is the real bound, and it is the right one: it is
            // time-based, so it releases on its own whether or not a projection
            // ever lands, and an entry is three fields rather than a document.
        }
    }

    private fun evict(entry: Entry) {
        if (entries.remove(entry.id, entry)) project(entry.sequence)
    }

    private fun project(sequence: Long) {
        projectedSequences += sequence
        while (projectedSequences.remove(watermark + 1)) {
            watermark++
        }
    }

    private fun matchingDocuments(
        q: String,
        filters: Filters
    ): List<Document> {
        // Blank `q` is a filter-only query, and it is answered here exactly as
        // `LuceneLookup.searchText` answers it: a filter with no text is a real
        // question, and a blank query with nothing to filter on is not one.
        //
        // This guard used to refuse *every* blank `q`, which was correct while the
        // lookup did the same — and became the half of a disagreement the moment that
        // was fixed. `merge` unions the overlay with the index and strips index hits
        // for documents still pending, so a document the overlay declined to match
        // *and* the index has not yet seen is in neither result: invisible. That is
        // not a rare window, it is the whole life of a document whose projection is
        // lagging or failing, which is precisely when someone filters to find the
        // record they just wrote.
        if (q.isBlank() && !filters.isSearching) return emptyList()
        return entries.values.mapNotNull { entry ->
            entry.document?.takeIf { document ->
                (q.isBlank() || document.content.contains(q, ignoreCase = true)) &&
                    matches(document, filters)
            }
        }
    }

    private fun matches(
        document: Document,
        filters: Filters
    ): Boolean =
        (filters.collections.isEmpty() || document.collection.value in filters.collections) &&
            document.frontmatter.tags.containsAll(filters.tags) &&
            filters.frontmatter.all { (key, value) -> document.frontmatter.values[key] == value }

    private fun Document.ref() =
        DocRef(
            id = id,
            collection = collection,
            path = path,
            contentHash = contentHash,
            revision = revision,
            updatedAt = updatedAt
        )

    private companion object {
        const val SNIPPET_LENGTH = 160
        val DEFAULT_UNPROJECTED_TTL: Duration = 5.minutes
    }
}
