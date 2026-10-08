package gokorei.tanseki.service.mcp

import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.McpLimits
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.SearchMode
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.service.TansekiVersion
import gokorei.tanseki.service.api.callerReason
import gokorei.tanseki.service.api.toFlatStrings
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.function.BiFunction

/**
 * MCP seam over the official Model Context Protocol Java SDK, designed
 * LLM-ergonomics-first and encoding responses as TOON (see [Toon], which records
 * why):
 *
 * - **3 tools**, consolidated by purpose: `search`, `document`, `traverse`.
 * - closed sets (`search.mode`, `document.action`, `traverse.rel`) are schema
 *   `enum`s; ids and collections stay open `str`.
 * - side effects are stated in prose and in conservative `ToolAnnotations`.
 * - errors are returned as values (TOON `error:` + `details:`) with a `code`,
 *   `hint`, and `validOptions`.
 *
 * The store is behind the [McpStore] port, so this server runs over stdio in a
 * dedicated process (embedded or against the daemon's HTTP API).
 */
class TansekiMcpServer(
    private val store: McpStore,
    private val logger: TansekiLogger = TansekiLogger.Noop
) {
    fun build(input: InputStream = System.`in`, output: OutputStream = System.out): McpSyncServer {
        val transport =
            StdioServerTransportProvider(
                JacksonMcpJsonMapperSupplier().get(),
                input,
                output,
                MAX_TRANSPORT_FRAME_CHARS
            )
        return McpServer
            .sync(transport)
            .serverInfo("tanseki", TansekiVersion.value)
            .instructions(
                "Tanseki knowledge store. Use `search` to discover documents, `document` to read/write/delete one, " +
                    "and `traverse` to walk relationships. Document ids are path-derived and contain '/', " +
                    "e.g. org/repo/pr-42/e1. Responses use TOON (Token-Oriented Object Notation), a compact " +
                    "YAML-like format: `key: value`, nested by indentation, arrays as `key[N]:` / `key[N]{f1,f2}:`."
            ).tools(tools())
            .build()
    }

    fun tools(): List<SyncToolSpecification> =
        listOf(
            searchTool(),
            documentTool(),
            traverseTool()
        )

    // ---- search -----------------------------------------------------------

    private fun searchTool(): SyncToolSpecification =
        tool(
            name = "search",
            description =
                "READ-ONLY. Search over the derived lookup index. Use this first to discover document " +
                    "ids by content, then call `document` with action=get. Returns a TOON list " +
                    "`hits[N]{id,score,snippet}` ordered best-first (empty when nothing matches). Narrow results " +
                    "with `collection`, `tags` (all listed tags must match), and `frontmatter` equality filters " +
                    "(e.g. {\"repo\":\"org/repo\",\"jira\":\"ABC-1\"}). Prefer a few distinctive keywords over " +
                    "long sentences. `mode=hybrid` fuses lexical and vector ranking for better recall on " +
                    "paraphrases, and silently falls back to lexical where no embedder is configured. " +
                    "`limit` defaults to 20.",
            properties =
                mapOf(
                    "query" to stringProp("Keywords to match.", McpLimits.MAX_QUERY_BYTES.toInt()),
                    "mode" to
                        enumProp(
                            SearchMode.WIRES,
                            "Ranking: `lexical` (default) or `hybrid` to add vector kNN via rank fusion."
                        ),
                    "collection" to
                        stringProp(
                            "Namespace to search, e.g. `notes`. Omit to search all.",
                            McpLimits.MAX_COLLECTION_LENGTH
                        ),
                    "tags" to
                        arrayProp(
                            "Structural tags; a document must have every listed tag.",
                            McpLimits.MAX_TAG_COUNT,
                            McpLimits.MAX_COLLECTION_LENGTH
                        ),
                    "frontmatter" to
                        objectProp(
                            "Frontmatter equality filters, e.g. {\"repo\":\"org/repo\"}. Values are " +
                                "resolved by ordinary scalar rules: {\"pr\":42} and {\"flag\":true} " +
                                "select the number and the boolean, and a string that looks like " +
                                "one must be quoted in the value, e.g. {\"pr\":\"\\\"42\\\"\"}, or it " +
                                "selects the number instead.",
                            McpLimits.MAX_FRONTMATTER_ENTRIES
                        ),
                    "limit" to intProp("Maximum hits to return (1..500, default 20).", 1, McpLimits.MAX_PAGE_LIMIT),
                    "offset" to intProp("Zero-based result offset (0..10000, default 0).", 0, McpLimits.MAX_PAGE_OFFSET)
                ),
            required = listOf("query"),
            annotations = readOnly("Search")
        ) { args ->
            validateToolArguments("search", args)
            val query =
                args.str("query")
                    ?: throw McpToolError(
                        "invalid_argument",
                        "`query` is required",
                        hint = "pass a few distinctive keywords"
                    )
            validateQuery(query)
            val mode = searchMode(args)
            val limit = args.boundedInt("limit", 20)
            val offset = args.boundedInt("offset", 0, 0, McpLimits.MAX_PAGE_OFFSET)
            val collection = args.optionalString("collection")?.also(::validateCollection)?.let(::setOf) ?: emptySet()
            val tags = args.optionalStringSet("tags").also(::validateTags)
            val frontmatterMap = args.optionalJsonMap("frontmatter").also(::validateFrontmatterFilter)
            val frontmatter = frontmatterMap?.toFlatStrings() ?: emptyMap()
            Toon.encode(
                buildJsonObject { put("hits", store.search(query, mode, collection, tags, frontmatter, limit, offset)) }
            )
        }

    /**
     * The requested [SearchMode], or [SearchMode.DEFAULT] when the caller named
     * none.
     *
     * Absent is not the same as unrecognised: the default keeps every existing
     * caller on the ranking it has always had, while a misspelled mode names
     * itself rather than quietly degrading to lexical — which is the failure this
     * parameter exists to stop, and one the response would otherwise hide.
     */
    private fun searchMode(args: Map<String, Any>): SearchMode {
        val input = args.optionalString("mode") ?: return SearchMode.DEFAULT
        return SearchMode.parse(input.trim().lowercase()) ?: throw McpToolError(
            "invalid_argument",
            "unknown search mode '$input'",
            hint = "omit `mode` for lexical, or ask for hybrid",
            validOptions = SearchMode.WIRES
        )
    }

    // ---- document (get | upsert | delete | history) -----------------------

    private fun documentTool(): SyncToolSpecification =
        tool(
            name = "document",
            description =
                "MUTATING (upsert/delete) and READ-ONLY (get/history). Manage a single document by id. " +
                    "`action` selects the behaviour:\n" +
                    "- `get`: fetch one document, including its `frontmatter`; use a search hit's id.\n" +
                    "- `upsert`: create or update. `content` is required (Markdown body). Optional `frontmatter` " +
                    "(flat map) is authoritative and the stored Markdown is normalized from it; optional " +
                    "`collection`, `path`, `message`, `author`, `ifRevision`. Re-sending identical content is a " +
                    "no-op. Returns {id, revision, contentHash, created}.\n" +
                    "- `delete`: remove the document; `message`/`author` are optional provenance and " +
                    "`ifRevision` is an optional optimistic-concurrency guard.\n" +
                    "- `history`: revision list, oldest-first, as {revisions:[...]}.\n" +
                    "Ids are path-derived and contain '/', e.g. org/repo/pr-42/e1.",
            properties =
                mapOf(
                    "action" to enumProp(listOf("get", "upsert", "delete", "history"), "Operation to perform."),
                    "id" to
                        stringProp(
                            "Document id (path-derived), e.g. org/repo/pr-42/e1.",
                            McpLimits.MAX_DOCUMENT_ID_LENGTH
                        ),
                    "content" to
                        stringProp("Markdown body (required for action=upsert).", McpLimits.MAX_CONTENT_BYTES.toInt()),
                    "frontmatter" to
                        objectProp(
                            "Flat frontmatter map, e.g. {\"title\":..,\"repo\":..,\"pr\":..}.",
                            McpLimits.MAX_FRONTMATTER_ENTRIES
                        ),
                    "collection" to
                        stringProp(
                            "Namespace; defaults to the existing document's or `vault`.",
                            McpLimits.MAX_COLLECTION_LENGTH
                        ),
                    "path" to stringProp("Adapter-relative path; defaults to `<id>.md`.", McpLimits.MAX_PATH_LENGTH),
                    "message" to stringProp("Commit message (upsert/delete).", McpLimits.MAX_MESSAGE_LENGTH),
                    "author" to
                        stringProp("Author recorded on the revision (default `mcp`).", McpLimits.MAX_AUTHOR_LENGTH),
                    "ifRevision" to
                        stringProp(
                            "Optimistic concurrency: required current revision.",
                            McpLimits.MAX_DOCUMENT_ID_LENGTH
                        ),
                    "limit" to intProp("History page size (1..500, default 100).", 1, McpLimits.MAX_PAGE_LIMIT),
                    "offset" to intProp("History page offset (0..10000, default 0).", 0, McpLimits.MAX_PAGE_OFFSET)
                ),
            required = listOf("action", "id"),
            annotations = mutating("Document")
        ) { args ->
            validateToolArguments("document", args)
            val action =
                args.str("action")
                    ?: throw McpToolError("invalid_argument", "`action` is required", validOptions = DOCUMENT_ACTIONS)
            val id = requireId(args)
            when (action) {
                "get" -> {
                    val document = store.get(id) ?: throw notFound(id)
                    validateReadableContent(document)
                    Toon.encode(document)
                }

                "history" -> {
                    val limit = args.boundedInt("limit", 100)
                    val offset = args.boundedInt("offset", 0, 0, McpLimits.MAX_PAGE_OFFSET)
                    Toon.encode(buildJsonObject { put("revisions", store.history(id, limit, offset)) })
                }

                "delete" -> {
                    val message = args.optionalString("message")?.also(::validateMessage)
                    val author = args.optionalString("author")?.also(::validateAuthor)
                    val ifRevision = args.optionalString("ifRevision")?.also(::validateRevision)
                    Toon.encode(store.delete(id, message, author, ifRevision))
                }

                "upsert" -> {
                    Toon.encode(upsertResult(id, args))
                }

                else -> {
                    throw McpToolError(
                        "invalid_argument",
                        "unknown action '$action'",
                        hint = "use one of the valid actions",
                        validOptions = DOCUMENT_ACTIONS
                    )
                }
            }
        }

    private fun upsertResult(id: String, args: Map<String, Any>): JsonElement {
        val content =
            args.str("content")
                ?: throw McpToolError(
                    "invalid_argument",
                    "`content` is required for action=upsert",
                    hint = "pass the Markdown body; put structured metadata in `frontmatter`"
                )
        validateContent(content)
        val collection = args.optionalString("collection")?.also(::validateCollection)
        val path = args.optionalString("path")?.also(::validatePath)
        val frontmatter = args.optionalJsonMap("frontmatter").also(::validateStorableFrontmatter)
        val message = args.optionalString("message")?.also(::validateMessage)
        val author = args.optionalString("author")?.also(::validateAuthor)
        val ifRevision = args.optionalString("ifRevision")?.also(::validateRevision)
        return store.upsert(
            id = id,
            content = content,
            collection = collection,
            path = path,
            frontmatter = frontmatter,
            message = message,
            author = author,
            ifRevision = ifRevision
        )
    }

    // ---- traverse ---------------------------------------------------------

    private fun traverseTool(): SyncToolSpecification =
        tool(
            name = "traverse",
            description =
                "READ-ONLY. Walk graph edges from a document to its related documents. `rel` is a relationship " +
                    "type (${REL_TYPES.joinToString(", ")}); common aliases such as `link` or `ref` are accepted. " +
                    "`depth` defaults to 3. Returns {ids:[...]} (empty when the document has no such edges).",
            properties =
                mapOf(
                    "from" to
                        stringProp("Starting document id, e.g. org/repo/pr-42/e1.", McpLimits.MAX_DOCUMENT_ID_LENGTH),
                    "rel" to enumProp(REL_TYPES, "Relationship type; defaults to links-to."),
                    "depth" to intProp("Hops to follow (0..10, default 3).", 0, McpLimits.MAX_TRAVERSAL_DEPTH)
                ),
            required = listOf("from"),
            annotations = readOnly("Traverse")
        ) { args ->
            validateToolArguments("traverse", args)
            val from =
                args.str("from")
                    ?: throw McpToolError(
                        "invalid_argument",
                        "`from` (document id) is required",
                        hint = "ids are path-derived, e.g. org/repo/pr-42/e1"
                    )
            validateDocumentId(from)
            val relInput = args.optionalString("rel")?.also(::validateRelation) ?: DEFAULT_REL
            val rel =
                resolveRel(relInput)
                    ?: throw McpToolError(
                        "invalid_argument",
                        "unknown relationship type '$relInput'",
                        hint = "use one of the valid relationship types",
                        validOptions = REL_TYPES
                    )
            val depth = args.boundedInt("depth", 3, 0, McpLimits.MAX_TRAVERSAL_DEPTH)
            Toon.encode(buildJsonObject { put("ids", store.traverse(from, rel, depth)) })
        }

    // ---- tool plumbing ----------------------------------------------------

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, Any>,
        required: List<String>,
        annotations: McpSchema.ToolAnnotations,
        handler: (Map<String, Any>) -> String
    ): SyncToolSpecification {
        val tool =
            McpSchema.Tool
                .builder(
                    name,
                    mapOf(
                        "type" to "object",
                        "properties" to properties,
                        "required" to required,
                        "additionalProperties" to false,
                        "maxProperties" to properties.size
                    )
                ).description(description)
                .annotations(annotations)
                .build()
        val callHandler =
            BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> { _, request ->
                val outcome =
                    runCatching {
                        validateRequestEnvelope(request)
                        handler(request.arguments() ?: emptyMap())
                    }.fold(
                        onSuccess = { it to false },
                        onFailure = { error ->
                            logToolFailure(request, error)
                            errorPayload(error) to true
                        }
                    )
                McpSchema.CallToolResult
                    .builder()
                    .addTextContent(outcome.first)
                    .isError(outcome.second)
                    .build()
            }
        return SyncToolSpecification
            .builder()
            .tool(tool)
            .callHandler(callHandler)
            .build()
    }

    private fun logToolFailure(request: McpSchema.CallToolRequest, error: Throwable) {
        logger.error(
            "mcp tool call failed",
            error,
            mapOf(
                "tool" to request.name(),
                "errorType" to (error::class.simpleName ?: "error"),
                "code" to (error as? McpToolError)?.code
            )
        )
    }

    /**
     * Bounds the whole `tools/call` request, not just `arguments`: `_meta` and
     * any other envelope field is client-controlled and would otherwise be
     * buffered unbounded before the tool runs.
     */
    private fun validateRequestEnvelope(request: McpSchema.CallToolRequest) {
        val meta = request.meta()
        if (meta != null && meta.size > MAX_META_ENTRIES) {
            throw McpToolError("request_too_large", "MCP request _meta exceeds $MAX_META_ENTRIES entries")
        }
        val envelope =
            buildJsonObject {
                put("name", request.name())
                put("arguments", (request.arguments() ?: emptyMap<Any, Any>()).toJsonElement())
                meta?.let { put("_meta", it.toJsonElement()) }
            }
        if (envelope
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
                .size
                .toLong() > McpLimits.MAX_REQUEST_ENVELOPE_BYTES
        ) {
            throw McpToolError(
                "request_too_large",
                "MCP request must be at most ${McpLimits.MAX_REQUEST_ENVELOPE_BYTES} bytes"
            )
        }
    }

    /**
     * Errors are values, but only *authored* text is echoed. Anything thrown by
     * a backend (store, index, HTTP client, subprocess) is reduced to a stable
     * category so adapter stderr, endpoints, and document data never reach the
     * model or the client's transcript.
     */
    private fun errorPayload(error: Throwable): String {
        val toolError =
            when (error) {
                is McpToolError -> {
                    error
                }

                is IllegalArgumentException,
                is InvalidInputException -> {
                    McpToolError("invalid_argument", callerReason(error) ?: "invalid request")
                }

                is NotFoundException -> {
                    McpToolError("not_found", "document not found")
                }

                is ConflictException -> {
                    McpToolError("conflict", "request conflicts with current state")
                }

                else -> {
                    McpToolError("internal", "internal error", hint = "retry, or narrow the request")
                }
            }
        return Toon.encode(
            buildJsonObject {
                put("error", toolError.message)
                put(
                    "details",
                    buildJsonObject {
                        put("code", toolError.code)
                        toolError.hint?.let { put("hint", it) }
                        toolError.validOptions?.let { options ->
                            put("validOptions", buildJsonArray { options.forEach { add(it) } })
                        }
                    }
                )
            }
        )
    }

    private fun requireId(args: Map<String, Any>): String =
        args
            .str("id")
            ?.also(::validateDocumentId)
            ?: throw McpToolError(
                "invalid_argument",
                "`id` is required",
                hint = "ids are path-derived, e.g. org/repo/pr-42/e1"
            )

    /**
     * A read is bounded by the same content limit as a write.
     *
     * The store can hold more than the seam is willing to return — a document
     * written by another client, a transfer import, a file dropped into a vault —
     * so `action=get` has to refuse rather than push an unbounded body into a
     * model's context. Truncating silently would return a document that reads
     * whole and is not.
     */
    private fun validateReadableContent(document: JsonObject) {
        val content = (document["content"] as? JsonPrimitive)?.contentOrNull ?: return
        if (content.toByteArray(StandardCharsets.UTF_8).size.toLong() > McpLimits.MAX_CONTENT_BYTES) {
            throw McpToolError(
                "content_too_large",
                "document content is larger than the ${McpLimits.MAX_CONTENT_BYTES} byte read bound",
                hint = "use `search` for a snippet, or read the document through the HTTP API"
            )
        }
    }

    private fun resolveRel(input: String): String? = REL_ALIASES[input.lowercase().trim().replace('_', '-')]

    private fun readOnly(title: String): McpSchema.ToolAnnotations =
        McpSchema.ToolAnnotations
            .builder()
            .title(title)
            .readOnlyHint(true)
            .destructiveHint(false)
            .idempotentHint(true)
            .openWorldHint(false)
            .build()

    /** Conservative annotation for a dual-behaviour tool that can mutate. */
    private fun mutating(title: String): McpSchema.ToolAnnotations =
        McpSchema.ToolAnnotations
            .builder()
            .title(title)
            .readOnlyHint(false)
            .destructiveHint(true)
            .idempotentHint(true)
            .openWorldHint(false)
            .build()

    private fun stringProp(description: String, maxLength: Int): Map<String, Any> =
        mapOf(
            "type" to "string",
            "maxLength" to maxLength,
            "description" to description
        )

    private fun intProp(description: String, minimum: Int, maximum: Int): Map<String, Any> =
        mapOf(
            "type" to "integer",
            "minimum" to minimum,
            "maximum" to maximum,
            "description" to description
        )

    private fun arrayProp(description: String, maxItems: Int, maxItemLength: Int): Map<String, Any> =
        mapOf(
            "type" to "array",
            "maxItems" to maxItems,
            "items" to mapOf("type" to "string", "maxLength" to maxItemLength),
            "description" to description
        )

    private fun objectProp(description: String, maxProperties: Int): Map<String, Any> =
        mapOf(
            "type" to "object",
            "maxProperties" to maxProperties,
            "additionalProperties" to true,
            "description" to description
        )

    private fun enumProp(values: List<String>, description: String): Map<String, Any> =
        mapOf("type" to "string", "enum" to values, "description" to description)

    private companion object {
        const val MAX_META_ENTRIES = 64

        /**
         * Hard cap on one inbound stdio line, in the characters the SDK's line
         * reader counts, and the only bound that applies *before* a request is
         * deserialized: [validateRequestEnvelope] can only bound what the SDK
         * has already materialised as a `tools/call` request.
         *
         * It sits above [McpLimits.MAX_REQUEST_ENVELOPE_BYTES] because a line
         * carries the JSON-RPC framing around the envelope (`jsonrpc`, `id`,
         * `method`) as well, and because a UTF-8 string never uses more
         * characters than bytes, so a request within the envelope bound is always
         * within this one.
         *
         * The boundary it guards is worth stating plainly: the SDK answers an
         * oversized line by closing the session rather than with a tool error, so
         * a client that sends more than this loses the connection and has to
         * reconnect. A request that fits here but overruns the envelope bound is
         * still answered with an errors-as-value `request_too_large`.
         */
        const val TRANSPORT_FRAME_SLACK = 8_192L
        const val MAX_TRANSPORT_FRAME_CHARS = (McpLimits.MAX_REQUEST_ENVELOPE_BYTES + TRANSPORT_FRAME_SLACK).toInt()
        val DOCUMENT_ACTIONS = listOf("get", "upsert", "delete", "history")
        val REL_TYPES = listOf("links-to", "references", "embeds", "mentions")
        const val DEFAULT_REL = "links-to"
        val REL_ALIASES =
            mapOf(
                "links-to" to "links-to",
                "link" to "links-to",
                "links" to "links-to",
                "wikilink" to "links-to",
                "backlink" to "links-to",
                "references" to "references",
                "reference" to "references",
                "ref" to "references",
                "embeds" to "embeds",
                "embed" to "embeds",
                "attachment" to "embeds",
                "file" to "embeds",
                "files" to "embeds",
                "mentions" to "mentions",
                "mention" to "mentions",
                "author" to "mentions"
            )
    }
}
