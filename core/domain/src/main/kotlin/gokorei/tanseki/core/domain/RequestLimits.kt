package gokorei.tanseki.core.domain

object RequestLimits {
    const val MAX_REQUEST_BODY_BYTES = 1_048_576L
    const val MAX_CONTENT_BYTES = 1_048_576L
    const val MAX_DOCUMENT_ID_LENGTH = 512
    const val MAX_COLLECTION_LENGTH = 128
    const val MAX_PATH_LENGTH = 1_024
    const val MAX_QUERY_BYTES = 4_096L
    const val MAX_AUTHOR_LENGTH = 128
    const val MAX_MESSAGE_LENGTH = 512
    const val MAX_BATCH_IDS = 100
    const val MAX_FRONTMATTER_ENTRIES = 64
    const val MAX_FRONTMATTER_ARRAY_ITEMS = 64
    const val MAX_FRONTMATTER_DEPTH = 8
    const val MAX_FRONTMATTER_VALUE_BYTES = 4_096L
    const val MAX_FRONTMATTER_BYTES = 1_048_576L
    const val MAX_TAG_COUNT = 64
    const val MAX_COLLECTIONS_PER_REQUEST = 64
    const val MAX_PAGE_LIMIT = 500
    const val MAX_PAGE_OFFSET = 10_000
    const val MAX_TRAVERSAL_DEPTH = 10
    const val MAX_IDEMPOTENCY_KEY_LENGTH = 256

    /**
     * Attachment ceiling, deliberately separate from [MAX_CONTENT_BYTES].
     *
     * [MAX_CONTENT_BYTES] bounds a Markdown document, which is text a person
     * edits and reads; 1 MiB is generous for that and cheap to hold in memory.
     * An attachment is a photo or an export, so inheriting the document bound
     * would refuse ordinary images. Raising the document bound instead would
     * weaken a contract that exists for every other request, so the two limits
     * are kept apart and both are documented.
     */
    const val MAX_ATTACHMENT_BYTES = 33_554_432L
}

object McpLimits {
    const val MAX_REQUEST_BODY_BYTES = RequestLimits.MAX_REQUEST_BODY_BYTES

    /** Whole `tools/call` envelope: arguments plus `name`, `_meta`, and framing. */
    const val MAX_REQUEST_ENVELOPE_BYTES = 1_310_720L
    const val MAX_CONTENT_BYTES = RequestLimits.MAX_CONTENT_BYTES
    const val MAX_QUERY_BYTES = RequestLimits.MAX_QUERY_BYTES
    const val MAX_BATCH_IDS = RequestLimits.MAX_BATCH_IDS
    const val MAX_TAG_COUNT = RequestLimits.MAX_TAG_COUNT
    const val MAX_FRONTMATTER_ENTRIES = RequestLimits.MAX_FRONTMATTER_ENTRIES
    const val MAX_FRONTMATTER_ARRAY_ITEMS = RequestLimits.MAX_FRONTMATTER_ARRAY_ITEMS
    const val MAX_FRONTMATTER_DEPTH = RequestLimits.MAX_FRONTMATTER_DEPTH
    const val MAX_FRONTMATTER_VALUE_BYTES = RequestLimits.MAX_FRONTMATTER_VALUE_BYTES
    const val MAX_FRONTMATTER_BYTES = RequestLimits.MAX_FRONTMATTER_BYTES
    const val MAX_PAGE_LIMIT = RequestLimits.MAX_PAGE_LIMIT
    const val MAX_PAGE_OFFSET = RequestLimits.MAX_PAGE_OFFSET
    const val MAX_TRAVERSAL_DEPTH = RequestLimits.MAX_TRAVERSAL_DEPTH
    const val MAX_DOCUMENT_ID_LENGTH = RequestLimits.MAX_DOCUMENT_ID_LENGTH
    const val MAX_COLLECTION_LENGTH = RequestLimits.MAX_COLLECTION_LENGTH
    const val MAX_PATH_LENGTH = RequestLimits.MAX_PATH_LENGTH
    const val MAX_AUTHOR_LENGTH = RequestLimits.MAX_AUTHOR_LENGTH
    const val MAX_MESSAGE_LENGTH = RequestLimits.MAX_MESSAGE_LENGTH
}
