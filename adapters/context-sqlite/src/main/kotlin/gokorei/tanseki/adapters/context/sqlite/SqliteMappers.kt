package gokorei.tanseki.adapters.context.sqlite

import gokorei.tanseki.adapters.context.sqlite.db.Documents
import gokorei.tanseki.adapters.context.sqlite.db.Projection_operations
import gokorei.tanseki.core.domain.BlobSupport
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.EdgeProps
import gokorei.tanseki.core.domain.ProjectionKind
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.text.FrontmatterCodec
import kotlin.time.Instant

/** Document row -> domain. */
internal fun Documents.toDomain(): Document =
    Document(
        id = DocId(id),
        collection = Collection(collection),
        path = path,
        content = content,
        contentHash = content_hash,
        revision = RevisionId(revision),
        updatedAt = Instant.fromEpochMilliseconds(updated_at),
        // The typed column is the index; the raw column is the provenance. A
        // verbatim-degraded block (duplicate keys, unmodellable YAML) parses
        // to an empty view, so without the stored block the read side would
        // hand back `rawFrontmatter == null` and the next body edit would
        // re-render canonically and delete the user's block.
        frontmatter = FrontmatterCodec.parse(frontmatter).copy(rawFrontmatter = frontmatter_raw),
        deleted = deleted != 0L
    )

/** Outbox row -> domain. */
internal fun Projection_operations.toDomain(): ProjectionOperation =
    ProjectionOperation(
        id = id,
        documentId = DocId(document_id),
        revision = RevisionId(revision),
        contentHash = content_hash,
        kind = ProjectionKind.valueOf(kind),
        edgeVersion = edge_version,
        projectionVersion = projection_version,
        createdAt = Instant.fromEpochMilliseconds(created_at),
        attempts = attempts.toInt(),
        deadLettered = dead_lettered != 0L
    )

internal fun toDocRef(row: Documents): DocRef =
    DocRef(
        DocId(row.id),
        Collection(row.collection),
        row.path,
        row.content_hash,
        RevisionId(row.revision),
        Instant.fromEpochMilliseconds(row.updated_at)
    )

/**
 * Turns a path prefix into a LIKE pattern, escaping the metacharacters so a
 * folder literally named `100%` or `a_b` matches only itself.
 */
internal fun likePrefix(prefix: String): String =
    prefix
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_") + "%"

internal fun parseProps(props: String): Map<String, String> = EdgeProps.decode(props)

internal fun sha256(bytes: ByteArray): String = BlobSupport.sha256(bytes)
