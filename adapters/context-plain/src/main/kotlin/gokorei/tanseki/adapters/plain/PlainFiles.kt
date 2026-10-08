@file:Suppress("ThrowsCount")

package gokorei.tanseki.adapters.plain

import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.BlobSupport
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.StoreCapabilities
import gokorei.tanseki.core.domain.documentIdFromPath
import gokorei.tanseki.core.domain.documentPath
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.text.MarkdownParser
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.time.Instant

internal fun readOnlyFailure(operation: String): UnsupportedOperationException =
    UnsupportedOperationException("PlainDirectoryStore is a read-only source and does not support $operation")

/**
 * Filesystem layer behind [PlainReads]: walking, per-file reads, and the rejected-path ledger.
 *
 * A foreign directory is untrusted input — Obsidian creates names Tanseki cannot model — so one
 * unmodellable file must never hide the rest of the directory. Such files are skipped and reported
 * via [rejectedDocumentPaths] instead of failing the listing.
 */
internal class PlainFiles(root: Path) {
    private val root: Path = root.toAbsolutePath().normalize()

    init {
        require(Files.isDirectory(this.root)) { "directory does not exist: $root" }
    }

    private val rejectedPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Relative paths that look like documents but cannot become a [DocId].
     *
     * Listed here so the import is visibly lossy rather than quietly so.
     */
    fun rejectedDocumentPaths(): List<String> = rejectedPaths.toList()

    fun readDocument(id: DocId): Document? {
        val file = fileFor(id) ?: return null
        if (!isReadableFile(file)) return null
        val bytes = runCatching { Files.readAllBytes(file) }.getOrNull() ?: return null
        val content = bytes.toString(Charsets.UTF_8)
        val mtime =
            runCatching {
                Instant.fromEpochMilliseconds(Files.getLastModifiedTime(file).toMillis())
            }.getOrNull() ?: return null
        return Document(
            id = id,
            collection = COLLECTION,
            path =
                this.root
                    .relativize(file)
                    .toString()
                    .replace('\\', '/'),
            content = content,
            contentHash = BlobSupport.sha256(content),
            revision = RevisionId(BlobSupport.sha256(content)),
            updatedAt = mtime,
            frontmatter = parsedFrontmatter(content)
        )
    }

    /**
     * The file holding [id], or null when [id] cannot address this directory.
     *
     * Ids under dotfile-prefixed paths (`.obsidian/…`, `.trash/…`) never address documents, so a
     * direct read of one is absent rather than an error — the listing could never have produced it.
     */
    fun fileFor(id: DocId): Path? {
        if (id.value.split('/').any { it.startsWith('.') }) return null
        val file = root.resolve(id.documentPath()).normalize()
        if (!file.startsWith(root)) return null
        return file
    }

    fun isReadableFile(file: Path): Boolean {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.isSymbolicLink(file)) return false
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false
        return true
    }

    fun documentFiles(): List<Path> {
        Files.walk(root).use { stream ->
            return stream
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .filter { it.extension.equals("md", ignoreCase = true) }
                .filter { !hasDotSegment(it) }
                .sorted(compareBy { root.relativize(it).toString() })
                .toList()
        }
    }

    /** True when any segment of the root-relative path is dotfile-prefixed. */
    fun hasDotSegment(file: Path): Boolean =
        root.relativize(file).any { it.toString().startsWith('.') }

    fun refForFileOrNull(file: Path): DocRef? {
        val relative = root.relativize(file).toString().replace('\\', '/')
        val id =
            runCatching { documentIdFromPath(relative) }.getOrNull()
                ?: run {
                    rejectedPaths.add(relative)
                    return null
                }
        if (id.value.split('/').any { it.startsWith('.') }) return null
        return try {
            DocRef(
                id = id,
                collection = COLLECTION,
                path = relative,
                contentHash = null,
                revision = null,
                updatedAt = Instant.fromEpochMilliseconds(Files.getLastModifiedTime(file).toMillis())
            )
        } catch (_: java.io.IOException) {
            // The source directory is foreign input: an external editor can delete or replace a file
            // between the walk and this read. Skipping it for this pass is correct; throwing would
            // fail the whole listing.
            null
        }
    }

    /**
     * The typed frontmatter view, degraded to empty (with the raw block preserved) when the block
     * is not modellable.
     *
     * [MarkdownParser.parse] is total over malformed YAML and already degrades; the `runCatching`
     * only guards the guarantee — a read must never throw for content alone.
     */
    fun parsedFrontmatter(content: String): Frontmatter =
        runCatching { MarkdownParser.parse(content).frontmatter }.getOrDefault(Frontmatter())

    companion object {
        internal val COLLECTION = Collection("vault")
    }
}

/**
 * Loud refusals for every [ContextStore] write, shared by read-only sources.
 *
 * The port's own edge/history defaults throw the domain `UnsupportedStoreOperationException`; these
 * name the adapter instead so a caller catching the JDK type sees which adapter refused.
 */
internal abstract class ThrowingWrites : ContextStore {
    override fun write(doc: Document, message: String, author: String, ifRevision: RevisionId?): Revision =
        throw readOnlyFailure("write")

    override fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
        throw readOnlyFailure("delete")

    override fun upsertEdge(edge: Edge): Unit = throw readOnlyFailure("upsertEdge")

    override fun removeEdges(src: DocId, rel: RelType?): Unit = throw readOnlyFailure("removeEdges")

    override fun putBlob(bytes: ByteArray): BlobRef = throw readOnlyFailure("putBlob")

    override fun getBlob(ref: BlobRef): ByteArray = throw readOnlyFailure("getBlob")
}

/**
 * The read half of the plain-directory adapter: real reads over [PlainFiles], loud refusals for
 * everything that would write.
 *
 * Split from [PlainDirectoryStore] so no single class trips the function-count gate; the facade
 * below adds only the operations whose port defaults would otherwise do something live.
 */
internal class PlainReads(internal val files: PlainFiles) : ThrowingWrites() {
    override fun read(id: DocId): Document? = files.readDocument(id)

    override fun list(collection: Collection?): List<DocRef> =
        files
            .documentFiles()
            .mapNotNull(files::refForFileOrNull)
            .filter { collection == null || it.collection == collection }
            .sortedBy { it.id.value }

    override fun neighbors(id: DocId, rel: RelType?): List<Edge> = emptyList()

    override fun history(id: DocId): List<Revision> = emptyList()

    override fun capabilities(): StoreCapabilities =
        StoreCapabilities(
            supportsHistory = false,
            supportsPatchGraph = false,
            supportsTransactions = false,
            consistency = Consistency.READ_YOUR_WRITES
        )
}
