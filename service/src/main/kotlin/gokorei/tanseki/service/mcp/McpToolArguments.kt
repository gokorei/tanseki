package gokorei.tanseki.service.mcp

import gokorei.tanseki.core.domain.McpLimits
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets

/**
 * Argument coercion and validation for the MCP tools.
 *
 * Split out of [TansekiMcpServer] so the tool surface (descriptions, schemas, and
 * handlers) stays readable: everything here is a request-local concern
 * that runs before a handler touches the store, and it is the same code path
 * for all three tools.
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
