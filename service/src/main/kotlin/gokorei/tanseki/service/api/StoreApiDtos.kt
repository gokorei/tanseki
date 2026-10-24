package gokorei.tanseki.service.api

import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.RelType
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

// @SerialName pins a stable, simple schema name for each DTO: the code-first
// OpenAPI spec (and the generated SDK model names) derive from it.

// ---- errors ---------------------------------------------------------------

@Serializable
@SerialName("ErrorBody")
data class ErrorBody(val code: String, val message: String, val detail: String? = null)

@Serializable
@SerialName("ErrorEnvelope")
data class ErrorEnvelope(val error: ErrorBody)

// ---- common ---------------------------------------------------------------

@Serializable
@SerialName("HealthDto")
data class HealthDto(val status: String)

@Serializable
@SerialName("DocRefDto")
data class DocRefDto(
    val id: String,
    val collection: String,
    val path: String,
    val contentHash: String? = null,
    val revision: String? = null
)

@Serializable
@SerialName("DocumentDto")
data class DocumentDto(
    val id: String,
    val collection: String,
    val path: String,
    val content: String,
    val contentHash: String,
    val revision: String,
    val updatedAt: String,
    val deleted: Boolean,
    val frontmatter: Map<String, JsonElement>
)

@Serializable
@SerialName("RevisionDto")
data class RevisionDto(
    val docId: String,
    val revision: String,
    val author: String,
    val message: String,
    val createdAt: String,
    val deps: List<String> = emptyList()
)

/** A half-open character range `[start, end)` into the snippet's `text`. */
@Serializable
@SerialName("HighlightDto")
data class HighlightDto(val start: Int, val end: Int)

/**
 * A ranked search result, renderable as a row without a follow-up fetch.
 *
 * `snippet` is raw Markdown windowed around the match, not rendered HTML —
 * rendering stays the client's decision. `highlights` are character offsets
 * into `snippet`, so a client can highlight without re-tokenizing.
 */
@Serializable
@SerialName("HitDto")
data class HitDto(
    val id: String,
    val score: Double,
    val snippet: String? = null,
    val path: String? = null,
    val title: String? = null,
    val highlights: List<HighlightDto> = emptyList()
)

@Serializable
@SerialName("DocumentListResponse")
data class DocumentListResponse(
    val documents: List<DocRefDto>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean,
    /**
     * Opaque token to pass as `?cursor=` to fetch the next page. Present only
     * when `hasMore`. Echo it back verbatim; do not construct one.
     */
    val nextCursor: String? = null
)

@Serializable
@SerialName("QueryDocumentsResponse")
data class QueryDocumentsResponse(val documents: List<DocumentDto>)

@Serializable
@SerialName("SearchResponse")
data class SearchResponse(
    val hits: List<HitDto>,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean
)

@Serializable
@SerialName("HistoryResponse")
data class HistoryResponse(
    val revisions: List<RevisionDto>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean
)

@Serializable
@SerialName("TraverseResponse")
data class TraverseResponse(val ids: List<String>)

@Serializable
@SerialName("BacklinksResponse")
data class BacklinksResponse(val ids: List<String>)

/**
 * The relationship vocabulary a traversal or backlink query may name.
 *
 * This is the whole set — the same four values [gokorei.tanseki.core.domain.RelTypes]
 * declares — and it is an enum rather than a string on purpose. A generated client
 * cannot send an invalid value at all, and a hand-written request carrying one
 * (notably a frontmatter *key* such as `files` or `repo`, which names where an
 * edge comes from rather than the relationship itself) fails deserialization and
 * is answered `400` instead of `200` with an empty list. An empty list means "no
 * such edges", never "no such relationship".
 */
@Serializable(with = TraverseRelSerializer::class)
@SerialName("TraverseRel")
enum class TraverseRel(val wire: String) {
    LinksTo("links-to"),

    References("references"),

    Embeds("embeds"),

    Mentions("mentions");

    internal companion object {
        fun parse(wire: String): TraverseRel? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Rejects an unknown relationship with the vocabulary named, rather than with
 * the decoder's unnamed complaint.
 *
 * kotlinx's own unknown-enum error says which *type* failed
 * ("TraverseRel does not contain element with name 'files'") but not which
 * values it accepts — so a caller passing a frontmatter key would learn their
 * value is wrong without learning what is right. The descriptor below keeps
 * the code-first schema an enum (see `docs/openapi.json`), so only the message
 * is custom, not the shape.
 */
@OptIn(InternalSerializationApi::class)
internal object TraverseRelSerializer : KSerializer<TraverseRel> {
    /**
     * The enum shape, spelling the four wire values.
     *
     * This is read for schema generation only — [deserialize] matches on
     * [TraverseRel.wire] directly — so it only has to report the kind and the
     * value spellings truthfully. The name is the `@SerialName` in full: a
     * custom serializer's descriptor replaces the generated one, which is what
     * would otherwise have carried the short name into the spec.
     */
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor("TraverseRel", SerialKind.ENUM) {
            TraverseRel.entries.forEach { element<String>(it.wire) }
        }

    override fun serialize(encoder: Encoder, value: TraverseRel) = encoder.encodeString(value.wire)

    override fun deserialize(decoder: Decoder): TraverseRel {
        val wire = decoder.decodeString()
        return TraverseRel.parse(wire)
            ?: throw InvalidInputException(
                "unknown relationship type '$wire' " +
                    "(use ${TraverseRel.entries.joinToString(", ") { it.wire }})"
            )
    }
}

/** The domain relationship a validated request names. */
internal fun TraverseRel.toRelType(): RelType = RelType(wire)

// ---- requests -------------------------------------------------------------

@Serializable
@SerialName("GetDocumentRequest")
data class GetDocumentRequest(val id: String, val collection: String? = null)

@Serializable
@SerialName("QueryDocumentsRequest")
data class QueryDocumentsRequest(val ids: List<String>, val collection: String? = null)

@Serializable
@SerialName("UpsertDocumentRequest")
data class UpsertDocumentRequest(
    val id: String,
    val content: String,
    val collection: String? = null,
    val path: String? = null,
    val frontmatter: Map<String, JsonElement>? = null,
    val author: String? = null,
    val message: String? = null,
    val contentHash: String? = null,
    val ifRevision: String? = null
)

@Serializable
@SerialName("DeleteDocumentRequest")
data class DeleteDocumentRequest(
    val id: String,
    val collection: String? = null,
    val message: String? = null,
    val author: String? = null,
    val ifRevision: String? = null
)

@Serializable
@SerialName("HistoryRequest")
data class HistoryRequest(
    val id: String,
    val collection: String? = null,
    val limit: Int? = null,
    val offset: Int? = null
)

@Serializable
@SerialName("TraverseRequest")
data class TraverseRequest(
    val id: String,
    val rel: TraverseRel? = null,
    val depth: Int? = null,
    val collection: String? = null
)

@Serializable
@SerialName("BacklinksRequest")
data class BacklinksRequest(
    val id: String,
    val rel: TraverseRel? = null,
    val collection: String? = null
)

@Serializable
@SerialName("ListDeletedResponse")
data class ListDeletedResponse(
    val documents: List<DocRefDto>,
    val total: Int
)

@Serializable
@SerialName("ListDeletedRequest")
data class ListDeletedRequest(
    val collection: String? = null,
    val limit: Int? = null
)

@Serializable
@SerialName("RestoreDocumentRequest")
data class RestoreDocumentRequest(
    val id: String,
    val collection: String? = null,
    val message: String? = null,
    val author: String? = null,
    val ifRevision: String? = null
)

@Serializable
@SerialName("RestoreResult")
data class RestoreResult(
    val id: String,
    val collection: String,
    val revision: String,
    val contentHash: String,
    val created: Boolean
)

@Serializable
@SerialName("RenameDocumentRequest")
data class RenameDocumentRequest(
    val id: String,
    val newId: String,
    val collection: String? = null,
    val message: String? = null,
    val author: String? = null,
    val ifRevision: String? = null
)

@Serializable
@SerialName("RenameResult")
data class RenameResult(
    val id: String,
    val previousId: String,
    val collection: String,
    val path: String,
    val revision: String,
    val contentHash: String
)

/**
 * A stored attachment, addressed by the digest of its own bytes.
 *
 * The reference is the identity: uploading the same bytes twice yields the same
 * reference, and the bytes can never be fetched under any other name.
 */
@Serializable
@SerialName("BlobRefDto")
data class BlobRefDto(
    val hash: String,
    val size: Long,
    val algorithm: String
)

/**
 * One committed change on `GET /v1/events`.
 *
 * [sequence] is the feed's own counter, not a revision: revisions are per
 * document, so they cannot order events across documents or tell a reconnecting
 * client what it missed. Echo the last one seen as `Last-Event-ID` to resume.
 */
@Serializable
@SerialName("ChangeEventDto")
data class ChangeEventDto(
    val sequence: Long,
    val kind: String,
    val documentId: String? = null,
    val collection: String? = null,
    val revision: String? = null,
    val contentHash: String? = null
)

@Serializable
@SerialName("UpsertResult")
data class UpsertResult(
    val id: String,
    val collection: String,
    val revision: String,
    val contentHash: String,
    val created: Boolean,
    /**
     * Set when the document's frontmatter block could not be read.
     *
     * The write succeeded and the block is preserved verbatim in `content`, but
     * the typed view behind it is empty — so the document will not index, filter,
     * or derive edges. A caller that only checks the status code would never know,
     * which is why the reason is in the response rather than only in a log.
     */
    val frontmatterError: String? = null
)

@Serializable
@SerialName("DeleteResult")
data class DeleteResult(val id: String, val collection: String, val revision: String)

internal val FRONTMATTER_KNOWN_KEYS = setOf("title", "author", "tags", "aliases", "updated_at", "content_hash")
