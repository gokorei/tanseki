@file:Suppress("ThrowsCount")

package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.ProjectionKind
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.UnavailableException
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.domain.documentPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.PijulClient
import gokorei.tanseki.core.ports.PijulPatch
import gokorei.tanseki.core.ports.TansekiLogger
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.concurrent.withLock
import kotlin.time.Instant

/**
 * The pending-history journal: what a mutation recorded before it touched the
 * file, and the recovery that finishes a crashed one on the next open.
 *
 * Kept out of [FileContextStore] so the crash-recovery protocol — written
 * intent, applied intent, recorded intent — is one place with the file IO that
 * reads and writes it. Applying an intent is intentionally re-run during
 * recovery: it must treat "already done" as success, and must throw rather than
 * guess when the filesystem disagrees with the journal, so a crash can never be
 * resolved into a half-mutation.
 */
internal class PendingHistoryJournal(
    private val layout: StoreLayout,
    private val files: MetadataFiles,
    private val sidecars: MetadataSidecars,
    private val outbox: ProjectionOutbox,
    private val recorder: PendingRecorder,
    private val logger: TansekiLogger
) {
    fun recoverPendingHistory(id: DocId): PendingHistoryRecovery? {
        val file = files.metadataFile(layout.historyDir, id.value)
        if (!files.metadataFileExists(file)) return null
        val pending =
            try {
                readPendingHistory(file)
            } catch (error: Exception) {
                quarantineCorruptHistory(layout, logger, file, error)
                return null
            }
        return recoverPendingHistory(pending)
    }

    fun recoverPendingHistory(pending: PendingHistory): PendingHistoryRecovery {
        outbox.enqueue(recorder.projectionOperation(pending))
        val patch =
            when (pending.state) {
                PendingHistoryState.PREPARED,
                PendingHistoryState.APPLIED -> {
                    recorder.recordPendingHistory(applyPendingMutation(pending))
                }

                PendingHistoryState.RECORDING -> {
                    recorder.recordedPatch(pending)?.let {
                        recorder.completeRecorded(pending, it)
                        it
                    } ?: recorder.recordPendingHistory(applyPendingMutation(pending))
                }

                PendingHistoryState.RECORDED -> {
                    recorder.requireRecordedPatch(pending).also {
                        recorder.completeRecorded(pending, it)
                    }
                }
            }
        outbox.enqueue(recorder.projectionOperation(pending, patch.hash))
        return PendingHistoryRecovery(patch, pending.action, pending.contentHash, pending.previousRevision)
    }

    private fun applyPendingMutation(pending: PendingHistory): PendingHistory =
        when (pending.action) {
            PendingHistoryAction.WRITE -> applyPendingWrite(pending)
            PendingHistoryAction.DELETE -> applyPendingDelete(pending)
            PendingHistoryAction.RENAME -> applyPendingRename(pending)
        }

    fun applyPendingWrite(pending: PendingHistory): PendingHistory {
        val file = layout.fileFor(pending.documentId)
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) layout.requireSafeRegularFile(file)
        val currentHash =
            if (Files.exists(
                    file,
                    LinkOption.NOFOLLOW_LINKS
                )
            ) {
                Hashes.sha256(Files.readString(file))
            } else {
                null
            }
        if (currentHash != pending.contentHash) {
            if (currentHash != pending.previousContentHash) {
                throw ConflictException(
                    pending.documentId,
                    "pending write expected ${pending.previousContentHash ?: "none"}, found ${currentHash ?: "none"}"
                )
            }
            files.atomicWriteString(file, pending.content)
        }
        sidecars.writeCollection(pending.documentId, Collection(pending.collection))
        return writePendingHistory(pending.copy(state = PendingHistoryState.APPLIED))
    }

    fun applyPendingDelete(pending: PendingHistory): PendingHistory {
        val file = layout.fileFor(pending.documentId)
        if (layout.requireSafeRegularFile(file)) {
            val currentHash = Hashes.sha256(Files.readString(file))
            if (currentHash != pending.previousContentHash) {
                throw ConflictException(
                    pending.documentId,
                    "pending delete expected ${pending.previousContentHash}, found $currentHash"
                )
            }
            Files.delete(file)
        }
        return writePendingHistory(pending.copy(state = PendingHistoryState.APPLIED))
    }

    /**
     * Moves the file, tolerating a move that already happened.
     *
     * Recovery re-runs this against whatever the filesystem actually holds, so it
     * has to treat "already moved" as success rather than as a conflict — that is
     * the normal state after a crash between [Files.move] and the patch record.
     * A file that is neither at the source nor at the target is a genuine conflict:
     * something else wrote there, and guessing which of the two to keep would
     * discard a document.
     */
    fun applyPendingRename(pending: PendingHistory): PendingHistory {
        val target = layout.fileFor(pending.documentId)
        val source = pending.previousId?.let { layout.fileFor(DocId(it)) }
        val sourcePresent = source != null && layout.requireSafeRegularFile(source)
        val targetPresent = Files.exists(target, LinkOption.NOFOLLOW_LINKS) && layout.requireSafeRegularFile(target)
        if (sourcePresent) {
            val currentHash = Hashes.sha256(Files.readString(source))
            if (currentHash != pending.previousContentHash) {
                throw ConflictException(
                    pending.documentId,
                    "pending rename expected ${pending.previousContentHash}, found $currentHash"
                )
            }
            if (targetPresent) {
                if (Hashes.sha256(Files.readString(target)) != pending.contentHash) {
                    throw ConflictException(
                        pending.documentId,
                        "pending rename found different content already at ${pending.path}"
                    )
                }
                Files.delete(source)
            } else {
                Files.createDirectories(target.parent)
                layout.requireContainedRealPath(target.parent)
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            }
        } else if (!targetPresent) {
            throw ConflictException(
                pending.documentId,
                "pending rename found neither the source nor the target document"
            )
        }
        sidecars.writeCollection(pending.documentId, Collection(pending.collection))
        return writePendingHistory(pending.copy(state = PendingHistoryState.APPLIED))
    }

    fun writePendingHistory(pending: PendingHistory): PendingHistory {
        val file = files.metadataFile(layout.historyDir, pending.documentId.value)
        files.atomicWriteString(file, encodePendingHistory(pending))
        return pending
    }

    fun readPendingHistory(file: Path): PendingHistory {
        val pending = decodePendingHistory(Files.readString(file))
        require(pending.path == layout.relative(layout.fileFor(pending.documentId)))
        if (pending.action == PendingHistoryAction.WRITE) {
            require(Hashes.sha256(pending.content) == pending.contentHash)
        } else {
            require(pending.contentHash == pending.previousContentHash)
            if (pending.previousContent.isNotEmpty()) {
                require(Hashes.sha256(pending.previousContent) == pending.previousContentHash)
            }
        }
        return pending
    }

    fun deletePendingHistory(id: DocId) {
        Files.deleteIfExists(files.metadataFile(layout.historyDir, id.value))
    }

    fun pendingHistoryFiles(): List<PendingHistory> {
        if (!Files.isDirectory(layout.historyDir)) return emptyList()
        val recovered = mutableListOf<PendingHistory>()
        Files.walk(layout.historyDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { !Files.isSymbolicLink(it) }
                .filter { !it.fileName.toString().startsWith(".") }
                .forEach { file ->
                    // One damaged record must not make the whole vault unopenable:
                    // construction calls this, so an unguarded throw here turns a
                    // single corrupt sidecar into a store that cannot start.
                    try {
                        recovered += readPendingHistory(file)
                    } catch (error: Exception) {
                        quarantineCorruptHistory(layout, logger, file, error)
                    }
                }
        }
        return recovered.sortedWith(compareBy<PendingHistory> { it.createdAt }.thenBy { it.documentId.value })
    }
}

/**
 * The recording tail of a pending mutation: the `pijul record` and the sidecar
 * completion that a successful patch commits.
 *
 * Split from [PendingHistoryJournal] so the mutation path (journal) and the
 * recording path (here) each stay one small class.
 */
internal class PendingRecorder(
    private val layout: StoreLayout,
    private val files: MetadataFiles,
    private val sidecars: MetadataSidecars,
    private val edges: EdgeIndex,
    private val tombstones: TombstoneIndex,
    private val locks: DocumentLocks,
    private val pijul: PijulClient,
    private val vault: VaultPath,
    private val deletePending: (DocId) -> Unit
) {
    fun recordPendingHistory(pending: PendingHistory): PijulPatch {
        if (pending.state == PendingHistoryState.RECORDED) {
            return requireRecordedPatch(pending).also { completeRecorded(pending, it) }
        }
        val recording = writePendingHistoryFrom(pending.copy(state = PendingHistoryState.RECORDING))
        val patch =
            locks.recordLock.withLock {
                pijul.record(
                    vault,
                    pending.message,
                    pending.author,
                    recordedPaths(pending),
                    pending.operationId
                )
            }
        val recorded = checkpointRecorded(recording, patch)
        completeRecorded(recorded, patch)
        return patch
    }

    /**
     * Paths a pending operation records.
     *
     * A rename records both ends of the move. Recording only the new path would
     * leave the DAG believing the old one still holds the document, so a checkout
     * of an earlier patch would bring the file back and the history would claim a
     * document that existed twice.
     */
    private fun recordedPaths(pending: PendingHistory): List<String> =
        when (pending.action) {
            PendingHistoryAction.RENAME -> {
                listOfNotNull(
                    pending.previousId?.let { DocId(it).documentPath() },
                    pending.path
                ).distinct()
            }

            else -> {
                listOf(pending.path)
            }
        }

    private fun writePendingHistoryFrom(pending: PendingHistory): PendingHistory {
        val file = files.metadataFile(layout.historyDir, pending.documentId.value)
        files.atomicWriteString(file, encodePendingHistory(pending))
        return pending
    }

    private fun checkpointRecorded(pending: PendingHistory, patch: PijulPatch): PendingHistory =
        writePendingHistoryFrom(
            pending.copy(
                state = PendingHistoryState.RECORDED,
                recordedRevision = patch.hash.value,
                recordedAt = patch.timestamp.toEpochMilliseconds()
            )
        )

    fun completeRecorded(pending: PendingHistory, patch: PijulPatch) {
        when (pending.action) {
            PendingHistoryAction.WRITE -> {
                sidecars.writeRevision(pending.documentId, patch.hash.value)
                files.deleteMetadataFile(layout.tombstonesDir, pending.documentId.value)
            }

            PendingHistoryAction.DELETE -> {
                tombstones.writeTombstone(pending, patch)
                files.deleteMetadataFile(layout.revisionsDir, pending.documentId.value)
                files.deleteMetadataFile(layout.edgesDir, pending.documentId.value)
                files.deleteMetadataFile(layout.collectionsDir, pending.documentId.value)
            }

            PendingHistoryAction.RENAME -> {
                // A rename always records its source; a record without one is a
                // corrupt encoding, and silently completing half a move is worse
                // than refusing it.
                val previous =
                    pending.previousId?.let(::DocId)
                        ?: throw UnavailableException("rename pending history has no source id")
                sidecars.writeRevision(pending.documentId, patch.hash.value)
                // Sidecars are keyed by id, so they move with the document; leaving
                // them would strand the revision and the edge set under a dead id.
                sidecars.moveMetadataFile(layout.revisionsDir, previous, pending.documentId)
                sidecars.moveMetadataFile(layout.edgesDir, previous, pending.documentId)
                sidecars.moveMetadataFile(layout.collectionsDir, previous, pending.documentId)
                // Then the inbound edges, which live in other documents' sidecars
                // and so are not covered by moving this one.
                edges.retargetIncomingEdges(previous, pending.documentId)
                // The earliest origin wins, so renaming twice still reaches the
                // first chain rather than only the most recent one.
                sidecars.recordRenameOrigin(pending.documentId, sidecars.renameOrigin(previous) ?: previous.value)
                files.deleteMetadataFile(layout.tombstonesDir, previous.value)
            }
        }
        deletePending(pending.documentId)
    }

    fun requireRecordedPatch(pending: PendingHistory): PijulPatch =
        recordedPatch(pending)
            ?: throw UnavailableException("recorded pending history is missing its patch")

    fun recordedPatch(pending: PendingHistory): PijulPatch? {
        if (pending.state == PendingHistoryState.RECORDED) {
            val hash = pending.recordedRevision ?: return null
            val recordedAt = pending.recordedAt ?: return null
            return PijulPatch(
                hash = RevisionId(hash),
                author = pending.author,
                message = pending.message,
                timestamp = Instant.fromEpochMilliseconds(recordedAt),
                operationId = pending.operationId
            )
        }
        // Two calls on purpose, not fan-out to batch: `status` is `diff --json`
        // and the log is `log --output-format json` — different subcommands, so
        // no single invocation serves both. The dirty check comes first so the
        // common unrecorded case costs one spawn, not two.
        val status = pijul.status(vault)
        if (pending.path in status.modified ||
            pending.path in status.added ||
            pending.path in status.removed
        ) {
            return null
        }
        val candidates =
            pijul.log(vault, pending.documentId, Int.MAX_VALUE).filter { patch ->
                if (pending.operationId != null) {
                    patch.operationId == pending.operationId
                } else {
                    patch.author == pending.author &&
                        patch.message == pending.message &&
                        patch.hash.value != pending.previousRevision
                }
            }
        if (candidates.size > 1) {
            throw UnavailableException(
                "ambiguous Pijul recovery for ${pending.documentId.value}: multiple patches match operation"
            )
        }
        return candidates.singleOrNull()
    }

    fun pendingOperationId(
        action: String,
        id: DocId,
        contentHash: String,
        previousRevision: String
    ): String = Hashes.sha256("$action|${id.value}|$contentHash|$previousRevision")

    fun projectionOperation(
        pending: PendingHistory,
        revision: RevisionId = RevisionId(pending.previousRevision)
    ): ProjectionOperation {
        val kind =
            when (pending.action) {
                PendingHistoryAction.WRITE -> ProjectionKind.UPSERT

                PendingHistoryAction.DELETE -> ProjectionKind.DELETE

                // A move is an upsert: the document is present under the new path,
                // and the lookup side is keyed by path.
                PendingHistoryAction.RENAME -> ProjectionKind.UPSERT
            }
        return ProjectionOperation(
            id = "${pending.documentId.value}|${kind.name}|${pending.contentHash}",
            documentId = pending.documentId,
            revision = revision,
            contentHash = pending.contentHash,
            kind = kind,
            edgeVersion = revision.value,
            projectionVersion = revision.value,
            createdAt = pending.createdAt
        )
    }
}

/** Moves a `.tanseki/history` record that cannot be decoded into `.tanseki/corrupt-history`. */
private fun quarantineCorruptHistory(layout: StoreLayout, logger: TansekiLogger, file: Path, error: Exception) {
    runCatching {
        Files.createDirectories(layout.corruptHistoryDir)
        Files.move(
            file,
            layout.corruptHistoryDir.resolve(file.fileName.toString()),
            StandardCopyOption.REPLACE_EXISTING
        )
    }
    logger.warn(
        "quarantined corrupt pending history",
        mapOf(
            "file" to file.fileName.toString(),
            "error" to (error.message ?: error::class.simpleName ?: "error")
        )
    )
}
