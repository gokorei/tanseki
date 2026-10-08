@file:Suppress("ThrowsCount")

package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.EdgeProps
import gokorei.tanseki.core.domain.ProjectionKind
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.VaultDirs
import gokorei.tanseki.core.ports.PijulPatch
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Instant

/**
 * The derived-state formats of [FileContextStore]: the sidecar record models,
 * their tab-delimited encodings, and the constants that version them.
 *
 * Split out of the store so the serialization layer is one small, shared place
 * and the store does not re-read its own schema while it works. Old on-disk
 * versions are still read: decoders accept every version the encoders have ever
 * written, so a vault keeps working across upgrades.
 */

internal data class IndexedLock(val lock: ReentrantLock, val order: Long)

internal data class PendingHistoryRecovery(
    val patch: PijulPatch,
    val action: PendingHistoryAction,
    val contentHash: String,
    val previousRevision: String
)

internal data class OperationRead(
    val operation: ProjectionOperation?,
    val corrupt: Boolean
)

internal data class Tombstone(
    val collection: String,
    val path: String,
    val revision: String,
    val contentHash: String,
    val content: String,
    val deletedAt: Long
)

internal data class PendingHistory(
    val action: PendingHistoryAction,
    val state: PendingHistoryState,
    val documentId: DocId,
    val path: String,
    val message: String,
    val author: String,
    val content: String,
    val contentHash: String,
    val previousContentHash: String?,
    val previousRevision: String,
    val collection: String,
    val createdAt: Instant,
    /**
     * Id the Pijul patch was recorded under. Null for a pending record
     * written before operation ids existed: recovery then matches the patch
     * by author, message and previous revision instead.
     */
    val operationId: String?,
    val recordedRevision: String?,
    val recordedAt: Long?,
    val previousContent: String = "",
    /**
     * Source id, for a rename. Null for every other action.
     *
     * The pending record is keyed and path-checked by [documentId], which for a
     * rename is the *target*: a record whose path had to match the source would
     * fail its own validation the moment the file moved, and keying it by the
     * source would leave the record describing an id that no longer exists.
     */
    val previousId: String? = null
)

internal enum class PendingHistoryAction {
    WRITE,
    DELETE,

    /**
     * A move. Recorded as one patch touching both the old and the new path,
     * because that is what a move is to a path-keyed DAG: the removal and the
     * addition belong together or the history claims the document briefly
     * existed twice.
     */
    RENAME
}

internal enum class PendingHistoryState {
    PREPARED,
    APPLIED,
    RECORDING,
    RECORDED
}

/**
 * Tool-owned directories that live inside a vault and are never documents.
 *
 * [VaultDirs.DERIVED] is Tanseki's own. The rest belong to the tools this
 * repo sits alongside: `.pijul` holds the VCS working copy, and
 * `.obsidian` and `.trash` belong to Obsidian itself. Walking any of them as
 * documents would surface deleted or generated content as if it were live.
 */
internal val BOOKKEEPING_DIRS = listOf(VaultDirs.DERIVED, ".pijul", ".obsidian", ".trash")
internal const val UNRECORDED = "worktree"
internal const val RELINK_AUTHOR = "tanseki-rename"
internal const val DEFAULT_COLLECTION = "vault"
internal const val METADATA_COMPONENT_LENGTH = 120
internal const val METADATA_V2_PREFIX = "v2_"
internal const val PENDING_HISTORY_VERSION = "4"
internal const val PENDING_HISTORY_NULL = "~"
internal const val TOMBSTONE_VERSION = "3"
internal const val CHANGE_TOKEN_WINDOW = 4 * 1024
internal const val UNKNOWN_SOURCE = "incoming"
internal val EDGE_ORDER = compareBy<Edge>({ it.rel.value }, { it.dst.value })

internal fun encodePendingHistory(pending: PendingHistory): String =
    listOf(
        PENDING_HISTORY_VERSION,
        pending.action.name,
        pending.state.name,
        encodePendingText(pending.documentId.value),
        encodePendingText(pending.path),
        encodePendingText(pending.message),
        encodePendingText(pending.author),
        encodePendingText(pending.content),
        encodePendingText(pending.contentHash),
        encodePendingNullableText(pending.previousContentHash),
        encodePendingText(pending.previousRevision),
        encodePendingText(pending.collection),
        pending.createdAt.toEpochMilliseconds().toString(),
        encodePendingNullableText(pending.operationId),
        encodePendingNullableText(pending.recordedRevision),
        pending.recordedAt?.toString() ?: PENDING_HISTORY_NULL,
        encodePendingText(pending.previousContent),
        encodePendingNullableText(pending.previousId)
    ).joinToString("\t")

internal fun decodePendingHistory(value: String): PendingHistory {
    val fields = value.split('\t')
    val version = fields.firstOrNull()
    require(version == "1" || version == "2" || version == "3" || version == PENDING_HISTORY_VERSION)
    require(
        fields.size ==
            if (version == "1") {
                15
            } else if (version == "2") {
                16
            } else if (version == "3") {
                17
            } else {
                18
            }
    )
    val action = PendingHistoryAction.valueOf(fields[1])
    val documentId = DocId(decodePendingText(fields[3]))
    val contentHash = decodePendingText(fields[8])
    val previousRevision = decodePendingText(fields[10])
    val recordedRevisionIndex = if (version == "1") 13 else 14
    val recordedAtIndex = if (version == "1") 14 else 15
    return PendingHistory(
        action = action,
        state = PendingHistoryState.valueOf(fields[2]),
        documentId = documentId,
        path = decodePendingText(fields[4]),
        message = decodePendingText(fields[5]),
        author = decodePendingText(fields[6]),
        content = decodePendingText(fields[7]),
        contentHash = contentHash,
        previousContentHash = decodePendingNullableText(fields[9]),
        previousRevision = previousRevision,
        collection = decodePendingText(fields[11]),
        createdAt = Instant.fromEpochMilliseconds(fields[12].toLong()),
        operationId = if (version == "1") null else decodePendingNullableText(fields[13]),
        recordedRevision = decodePendingNullableText(fields[recordedRevisionIndex]),
        recordedAt = decodePendingNullableText(fields[recordedAtIndex])?.toLong(),
        previousContent = if (fields.size > 16) decodePendingText(fields[16]) else "",
        // Only version 4 records a rename source. An older record predates
        // rename, so there is nothing to recover and nothing to guess.
        previousId = if (fields.size > 17) decodePendingNullableText(fields[17]) else null
    )
}

internal fun encodePendingText(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

internal fun decodePendingText(value: String): String =
    String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

internal fun encodePendingNullableText(value: String?): String =
    value?.let(::encodePendingText) ?: PENDING_HISTORY_NULL

internal fun decodePendingNullableText(value: String): String? =
    if (value == PENDING_HISTORY_NULL) null else decodePendingText(value)

internal fun encodeTombstone(tombstone: Tombstone): String =
    listOf(
        TOMBSTONE_VERSION,
        encodePendingText(tombstone.collection),
        encodePendingText(tombstone.path),
        encodePendingText(tombstone.revision),
        encodePendingText(tombstone.contentHash),
        encodePendingText(tombstone.content),
        tombstone.deletedAt.toString()
    ).joinToString("\t")

/**
 * The outbox record format. One tab-delimited line per operation, versioned by
 * field count (8..10) so records written before attempts/deadLettered existed
 * still decode.
 */
internal object OperationCodec {
    fun encode(operation: ProjectionOperation): ByteArray =
        listOf(
            operation.id,
            operation.documentId.value,
            operation.revision.value,
            operation.contentHash,
            operation.kind.name,
            operation.edgeVersion,
            operation.projectionVersion,
            operation.createdAt.toEpochMilliseconds().toString(),
            operation.attempts.toString(),
            operation.deadLettered.toString()
        ).joinToString("\t").toByteArray(StandardCharsets.UTF_8)

    fun decode(value: String): ProjectionOperation {
        val fields = value.trim().split('\t')
        require(fields.size in 8..10)
        return ProjectionOperation(
            id = fields[0],
            documentId = DocId(fields[1]),
            revision = RevisionId(fields[2]),
            contentHash = fields[3],
            kind = ProjectionKind.valueOf(fields[4]),
            edgeVersion = fields[5],
            projectionVersion = fields[6],
            createdAt = Instant.fromEpochMilliseconds(fields[7].toLong()),
            attempts = fields.getOrNull(8)?.toIntOrNull() ?: 0,
            deadLettered = fields.getOrNull(9)?.toBoolean() ?: false
        )
    }
}

internal fun encodeEdges(edges: List<Edge>): ByteArray =
    edges.joinToString(System.lineSeparator(), transform = ::encodeEdge).toByteArray(StandardCharsets.UTF_8)

internal fun encodeEdge(edge: Edge): String {
    val props = EdgeProps.encode(edge.props)
    return "${edge.rel.value}\t${edge.dst.value}\t$props"
}

internal fun decodeEdge(line: String, src: DocId): Edge? {
    val parts = line.split('\t')
    if (parts.size < 2) return null
    val props = EdgeProps.decode(parts.getOrNull(2).orEmpty())
    return Edge(src, DocId(parts[1]), RelType(parts[0]), props)
}
