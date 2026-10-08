package gokorei.tanseki.core.domain

/** Stable, path-derived document identifier. */
@JvmInline
value class DocId(val value: String) {
    init {
        require(value.isNotBlank()) { "DocId must not be blank" }
        require(value == value.trim()) { "DocId must not have surrounding whitespace" }
        require('\\' !in value) { "DocId must use '/' as its path separator" }
        require(value.none { it.isISOControl() }) { "DocId must not contain control characters" }
        require(!value.endsWith(".md", ignoreCase = true)) { "DocId must not include the .md suffix" }
        require(value.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "DocId must contain only non-empty relative path segments"
        }
    }

    override fun toString(): String = value
}

/**
 * The one path rule every profile shares: a document's path is its id plus the
 * Markdown suffix. The vault stores the file at exactly this relative path, so
 * no adapter may accept anything else — see [Document] for the contract.
 */
fun DocId.documentPath(): String = "$value.md"

fun documentIdFromPath(path: String): DocId {
    require(path.endsWith(MARKDOWN_SUFFIX)) { "document path must end in .md" }
    return DocId(path.removeSuffix(MARKDOWN_SUFFIX))
}

private const val MARKDOWN_SUFFIX = ".md"

/** Backend-neutral revision identifier (Pijul patch hash, DB revision, ...). */
@JvmInline
value class RevisionId(val value: String) {
    init {
        require(value.isNotBlank()) { "RevisionId must not be blank" }
    }

    override fun toString(): String = value
}

/** Namespacing/collection key isolating a producer's documents. */
@JvmInline
value class Collection(val value: String) {
    init {
        require(value.isNotBlank()) { "Collection must not be blank" }
    }

    override fun toString(): String = value
}

/** Relationship type for graph edges (e.g. `links-to`, `references`). */
@JvmInline
value class RelType(val value: String) {
    init {
        require(value.isNotBlank()) { "RelType must not be blank" }
    }

    override fun toString(): String = value
}

/** Location of a vault on the host filesystem, expressed without I/O types. */
@JvmInline
value class VaultPath(val value: String) {
    init {
        require(value.isNotBlank()) { "VaultPath must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Names of the directories a vault carries alongside its documents.
 *
 * These are DERIVED state: the Lookup index is rebuildable from the documents, but
 * the bookkeeping directory is not entirely so. A document's collection assignment
 * and its recorded revision live only there, so this directory is part of the
 * on-disk contract for an existing vault.
 */
object VaultDirs {
    /** Derived state: the Lookup index, revisions, edges, blobs, tombstones. */
    const val DERIVED = ".tanseki"

    /** Where the derived-state directory sits for a vault at [vault]. */
    fun derived(vault: java.nio.file.Path): java.nio.file.Path = vault.resolve(DERIVED)
}
