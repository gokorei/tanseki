package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document

/**
 * Collection ownership for one id: the live document's collection, else the
 * tombstone's.
 *
 * Collections are immutable for an id, exactly as on the database adapters,
 * which reject a cross-collection rewrite even across deletion. Silently
 * retagging here would move the document across collections on one profile
 * while the others report a conflict, so the write path asks here first. The
 * tombstone counts too, so ownership survives deletion on every profile rather
 * than only where the row is still present.
 *
 * Kept out of [MutationPipeline] so that pipeline stays at its complexity and
 * size budgets: this is one rule with its own reads, not one more branch in
 * the write path.
 */
internal class CollectionOwnership(
    private val layout: StoreLayout,
    private val files: MetadataFiles,
    private val tombstones: TombstoneIndex
) {
    fun requireImmutable(live: Document?, doc: Document) {
        val owner = live?.collection ?: tombstoned(doc.id) ?: return
        if (owner != doc.collection) {
            throw ConflictException(doc.id, "collection is immutable for document '${doc.id.value}'")
        }
    }

    /**
     * The collection a deleted document belonged to, if its tombstone is still
     * on disk and still decodes. A corrupt or absent tombstone reads as absent:
     * quarantining belongs to the listing paths, and a write must not fail on
     * metadata it was never going to trust.
     */
    private fun tombstoned(id: DocId): Collection? {
        val file = files.metadataFileOrNull(layout.tombstonesDir, id) ?: return null
        return runCatching { Collection(tombstones.readTombstone(id, file).collection) }.getOrNull()
    }
}
