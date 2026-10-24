package gokorei.tanseki.service.api

import gokorei.tanseki.core.domain.BooleanValue
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.FrontmatterValue
import gokorei.tanseki.core.domain.MappingValue
import gokorei.tanseki.core.domain.NumberValue
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.SequenceValue
import gokorei.tanseki.core.domain.TextValue
import gokorei.tanseki.core.ports.Hit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

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
