package gokorei.tanseki.adapters.context.sqlite

import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.documentPath
import gokorei.tanseki.core.ports.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The conflict and revision-shaping rules the write paths share.
 *
 * The one path rule every profile shares: a document's path is its id plus
 * the Markdown suffix. The vault stores the file at exactly that relative
 * path, so this adapter — which could physically store anything — refuses
 * divergent paths instead of recording metadata the vault cannot round-trip.
 */
internal fun requireDerivedPath(doc: Document) {
    if (doc.path != doc.id.documentPath()) {
        throw InvalidInputException("document path must be derived from its id")
    }
}

internal fun pathHeldConflict(from: DocId, path: String, holderId: String): ConflictException =
    ConflictException(from, "path $path is held by $holderId; resolve the collision before renaming")

internal fun pathConflict(doc: Document, ownerId: String): ConflictException =
    ConflictException(
        doc.id,
        "path '${doc.path}' is already owned by '$ownerId' in collection '${doc.collection.value}'"
    )

internal fun pathConflict(doc: Document): ConflictException =
    ConflictException(
        doc.id,
        "path '${doc.path}' is already owned in collection '${doc.collection.value}'"
    )

internal fun collectionConflict(doc: Document, ownerId: String): ConflictException =
    ConflictException(
        doc.id,
        "collection is immutable for document '$ownerId'"
    )

internal fun revisionConflict(doc: Document): ConflictException =
    revisionConflict(doc.id, doc.revision)

internal fun revisionConflict(id: DocId, revision: RevisionId): ConflictException =
    ConflictException(id, "revision '${revision.value}' has already been used with different history")

internal fun sameRevisionContent(existingHash: String, existingContent: String?, revision: Revision): Boolean =
    existingHash == revision.contentHash &&
        (existingContent == null || revision.content == null || existingContent == revision.content)

internal fun nextHistoryTimestamp(clock: Clock, documentTime: Instant, latest: Revision?): Instant =
    maxOf(clock.now(), documentTime, latest?.createdAt ?: documentTime) + 1.milliseconds

internal fun synthesizeRevision(doc: Document, author: String, message: String) =
    Revision(
        docId = doc.id,
        revision = doc.revision,
        author = author,
        message = message,
        contentHash = doc.contentHash,
        createdAt = doc.updatedAt
    )
