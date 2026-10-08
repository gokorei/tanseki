package gokorei.tanseki.service.mcp

import gokorei.tanseki.core.application.DocumentCommandService
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.application.UpsertDocumentCommand
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.SearchMode
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.service.api.toDto
import gokorei.tanseki.service.api.toFrontmatter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** A structured, self-healing tool failure, returned as an errors-as-value payload. */
class McpToolError(
    val code: String,
    override val message: String,
    val hint: String? = null,
    val validOptions: List<String>? = null
) : RuntimeException(message)

internal fun notFound(id: String): McpToolError =
    McpToolError(
        "not_found",
        "no document with id '$id'",
        hint = "use the search tool to find the correct id, then retry"
    )

/**
 * The store operations the MCP tools need, independent of where the store lives.
 * Backed by the embedded composition ([LocalMcpStore]) or the running daemon's
 * HTTP API ([HttpMcpStore]). All methods return JSON-ready values.
 */
interface McpStore {
    /**
     * Rank [query] under [mode]. The same modes `/v1/search?mode=` accepts, so a
     * caller that moves between the seams is not silently downgraded to lexical.
     */
    fun search(
        query: String,
        mode: SearchMode,
        collections: Set<String>,
        tags: Set<String>,
        frontmatter: Map<String, String>,
        limit: Int,
        offset: Int
    ): JsonArray

    fun get(id: String): JsonObject?

    fun upsert(
        id: String,
        content: String,
        collection: String?,
        path: String?,
        frontmatter: Map<String, JsonElement>?,
        message: String?,
        author: String?,
        ifRevision: String?
    ): JsonObject

    fun delete(
        id: String,
        message: String?,
        author: String?,
        ifRevision: String? = null
    ): JsonObject

    fun history(id: String, limit: Int, offset: Int): JsonArray

    fun traverse(from: String, rel: String, depth: Int): JsonArray
}

/** In-process [McpStore] over a [QueryFacade] (embedded single-writer mode). */
class LocalMcpStore(private val facade: QueryFacade) : McpStore {
    private val json = Json { encodeDefaults = true }
    private val documentCommands = DocumentCommandService(facade)

    override fun search(
        query: String,
        mode: SearchMode,
        collections: Set<String>,
        tags: Set<String>,
        frontmatter: Map<String, String>,
        limit: Int,
        offset: Int
    ): JsonArray {
        val filters = Filters.fromStrings(collections = collections, tags = tags, frontmatter = frontmatter)
        // Fusion and the fallback to lexical without an embedder both live in
        // QueryFacade, which is what /v1/search routes through as well; choosing
        // here and duplicating that policy is how the two seams would drift.
        val hits =
            runBlocking {
                when (mode) {
                    SearchMode.Lexical -> facade.searchTextPage(query, filters, limit, offset)
                    SearchMode.Hybrid -> facade.searchHybridPage(query, filters, limit, offset)
                }
            }.take(limit)
        return json.encodeToJsonElement(hits.map { it.toDto() }) as JsonArray
    }

    override fun get(id: String): JsonObject? =
        runBlocking { facade.get(DocId(id)) }?.let { json.encodeToJsonElement(it.toDto()) as JsonObject }

    override fun upsert(
        id: String,
        content: String,
        collection: String?,
        path: String?,
        frontmatter: Map<String, JsonElement>?,
        message: String?,
        author: String?,
        ifRevision: String?
    ): JsonObject {
        val result =
            runBlocking {
                documentCommands.upsert(
                    UpsertDocumentCommand(
                        id = id,
                        content = content,
                        collection = collection,
                        path = path,
                        frontmatter = frontmatter?.toFrontmatter(),
                        message = message ?: "upsert",
                        author = author ?: "mcp",
                        ifRevision = ifRevision
                    )
                )
            }
        return buildJsonObject {
            put("id", result.document.id.value)
            put("revision", result.revision.revision.value)
            put("contentHash", result.document.contentHash)
            put("created", result.created)
        }
    }

    override fun delete(
        id: String,
        message: String?,
        author: String?,
        ifRevision: String?
    ): JsonObject {
        runBlocking { facade.get(DocId(id)) } ?: throw notFound(id)
        val expected =
            ifRevision?.let {
                runCatching { RevisionId(it) }.getOrElse {
                    throw McpToolError("invalid_argument", "ifRevision is not a valid revision")
                }
            }
        val revision =
            runBlocking {
                facade.delete(DocId(id), message ?: "delete", author ?: "mcp", expected)
            }
        return buildJsonObject {
            put("id", id)
            put("revision", revision.revision.value)
        }
    }

    override fun history(id: String, limit: Int, offset: Int): JsonArray {
        runBlocking { facade.get(DocId(id)) } ?: throw notFound(id)
        return json.encodeToJsonElement(
            runBlocking {
                facade.historyPage(DocId(id), limit, offset)
            }.items.map { it.toDto() }
        ) as JsonArray
    }

    override fun traverse(from: String, rel: String, depth: Int): JsonArray =
        json.encodeToJsonElement(
            runBlocking {
                facade.traverse(
                    DocId(from),
                    gokorei.tanseki.core.domain
                        .RelType(rel),
                    depth
                )
            }.map { it.value }
        ) as JsonArray
}

/** [McpStore] that talks to a running daemon's canonical `/v1` HTTP API. */
class HttpMcpStore(
    baseUrl: String,
    private val apiKey: String? = null,
    private val client: HttpClient = HttpClient.newHttpClient(),
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES
) : McpStore, AutoCloseable {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val timeout: Duration = Duration.ofSeconds(15)

    init {
        require(maxResponseBytes > 0) { "maxResponseBytes must be > 0" }
    }

    override fun close() {
        // Java's client owns a selector thread; closing it is what stops that thread.
        runCatching { client.close() }
    }

    override fun search(
        query: String,
        mode: SearchMode,
        collections: Set<String>,
        tags: Set<String>,
        frontmatter: Map<String, String>,
        limit: Int,
        offset: Int
    ): JsonArray {
        val params =
            mutableListOf("q=${encode(query)}", "mode=${encode(mode.wire)}", "limit=$limit", "offset=$offset")
        collections.forEach { params += "collection=${encode(it)}" }
        tags.forEach { params += "tags=${encode(it)}" }
        frontmatter.forEach { (key, value) -> params += "fm=${encode("$key=$value")}" }
        val (status, body) = send("GET", "/v1/search?${params.joinToString("&")}", null)
        if (status >= 400) fail(status, body)
        return json.parseToJsonElement(body).jsonObject["hits"]?.jsonArray ?: JsonArray(emptyList())
    }

    override fun get(id: String): JsonObject? {
        val (status, body) = send("POST", "/v1/documents:get", buildJsonObject { put("id", id) })
        if (status == 404) return null
        if (status >= 400) fail(status, body)
        return json.parseToJsonElement(body).jsonObject
    }

    override fun upsert(
        id: String,
        content: String,
        collection: String?,
        path: String?,
        frontmatter: Map<String, JsonElement>?,
        message: String?,
        author: String?,
        ifRevision: String?
    ): JsonObject {
        val payload =
            buildJsonObject {
                put("id", id)
                put("content", content)
                collection?.let { put("collection", it) }
                path?.let { put("path", it) }
                frontmatter?.let { fm -> put("frontmatter", JsonObject(fm)) }
                message?.let { put("message", it) }
                author?.let { put("author", it) }
                ifRevision?.let { put("ifRevision", it) }
            }
        val (status, body) = send("POST", "/v1/documents:upsert", payload)
        if (status >= 400) fail(status, body)
        return json.parseToJsonElement(body).jsonObject
    }

    override fun delete(
        id: String,
        message: String?,
        author: String?,
        ifRevision: String?
    ): JsonObject {
        val payload =
            buildJsonObject {
                put("id", id)
                message?.let { put("message", it) }
                author?.let { put("author", it) }
                ifRevision?.let { put("ifRevision", it) }
            }
        val (status, body) = send("POST", "/v1/documents:delete", payload)
        if (status >= 400) fail(status, body)
        val result = json.parseToJsonElement(body).jsonObject
        return buildJsonObject {
            put("id", result["id"]?.jsonPrimitive?.contentOrNull ?: id)
            result["revision"]?.jsonPrimitive?.contentOrNull?.let { put("revision", it) }
        }
    }

    override fun history(id: String, limit: Int, offset: Int): JsonArray {
        val payload =
            buildJsonObject {
                put("id", id)
                put("limit", limit)
                put("offset", offset)
            }
        val (status, body) = send("POST", "/v1/documents:history", payload)
        if (status >= 400) fail(status, body)
        return json.parseToJsonElement(body).jsonObject["revisions"]?.jsonArray ?: JsonArray(emptyList())
    }

    override fun traverse(from: String, rel: String, depth: Int): JsonArray {
        val payload =
            buildJsonObject {
                put("id", from)
                put("rel", rel)
                put("depth", depth)
            }
        val (status, body) = send("POST", "/v1/documents:traverse", payload)
        if (status >= 400) fail(status, body)
        return json.parseToJsonElement(body).jsonObject["ids"]?.jsonArray ?: JsonArray(emptyList())
    }

    private fun send(method: String, path: String, body: JsonElement?): Pair<Int, String> {
        val published = body?.toString()
        if (published != null && published.toByteArray(StandardCharsets.UTF_8).size.toLong() > maxResponseBytes) {
            throw McpToolError("request_too_large", "request is larger than the store accepts")
        }
        val builder =
            HttpRequest
                .newBuilder(URI.create("$base$path"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
        apiKey?.let { builder.header("X-API-Key", it) }
        builder.method(
            method,
            published?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        )
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val bytes =
            response.body().use { stream ->
                val declared =
                    response
                        .headers()
                        .firstValue("content-length")
                        .orElse(null)
                        ?.toLongOrNull()
                if (declared != null && declared > maxResponseBytes) responseTooLarge()
                val buffer = ByteArrayOutputStream(minOf(maxResponseBytes, 8_192))
                val chunk = ByteArray(8_192)
                var total = 0L
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > maxResponseBytes) responseTooLarge()
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        return response.statusCode() to String(bytes, StandardCharsets.UTF_8)
    }

    private fun responseTooLarge(): Nothing =
        throw McpToolError(
            "response_too_large",
            "store response exceeds the ${maxResponseBytes}B bound",
            hint = "narrow the request (smaller page, more specific filter)"
        )

    /**
     * Maps a failing store response to a tool error without echoing the daemon
     * endpoint, its body detail, or the daemon's internal message: only the
     * stable Tanseki error code and a generic message cross the seam.
     */
    private fun fail(status: Int, body: String): Nothing {
        if (status == 404) {
            throw McpToolError(
                "not_found",
                "no document with the requested id",
                hint = "use the search tool to find the correct id, then retry"
            )
        }
        val error =
            runCatching { json.parseToJsonElement(body.take(MAX_ERROR_BODY_CHARS)).jsonObject["error"]?.jsonObject }
                .getOrNull()
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull ?: "unavailable"
        val hint = MCP_ERROR_HINTS[code] ?: "the store rejected the request; retry or narrow it"
        throw McpToolError(code, "store returned HTTP $status", hint = hint)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val MAX_ERROR_BODY_CHARS = 4_096
        val MCP_ERROR_HINTS =
            mapOf(
                "invalid_argument" to "check the arguments against the tool schema",
                "conflict" to "re-read the document and retry with its current revision",
                "failed_precondition" to "re-read the document and retry with its current revision",
                "forbidden" to "the credential is not scoped to this document's collection",
                "unauthenticated" to "the store rejected the API credential",
                "request_too_large" to "narrow the request (smaller page, shorter content)"
            )
    }
}
