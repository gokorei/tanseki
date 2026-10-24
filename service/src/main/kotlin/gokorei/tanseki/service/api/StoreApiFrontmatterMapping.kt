package gokorei.tanseki.service.api

import gokorei.tanseki.core.domain.BooleanValue
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.FrontmatterValue
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.MappingValue
import gokorei.tanseki.core.domain.NumberValue
import gokorei.tanseki.core.domain.SequenceValue
import gokorei.tanseki.core.domain.TextValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

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
