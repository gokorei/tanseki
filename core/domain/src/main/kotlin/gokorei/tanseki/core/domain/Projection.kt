package gokorei.tanseki.core.domain

import kotlin.time.Instant

enum class ProjectionKind {
    UPSERT,
    DELETE
}

data class ProjectionOperation(
    val id: String,
    val documentId: DocId,
    val revision: RevisionId,
    val contentHash: String,
    val kind: ProjectionKind,
    val edgeVersion: String,
    val projectionVersion: String,
    val createdAt: Instant,
    /** Failed delivery attempts recorded so far; drives dead-lettering. */
    val attempts: Int = 0,
    /**
     * True once the delivery budget was exhausted. A dead-lettered operation is
     * out of the delivery path: it is no longer retried, and it no longer counts
     * as stuck, but it stays in the outbox so an operator can see what the
     * projection could not deliver.
     */
    val deadLettered: Boolean = false
) {
    companion object {
        fun upsert(
            document: Document,
            revision: RevisionId,
            createdAt: Instant,
            contentHash: String = document.contentHash
        ): ProjectionOperation =
            create(
                documentId = document.id,
                revision = revision,
                contentHash = contentHash,
                kind = ProjectionKind.UPSERT,
                createdAt = createdAt
            )

        fun delete(
            documentId: DocId,
            revision: RevisionId,
            contentHash: String,
            createdAt: Instant
        ): ProjectionOperation =
            create(
                documentId = documentId,
                revision = revision,
                contentHash = contentHash,
                kind = ProjectionKind.DELETE,
                createdAt = createdAt
            )

        private fun create(
            documentId: DocId,
            revision: RevisionId,
            contentHash: String,
            kind: ProjectionKind,
            createdAt: Instant
        ): ProjectionOperation =
            ProjectionOperation(
                id = "${documentId.value}|${kind.name}|$contentHash",
                documentId = documentId,
                revision = revision,
                contentHash = contentHash,
                kind = kind,
                edgeVersion = revision.value,
                projectionVersion = revision.value,
                createdAt = createdAt
            )
    }
}
