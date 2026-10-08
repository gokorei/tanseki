@file:Suppress("ThrowsCount")

package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.BlobCorruptionException
import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.BlobSupport
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.ProjectionKind
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.StoreCapabilities
import gokorei.tanseki.core.domain.UnavailableException
import gokorei.tanseki.core.domain.VaultDirs
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.domain.documentIdFromPath
import gokorei.tanseki.core.domain.documentPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.PijulClient
import gokorei.tanseki.core.ports.PijulPatch
import gokorei.tanseki.core.ports.ProjectionBacklog
import gokorei.tanseki.core.ports.ProjectionClaim
import gokorei.tanseki.core.ports.ProjectionOperationStore
import gokorei.tanseki.core.ports.StorePage
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.core.text.LinkResolver
import gokorei.tanseki.core.text.MarkdownParser
import gokorei.tanseki.core.text.WikilinkRewriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlin.concurrent.withLock
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Vault-mode [ContextStore]: Markdown files are canonical (hand-editable),
 * history comes from the Pijul patch DAG via [PijulClient], and derived
 * bookkeeping (edges, revisions, blobs) lives under `.tanseki/` so document
 * content round-trips byte-for-byte.
 *
 * - **ids** are path-derived (`notes/a.md` -> `notes/a`).
 * - **content hash** is the sha256 of the file text → idempotent writes.
 * - **`ifRevision`** compares against the recorded patch hash → optimistic CAS.
 * - **blobs** are content-addressed under `.tanseki/blobs/sha256/`.
 */
class FileContextStore(
    private val vault: VaultPath,
    private val pijul: PijulClient,
    private val clock: Clock,
    private val logger: TansekiLogger = TansekiLogger.Noop
) : ContextStore, ProjectionOperationStore {
    private val root: Path = Path.of(vault.value).toAbsolutePath().normalize()
    private lateinit var layout: StoreLayout
    private lateinit var files: MetadataFiles
    private lateinit var scanner: VaultScanner
    private lateinit var sidecars: MetadataSidecars
    private lateinit var edges: EdgeIndex
    private lateinit var blobs: BlobStore
    private lateinit var outbox: ProjectionOutbox
    private lateinit var leases: ProjectionLeases
    private lateinit var recorder: PendingRecorder
    private lateinit var journal: PendingHistoryJournal
    private lateinit var tombstones: TombstoneIndex
    private lateinit var mutations: MutationPipeline
    private val locks = DocumentLocks()

    init {
        require(Files.isDirectory(root)) { "vault does not exist: ${vault.value}" }
        layout = StoreLayout(vault, root.toRealPath())
        files = MetadataFiles(layout)
        scanner = VaultScanner(layout)
        sidecars = MetadataSidecars(layout, files)
        edges = EdgeIndex(layout, files)
        blobs = BlobStore(layout, files)
        leases =
            ProjectionLeases(layout, files, clock) { id ->
                readOperation(files.metadataFile(layout.projectionsDir, id), files)?.operation
            }
        outbox = ProjectionOutbox(layout, files, leases, logger)
        tombstones = TombstoneIndex(layout, files, logger)
        recorder =
            PendingRecorder(
                layout,
                files,
                sidecars,
                edges,
                tombstones,
                locks,
                pijul,
                vault
            ) { id -> journal.deletePendingHistory(id) }
        journal = PendingHistoryJournal(layout, files, sidecars, outbox, recorder, logger)
        mutations = MutationPipeline(layout, files, scanner, clock, locks, this, outbox, journal, recorder, tombstones)
        pijul.init(vault)
        migrateLegacyMetadata()
        journal.pendingHistoryFiles().forEach { journal.recoverPendingHistory(it) }
    }

    override fun read(id: DocId): Document? {
        val file = layout.fileFor(id)
        if (!layout.requireSafeRegularFile(file)) return null
        // The scanner only admits the exact on-disk spelling, and the OS may
        // fold a canonical path onto a differently-cased entry (UPPER.md onto
        // UPPER.MD on macOS). Without this, one id reads 200 on a
        // case-insensitive filesystem and 404 on a case-sensitive one, while
        // list() reports it on neither. A folded name reads as absent.
        if (!layout.isExactVaultPath(file)) return null
        // The file can be removed between the existence check and the read; a
        // vanished document reads as absent rather than failing the caller.
        val content = runCatching { Files.readString(file) }.getOrNull() ?: return null
        return Document(
            id = id,
            collection = sidecars.readCollection(id),
            path = layout.relative(file),
            content = content,
            contentHash = Hashes.sha256(content),
            revision = RevisionId(sidecars.readRevision(id) ?: UNRECORDED),
            updatedAt = Instant.fromEpochMilliseconds(Files.getLastModifiedTime(file).toMillis()),
            frontmatter = MarkdownParser.parse(content).frontmatter
        )
    }

    override fun readIncludingDeleted(id: DocId): Document? {
        read(id)?.let { return it }
        val file = files.metadataFileOrNull(layout.tombstonesDir, id) ?: return null
        val tombstone = tombstones.readTombstone(id, file)
        val content = tombstone.content
        return Document(
            id = id,
            collection = Collection(tombstone.collection),
            path = tombstone.path,
            content = content,
            contentHash = tombstone.contentHash,
            revision = RevisionId(tombstone.revision),
            updatedAt = Instant.fromEpochMilliseconds(tombstone.deletedAt),
            frontmatter = Frontmatter(),
            deleted = true
        )
    }

    override fun readMany(ids: List<DocId>): List<Document> = ids.distinct().mapNotNull(::read)

    override fun write(
        doc: Document,
        message: String,
        author: String,
        ifRevision: RevisionId?
    ): Revision = mutations.write(doc, message, author, ifRevision)

    override fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
        mutations.delete(id, message, author, ifRevision)

    override fun restore(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
        mutations.restore(id, message, author, ifRevision)

    override fun rename(
        from: DocId,
        to: DocId,
        message: String,
        author: String,
        ifRevision: RevisionId?
    ): Revision = mutations.rename(from, to, message, author, ifRevision)

    @Synchronized
    override fun listDeleted(collection: Collection?): List<DocRef> = tombstones.listDeleted(collection)

    @Synchronized
    override fun list(collection: Collection?): List<DocRef> {
        if (!Files.isDirectory(root)) return emptyList()
        return scanner
            .documentFiles()
            .asSequence()
            .mapNotNull(::refForFileOrNull)
            .filter { collection == null || it.collection == collection }
            .sortedBy { it.id.value }
            .toList()
    }

    @Synchronized
    override fun listPage(
        collection: Collection?,
        limit: Int,
        offset: Int,
        pathPrefix: String?
    ): StorePage<DocRef> {
        if (pathPrefix.isNullOrEmpty()) return listPageUnfiltered(collection, limit, offset)
        require(limit > 0) { "limit must be positive" }
        require(offset >= 0) { "offset must not be negative" }
        val all = sortedRefs(collection).filter { it.path.startsWith(pathPrefix) }
        return StorePage(
            items = all.drop(offset).take(limit),
            total = all.size,
            hasMore = offset.toLong() + limit < all.size
        )
    }

    private fun listPageUnfiltered(collection: Collection?, limit: Int, offset: Int): StorePage<DocRef> {
        require(limit > 0) { "limit must be positive" }
        require(offset >= 0) { "offset must not be negative" }
        val all = sortedRefs(collection)
        return StorePage(
            items = all.drop(offset).take(limit),
            total = all.size,
            hasMore = offset.toLong() + limit < all.size
        )
    }

    override fun listPageAfter(
        collection: Collection?,
        pathPrefix: String?,
        after: DocId?,
        limit: Int
    ): StorePage<DocRef> {
        require(limit > 0) { "limit must be positive" }
        val matched = sortedRefs(collection).filter { pathPrefix.isNullOrEmpty() || it.path.startsWith(pathPrefix) }
        val remaining = if (after == null) matched else matched.filter { it.id.value > after.value }
        return StorePage(
            items = remaining.take(limit),
            total = matched.size,
            hasMore = remaining.size > limit
        )
    }

    /**
     * Every reference in [collection], ordered by id.
     *
     * `scanner.documentFiles()` orders by relative path including the `.md` suffix, which
     * is close to but not the same as id order — ids are path-derived, so `a.md`
     * sorts against `a/b.md` by `.` vs `/` rather than by the id itself. A cursor
     * needs one total order that every adapter agrees on, so it is applied here
     * rather than inherited from the directory walk.
     */
    private fun sortedRefs(collection: Collection?): List<DocRef> {
        if (!Files.isDirectory(root)) return emptyList()
        return scanner
            .documentFiles()
            .mapNotNull(::refForFileOrNull)
            .filter { collection == null || it.collection == collection }
            .sortedBy { it.id.value }
    }

    override fun listAll(): List<DocRef> {
        val active = list()
        if (!Files.isDirectory(layout.tombstonesDir)) return active
        val tombstones =
            Files.walk(layout.tombstonesDir).use { stream ->
                val refs = mutableListOf<DocRef>()
                stream
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .filter { !Files.isSymbolicLink(it) }
                    .forEach { file -> tombstones.tombstoneRefOrNull(file)?.let(refs::add) }
                refs
            }
        return (active + tombstones).distinctBy { it.id }.sortedBy { it.id.value }
    }

    /** A tombstone sidecar as a [DocRef], or null after quarantining a corrupt one. */

    override fun projectionOperations(): ProjectionOperationStore = this

    override fun enqueue(operation: ProjectionOperation) = outbox.enqueue(operation)

    @Synchronized
    override fun pending(limit: Int): List<ProjectionOperation> = outbox.pending(limit)

    @Synchronized
    override fun complete(operation: ProjectionOperation) = outbox.complete(operation)

    @Synchronized
    override fun claim(
        operationId: String,
        owner: String,
        lease: Duration,
        now: Instant
    ): ProjectionClaim? = leases.claim(operationId, owner, lease, now)

    @Synchronized
    override fun completeClaim(claim: ProjectionClaim): Boolean = leases.completeClaim(claim)

    @Synchronized
    override fun releaseClaim(claim: ProjectionClaim) = leases.releaseClaim(claim)

    @Synchronized
    override fun leasedOperations(now: Instant): List<ProjectionClaim> = leases.leasedOperations(now)

    @Synchronized
    override fun recordFailure(operation: ProjectionOperation, error: Throwable?): Int =
        outbox.recordFailure(operation)

    @Synchronized
    override fun deadLetter(operation: ProjectionOperation, error: Throwable?) =
        outbox.deadLetter(operation)

    @Synchronized
    override fun deadLettered(limit: Int): List<ProjectionOperation> = outbox.deadLettered(limit)

    @Synchronized
    override fun pendingLive(limit: Int): List<ProjectionOperation> = outbox.pendingLive(limit)

    @Synchronized
    override fun quarantineCorrupt(): Int = outbox.quarantineCorrupt()

    @Synchronized
    override fun backlog(): ProjectionBacklog = outbox.backlog()

    @Synchronized
    override fun upsertEdge(edge: Edge) = edges.upsertEdge(edge)

    @Synchronized
    override fun removeEdges(src: DocId, rel: RelType?) = edges.removeEdges(src, rel)

    @Synchronized
    override fun removeIncomingEdges(dst: DocId, rel: RelType?) = edges.removeIncomingEdges(dst, rel)

    @Synchronized
    override fun clearEdges() = edges.clearEdges()

    @Synchronized
    override fun replaceEdges(src: DocId, edgeList: List<Edge>) = edges.replaceEdges(src, edgeList)

    @Synchronized
    override fun neighbors(id: DocId, rel: RelType?): List<Edge> = edges.neighbors(id, rel)

    @Synchronized
    override fun incomingNeighbors(id: DocId, rel: RelType?): List<Edge> = edges.incomingNeighbors(id, rel)

    override fun history(id: DocId): List<Revision> {
        val original = sidecars.renameOrigin(id)?.let { runCatching { DocId(it) }.getOrNull() }
        if (original == null || original == id) {
            return revisionsOf(pijul.log(vault, id, Int.MAX_VALUE), id)
        }
        // The pre-rename patches belong to this document; they are reported under
        // its current id so a caller paging one history sees one sequence rather
        // than a truncated past and an unrelated future. One `log -- old new`
        // instead of two filtered logs: pijul returns the union server-side, and
        // a change touching both paths (a rename records both ends) is deduped
        // by [PijulClient.logUnion] rather than counted twice.
        val union = pijul.logUnion(vault, listOf(original, id), Int.MAX_VALUE)
        return revisionsOf(union, id).sortedWith(compareBy({ it.createdAt }, { it.revision.value }))
    }

    private fun revisionsOf(patches: List<PijulPatch>, id: DocId): List<Revision> =
        patches.map { patch ->
            Revision(
                docId = id,
                revision = patch.hash,
                author = patch.author,
                message = patch.message,
                contentHash = "",
                createdAt = patch.timestamp,
                deps = patch.dependencies
            )
        }

    override fun historyOwner(id: DocId): DocRef? {
        read(id)?.let {
            return DocRef(id, it.collection, it.path, it.contentHash, it.revision, it.updatedAt)
        }
        val file = files.metadataFileOrNull(layout.tombstonesDir, id) ?: return null
        val tombstone = tombstones.readTombstone(id, file)
        return DocRef(
            id = id,
            collection = Collection(tombstone.collection),
            path = tombstone.path,
            revision = RevisionId(tombstone.revision),
            updatedAt = Instant.fromEpochMilliseconds(tombstone.deletedAt)
        )
    }

    override fun putBlob(bytes: ByteArray): BlobRef = blobs.putBlob(bytes)

    override fun getBlob(ref: BlobRef): ByteArray = blobs.getBlob(ref)

    override fun capabilities() =
        StoreCapabilities(
            supportsHistory = true,
            supportsPatchGraph = true,
            supportsTransactions = false,
            consistency = Consistency.READ_YOUR_WRITES,
            supportsTombstoneEnumeration = true,
            supportsSync = true
        )

    fun rejectedDocumentPaths(): List<String> = scanner.rejectedDocumentPaths()

    private fun refForFileOrNull(file: Path): DocRef? {
        val id = scanner.idForOrNull(file) ?: return null
        return try {
            DocRef(
                id = id,
                collection = sidecars.readCollection(id),
                path = layout.relative(file),
                contentHash = null,
                revision = sidecars.readRevision(id)?.let(::RevisionId),
                updatedAt = Instant.fromEpochMilliseconds(Files.getLastModifiedTime(file).toMillis()),
                changeToken = scanner.changeToken(file)
            )
        } catch (_: java.io.IOException) {
            // A vault is foreign input: an external editor can delete or replace
            // a file between the directory walk and this read. Skipping it for
            // this pass is correct; throwing would fail the whole listing.
            null
        }
    }

    private fun projectionFile(operationId: String): Path = files.metadataFile(layout.projectionsDir, operationId)

    private fun migrateLegacyMetadata() {
        val ids = scanner.documentIds()
        listOf(layout.revisionsDir, layout.edgesDir, layout.collectionsDir).forEach { directory ->
            migrateDocumentMetadata(directory, ids)
        }
        migrateProjectionMetadata()
    }

    private fun migrateDocumentMetadata(directory: Path, ids: List<String>) {
        val legacyFiles =
            ids
                .mapNotNull { id ->
                    files.legacyMetadataFile(directory, id)?.let { legacy ->
                        legacy to files.metadataFile(directory, id)
                    }
                }.groupBy({ it.first }, { it.second })
        legacyFiles.forEach { (legacy, targets) ->
            if (!files.metadataFileExists(legacy)) return@forEach
            targets.forEach { target ->
                if (!files.metadataFileExists(target)) files.copyMetadataFile(legacy, target)
            }
            if (targets.size == 1 && files.metadataFilesEqual(legacy, targets.single())) {
                Files.deleteIfExists(legacy)
            }
        }
    }

    private fun migrateProjectionMetadata() {
        if (!Files.isDirectory(layout.projectionsDir)) return
        val operations =
            buildList {
                Files.walk(layout.projectionsDir).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                        .filter { !Files.isSymbolicLink(it) }
                        .forEach { file ->
                            val operation = readOperation(file, files)?.operation ?: return@forEach
                            add(file to operation)
                        }
                }
            }
        operations
            .groupBy({ it.first }, { it.second })
            .forEach { (file, entries) ->
                if (entries.size != 1) return@forEach
                val target = projectionFile(entries.single().id)
                if (file != target && !files.metadataFileExists(target)) {
                    files.copyMetadataFile(file, target)
                }
                if (file != target && files.metadataFilesEqual(file, target)) {
                    Files.deleteIfExists(file)
                }
            }
    }
}
