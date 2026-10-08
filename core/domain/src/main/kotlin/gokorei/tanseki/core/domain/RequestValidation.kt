package gokorei.tanseki.core.domain

import java.nio.charset.StandardCharsets

/**
 * Canonical bounds and wording for literal request fields.
 *
 * Both seams — the `/v1` HTTP API and the MCP tools — validate the same document
 * id, query, collection, tag, content, path, author, revision and message, so the
 * rules live here once and both call this. They used to be hand-copied, and the
 * copies drifted (different wordings, a different revision bound). A boundary
 * input must get the same accept/reject decision on either seam.
 *
 * Every validator throws [InvalidInputException]; the seams map it onto their own
 * error envelope (a JSON 400 for `/v1`, an `McpToolError` value for an MCP tool),
 * so the rule is shared without importing one seam's plumbing into the other.
 */
object RequestValidation {
    fun validateQuery(value: String) {
        if (value.toByteArray(StandardCharsets.UTF_8).size.toLong() > RequestLimits.MAX_QUERY_BYTES) {
            invalid("search query is too long")
        }
    }

    fun validateDocumentId(value: String) {
        if (value.isBlank()) invalid("document id must not be blank")
        if (value.toByteArray(StandardCharsets.UTF_8).size > RequestLimits.MAX_DOCUMENT_ID_LENGTH) {
            invalid("document id is too long")
        }
        if (value.any(Char::isISOControl)) invalid("document id contains control characters")
    }

    fun validateCollection(value: String?) {
        if (value == null) return
        if (value.isBlank()) invalid("collection must not be blank")
        if (value.toByteArray(StandardCharsets.UTF_8).size > RequestLimits.MAX_COLLECTION_LENGTH) {
            invalid("collection is too long")
        }
        if (value.any(Char::isISOControl)) invalid("collection contains control characters")
    }

    fun validateTags(values: Set<String>) {
        if (values.size > RequestLimits.MAX_TAG_COUNT) invalid("too many tags")
        values.forEach { value ->
            if (value.isBlank()) invalid("tag must not be blank")
            if (value.toByteArray(StandardCharsets.UTF_8).size > RequestLimits.MAX_COLLECTION_LENGTH) {
                invalid("tag is too long")
            }
        }
    }

    fun validateContent(value: String) {
        if (value.toByteArray(StandardCharsets.UTF_8).size.toLong() > RequestLimits.MAX_CONTENT_BYTES) {
            invalid("document content is too long")
        }
    }

    fun validatePath(value: String?) {
        if (value == null) return
        if (value.isBlank() || value.any(Char::isISOControl)) {
            invalid("path must not be blank or contain control characters")
        }
        if (value.toByteArray(StandardCharsets.UTF_8).size > RequestLimits.MAX_PATH_LENGTH) {
            invalid("document path is too long")
        }
    }

    fun validateAuthor(value: String?) {
        if (value == null) return
        if (value.isBlank() || value.any(Char::isISOControl)) {
            invalid("author must not be blank or contain control characters")
        }
        if (value.toByteArray(StandardCharsets.UTF_8).size > RequestLimits.MAX_AUTHOR_LENGTH) {
            invalid("author is too long")
        }
    }

    fun validateRevision(value: String?) {
        if (value == null) return
        if (value.isBlank() || value.any(Char::isISOControl)) {
            invalid("revision must not be blank or contain control characters")
        }
        if (value.toByteArray(StandardCharsets.UTF_8).size > RequestLimits.MAX_DOCUMENT_ID_LENGTH) {
            invalid("revision is too long")
        }
    }

    fun validateMessage(value: String?) {
        if (value == null) return
        if (value.isBlank()) invalid("message must not be blank")
        if (value.toByteArray(StandardCharsets.UTF_8).size > RequestLimits.MAX_MESSAGE_LENGTH) {
            invalid("message is too long")
        }
        if (value.any(Char::isISOControl)) invalid("message contains control characters")
    }

    private fun invalid(message: String): Nothing = throw InvalidInputException(message)
}
