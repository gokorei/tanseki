package gokorei.tanseki.service.api

import gokorei.tanseki.core.domain.BooleanValue
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.FrontmatterValue
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.MappingValue
import gokorei.tanseki.core.domain.NumberValue
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.SequenceValue
import gokorei.tanseki.core.domain.TextValue
import gokorei.tanseki.core.ports.Hit
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlin.time.Instant

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

// ---- mappings -------------------------------------------------------------

internal val FRONTMATTER_KNOWN_KEYS = setOf("title", "author", "tags", "aliases", "updated_at", "content_hash")

internal fun Frontmatter.toJson(): Map<String, JsonElement> {
    val map = linkedMapOf<String, JsonElement>()
    title?.let { map["title"] = JsonPrimitive(it) }
    author?.let { map["author"] = JsonPrimitive(it) }
    if (tags.isNotEmpty()) map["tags"] = buildJsonArray { tags.forEach { add(it) } }
    if (aliases.isNotEmpty()) map["aliases"] = buildJsonArray { aliases.forEach { add(it) } }
    updatedAt?.let { map["updated_at"] = JsonPrimitive(it.toString()) }
    contentHash?.let { map["content_hash"] = JsonPrimitive(it) }
    for ((key, value) in values) map[key] = value.toJson()
    return map
}

/**
 * Renders a frontmatter value back as the JSON it was written as.
 *
 * A string stays a JSON string, a number a JSON number, and a collection a JSON
 * array or object. Flattening these to text is what used to make a round trip
 * lossy: a caller that sent `"007"` read back a bare `007`, which the next
 * reader resolves as a number.
 */
internal fun FrontmatterValue.toJson(): JsonElement =
    when (this) {
        is TextValue -> JsonPrimitive(value)
        is NumberValue -> numberJson(this)
        is BooleanValue -> JsonPrimitive(value)
        is SequenceValue -> JsonArray(items.map { it.toJson() })
        is MappingValue -> JsonObject(entries.mapValues { (_, value) -> value.toJson() })
    }

/**
 * JSON for a number, keeping `.inf`/`.nan` out of the number position.
 *
 * Those spellings parse as doubles but are not RFC-8259 JSON numbers, so emitting
 * them unquoted makes the whole response invalid JSON — a strict consumer,
 * including this project's own MCP proxy, then cannot read the daemon's reply at
 * all. They leave as strings; a finite number stays an unquoted JSON number.
 */
private fun numberJson(number: NumberValue): JsonElement {
    val parsed = number.value.toDoubleOrNull()
    return if (parsed != null && !parsed.isFinite()) {
        JsonPrimitive(number.value)
    } else {
        JsonUnquotedLiteral(number.value)
    }
}

/**
 * Reads the contract keys out of a caller-supplied frontmatter map.
 *
 * A contract key has a typed field on [Frontmatter], so a value of the wrong
 * shape cannot be carried. Two things follow, and this used to do neither:
 *
 *  - A JSON scalar *is* carried, as its text. `title: 123` becomes `"123"`, which
 *    is the number the caller sent rather than a guess about what they meant.
 *  - A shape that cannot be carried is **refused with a reason**, not dropped. The
 *    earlier version read each key with `takeIf { it.isString }`, so `title: 123`,
 *    `author: 7` and `tags: "a,b,c"` all became `null`/empty and vanished: the
 *    write returned `200`, the document was rendered with no frontmatter block at
 *    all, and nothing said so. A key silently removed is worse than a key refused,
 *    because the caller has no way to learn it happened.
 *
 * `tags` is refused rather than coerced for a bare string specifically. Splitting
 * `"a,b,c"` on the comma would be a guess, and a guess that is wrong for any tag
 * containing a comma — the same ambiguity [EdgeDeriver] used to have and no longer
 * does. The array form carries the meaning exactly.
 */
internal fun Map<String, JsonElement>.toFrontmatter(): Frontmatter {
    val values =
        entries
            .filterNot { it.key in FRONTMATTER_KNOWN_KEYS }
            .mapNotNull { (key, value) -> value.toFrontmatterValue()?.let { key to it } }
            .toMap()
    return Frontmatter(
        title = contractScalar("title"),
        author = contractScalar("author"),
        tags = contractTags(),
        aliases = contractStringList("aliases"),
        updatedAt = contractInstant("updated_at"),
        contentHash = contractScalar("content_hash"),
        values = values
    )
}

/** A contract key that is modelled as text, carried whatever scalar JSON type it arrived as. */
private fun Map<String, JsonElement>.contractScalar(key: String): String? =
    when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.content
        is JsonArray, is JsonObject -> refuseShape(key, value)
    }

/**
 * `tags`, which is a list rather than a scalar.
 *
 * A bare string is refused rather than split on a comma: the split would be a
 * guess, and it is wrong for any tag that contains one. The array form carries
 * the meaning exactly.
 */
private fun Map<String, JsonElement>.contractTags(): List<String> = contractStringList("tags")

/**
 * A contract key modelled as a string list (`tags`, `aliases`).
 *
 * A bare string is refused rather than split on a comma: the split would be a
 * guess, and it is wrong for any entry that contains one. The array form carries
 * the meaning exactly.
 */
private fun Map<String, JsonElement>.contractStringList(key: String): List<String> =
    when (val value = this[key]) {
        null, JsonNull -> emptyList()
        is JsonArray -> value.map { element -> element.requireListString(key) }
        is JsonPrimitive, is JsonObject -> refuseShape(key, value, "an array of strings")
    }

private fun JsonElement.requireListString(key: String): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw InvalidInputException("frontmatter key '$key' must contain strings only")

/**
 * An instant that will not parse is a caller's mistake worth naming; nulling it
 * would leave the document with no timestamp and no error.
 */
private fun Map<String, JsonElement>.contractInstant(key: String): Instant? =
    contractScalar(key)?.let { text ->
        runCatching { Instant.parse(text) }.getOrElse {
            throw InvalidInputException("frontmatter key '$key' is not an instant: $text")
        }
    }

private fun refuseShape(key: String, value: JsonElement, expected: String = "a scalar"): Nothing =
    throw InvalidInputException("frontmatter key '$key' must be $expected, not ${value.jsonTypeName()}")

private fun JsonElement.jsonTypeName(): String =
    when (this) {
        is JsonArray -> "an array"
        is JsonObject -> "an object"
        else -> "a scalar"
    }

/**
 * Maps a JSON value onto the typed model, keeping the distinction the caller drew.
 *
 * A JSON string stays text even when it reads as a number, which is the whole
 * point: `"42"` and `42` are different documents and the store must not pick
 * one on the caller's behalf.
 *
 * A JSON null maps to `null` rather than to a value: it is the absence of one, so the key is
 * left out and reads as absent. That matches the YAML parser and the contract keys, which
 * already treat null as absent (`contractScalar`, `contractTags`). A null inside an array or
 * object is dropped the same way.
 */
private fun JsonElement.toFrontmatterValue(): FrontmatterValue? =
    when (this) {
        JsonNull -> {
            null
        }

        is JsonPrimitive -> {
            if (isString) {
                TextValue(content)
            } else if (content == "true" || content == "false") {
                BooleanValue(content.toBoolean())
            } else {
                NumberValue(content)
            }
        }

        is JsonArray -> {
            SequenceValue(mapNotNull { it.toFrontmatterValue() })
        }

        is JsonObject -> {
            MappingValue(
                entries.mapNotNull { (key, value) -> value.toFrontmatterValue()?.let { key to it } }.toMap()
            )
        }
    }

internal fun Map<String, JsonElement>.toFlatStrings(): Map<String, String> =
    entries.associate { (key, value) -> key to ((value as? JsonPrimitive)?.content ?: value.toString()) }

internal fun DocRef.toDto() = DocRefDto(id.value, collection.value, path, contentHash, revision?.value)

internal fun Document.toDto() =
    DocumentDto(
        id = id.value,
        collection = collection.value,
        path = path,
        content = content,
        contentHash = contentHash,
        revision = revision.value,
        updatedAt = updatedAt.toString(),
        deleted = deleted,
        frontmatter = frontmatter.toJson()
    )

internal fun Revision.toDto() =
    RevisionDto(
        docId = docId.value,
        revision = revision.value,
        author = author,
        message = message,
        createdAt = createdAt.toString(),
        deps = deps.map { it.value }
    )

internal fun Hit.toDto() =
    HitDto(
        id = id.value,
        score = score,
        snippet = snippet,
        path = path,
        title = title,
        highlights = highlights.map { HighlightDto(it.first, it.last + 1) }
    )
