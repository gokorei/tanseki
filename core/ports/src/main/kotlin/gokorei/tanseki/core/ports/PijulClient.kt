package gokorei.tanseki.core.ports

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.VaultPath
import kotlin.time.Instant

/** One patch entry in the Pijul patch DAG. */
data class PijulPatch(
    val hash: RevisionId,
    val author: String,
    val message: String,
    val timestamp: Instant,
    val dependencies: List<RevisionId> = emptyList(),
    val operationId: String? = null
)

/** Coarse working-tree status for a vault. */
data class PijulStatus(
    val clean: Boolean,
    val modified: List<String> = emptyList(),
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList()
)

/**
 * Text-layer version control boundary. Adapters drive the `pijul` CLI in a
 * bounded, batched way; core never sees subprocess concerns. Swappable for a
 * future native binding without touching core.
 */
interface PijulClient {
    /** Initialise the vault as a Pijul repository if it is not one already. */
    fun init(vault: VaultPath) = Unit

    fun status(vault: VaultPath): PijulStatus

    fun log(vault: VaultPath, docId: DocId? = null, limit: Int = 100): List<PijulPatch>

    /**
     * Union of the filtered logs for [docIds] in as few subprocesses as the
     * adapter can manage — one `pijul log -- a b` for the CLI, where a process
     * spawn dominates the read latency and the semaphore serialises fan-out.
     *
     * The default fans out to [log] and dedupes by hash; adapters that can
     * filter server-side override it with a single invocation. [limit] applies
     * per document before the merge, so the union can hold up to
     * `docIds.size * limit` patches.
     */
    fun logUnion(vault: VaultPath, docIds: List<DocId>, limit: Int = 100): List<PijulPatch> {
        require(limit > 0) { "limit must be > 0" }
        return docIds.distinct().flatMap { log(vault, it, limit) }.distinctBy { it.hash }
    }

    fun diff(vault: VaultPath, path: String? = null): String

    /**
     * Per-path diffs for [paths] in as few subprocesses as the adapter can
     * manage — one `pijul diff --json -- a b` for the CLI. Paths with no
     * changes are absent from the map rather than present with an empty diff.
     *
     * The default fans out to [diff]; adapters that can filter server-side
     * override it with a single invocation.
     */
    fun diffMany(vault: VaultPath, paths: List<String>): Map<String, String> =
        paths.distinct().associateWith { diff(vault, it) }

    fun record(
        vault: VaultPath,
        message: String,
        author: String,
        paths: List<String>,
        operationId: String? = null
    ): PijulPatch

    fun apply(vault: VaultPath, patch: String)
}
