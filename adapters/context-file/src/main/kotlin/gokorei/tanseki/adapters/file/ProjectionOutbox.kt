package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.ports.ProjectionBacklog
import gokorei.tanseki.core.ports.TansekiLogger
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The projection outbox records and their lifecycle.
 *
 * Kept out of [FileContextStore] so the outbox delivery path — enqueue, pending,
 * completion, failure accounting and quarantine — is one place next to its
 * lease logic ([ProjectionLeases]) instead of being the second half of the
 * store. The corrupt count is deliberately read and reset here, in the same
 * scans that produce it, so the backlog and [quarantineCorrupt] never disagree.
 */
internal class ProjectionOutbox(
    private val layout: StoreLayout,
    private val files: MetadataFiles,
    private val leases: ProjectionLeases,
    private val logger: TansekiLogger
) {
    private var corruptProjections = 0

    @Synchronized
    fun enqueue(operation: ProjectionOperation) {
        val file = files.metadataFile(layout.projectionsDir, operation.id)
        val existing = readOperation(file, files)?.operation
        files.atomicWrite(
            file,
            OperationCodec.encode(
                if (existing == null) {
                    operation
                } else {
                    operation.copy(attempts = maxOf(existing.attempts, operation.attempts))
                }
            )
        )
    }

    @Synchronized
    fun pending(limit: Int): List<ProjectionOperation> {
        if (!Files.isDirectory(layout.projectionsDir)) {
            corruptProjections = 0
            return emptyList()
        }
        corruptProjections = 0
        val operations = linkedMapOf<String, ProjectionOperation>()
        Files.walk(layout.projectionsDir).use { stream ->
            val outboxFiles =
                stream
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .toList()
            outboxFiles.forEach { addPendingOperation(operations, layout, files, it) }
            corruptProjections =
                outboxFiles.count { readOperation(it, files)?.corrupt == true }
        }
        return operations.values
            .sortedWith(compareBy<ProjectionOperation> { it.createdAt }.thenBy { it.id })
            .take(limit)
    }

    @Synchronized
    fun complete(operation: ProjectionOperation) {
        files.deleteMetadataFile(layout.projectionsDir, operation.id)
        Files.deleteIfExists(layout.projectionsDir.resolve(Hashes.sha256(operation.id)))
        leases.deleteLease(operation.id)
    }

    @Synchronized
    fun recordFailure(operation: ProjectionOperation): Int {
        val file = files.metadataFile(layout.projectionsDir, operation.id)
        val existing = readOperation(file, files)?.operation
        val recorded = (existing ?: operation).copy(attempts = (existing?.attempts ?: operation.attempts) + 1)
        files.atomicWrite(file, OperationCodec.encode(recorded))
        return recorded.attempts
    }

    /** Keeps the record in the outbox, flagged out of the delivery path. */
    @Synchronized
    fun deadLetter(operation: ProjectionOperation) {
        val file = files.metadataFile(layout.projectionsDir, operation.id)
        val existing = readOperation(file, files)?.operation
        files.atomicWrite(file, OperationCodec.encode((existing ?: operation).copy(deadLettered = true)))
    }

    @Synchronized
    fun deadLettered(limit: Int): List<ProjectionOperation> =
        pending(Int.MAX_VALUE)
            .filter { it.deadLettered }
            .take(limit)

    @Synchronized
    fun pendingLive(limit: Int): List<ProjectionOperation> =
        pending(Int.MAX_VALUE)
            .filterNot { it.deadLettered }
            .take(limit.coerceAtLeast(0))

    /**
     * Moves outbox entries that cannot be decoded into `.tanseki/corrupt-projections`
     * so one damaged file cannot stall the operations behind it, and returns how
     * many were quarantined.
     */
    @Synchronized
    fun quarantineCorrupt(): Int {
        if (!Files.isDirectory(layout.projectionsDir)) return 0
        val quarantined = corruptProjectionFiles(layout, files)
        Files.createDirectories(layout.corruptProjectionsDir)
        quarantined.forEach { file ->
            val target = layout.corruptProjectionsDir.resolve(file.fileName.toString())
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
        }
        if (quarantined.isNotEmpty()) {
            corruptProjections = 0
            logger.warn("quarantined corrupt projection operations", mapOf("count" to quarantined.size))
        }
        return quarantined.size
    }

    @Synchronized
    fun backlog(): ProjectionBacklog {
        val records = pending(Int.MAX_VALUE)
        val live = records.filterNot { it.deadLettered }
        val stuck = live.filter { it.attempts > 0 }
        return ProjectionBacklog(
            pending = live.size,
            oldestCreatedAt = live.firstOrNull()?.createdAt,
            stuck = stuck.size,
            oldestStuckAt = stuck.firstOrNull()?.createdAt,
            corrupt = corruptProjections,
            deadLettered = records.size - live.size
        )
    }
}

private fun addPendingOperation(
    operations: MutableMap<String, ProjectionOperation>,
    layout: StoreLayout,
    files: MetadataFiles,
    file: Path
) {
    if (Files.isSymbolicLink(file)) return
    val outcome = readOperation(file, files) ?: return
    if (outcome.corrupt) return
    val operation = outcome.operation ?: return
    val target = files.metadataFile(layout.projectionsDir, operation.id)
    if (file != target && !files.metadataFileExists(target)) files.copyMetadataFile(file, target)
    val canonical = if (file == target) operation else readOperation(target, files)?.operation
    canonical?.let { operations.putIfAbsent(it.id, it) }
}

private fun corruptProjectionFiles(layout: StoreLayout, files: MetadataFiles): List<Path> {
    val corrupt = mutableListOf<Path>()
    Files.walk(layout.projectionsDir).use { stream ->
        stream
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(it) }
            .forEach { file -> if (readOperation(file, files)?.corrupt == true) corrupt.add(file) }
    }
    return corrupt.sortedBy { it.toString() }
}

/**
 * Decodes one outbox file. A file that vanished mid-walk (racing
 * [ProjectionOutbox.complete]) is skipped, not reported; a file that exists but
 * cannot be decoded — undecodable bytes, a truncated record, a symlink — is
 * reported as corrupt so the backlog surfaces it and [ProjectionOutbox.quarantineCorrupt]
 * can move it out of the delivery path instead of the vault dropping it forever.
 */
internal fun readOperation(file: Path, files: MetadataFiles): OperationRead? =
    try {
        if (!files.metadataFileExists(file)) return null
        OperationRead(OperationCodec.decode(Files.readString(file)), corrupt = false)
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    } catch (_: java.io.IOException) {
        OperationRead(null, corrupt = true)
    } catch (_: IllegalArgumentException) {
        OperationRead(null, corrupt = true)
    } catch (_: InvalidInputException) {
        OperationRead(null, corrupt = true)
    }
