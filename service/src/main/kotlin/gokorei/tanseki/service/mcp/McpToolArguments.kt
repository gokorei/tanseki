package gokorei.tanseki.service.mcp

import gokorei.tanseki.core.domain.FrontmatterFilterValue
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.McpLimits
import gokorei.tanseki.core.domain.RequestValidation
import gokorei.tanseki.core.domain.explain
import gokorei.tanseki.core.domain.resolveFrontmatterFilter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets

/**
 * Argument coercion and validation for the MCP tools.
 *
 * Split out of [TansekiMcpServer] so the tool surface (descriptions, schemas, and
 * handlers) stays readable: everything here is a pure, request-local concern
 * that runs before a handler touches the store, and it is the same code path
 * for all three tools. The rules are deliberately total — every malformed
 * argument becomes a [McpToolError] value the tool returns, never a thrown
 * exception that escapes into the transport.
 *
 * `TOOL_FIELDS` is the closed set of arguments each tool accepts; anything else
 * is rejected as a typo before the handler runs.
 */

private val TOOL_FIELDS =
    mapOf(
        "search" to setOf("query", "mode", "collection", "tags", "frontmatter", "limit", "offset"),
        "document" to
            setOf(
                "action",
                "id",
                "content",
                "frontmatter",
                "collection",
                "path",
                "message",
                "author",
                "ifRevision",
                "limit",
                "offset"
            ),
        "traverse" to setOf("from", "rel", "depth")
    )

// ---- coercion ---------------------------------------------------------------

internal fun Map<String, Any>.str(key: String): String? = this[key] as? String

internal fun Map<String, Any>.optionalString(key: String): String? =
    if (!containsKey(key) || this[key] == null) null else str(key) ?: invalidMcp("`$key` must be a string")

internal fun Map<String, Any>.boundedInt(
    key: String,
    default: Int,
    minimum: Int = 1,
    maximum: Int = McpLimits.MAX_PAGE_LIMIT
): Int {
    if (!containsKey(key) || this[key] == null) return default
    val number = this[key] as? Number ?: invalidMcp("`$key` must be an integer")
    val value = number.toDouble()
    val result = value.toInt()
    if (value != result.toDouble() || result !in minimum..maximum) {
        invalidMcp("`$key` must be between $minimum and $maximum")
    }
    return result
}

internal fun Map<String, Any>.optionalStringSet(key: String): Set<String> {
    if (!containsKey(key) || this[key] == null) return emptySet()
    val values = this[key] as? List<*> ?: invalidMcp("`$key` must be an array of strings")
    return values
        .map { value ->
            value as? String ?: invalidMcp("`$key` must be an array of strings")
        }.toSet()
}

internal fun Map<String, Any>.optionalJsonMap(key: String): Map<String, JsonElement>? {
    if (!containsKey(key) || this[key] == null) return null
    val values = this[key] as? Map<*, *> ?: invalidMcp("`$key` must be an object")
    return values.entries.associate { (entryKey, value) -> entryKey.toString() to value.toJsonElement() }
}

// ---- envelope and per-field bounds ------------------------------------------

internal fun validateToolArguments(name: String, args: Map<String, Any>) {
    val allowed = TOOL_FIELDS.getValue(name)
    val unknown = args.keys - allowed
    if (unknown.isNotEmpty()) invalidMcp("unsupported argument '${unknown.first()}'")
    val body =
        buildJsonObject {
            args.forEach { (key, value) -> put(key, value.toJsonElement()) }
        }
    if (body
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
            .size
            .toLong() > McpLimits.MAX_REQUEST_BODY_BYTES
    ) {
        throw McpToolError(
            "request_too_large",
            "MCP request body must be at most ${McpLimits.MAX_REQUEST_BODY_BYTES} bytes"
        )
    }
    validateJsonElement(body)
}

internal fun validateQuery(value: String) = mcpRules { RequestValidation.validateQuery(value) }

internal fun validateDocumentId(value: String) = mcpRules { RequestValidation.validateDocumentId(value) }

internal fun validateCollection(value: String) = mcpRules { RequestValidation.validateCollection(value) }

internal fun validateTags(values: Set<String>) = mcpRules { RequestValidation.validateTags(values) }

internal fun validateContent(value: String) = mcpRules { RequestValidation.validateContent(value) }

internal fun validatePath(value: String) = mcpRules { RequestValidation.validatePath(value) }

internal fun validateMessage(value: String) = mcpRules { RequestValidation.validateMessage(value) }

internal fun validateAuthor(value: String) = mcpRules { RequestValidation.validateAuthor(value) }

internal fun validateRevision(value: String) = mcpRules { RequestValidation.validateRevision(value) }

internal fun validateRelation(value: String) {
    if (value.any(Char::isISOControl)) invalidMcp("relationship type contains control characters")
    if (value.toByteArray(StandardCharsets.UTF_8).size > McpLimits.MAX_COLLECTION_LENGTH) {
        invalidMcp("relationship type is too long")
    }
}

/**
 * Runs the shared [RequestValidation] rules, translating their domain exception
 * into the MCP tool-error value the seam returns instead of a thrown exception.
 */
private inline fun mcpRules(block: () -> Unit): Unit =
    try {
        block()
    } catch (error: InvalidInputException) {
        invalidMcp(error.message ?: "invalid argument")
    }

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

private fun validateJsonElement(value: JsonElement, depth: Int = 0) {
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

internal fun invalidMcp(message: String): Nothing = throw McpToolError("invalid_argument", message)

/** Client-supplied values are JSON-shaped, so bound them before they are echoed. */
internal fun Any?.toJsonElement(depth: Int = 0): JsonElement {
    if (depth > McpLimits.MAX_FRONTMATTER_DEPTH) invalidMcp("request body is too deeply nested")
    return when (this) {
        null -> {
            JsonNull
        }

        is Boolean -> {
            JsonPrimitive(this)
        }

        is Number -> {
            JsonPrimitive(this)
        }

        is String -> {
            JsonPrimitive(this)
        }

        is List<*> -> {
            val items = this
            buildJsonArray { items.forEach { add(it.toJsonElement(depth + 1)) } }
        }

        is Map<*, *> -> {
            val entries = this
            buildJsonObject { entries.forEach { (k, v) -> put(k.toString(), v.toJsonElement(depth + 1)) } }
        }

        else -> {
            JsonPrimitive(toString())
        }
    }
}
