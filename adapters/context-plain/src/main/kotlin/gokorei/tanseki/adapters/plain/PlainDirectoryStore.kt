@file:Suppress("ThrowsCount")

package gokorei.tanseki.adapters.plain

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.ContextStore
import java.nio.file.Path

/**
 * Read-only [ContextStore] over an arbitrary directory of Markdown files.
 *
 * This is the import source for a foreign vault: [gokorei.tanseki.adapters.file.FileContextStore]
 * must never be pointed at one, because opening it runs `pijul init` and creates the derived-state
 * directory inside the target. This adapter performs no initialization and holds no derived state —
 * every read goes straight to the filesystem — so a source directory is byte-identical before and
 * after a full walk.
 *
 * Listing walks `.md` files and skips tool state (`.obsidian/`, `.trash/`, `.tanseki/`,
 * `.pijul/`) and every other dotfile-prefixed path. A file whose name cannot become a [DocId] is
 * skipped and reported via [rejectedDocumentPaths] rather than failing the listing.
 *
 * Port deviations, all deliberate for a source that has no store of its own:
 * - every mutating operation (`write`, `delete`, `restore`, `rename`, `appendHistory`, edge writes,
 *   blob writes and reads) throws [UnsupportedOperationException] naming this adapter. The port's
 *   own edge/history defaults throw the domain `UnsupportedStoreOperationException`; this adapter
 *   names itself instead so a caller catching the JDK type — which is what the ticket's acceptance
 *   criteria assert — sees which adapter refused. `removeIncomingEdges` and `incomingNeighbors`
 *   inherit the port defaults.
 * - `neighbors` is empty: edges are derived by the `Indexer` from document content, not stored by
 *   the source. `ModeTransfer` then derives and writes them at the target.
 * - `history` is empty and the history owner is null: no history is invented for a directory that
 *   keeps none. `capabilities` advertises `supportsHistory = false` so `ModeTransfer` synthesizes
 *   one revision per document from the document itself instead.
 * - `capabilities` advertises `supportsPatchGraph = false`, so `importDocument` keeps the source
 *   id/path pair verbatim instead of re-deriving it.
 * - the shared `ContextStoreContract` testkit is not run against this adapter: it requires writes,
 *   which this adapter refuses by design. The deviations above are the justification.
 *
 * Implementation split ([PlainFiles], [PlainReads], this facade) so no single class trips the
 * function-count gate without touching the shared lint baseline.
 */
class PlainDirectoryStore private constructor(private val reads: PlainReads) : ContextStore by reads {
    constructor(directory: Path) : this(PlainReads(PlainFiles(directory)))

    /**
     * Relative paths that look like documents but cannot become a [DocId].
     *
     * A foreign directory is untrusted input: Obsidian creates names Tanseki cannot model, and one
     * such file must not hide the rest of the directory. Listed here so the import is visibly
     * lossy rather than quietly so.
     */
    fun rejectedDocumentPaths(): List<String> = reads.files.rejectedDocumentPaths()

    override fun restore(id: DocId, message: String, author: String, ifRevision: RevisionId?): Revision =
        throw readOnlyFailure("restore")

    override fun rename(
        from: DocId,
        to: DocId,
        message: String,
        author: String,
        ifRevision: RevisionId?
    ): Revision = throw readOnlyFailure("rename")

    override fun replaceEdges(src: DocId, edges: List<Edge>): Unit = throw readOnlyFailure("replaceEdges")

    override fun clearEdges(): Unit = throw readOnlyFailure("clearEdges")

    override fun appendHistory(id: DocId, revisions: List<Revision>): Unit = throw readOnlyFailure("appendHistory")

    override fun historyOwner(id: DocId): DocRef? = null
}
