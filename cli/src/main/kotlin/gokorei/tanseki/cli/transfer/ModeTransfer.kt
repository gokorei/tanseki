package gokorei.tanseki.cli.transfer

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.TransferState
import gokorei.tanseki.core.ports.importDocument
import gokorei.tanseki.core.ports.isStaleTransfer

/** Which part of the copy a document failed in, when it is not the read itself. */
enum class TransferStage(val label: String) {
    READ("read"),
    WRITE("write"),
    EDGES("edges"),
    HISTORY("history"),
    INCOMING_EDGES("incoming-edges")
}

/**
 * One document that could not be completed, and where it broke.
 *
 * [error] is the exception's type name and never its message: store exceptions
 * carry document content, and a transfer log is not the place for it.
 */
data class TransferFailure(
    val id: DocId,
    val stage: TransferStage,
    val error: String
)

/** Counts produced by a [ModeTransfer.copy] run. */
data class TransferReport(
    val documentsImported: Int,
    val documentsSkipped: Int,
    val edgesImported: Int,
    val revisionsImported: Int = 0,
    val tombstonesImported: Int = 0,
    val tombstonesSkipped: Int = 0,
    val tombstonesStale: Int = 0,
    val documentsStale: Int = 0,
    val unreadable: List<DocId> = emptyList(),
    /**
     * Documents the run could not finish, beyond those it could not read.
     *
     * Separate from [unreadable] because the remedy differs: an unreadable
     * document needs fixing at the source, a failed one needs checking in the
     * target. A read that threw also lands in [unreadable] and here, so a
     * malformed note is never mistaken for a target-side fault.
     */
    val failures: List<TransferFailure> = emptyList(),
    /**
     * Source ids that collide on one target id (two source documents the target
     * cannot tell apart). Importing them would silently drop one of the two, so
     * the run reports them instead of losing data.
     */
    val conflictingIds: List<DocId> = emptyList(),
    /**
     * Every filename sanitization the run applied, as source path -> target
     * id. Empty for store-to-store copies (their ids are already valid) and
     * populated by directory imports under the [FilenameSanitizer] policy.
     */
    val renames: List<SanitizeRename> = emptyList(),
    /**
     * Post-sanitize collisions: sources that mapped onto one target id. The
     * lexicographically first source wins; the rest are never written.
     */
    val sanitizeCollisions: List<SanitizeCollision> = emptyList(),
    /**
     * Inbound `[[wikilinks]]` a rename genuinely broke. Content is imported
     * byte-identical (links are not rewritten), so each entry names a link
     * that pointed at the old filename and no longer resolves to the note
     * post-import (dangling or resolving elsewhere). Bare-name links that
     * still resolve through trailing-segment match are not reported.
     */
    val wikilinkInvalidations: List<WikilinkInvalidation> = emptyList(),
    /**
     * `[[wikilinks]]` that resolve to no document in the target. Counted
     * separately from [wikilinkInvalidations] (which names links a rename
     * broke): a dangling link is the vault's own state, not something the
     * importer lost.
     */
    val unresolvedLinks: Int = 0
) {
    /**
     * True when the run lost or mangled something: unreadable sources, failed
     * writes, colliding ids, or renamed files. A user scripting an import
     * detects a lossy run from this alone. Dangling wikilinks do not count:
     * they describe the vault, not the import.
     */
    val isLossy: Boolean
        get() =
            unreadable.isNotEmpty() ||
                failures.isNotEmpty() ||
                conflictingIds.isNotEmpty() ||
                renames.isNotEmpty() ||
                sanitizeCollisions.isNotEmpty()
}

/**
 * Copies documents, their edges and their history between two [ContextStore]
 * instances (vault files <-> SQLite library <-> server Postgres). Because both
 * adapters share the port contract, this is a feature, not a migration:
 *
 * - a document is rewritten once, up front, to the id/path pair the *target*
 *   can store ([importDocument]); every id the copy then uses — the document, its
 *   history, its outgoing edges and both endpoints of every edge — is derived
 *   from that same rewrite, so a target that derives ids from Markdown paths
 *   never ends up with an edge pointing at a document it does not hold;
 * - two source documents that collapse onto one target id are reported as
 *   [TransferReport.conflictingIds] instead of overwriting each other;
 * - writes are idempotent on `content_hash`, so re-running copies nothing;
 * - a document the target already holds at a **newer** revision is never
 *   overwritten: it is reported as stale and left alone, which is decided by the
 *   target's own `importTransferState` result, not by a pre-check;
 * - edges are replaced per source doc so the target mirrors the source, and
 *   incoming edges of a tombstoned document are dropped **after** the edge pass
 *   so the target carries no dangling references to a deleted id;
 * - history is imported when the target advertises
 *   [gokorei.tanseki.core.domain.StoreCapabilities.supportsHistoryImport] and is itself
 *   idempotent (revisions the target already knows are not re-inserted).
 *
 * History cannot be injected into vault mode (its history is the Pijul patch
 * DAG); that hop simply skips history import. Blobs are content-addressed and
 * can be copied lazily.
 */
class ModeTransfer {
    fun copy(
        source: ContextStore,
        target: ContextStore,
        message: String = "mode transfer",
        author: String = "tanseki-cli"
    ): TransferReport {
        val run = Run(source, target, message, author)
        val pending = source.listAll().mapNotNull { ref -> run.visit(ref) }
        val resolve = resolveId(source, target, run.idMap)
        var edges = 0
        var revisions = 0
        pending.forEach { entry ->
            edges += run.copyEdges(entry.sourceId, entry.targetId, resolve)
            revisions += entry.historyImported
        }
        // Last, so an edge copied from a document that is itself being deleted
        // cannot reintroduce a reference the tombstone just invalidated.
        run.tombstoned.forEach { id ->
            run.guarded(id, TransferStage.INCOMING_EDGES, Unit) { target.removeIncomingEdges(id) }
        }

        return TransferReport(
            documentsImported = run.imported,
            documentsSkipped = run.skipped,
            edgesImported = edges,
            revisionsImported = revisions,
            tombstonesImported = run.tombstonesImported,
            tombstonesSkipped = run.tombstonesSkipped,
            tombstonesStale = run.tombstonesStale,
            documentsStale = run.documentsStale,
            unreadable = run.unreadable,
            failures = run.failures.toList(),
            conflictingIds = run.conflicting.toList()
        )
    }

    /** One copy run: the id mapping plus the counters the report is built from. */
    private inner class Run(
        private val source: ContextStore,
        private val target: ContextStore,
        private val message: String,
        private val author: String
    ) {
        val idMap = LinkedHashMap<DocId, DocId>()
        val targetOwners = LinkedHashMap<DocId, DocId>()
        val tombstoned = linkedSetOf<DocId>()
        val conflicting = linkedSetOf<DocId>()
        val unreadable = mutableListOf<DocId>()
        val failures = mutableListOf<TransferFailure>()
        var imported = 0
        var skipped = 0
        var tombstonesImported = 0
        var tombstonesSkipped = 0
        var tombstonesStale = 0
        var documentsStale = 0

        /**
         * Runs [body], recording [id] as failed at [stage] if it throws.
         *
         * The whole point of this class is that one bad document out of thousands
         * must not cost the user the other thousands. A transfer that throws out of
         * [visit] takes the entire run with it, and for an "import my vault"
         * command that is the worst available outcome: nothing arrives and the
         * report says nothing about why.
         *
         * Returns [fallback] when the body fails, so a caller that needs a value
         * can proceed with the rest of the run rather than unwinding.
         */
        fun <T> guarded(
            id: DocId,
            stage: TransferStage,
            fallback: T,
            body: () -> T
        ): T =
            try {
                body()
            } catch (error: Exception) {
                // Exceptions, not Throwable: an OutOfMemoryError or a
                // StackOverflowError means the process is already gone, and
                // recording it as one skipped document would misdescribe that.
                failures += TransferFailure(id, stage, error::class.simpleName ?: "Exception")
                fallback
            }

        /** Copies one listed document; null when there is nothing to copy. */
        fun visit(ref: DocRef): PendingCopy? {
            val document =
                guarded(ref.id, TransferStage.READ, null) { source.readIncludingDeleted(ref.id) }
            if (document == null) {
                unreadable += ref.id
                return null
            }
            val targetId = importDocument(target, document).id
            if (isCollision(ref.id, targetId)) return null

            val existing = guarded(ref.id, TransferStage.READ, null) { target.readIncludingDeleted(targetId) }
            // Not inside guarded: an older local write is a correctness answer,
            // not an error, and folding it into the failure ledger would report a
            // deliberate decision as a fault.
            if (existing != null && isStaleTransfer(existing, document)) {
                if (document.deleted) tombstonesStale++ else documentsStale++
                return null
            }
            val historyImported =
                if (target.capabilities().supportsHistoryImport) {
                    // Contained rather than fatal: the document itself still
                    // copies, so a target that cannot accept history for one id
                    // should not also lose the document.
                    guarded(ref.id, TransferStage.HISTORY, 0) {
                        importMissingHistory(source, target, ref.id, targetId)
                    }
                } else {
                    0
                }
            val applied =
                guarded(ref.id, TransferStage.WRITE, false) {
                    target.importTransferState(TransferState(document, historyOf(ref.id, document)))
                }
            if (!applied) {
                // False is the target refusing as stale. A recorded failure above
                // returned the same fallback, so it must not also be counted as a
                // refusal: the document was not refused, it could not be written.
                if (failures.none { it.id == ref.id && it.stage == TransferStage.WRITE }) {
                    countRefusal(document, existing)
                }
                return null
            }
            countApplied(document, existing)
            // Only a tombstone that actually landed at the target may strip the
            // target's inbound edges: a stale or refused tombstone left a live
            // document in place, and its edges must survive.
            if (document.deleted) tombstoned += targetId
            return PendingCopy(sourceId = ref.id, targetId = targetId, historyImported = historyImported)
        }

        /** One document's edges; 0 when the edge pass failed. */
        fun copyEdges(
            sourceId: DocId,
            targetId: DocId,
            resolveId: (DocId) -> DocId?
        ): Int =
            guarded(sourceId, TransferStage.EDGES, 0) {
                var unresolved = false
                val resolved =
                    source.neighbors(sourceId).mapNotNull { edge ->
                        val sourceEndpoint = resolveId(edge.src)
                        val targetEndpoint = resolveId(edge.dst)
                        if (sourceEndpoint == null || targetEndpoint == null) {
                            unresolved = true
                            null
                        } else {
                            edge.copy(src = sourceEndpoint, dst = targetEndpoint)
                        }
                    }
                // An edge whose endpoint cannot be resolved is dropped rather than
                // emitted with its source id: a synthesized id would address a
                // document the target does not hold.
                if (unresolved) {
                    failures += TransferFailure(sourceId, TransferStage.EDGES, "unresolved edge reference")
                }
                target.replaceEdges(targetId, resolved)
                target.neighbors(targetId).size
            }

        /**
         * Two source documents the target cannot tell apart would leave whichever
         * landed last, so neither the second nor its edges are applied and the
         * source id is reported.
         */
        private fun isCollision(sourceId: DocId, targetId: DocId): Boolean {
            val owner = targetOwners[targetId]
            idMap[sourceId] = targetId
            if (owner == null || owner == sourceId) {
                targetOwners[targetId] = sourceId
                return false
            }
            conflicting += sourceId
            return true
        }

        private fun historyOf(
            sourceId: DocId,
            document: Document
        ): List<Revision> =
            source.history(sourceId).ifEmpty {
                listOf(
                    Revision(
                        document.id,
                        document.revision,
                        author,
                        message,
                        document.contentHash,
                        document.updatedAt,
                        content = document.content
                    )
                )
            }

        /** The target refused the copy as stale: nothing was written at all. */
        private fun countRefusal(
            document: Document,
            existing: Document?
        ) {
            when {
                existing != null && sameDocument(existing, document) -> {
                    if (document.deleted) tombstonesSkipped++ else skipped++
                }

                document.deleted -> {
                    tombstonesSkipped++
                }

                else -> {
                    documentsStale++
                }
            }
        }

        private fun countApplied(
            document: Document,
            existing: Document?
        ) {
            when {
                document.deleted && existing != null && sameDocument(existing, document) -> tombstonesSkipped++
                document.deleted -> tombstonesImported++
                existing != null && sameDocument(existing, document) -> skipped++
                else -> imported++
            }
        }
    }

    /**
     * Maps a source id to the id the target stores it under, or null when it cannot
     * be resolved. Ids the copy never listed (an edge to a document outside the
     * exported set) are read from the source and rewritten exactly like a listed
     * document; an id that cannot be read is reported instead of being emitted
     * unchanged, which would leave the target with an edge it cannot address.
     */
    private fun resolveId(
        source: ContextStore,
        target: ContextStore,
        idMap: Map<DocId, DocId>
    ): (DocId) -> DocId? = { id ->
        idMap[id]
            ?: runCatching { importDocument(target, source.readIncludingDeleted(id) ?: throw NotFoundException(id)).id }
                .getOrNull()
    }

    private fun sameDocument(
        first: Document,
        second: Document
    ): Boolean =
        first.id == second.id &&
            first.collection == second.collection &&
            first.path == second.path &&
            first.content == second.content &&
            first.contentHash == second.contentHash &&
            first.revision == second.revision &&
            first.frontmatter == second.frontmatter &&
            first.updatedAt == second.updatedAt &&
            first.deleted == second.deleted

    /** Import revisions the target does not already know; returns how many. */
    private fun importMissingHistory(
        source: ContextStore,
        target: ContextStore,
        sourceId: DocId,
        targetId: DocId
    ): Int {
        val known = target.history(targetId).mapTo(mutableSetOf()) { it.revision }
        val missing = source.history(sourceId).filterNot { it.revision in known }
        if (missing.isNotEmpty()) {
            target.appendHistory(targetId, missing.map { it.copy(docId = targetId) })
        }
        return missing.size
    }

    private data class PendingCopy(
        val sourceId: DocId,
        val targetId: DocId,
        val historyImported: Int
    )
}
