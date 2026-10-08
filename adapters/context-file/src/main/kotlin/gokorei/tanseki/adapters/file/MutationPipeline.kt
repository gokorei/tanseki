@file:Suppress("ThrowsCount")

package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.documentIdFromPath
import gokorei.tanseki.core.domain.documentPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.text.LinkResolver
import gokorei.tanseki.core.text.WikilinkRewriter
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.time.Instant

/**
 * The document mutation paths: write, delete, restore and rename, each recorded
 * through the pending-history journal and delivered through the outbox.
 *
 * Kept out of [FileContextStore] so the four pipelines (which share the same
 * shape — drive the journal, enqueue the projection work, return the recorded
 * revision) are one place instead of half the store. The per-document locks are
 * taken here, and link rewrites after a rename go through [relinkDocument],
 * which writes through the same path to earn its own revision.
 */
internal class MutationPipeline(
    private val layout: StoreLayout,
    private val files: MetadataFiles,
    private val scanner: VaultScanner,
    private val clock: Clock,
    private val locks: DocumentLocks,
    private val store: FileContextStore,
    private val outbox: ProjectionOutbox,
    private val journal: PendingHistoryJournal,
    private val recorder: PendingRecorder,
    private val tombstones: TombstoneIndex
) {
    private val ownership = CollectionOwnership(layout, files, tombstones)

    fun write(
        doc: Document,
        message: String,
        author: String,
        ifRevision: RevisionId? = null
    ): Revision =
        locks.withDocumentLock(layout.fileFor(doc.id)) {
            writeStaged(doc, message, author, ifRevision)
        }

    private fun writeStaged(
        doc: Document,
        message: String,
        author: String,
        ifRevision: RevisionId?
    ): Revision {
        if (doc.deleted) throw InvalidInputException("deleted documents must be written with delete()")
        if (doc.path != doc.id.documentPath()) {
            throw InvalidInputException("document path must be derived from its id")
        }
        journal.recoverPendingHistory(doc.id)
        val existing = store.read(doc.id)
        ownership.requireImmutable(existing, doc)
        val hash = Hashes.sha256(doc.content)

        if (ifRevision != null && existing?.revision != ifRevision) {
            throw ConflictException(
                doc.id,
                "revision mismatch: expected ${ifRevision.value}, found ${existing?.revision?.value ?: "none"}"
            )
        }
        if (existing != null && existing.contentHash == hash) {
            return Revision(doc.id, existing.revision, author, message, hash, existing.updatedAt)
        }

        val file = layout.fileFor(doc.id)
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) layout.requireSafeRegularFile(file)
        Files.createDirectories(file.parent)
        layout.requireContainedRealPath(file.parent)
        if (Files.isSymbolicLink(file)) throw layout.invalidPath("symbolic links are not valid document files")
        val pending =
            PendingHistory(
                action = PendingHistoryAction.WRITE,
                state = PendingHistoryState.PREPARED,
                documentId = doc.id,
                path = layout.relative(file),
                message = message,
                author = author,
                content = doc.content,
                contentHash = hash,
                previousContentHash = existing?.contentHash,
                previousRevision = existing?.revision?.value ?: UNRECORDED,
                collection = doc.collection.value,
                createdAt = clock.now(),
                operationId =
                    recorder.pendingOperationId(
                        "WRITE",
                        doc.id,
                        hash,
                        existing?.revision?.value ?: UNRECORDED
                    ),
                recordedRevision = null,
                recordedAt = null
            )
        journal.writePendingHistory(pending)
        outbox.enqueue(recorder.projectionOperation(pending))
        val applied = journal.applyPendingWrite(pending)
        val patch = recorder.recordPendingHistory(applied)
        outbox.enqueue(recorder.projectionOperation(applied, patch.hash))

        return Revision(
            docId = doc.id,
            revision = patch.hash,
            author = author,
            message = message,
            contentHash = hash,
            createdAt = patch.timestamp
        )
    }

    fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
        locks.withDocumentLock(layout.fileFor(id)) {
            deleteStaged(id, message, author, ifRevision)
        }

    private fun deleteStaged(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision {
        val recovered = journal.recoverPendingHistory(id)
        if (recovered?.action == PendingHistoryAction.DELETE) {
            if (ifRevision != null && recovered.previousRevision != ifRevision.value) {
                throw ConflictException(id, "revision mismatch")
            }
            return Revision(
                id,
                recovered.patch.hash,
                recovered.patch.author,
                recovered.patch.message,
                recovered.contentHash,
                recovered.patch.timestamp
            )
        }
        val file = layout.fileFor(id)
        if (!layout.requireSafeRegularFile(file)) throw NotFoundException(id)
        val previous = store.read(id)!!
        if (ifRevision != null && previous.revision != ifRevision) {
            throw ConflictException(
                id,
                "revision mismatch: expected ${ifRevision.value}, found ${previous.revision.value}"
            )
        }
        val pending =
            PendingHistory(
                action = PendingHistoryAction.DELETE,
                state = PendingHistoryState.PREPARED,
                documentId = id,
                path = layout.relative(file),
                message = message,
                author = author,
                content = "",
                contentHash = previous.contentHash,
                previousContentHash = previous.contentHash,
                previousRevision = previous.revision.value,
                collection = previous.collection.value,
                createdAt = clock.now(),
                operationId = recorder.pendingOperationId("DELETE", id, previous.contentHash, previous.revision.value),
                recordedRevision = null,
                recordedAt = null,
                previousContent = previous.content
            )
        journal.writePendingHistory(pending)
        outbox.enqueue(recorder.projectionOperation(pending))
        val applied = journal.applyPendingDelete(pending)
        val patch = recorder.recordPendingHistory(applied)
        outbox.enqueue(recorder.projectionOperation(applied, patch.hash))

        return Revision(id, patch.hash, author, message, previous.contentHash, patch.timestamp)
    }

    fun restore(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
        locks.withDocumentLock(layout.fileFor(id)) {
            restoreStaged(id, message, author, ifRevision)
        }

    private fun restoreStaged(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision {
        // Distinguish "live" from "never seen" before touching the tombstone: a
        // live document has no tombstone at all, so tombstone lookup alone would
        // report a document that plainly exists as missing.
        val live = store.read(id)
        if (live != null) {
            val current = store.history(id).lastOrNull()
            return Revision(
                docId = id,
                revision = current?.revision ?: live.revision,
                author = current?.author ?: author,
                message = current?.message ?: message,
                contentHash = live.contentHash,
                createdAt = current?.createdAt ?: live.updatedAt
            )
        }
        val tombstoneFile = files.metadataFileOrNull(layout.tombstonesDir, id) ?: throw NotFoundException(id)
        val tombstone = tombstones.readTombstone(id, tombstoneFile)
        if (ifRevision != null && tombstone.revision != ifRevision.value) {
            throw ConflictException(
                id,
                "revision mismatch: expected ${ifRevision.value}, found ${tombstone.revision}"
            )
        }
        // A tombstone released its path, so a document created after the delete
        // may hold it now. Restoring over that would destroy it, which is the one
        // outcome a trash view must never produce silently.
        val holder = scanner.documentFiles().firstOrNull { layout.relative(it) == tombstone.path }
        if (holder != null) {
            throw ConflictException(
                id,
                "path ${tombstone.path} is held by ${documentIdFromPath(layout.relative(holder))}; " +
                    "resolve the collision before restoring"
            )
        }
        val restored =
            Document(
                id = id,
                collection = Collection(tombstone.collection),
                path = tombstone.path,
                content = tombstone.content,
                contentHash = tombstone.contentHash,
                revision = RevisionId(tombstone.revision),
                updatedAt = Instant.fromEpochMilliseconds(tombstone.deletedAt),
                frontmatter = Frontmatter()
            )
        write(restored, message, author)
        files.deleteMetadataFile(layout.tombstonesDir, id.value)
        return latestHistoryRevision(id, author, message)
    }

    private fun latestHistoryRevision(id: DocId, author: String, message: String): Revision {
        val stored = store.read(id) ?: throw NotFoundException(id)
        val revision = store.history(id).lastOrNull()
        return Revision(
            docId = id,
            revision = revision?.revision ?: stored.revision,
            author = revision?.author ?: author,
            message = revision?.message ?: message,
            contentHash = stored.contentHash,
            createdAt = revision?.createdAt ?: stored.updatedAt
        )
    }

    fun rename(
        from: DocId,
        to: DocId,
        message: String,
        author: String,
        ifRevision: RevisionId?
    ): Revision =
        locks.withDocumentLocks(listOf(layout.fileFor(from), layout.fileFor(to))) {
            renameStaged(from, to, message, author, ifRevision)
        }

    private fun renameStaged(
        from: DocId,
        to: DocId,
        message: String,
        author: String,
        ifRevision: RevisionId?
    ): Revision {
        if (from == to) throw layout.invalidPath("rename requires a different document id")
        // The pending record is keyed by the target, so a rename interrupted before
        // it was recorded is found there and finished rather than started again.
        journal.recoverPendingHistory(to)?.let { recovered ->
            if (recovered.action == PendingHistoryAction.RENAME) {
                return Revision(
                    to,
                    recovered.patch.hash,
                    author,
                    message,
                    recovered.contentHash,
                    recovered.patch.timestamp
                )
            }
        }
        val source = store.read(from) ?: throw NotFoundException(from)
        if (ifRevision != null && source.revision != ifRevision) {
            throw ConflictException(
                from,
                "revision mismatch: expected ${ifRevision.value}, found ${source.revision.value}"
            )
        }
        val targetPath = to.documentPath()
        val holder = scanner.documentFiles().firstOrNull { layout.relative(it) == targetPath }
        if (holder != null) {
            throw ConflictException(
                from,
                "path $targetPath is held by ${documentIdFromPath(layout.relative(holder))}; " +
                    "resolve the collision before renaming"
            )
        }
        // A tombstone releases its path, so renaming onto one reclaims it.
        files.deleteMetadataFile(layout.tombstonesDir, to.value)

        // Captured before the move, and resolved against this snapshot rather than
        // the vault as it stands afterwards. Once the file has moved, a link still
        // reading `[[notes/target]]` resolves to nothing at all, so a rewriter
        // running after the move would find no referrer to fix and leave every one
        // of them dangling. Which links pointed here is a question about the vault
        // before the move, so it is answered there.
        val beforeMove = store.listAll()
        val beforeMoveIds = beforeMove.mapTo(mutableSetOf()) { it.id }
        val referrers =
            store
                .incomingNeighbors(from)
                .map { it.src }
                .distinct()
                .filter { it != from }

        val pending =
            PendingHistory(
                action = PendingHistoryAction.RENAME,
                state = PendingHistoryState.PREPARED,
                documentId = to,
                previousId = from.value,
                path = targetPath,
                message = message,
                author = author,
                content = source.content,
                contentHash = source.contentHash,
                previousContentHash = source.contentHash,
                previousRevision = source.revision.value,
                collection = source.collection.value,
                createdAt = clock.now(),
                operationId = recorder.pendingOperationId("RENAME", to, source.contentHash, source.revision.value),
                recordedRevision = null,
                recordedAt = null,
                previousContent = source.content
            )
        journal.writePendingHistory(pending)
        outbox.enqueue(recorder.projectionOperation(pending))
        val applied = journal.applyPendingRename(pending)
        val patch = recorder.recordPendingHistory(applied)
        outbox.enqueue(recorder.projectionOperation(applied, patch.hash))
        referrers.forEach { relinkDocument(it, from, to, beforeMove, beforeMoveIds) }

        return Revision(to, patch.hash, author, message, source.contentHash, patch.timestamp)
    }

    /**
     * Re-points one referring document's `[[wikilinks]]` at [to].
     *
     * Links are the durable record of the reference and edges are derived from
     * them, so the text is what has to change. The document is rewritten through
     * [write] rather than edited in place, so the change gets its own revision and
     * its own patch instead of appearing as an unrecorded edit.
     */
    private fun relinkDocument(
        id: DocId,
        from: DocId,
        to: DocId,
        beforeMove: List<DocRef>,
        beforeMoveIds: Set<DocId>
    ) {
        val document = store.read(id) ?: return
        val rewritten =
            WikilinkRewriter.retarget(
                document.content,
                { target ->
                    LinkResolver.resolve(
                        target,
                        exists = { candidate -> candidate in beforeMoveIds },
                        refs = beforeMove
                    )
                },
                from,
                to
            )
        if (rewritten == document.content) return
        write(
            document.copy(
                content = rewritten,
                contentHash = Hashes.sha256(rewritten),
                updatedAt = clock.now()
            ),
            "relinked after rename",
            RELINK_AUTHOR
        )
    }
}
