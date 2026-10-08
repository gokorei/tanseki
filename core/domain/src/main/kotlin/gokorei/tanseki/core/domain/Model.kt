package gokorei.tanseki.core.domain

import kotlin.time.Instant

/**
 * Canonical unit of content. Frontmatter and links are stored in-file; the
 * parsed/derived forms live in the [Document] and the graph [Edge]s.
 *
 * **Path contract.** [path] is always derived from [id]: `"<id>.md"`
 * ([documentPath]). A caller-supplied path must equal the derived one or be
 * omitted; anything else is rejected with [InvalidInputException]. The rule is
 * enforced in the command layer and in every adapter, so the same upsert
 * stores the same path metadata on the vault, library and server profiles —
 * the vault cannot represent anything else, because there the file's location
 * *is* the path. Two ids therefore never share a path, and a collection is
 * immutable for an id: rewriting an id under another collection fails with
 * [ConflictException] instead of moving it.
 */
data class Document(
    val id: DocId,
    val collection: Collection,
    val path: String,
    val content: String,
    val contentHash: String,
    val revision: RevisionId,
    val updatedAt: Instant,
    val frontmatter: Frontmatter = Frontmatter(),
    val deleted: Boolean = false
)

/** A single point in a document's history (normalized across backends). */
data class Revision(
    val docId: DocId,
    val revision: RevisionId,
    val author: String,
    val message: String,
    val contentHash: String,
    val createdAt: Instant,
    val deps: List<RevisionId> = emptyList(),
    val content: String? = null
)

/** Lightweight reference to a document, returned by listing operations. */
data class DocRef(
    val id: DocId,
    val collection: Collection,
    val path: String,
    val contentHash: String? = null,
    val revision: RevisionId? = null,
    val updatedAt: Instant? = null,
    /**
     * Opaque per-listing fingerprint that changes whenever the stored bytes
     * change. Adapters that cannot expose a cheap content hash (the vault, where
     * listing must not read every file) publish a size/partial-digest token
     * instead, so external edits are detected without depending on mtime alone.
     */
    val changeToken: String? = null
)

/** A directed relationship between two documents. */
data class Edge(
    val src: DocId,
    val dst: DocId,
    val rel: RelType,
    val props: Map<String, String> = emptyMap()
)

/** Content-addressed reference to an attachment. */
data class BlobRef(
    val hash: String,
    val size: Long,
    val algorithm: String = "sha256"
)
