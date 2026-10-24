package gokorei.tanseki.service.mcp

import gokorei.tanseki.core.domain.McpLimits
import gokorei.tanseki.core.domain.RequestValidation
import java.nio.charset.StandardCharsets

/**
 * Per-field bounds for MCP tool arguments.
 *
 * One thin check per field over the shared [RequestValidation] rules, so the
 * tool seam rejects the same values the domain rejects, with the same words.
 */
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
