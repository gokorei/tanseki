package gokorei.tanseki.service.mcp

import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.McpLimits
import gokorei.tanseki.core.domain.RequestValidation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Primitive coercions for MCP tool arguments.
 *
 * Every function here reads one caller-supplied value and either returns it or
 * throws [McpToolError]: malformed arguments become tool-error values, never
 * exceptions that escape into the transport.
 */
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

/**
 * Runs the shared [RequestValidation] rules, translating their domain exception
 * into the MCP tool-error value the seam returns instead of a thrown exception.
 */
internal inline fun mcpRules(block: () -> Unit): Unit =
    try {
        block()
    } catch (error: InvalidInputException) {
        invalidMcp(error.message ?: "invalid argument")
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
