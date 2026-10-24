package gokorei.tanseki.service.mcp

import gokorei.tanseki.core.domain.FrontmatterFilterValue
import gokorei.tanseki.core.domain.McpLimits
import gokorei.tanseki.core.domain.explain
import gokorei.tanseki.core.domain.resolveFrontmatterFilter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets

/**
 * Frontmatter being **stored**.
 *
 * Nested objects and arrays are carried, not refused. The value model keeps
 * structure, so a nested map round-trips and every leaf stays filterable, and the
 * `/v1` seam already accepts exactly this. Refusing it here made the two seams
 * disagree about the same document.
 */
internal fun validateStorableFrontmatter(values: Map<String, JsonElement>?) {
    validateFrontmatter(values, nested = true)
}

/**
 * Frontmatter being used as a **search filter**.
 *
 * Scalars only, and that is a different rule for a different reason. A filter is
 * flattened by `toFlatStrings` and compared against the encoded form the index
 * stored, so a nested value would arrive as the string `{"nested":true}` and match
 * nothing — accepted, and quietly wrong. Refusing it is the only honest answer,
 * and it is why this is not the same check as [validateStorableFrontmatter].
 *
 * Each scalar is also resolved through [resolveFrontmatterFilter] with its key, so this seam
 * rejects the same unresolvable value *and* the same unaddressable key `GET /v1/search` rejects,
 * with the same words.
 * The rules themselves are not restated here: they are the domain's, next to the
 * value types, because a value that resolves to a number on one seam and to text on
 * the other is exactly the disagreement [McpHttpSearchParityTest] exists to catch.
 */
internal fun validateFrontmatterFilter(values: Map<String, JsonElement>?) {
    validateFrontmatter(values, nested = false)
    values?.forEach { (key, value) ->
        val text = (value as? JsonPrimitive)?.content ?: return@forEach
        when (val resolved = resolveFrontmatterFilter(key, text)) {
            is FrontmatterFilterValue.Invalid -> invalidMcp(resolved.explain("$key=$text"))
            is FrontmatterFilterValue.Resolved -> Unit
        }
    }
}

private fun validateFrontmatter(values: Map<String, JsonElement>?, nested: Boolean) {
    if (values == null) return
    if (values.size > McpLimits.MAX_FRONTMATTER_ENTRIES) invalidMcp("too many frontmatter entries")
    if (JsonObject(values)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
            .size
            .toLong() >
        McpLimits.MAX_FRONTMATTER_BYTES
    ) {
        invalidMcp("frontmatter is too large")
    }
    values.forEach { (key, value) ->
        if (key.isBlank()) invalidMcp("frontmatter key must not be blank")
        if (key.toByteArray(StandardCharsets.UTF_8).size > McpLimits.MAX_COLLECTION_LENGTH) {
            invalidMcp("frontmatter key is too long")
        }
        validateFrontmatterValue(value, key, nested)
    }
}

/**
 * One frontmatter value.
 *
 * With [nested] allowed, structure is carried rather than refused and only the
 * bounds apply. Without it, a collection is refused by name — see
 * [validateFrontmatterFilter] for why a filter cannot be structured.
 *
 * Nothing here asserts which key may take which shape. That rule is enforced once,
 * in `toFrontmatter`, which both seams call; two validators asserting it is how the
 * two seams came to disagree in the first place.
 */
private fun validateFrontmatterValue(value: JsonElement, key: String, nested: Boolean) {
    when (value) {
        is JsonObject -> {
            if (!nested) invalidMcp("frontmatter filter '$key' must be a scalar, not an object")
            value.forEach { (nestedKey, nested_) ->
                if (nestedKey.isBlank()) invalidMcp("frontmatter key must not be blank")
                validateFrontmatterValue(nested_, nestedKey, nested)
            }
        }

        is JsonArray -> {
            if (!nested) invalidMcp("frontmatter filter '$key' must be a scalar, not an array")
            value.forEach { element -> validateFrontmatterValue(element, key, nested) }
        }

        is JsonPrimitive -> {
            validateJsonElement(value)
        }
    }
}

internal fun validateJsonElement(value: JsonElement, depth: Int = 0) {
    if (depth > McpLimits.MAX_FRONTMATTER_DEPTH) invalidMcp("frontmatter is too deeply nested")
    when (value) {
        is JsonArray -> {
            if (value.size > McpLimits.MAX_FRONTMATTER_ARRAY_ITEMS) invalidMcp("frontmatter array is too large")
            value.forEach { validateJsonElement(it, depth + 1) }
        }

        is JsonObject -> {
            if (value.size > McpLimits.MAX_FRONTMATTER_ENTRIES) invalidMcp("frontmatter object is too large")
            value.forEach { (key, element) ->
                if (key.isBlank()) invalidMcp("request field name must not be blank")
                if (key.toByteArray(StandardCharsets.UTF_8).size > McpLimits.MAX_COLLECTION_LENGTH) {
                    invalidMcp("frontmatter key is too long")
                }
                validateJsonElement(element, depth + 1)
            }
        }

        is JsonPrimitive -> {
            validateFrontmatterPrimitive(value)
        }
    }
}

private fun validateFrontmatterPrimitive(value: JsonPrimitive) {
    if (value is JsonNull) return
    if (value.content.any(Char::isISOControl)) {
        invalidMcp("frontmatter value contains control characters")
    }
    if (value.content
            .toByteArray(StandardCharsets.UTF_8)
            .size
            .toLong() > McpLimits.MAX_FRONTMATTER_VALUE_BYTES
    ) {
        invalidMcp("frontmatter value is too long")
    }
}
