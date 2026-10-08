package gokorei.tanseki.cli.transfer

import gokorei.tanseki.core.application.EdgeDeriver
import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.text.MarkdownParser
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.time.Instant

/**
 * Imports a plain directory of Markdown files into a [ContextStore] under the
 * [FilenameSanitizer] policy.
 *
 * This is the importer-side counterpart to the vault scanner's tolerance: the
 * scanner skips Obsidian-legal-but-invalid names and reports them, while this
 * importer gives them a deterministic valid id and reports the mapping, so no
 * note is dropped and none is silently mangled. `DocId` itself is untouched.
 *
 * Behaviour:
 * - candidates are `*.md` files matched case-insensitively (Obsidian opens
 *   `NOTES.MD`); bookkeeping directories (`.tanseki`,
 *   `.pijul`, `.obsidian`, `.trash`) and dotfile segments are excluded;
 * - the source directory is only read, never written;
 * - content is stored byte-identical; wikilinks are not rewritten, and every
 *   inbound link a rename genuinely breaks (no longer resolves to the note
 *   post-import) is listed in [TransferReport.wikilinkInvalidations];
 *   bare-name links that still resolve through trailing-segment match are not
 *   invalidations;
 * - post-sanitize collisions keep the lexicographically first source and
 *   report the rest in [TransferReport.sanitizeCollisions] — never overwrite.
 * - with `dryRun = true` the run computes the same report without writing:
 *   no document, edge, or history call reaches the target store, so a dry run
 *   against a fresh library leaves no database behind and a dry run against an
 *   existing one changes neither its documents nor their revisions. Edge counts
 *   are derived against a read-only overlay of the planned documents, which is
 *   what the target would hold after a real run, so both modes describe the
 *   same computation.
 */
class DirectoryImporter(
    private val collection: Collection = Collection("vault"),
    private val message: String = "sanitized import",
    private val author: String = "tanseki-cli",
    private val now: () -> Instant = { Instant.fromEpochSeconds(1_700_000_000) }
) {
    fun copy(sourceDir: Path, target: ContextStore, dryRun: Boolean = false): TransferReport {
        val root = sourceDir.toAbsolutePath().normalize()
        require(Files.isDirectory(root)) { "import source does not exist: $sourceDir" }
        val read = readSources(candidateFiles(root))
        val plan = FilenameSanitizer.sanitizeAll(read.contents.keys)
        val applied = applyDocuments(root, target, plan, read.contents, dryRun)
        val edges = deriveEdges(target, applied.planned, dryRun)
        // Resolve-check against the post-import view (target plus planned docs),
        // so a bare-name link that still resolves through trailing-segment
        // match is not reported: only genuinely dangling (or mis-resolved)
        // links count as invalidated. The overlay covers both modes: a real run
        // already wrote the plan, a dry run only overlaid it.
        val postView = OverlayStore(target, applied.planned)
        val postResolve = EdgeDeriver(postView)::resolve
        val invalidations = FilenameSanitizer.findInvalidatedWikilinks(read.contents, plan, postResolve)
        return TransferReport(
            documentsImported = applied.imported,
            documentsSkipped = applied.skipped,
            edgesImported = edges.edges,
            unreadable = read.unreadable.sortedBy { it.value },
            failures = read.failures + applied.failures + edges.failures,
            renames = plan.renames.filter { plan.winners.containsKey(it.sourcePath) },
            sanitizeCollisions = plan.collisions,
            wikilinkInvalidations = invalidations,
            unresolvedLinks = edges.unresolved
        )
    }

    /** Every candidate file read off disk, with the ones that could not be read reported. */
    private fun readSources(candidates: List<Pair<String, Path>>): SourceRead {
        val contents = linkedMapOf<String, String>()
        val unreadable = mutableListOf<DocId>()
        val failures = mutableListOf<TransferFailure>()
        candidates.forEach { (sourcePath, file) ->
            val text =
                try {
                    Files.readString(file)
                } catch (error: Exception) {
                    val id = FilenameSanitizer.sanitizeOne(sourcePath).targetId
                    unreadable += id
                    failures += TransferFailure(id, TransferStage.READ, error::class.simpleName ?: "Exception")
                    null
                }
            if (text != null) contents[sourcePath] = text
        }
        return SourceRead(contents, unreadable, failures)
    }

    /**
     * Writes (or, for a dry run, pretends to write) every winning document.
     *
     * Winners iterate in sorted source order so the outcome is stable across
     * runs and filesystems.
     */
    private fun applyDocuments(
        root: Path,
        target: ContextStore,
        plan: SanitizePlan,
        contents: Map<String, String>,
        dryRun: Boolean
    ): AppliedDocuments {
        var imported = 0
        var skipped = 0
        val failures = mutableListOf<TransferFailure>()
        val planned = linkedMapOf<DocId, Document>()
        plan.winners.entries.sortedBy { it.key }.forEach { (sourcePath, sanitized) ->
            val text = contents.getValue(sourcePath)
            when (applyOne(root, target, sourcePath, sanitized, text, dryRun, failures, planned)) {
                ApplyOutcome.IMPORTED -> imported++
                ApplyOutcome.SKIPPED -> skipped++
                ApplyOutcome.FAILED -> Unit
            }
        }
        return AppliedDocuments(planned, imported, skipped, failures)
    }

    /** What applying one winning document recorded. */
    private enum class ApplyOutcome { IMPORTED, SKIPPED, FAILED }

    /** Builds, writes (or pretends to write), and plans one document. */
    private fun applyOne(
        root: Path,
        target: ContextStore,
        sourcePath: String,
        sanitized: SanitizedFile,
        text: String,
        dryRun: Boolean,
        failures: MutableList<TransferFailure>,
        planned: MutableMap<DocId, Document>
    ): ApplyOutcome {
        val document = buildDocument(root, sourcePath, sanitized, text)
        val existing = runCatching { target.readIncludingDeleted(sanitized.targetId) }.getOrNull()
        // A differing hash (or no existing document) would be imported, an
        // identical one skipped — the same decision either mode records.
        val countsAsImported = existing == null || existing.contentHash != document.contentHash
        if (dryRun) {
            planned[document.id] = document
            return if (countsAsImported) ApplyOutcome.IMPORTED else ApplyOutcome.SKIPPED
        }
        val wrote =
            try {
                target.write(document, message, author)
                true
            } catch (error: Exception) {
                failures += TransferFailure(document.id, TransferStage.WRITE, error::class.simpleName ?: "Exception")
                false
            }
        if (!wrote) return ApplyOutcome.FAILED
        planned[document.id] = target.read(sanitized.targetId) ?: document
        return if (countsAsImported) ApplyOutcome.IMPORTED else ApplyOutcome.SKIPPED
    }

    private fun buildDocument(root: Path, sourcePath: String, sanitized: SanitizedFile, text: String): Document {
        val file = root.resolve(sourcePath)
        val updatedAt =
            runCatching {
                Instant.fromEpochMilliseconds(Files.getLastModifiedTime(file).toMillis())
            }.getOrDefault(now())
        val hash = sha256(text)
        return Document(
            id = sanitized.targetId,
            collection = collection,
            path = sanitized.targetPath,
            content = text,
            contentHash = hash,
            // Content-derived so a changed file re-imports under a fresh
            // revision instead of colliding with the previous import's
            // revision id, while an unchanged file keeps its hash and
            // stays idempotent via the store's content-hash check.
            revision = RevisionId("import-${hash.take(16)}"),
            updatedAt = updatedAt,
            frontmatter =
                runCatching {
                    MarkdownParser.parse(text).frontmatter
                }.getOrDefault(
                    gokorei.tanseki.core.domain
                        .Frontmatter()
                )
        )
    }

    /**
     * Derives edges once every document is in place (or overlaid, for a dry
     * run), so a forward link to a later file resolves the same way in both
     * modes instead of depending on write order.
     */
    private fun deriveEdges(target: ContextStore, planned: Map<DocId, Document>, dryRun: Boolean): EdgeCounts {
        val deriver = EdgeDeriver(if (dryRun) OverlayStore(target, planned) else target)
        var edges = 0
        var unresolved = 0
        val failures = mutableListOf<TransferFailure>()
        planned.values.forEach { document ->
            edges +=
                if (dryRun) {
                    runCatching { deriver.derive(document).size }.getOrDefault(0)
                } else {
                    try {
                        deriver.reconcile(document).size
                    } catch (error: Exception) {
                        failures +=
                            TransferFailure(document.id, TransferStage.EDGES, error::class.simpleName ?: "Exception")
                        0
                    }
                }
            unresolved += countUnresolved(document) { link -> deriver.resolve(link) }
        }
        return EdgeCounts(edges, unresolved, failures)
    }

    private fun countUnresolved(document: Document, resolve: (String) -> DocId?): Int =
        runCatching { MarkdownParser.parse(document.content).links }
            .getOrDefault(emptyList())
            .count { link -> resolve(link.target) == null }

    /** One read pass over the source directory. */
    private data class SourceRead(
        val contents: Map<String, String>,
        val unreadable: List<DocId>,
        val failures: List<TransferFailure>
    )

    /** Every winning document, with the import/skip decision the run recorded. */
    private data class AppliedDocuments(
        val planned: Map<DocId, Document>,
        val imported: Int,
        val skipped: Int,
        val failures: List<TransferFailure>
    )

    /** Edge derivation outcome for one run. */
    private data class EdgeCounts(
        val edges: Int,
        val unresolved: Int,
        val failures: List<TransferFailure>
    )

    /**
     * Read-only view of the target plus the documents a dry run would have
     * written. Reads the plan first and delegates everything else to the
     * target; writes throw, because edge derivation only reads, so anything
     * reaching a write is a bug rather than a plan.
     */
    private class OverlayStore(
        private val target: ContextStore,
        private val planned: Map<DocId, Document>
    ) : ContextStore by target {
        override fun read(id: DocId): Document? = planned[id] ?: target.read(id)

        override fun readIncludingDeleted(id: DocId): Document? =
            planned[id] ?: target.readIncludingDeleted(id)

        override fun list(collection: Collection?): List<DocRef> {
            val merged = target.list(collection).associateBy { it.id }.toMutableMap()
            planned.values.forEach { document ->
                if (collection == null || document.collection == collection) {
                    merged[document.id] =
                        DocRef(
                            document.id,
                            document.collection,
                            document.path,
                            document.contentHash,
                            document.revision,
                            document.updatedAt
                        )
                }
            }
            return merged.values.sortedBy { it.id.value }
        }

        override fun write(doc: Document, message: String, author: String, ifRevision: RevisionId?): Revision =
            throw UnsupportedOperationException("dry-run overlay is read-only")

        override fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
            throw UnsupportedOperationException("dry-run overlay is read-only")

        override fun upsertEdge(edge: Edge): Unit =
            throw UnsupportedOperationException("dry-run overlay is read-only")

        override fun removeEdges(src: DocId, rel: RelType?): Unit =
            throw UnsupportedOperationException("dry-run overlay is read-only")

        override fun putBlob(bytes: ByteArray): BlobRef =
            throw UnsupportedOperationException("dry-run overlay is read-only")
    }

    private fun candidateFiles(root: Path): List<Pair<String, Path>> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .filter { !isExcluded(root.relativize(it).toString().replace('\\', '/')) }
                .filter { it.extension.equals("md", ignoreCase = true) }
                .map { it to root.relativize(it).toString().replace('\\', '/') }
                .sorted(compareBy { it.second })
                .map { (file, relative) -> relative to file }
                .toList()
        }
    }

    private fun isExcluded(relative: String): Boolean {
        val segments = relative.split('/')
        if (segments.any { it in BOOKKEEPING }) return true
        // Obsidian state and OS metadata live in dotfile segments; a document
        // named exactly `.md` has an empty stem and is not importable either —
        // the sanitizer would map it, but excluding it here keeps the walk to
        // files that look like notes rather than sidecars.
        if (segments.any { it.startsWith('.') }) return true
        return false
    }

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val BOOKKEEPING = setOf(".tanseki", ".pijul", ".obsidian", ".trash")
    }
}
