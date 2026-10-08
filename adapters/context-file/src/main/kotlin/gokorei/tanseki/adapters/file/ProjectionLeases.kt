package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ProjectionClaim
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The claim-lock sidecars of the projection outbox.
 *
 * Kept out of [FileContextStore] with [ProjectionOutbox]: a claim is a lease
 * file created with `CREATE_NEW` beside the operation, so two workers racing for
 * the same operation cannot both create it — the property the SQL backends get
 * from a conditional UPDATE.
 */
internal class ProjectionLeases(
    private val layout: StoreLayout,
    private val files: MetadataFiles,
    private val clock: Clock,
    private val operationById: (String) -> ProjectionOperation?
) {
    /**
     * Takes ownership by creating the lease file exclusively.
     *
     * An expired lease is taken over rather than respected: a holder that died must
     * not strand its operation forever. The takeover re-checks under the instance
     * lock, so two callers cannot both decide the same stale lease is theirs.
     */
    @Synchronized
    fun claim(
        operationId: String,
        owner: String,
        lease: Duration,
        now: Instant
    ): ProjectionClaim? {
        // A dead-lettered operation is out of the delivery path. The SQL backends
        // filter it inside the claiming statement; here the lease is a separate
        // sidecar file, so it has to be checked explicitly or a poison operation
        // would keep being picked up and re-failing forever.
        val operation = operationById(operationId)
        if (operation == null || operation.deadLettered) return null
        val file = leaseFile(operationId)
        Files.createDirectories(layout.projectionLeasesDir)
        val token =
            java.util.UUID
                .randomUUID()
                .toString()
        val expiresAt = now + lease
        if (tryCreateLease(file, operationId, owner, token, expiresAt)) {
            return ProjectionClaim(operationId, owner, token, expiresAt)
        }
        val existing = readLease(file) ?: return null
        if (existing.isLive(now)) return null
        Files.deleteIfExists(file)
        return if (tryCreateLease(file, operationId, owner, token, expiresAt)) {
            ProjectionClaim(operationId, owner, token, expiresAt)
        } else {
            // Lost the takeover race; the winner's lease stands.
            null
        }
    }

    private fun tryCreateLease(
        file: Path,
        operationId: String,
        owner: String,
        token: String,
        expiresAt: Instant
    ): Boolean =
        try {
            // `metadataFile` chunks long ids into nested directories, and
            // `writeString` will not create them — without this the claim fails on
            // an ordinary write, as a 500 from an upsert with nothing to do with
            // projections.
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                listOf(operationId, owner, token, expiresAt.toEpochMilliseconds().toString()).joinToString("\t"),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            true
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            false
        }

    private fun readLease(file: Path): ProjectionClaim? =
        try {
            val fields = Files.readString(file).trim().split('\t')
            if (fields.size != 4) {
                null
            } else {
                ProjectionClaim(
                    operationId = fields[0],
                    owner = fields[1],
                    token = fields[2],
                    expiresAt = Instant.fromEpochMilliseconds(fields[3].toLong())
                )
            }
        } catch (_: java.io.IOException) {
            null
        } catch (_: NumberFormatException) {
            null
        }

    /**
     * Deletes the operation only while the fencing token still matches the lease.
     *
     * The lease is re-read first and the delete is skipped on mismatch, so a worker
     * whose lease was taken over cannot remove an operation the new owner is still
     * delivering.
     */
    @Synchronized
    fun completeClaim(claim: ProjectionClaim): Boolean {
        val lease = readLease(leaseFile(claim.operationId)) ?: return false
        if (lease.token != claim.token) return false
        if (!lease.isLive(clock.now())) return false
        files.deleteMetadataFile(layout.projectionsDir, claim.operationId)
        Files.deleteIfExists(layout.projectionsDir.resolve(Hashes.sha256(claim.operationId)))
        Files.deleteIfExists(leaseFile(claim.operationId))
        return !Files.exists(files.metadataFile(layout.projectionsDir, claim.operationId))
    }

    @Synchronized
    fun releaseClaim(claim: ProjectionClaim) {
        val file = leaseFile(claim.operationId)
        val lease = readLease(file)
        if (lease == null || lease.token == claim.token) Files.deleteIfExists(file)
    }

    /**
     * The operation id is stored inside the lease rather than derived from the file
     * name, because the file name is a base64 encoding and is not reversible in a
     * way worth trusting.
     */
    @Synchronized
    fun leasedOperations(now: Instant): List<ProjectionClaim> {
        if (!Files.isDirectory(layout.projectionLeasesDir)) return emptyList()
        val live = mutableListOf<ProjectionClaim>()
        Files.walk(layout.projectionLeasesDir).use { stream ->
            stream
                .filter { file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }
                .forEach { file ->
                    val lease = readLease(file) ?: return@forEach
                    if (lease.isLive(now) && lease.operationId.isNotEmpty()) live += lease
                }
        }
        return live
    }

    /** Deletes the sidecar, used by [ProjectionOutbox.complete]. */
    fun deleteLease(operationId: String) {
        Files.deleteIfExists(leaseFile(operationId))
    }

    /**
     * Encoded exactly like every other metadata path.
     *
     * A raw `resolve(operationId)` looked fine and was not: an operation id may
     * contain `/`, so the lease path would land in a subdirectory that does not
     * exist and the write would fail — surfacing as a 500 on an ordinary write.
     */
    private fun leaseFile(operationId: String): Path = files.metadataFile(layout.projectionLeasesDir, operationId)
}
