package gokorei.tanseki.adapters.meili

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.RateLimitedException
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.TansekiException
import gokorei.tanseki.core.domain.UnavailableException
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.RetryPolicy
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.core.ports.retry
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000L
private const val DEFAULT_SOCKET_TIMEOUT_MILLIS = 30_000L
private const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 30_000L

private fun defaultMeiliHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = DEFAULT_SOCKET_TIMEOUT_MILLIS
            requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS
        }
    }

internal data class MeiliHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)

internal interface MeiliHttpClient : AutoCloseable {
    suspend fun request(
        method: HttpMethod,
        path: String,
        body: String?
    ): MeiliHttpResponse

    override fun close() = Unit
}

private class KtorMeiliHttpClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val client: HttpClient
) : MeiliHttpClient {
    override suspend fun request(
        method: HttpMethod,
        path: String,
        body: String?
    ): MeiliHttpResponse {
        val response =
            client.request("$baseUrl$path") {
                this.method = method
                apiKey?.let { header("Authorization", "Bearer $it") }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        return MeiliHttpResponse(
            response.status.value,
            response.bodyAsText(),
            response.headers.entries().associate { header -> header.key to header.value.joinToString(",") }
        )
    }

    override fun close() {
        client.close()
    }
}

/**
 * Server-profile [Lookup] backed by Meilisearch over HTTP.
 *
 * Two indexes: `<name>` for node documents (text + filters) and
 * `<name>_edges` for graph edges (traversal). Meilisearch is task-based and
 * eventually consistent, so every write waits for its task to succeed before
 * returning. kNN is not wired (embeddings are a separate server concern), so
 * [searchVector] returns empty rather than lying.
 */
class MeiliLookup private constructor(
    private val indexName: String,
    private val client: MeiliHttpClient,
    private val logger: TansekiLogger,
    private val httpRetryPolicy: RetryPolicy,
    private val taskPollAttempts: Int,
    private val taskPollInterval: Duration,
    private val taskPollTimeout: Duration
) : Lookup, AutoCloseable {
    constructor(
        baseUrl: String,
        apiKey: String? = null,
        indexName: String = "tanseki",
        client: HttpClient = defaultMeiliHttpClient(),
        logger: TansekiLogger = TansekiLogger.Noop
    ) : this(
        indexName,
        KtorMeiliHttpClient(baseUrl, apiKey, client),
        logger,
        DEFAULT_HTTP_RETRY_POLICY,
        DEFAULT_TASK_POLL_ATTEMPTS,
        DEFAULT_TASK_POLL_INTERVAL,
        DEFAULT_TASK_POLL_TIMEOUT
    )

    init {
        require(taskPollAttempts >= 1) { "taskPollAttempts must be >= 1" }
        require(!taskPollInterval.isNegative()) { "taskPollInterval must not be negative" }
        require(!taskPollTimeout.isNegative()) { "taskPollTimeout must not be negative" }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val docsIndex = indexName
    private val edgesIndex = "${indexName}_edges"

    @Volatile
    private var ready = false

    // Serialises first-use index creation. `ready` is a visibility flag, not a
    // lock: two concurrent callers could both pass the check and race the
    // create-index POST, so the whole initialization runs under a mutex and the
    // flag is re-checked inside it.
    private val readyLock = Mutex()

    override fun index(doc: Document, edges: List<Edge>) =
        runBlocking {
            ensureReady()
            addDocuments(docsIndex, buildJsonArray { add(documentJson(doc)) })

            deleteByFilter(edgesIndex, "edge_src = ${quote(doc.id.value)}")
            if (edges.isNotEmpty()) {
                addDocuments(edgesIndex, buildJsonArray { edges.forEach { add(edgeJson(it)) } })
            }
            logger.debug("meili index", mapOf("doc" to doc.id.value, "edges" to edges.size))
        }

    override fun remove(id: DocId) =
        runBlocking {
            ensureReady()
            val response = call(HttpMethod.Delete, "/indexes/$docsIndex/documents/${keyFor(id.value)}", null)
            waitForTask(response.body)
            deleteByFilter(edgesIndex, "edge_src = ${quote(id.value)}")
            deleteByFilter(edgesIndex, "edge_dst = ${quote(id.value)}")
        }

    override fun clear() =
        runBlocking {
            ensureReady()
            deleteAll(docsIndex)
            deleteAll(edgesIndex)
        }

    override fun indexedIds(): Set<DocId> =
        runBlocking {
            ensureReady()
            val ids = linkedSetOf<DocId>()
            var offset = 0
            while (true) {
                val response =
                    call(
                        HttpMethod.Get,
                        "/indexes/$docsIndex/documents?fields=docId&limit=1000&offset=$offset",
                        null
                    )
                val result = json.parseToJsonElement(response.body).jsonObject
                val documents = result["results"]?.jsonArray ?: JsonArray(emptyList())
                documents.forEach { element ->
                    element.jsonObject["docId"]
                        ?.jsonPrimitive
                        ?.content
                        ?.let { ids += DocId(it) }
                }
                val total = result["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                if (documents.isEmpty() || offset + documents.size >= total) {
                    return@runBlocking ids
                }
                offset += documents.size
            }
            ids
        }

    override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> =
        runBlocking {
            ensureReady()
            // A blank `q` with no filters is not a question — same rule as the
            // Lucene adapter (`Filters.isSearching`). Without this, Meilisearch
            // answers an empty `q` with every document (placeholder search).
            if (q.isBlank() && !filters.isSearching) return@runBlocking emptyList()
            val payload =
                buildJsonObject {
                    put("q", q)
                    put("limit", limit)
                    put("showRankingScore", true)
                    put(
                        "attributesToRetrieve",
                        buildJsonArray {
                            add("docId")
                            add("content")
                        }
                    )
                    filterExpression(filters)?.let { put("filter", it) }
                }
            val response = call(HttpMethod.Post, "/indexes/$docsIndex/search", payload)
            val hits = json.parseToJsonElement(response.body).jsonObject["hits"]?.jsonArray ?: JsonArray(emptyList())
            hits
                .mapNotNull { element ->
                    val obj = element.jsonObject
                    val id = obj["docId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val score = obj["_rankingScore"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                    Hit(DocId(id), score, obj["content"]?.jsonPrimitive?.content?.take(SNIPPET_LENGTH))
                }.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.id.value })
        }

    override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> {
        // Vectors are a server-side embedding concern; not wired in this adapter yet.
        return emptyList()
    }

    override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> =
        runBlocking {
            ensureReady()
            val visited = LinkedHashSet<DocId>()
            var frontier = listOf(id)
            repeat(depth.coerceAtLeast(0)) {
                val next = mutableListOf<DocId>()
                for (node in frontier) {
                    val payload =
                        buildJsonObject {
                            put("q", "")
                            put("limit", MAX_RESULTS)
                            put("filter", "edge_src = ${quote(node.value)} AND edge_rel = ${quote(rel.value)}")
                        }
                    val response = call(HttpMethod.Post, "/indexes/$edgesIndex/search", payload)
                    val hits =
                        json.parseToJsonElement(response.body).jsonObject["hits"]?.jsonArray ?: JsonArray(emptyList())
                    hits.forEach { element ->
                        val dst = element.jsonObject["edge_dst"]?.jsonPrimitive?.content ?: return@forEach
                        val target = DocId(dst)
                        if (visited.add(target)) next += target
                    }
                }
                frontier = next
            }
            visited.toList()
        }

    override fun backlinks(id: DocId, rel: RelType?): List<DocId> =
        runBlocking {
            ensureReady()
            val filter =
                buildString {
                    append("edge_dst = ")
                    append(quote(id.value))
                    if (rel != null) {
                        append(" AND edge_rel = ")
                        append(quote(rel.value))
                    }
                }
            val payload =
                buildJsonObject {
                    put("q", "")
                    put("limit", MAX_RESULTS)
                    put("filter", filter)
                }
            val response = call(HttpMethod.Post, "/indexes/$edgesIndex/search", payload)
            val hits =
                json.parseToJsonElement(response.body).jsonObject["hits"]?.jsonArray ?: JsonArray(emptyList())
            hits
                .mapNotNull { it.jsonObject["edge_src"]?.jsonPrimitive?.content }
                .map(::DocId)
                .distinct()
        }

    override fun rebuild(store: ContextStore) =
        runBlocking {
            ensureReady()
            deleteAll(docsIndex)
            deleteAll(edgesIndex)
            for (ref in store.list()) {
                val doc = store.read(ref.id) ?: continue
                index(doc, store.neighbors(ref.id))
            }
        }

    override fun close() {
        client.close()
    }

    // ---- Meili plumbing ---------------------------------------------------

    private suspend fun ensureReady() {
        if (ready) return
        readyLock.withLock {
            if (ready) return@withLock
            initialize()
        }
    }

    private suspend fun initialize() {
        createIndexIfMissing(docsIndex)
        createIndexIfMissing(edgesIndex)
        updateSettings(
            docsIndex,
            buildJsonObject {
                put(
                    "searchableAttributes",
                    buildJsonArray {
                        add("title")
                        add("content")
                        add("tags")
                    }
                )
                put(
                    "filterableAttributes",
                    buildJsonArray {
                        add("collection")
                        add("tags")
                        add("fm_keys")
                    }
                )
                put(
                    "rankingRules",
                    buildJsonArray {
                        add("words")
                        add("typo")
                        add("proximity")
                        add("attribute")
                        add("sort")
                        add("exactness")
                    }
                )
            }
        )
        updateSettings(
            edgesIndex,
            buildJsonObject {
                put(
                    "filterableAttributes",
                    buildJsonArray {
                        add("edge_src")
                        add("edge_dst")
                        add("edge_rel")
                    }
                )
            }
        )
        ready = true
    }

    private suspend fun createIndexIfMissing(uid: String) {
        val response = call(HttpMethod.Get, "/indexes/$uid", null, acceptedStatuses = setOf(404))
        if (response.statusCode == 200) return
        val created =
            call(
                HttpMethod.Post,
                "/indexes",
                buildJsonObject {
                    put("uid", uid)
                    put("primaryKey", "id")
                }
            )
        waitForTask(created.body)
    }

    private suspend fun updateSettings(uid: String, settings: JsonObject) {
        val response = call(HttpMethod.Patch, "/indexes/$uid/settings", settings)
        waitForTask(response.body)
    }

    private suspend fun addDocuments(uid: String, documents: JsonArray) {
        val response = call(HttpMethod.Post, "/indexes/$uid/documents", documents)
        waitForTask(response.body)
    }

    private suspend fun deleteByFilter(uid: String, filter: String) {
        val response =
            call(
                HttpMethod.Post,
                "/indexes/$uid/documents/delete",
                buildJsonObject { put("filter", filter) }
            )
        waitForTask(response.body)
    }

    private suspend fun deleteAll(uid: String) {
        val response = call(HttpMethod.Delete, "/indexes/$uid/documents", null)
        waitForTask(response.body)
    }

    private suspend fun waitForTask(responseBody: String) {
        val uid = taskUid(responseBody)
        val started =
            kotlin.time.TimeSource.Monotonic
                .markNow()
        var polls = 0
        while (true) {
            polls++
            val response = call(HttpMethod.Get, "/tasks/$uid", null)
            val status =
                runCatching {
                    json
                        .parseToJsonElement(response.body)
                        .jsonObject["status"]
                        ?.jsonPrimitive
                        ?.content
                }.getOrNull()
            if (status == "succeeded") return
            if (status != "enqueued" && status != "processing") {
                return taskFailure(uid, status ?: "missing", response.body)
            }
            val attemptLimitReached = taskPollAttempts != Int.MAX_VALUE && polls >= taskPollAttempts
            val deadlineReached = started.elapsedNow() >= taskPollTimeout
            if (attemptLimitReached || deadlineReached) {
                return taskTimedOut(uid, polls, attemptLimitReached)
            }
            delay(taskPollInterval)
        }
    }

    private fun taskUid(responseBody: String): Long =
        runCatching {
            json
                .parseToJsonElement(responseBody)
                .jsonObject["taskUid"]
                ?.jsonPrimitive
                ?.longOrNull
        }.getOrNull()
            ?: throw UnavailableException(
                "Meilisearch operation returned no taskUid: ${responseBody.trim().take(ERROR_BODY_LENGTH)}"
            )

    private fun taskFailure(
        uid: Long,
        status: String,
        responseBody: String
    ): Nothing =
        throw UnavailableException(
            "Meilisearch task $uid $status: ${responseBody.trim().take(ERROR_BODY_LENGTH)}"
        )

    private fun taskTimedOut(uid: Long, polls: Int, attemptLimitReached: Boolean): Nothing =
        if (attemptLimitReached) {
            throw UnavailableException("Meilisearch task $uid timed out after $polls polls")
        } else {
            throw UnavailableException(
                "Meilisearch task $uid is still pending after $polls polls; retrying is safe"
            )
        }

    private suspend fun call(
        method: HttpMethod,
        path: String,
        body: JsonElement?,
        acceptedStatuses: Set<Int> = emptySet()
    ): MeiliHttpResponse =
        if (isSafe(method, path)) {
            retry(httpRetryPolicy) { requestOnce(method, path, body, acceptedStatuses) }
        } else {
            requestOnce(method, path, body, acceptedStatuses)
        }

    private suspend fun requestOnce(
        method: HttpMethod,
        path: String,
        body: JsonElement?,
        acceptedStatuses: Set<Int>
    ): MeiliHttpResponse =
        try {
            val response = client.request(method, path, body?.toString())
            if (response.statusCode !in 200..299 && response.statusCode !in acceptedStatuses) {
                throw httpFailure(method, path, response)
            }
            response
        } catch (error: Throwable) {
            throw mapFailure(method, path, error)
        }

    private fun httpFailure(
        method: HttpMethod,
        path: String,
        response: MeiliHttpResponse
    ): TansekiException {
        val detail = response.body.trim().take(ERROR_BODY_LENGTH)
        val prefix = "Meilisearch ${method.value} $path returned ${response.statusCode}"
        return when (response.statusCode) {
            408, in 500..599 -> UnavailableException("$prefix: $detail")
            429 -> RateLimitedException(retryAfterMillis(response))
            else -> InvalidInputException("$prefix: $detail")
        }
    }

    private fun retryAfterMillis(response: MeiliHttpResponse): Long? {
        val raw =
            response.headers.entries
                .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                ?.value
                ?: return null
        raw.toLongOrNull()?.let { return it.coerceAtLeast(0) * 1_000L }
        return runCatching {
            val target =
                ZonedDateTime
                    .parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
            val now =
                java.time.Instant
                    .now()
                    .toEpochMilli()
            (target - now).coerceAtLeast(0)
        }.getOrNull()
    }

    private fun mapFailure(
        method: HttpMethod,
        path: String,
        error: Throwable
    ): TansekiException =
        when (error) {
            is CancellationException -> {
                throw error
            }

            is HttpRequestTimeoutException -> {
                UnavailableException("Meilisearch ${method.value} $path timed out", error)
            }

            is ConnectTimeoutException -> {
                UnavailableException("Meilisearch ${method.value} $path timed out while connecting", error)
            }

            is SocketTimeoutException -> {
                UnavailableException("Meilisearch ${method.value} $path timed out on the socket", error)
            }

            is TansekiException -> {
                error
            }

            else -> {
                UnavailableException("Meilisearch ${method.value} $path failed", error)
            }
        }

    private fun isSafe(
        method: HttpMethod,
        path: String
    ): Boolean = method == HttpMethod.Get || path.endsWith("/search")

    private fun documentJson(doc: Document): JsonObject =
        buildJsonObject {
            put("id", keyFor(doc.id.value))
            put("docId", doc.id.value)
            put("collection", doc.collection.value)
            put("path", doc.path)
            put("content", doc.content)
            put("contentHash", doc.contentHash)
            put("revision", doc.revision.value)
            doc.frontmatter.title?.let { put("title", it) }
            put("tags", buildJsonArray { doc.frontmatter.tags.forEach { add(it) } })
            if (doc.frontmatter.values.isNotEmpty()) put("fm_keys", frontmatterKeys(doc.frontmatter))
        }

    private fun edgeJson(edge: Edge): JsonObject =
        buildJsonObject {
            put("id", keyFor("${edge.src.value}|${edge.rel.value}|${edge.dst.value}"))
            put("edge_src", edge.src.value)
            put("edge_dst", edge.dst.value)
            put("edge_rel", edge.rel.value)
        }

    /**
     * Meilisearch primary keys only allow `[a-zA-Z0-9_-]`, but Tanseki ids are
     * path-derived. Hash the id into a safe key; the real id is stored in the
     * `docId`/`edge_*` fields.
     */
    private fun keyFor(value: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun filterExpression(filters: Filters): String? {
        val parts = mutableListOf<String>()
        filters.collections.forEach { parts += "collection = ${quote(it)}" }
        filters.tags.forEach { parts += "tags = ${quote(it)}" }
        filters.frontmatter.forEach { (key, value) -> parts += "fm_keys = ${quote("$key=${value.encode()}")}" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
    }

    private fun quote(value: String): String {
        if (value.any { it.isISOControl() }) {
            throw InvalidInputException("Meilisearch filter values must not contain control characters")
        }
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    companion object {
        /**
         * The `fm_` filter tokens for a document's non-contract frontmatter.
         *
         * A collection contributes two tokens per key: the whole value, so a filter
         * can name it exactly, and each scalar leaf, so `fm_files=…a.py` matches a
         * document whose `files` is a five-element list. Without the leaf token a
         * list would only be filterable by reproducing the entire list, which is not
         * a filter anyone can write.
         *
         * Extracted from the document body so it can be asserted without a running
         * Meilisearch — the indexing contract is worth testing on a laptop, not only
         * behind a container.
         */
        internal fun frontmatterKeys(frontmatter: Frontmatter): JsonArray =
            buildJsonArray {
                frontmatter.values.forEach { (key, value) ->
                    add("$key=${value.encode()}")
                    val whole = value.encode()
                    value.leaves().forEach { leaf ->
                        val encoded = leaf.encode()
                        if (encoded != whole) add("$key=$encoded")
                    }
                }
            }

        private const val DEFAULT_TASK_POLL_ATTEMPTS = Int.MAX_VALUE
        private const val ERROR_BODY_LENGTH = 500
        private const val MAX_RESULTS = 1000
        private const val SNIPPET_LENGTH = 200
        private val DEFAULT_HTTP_RETRY_POLICY =
            RetryPolicy(
                maxAttempts = 3,
                baseDelay = 100.milliseconds,
                maxDelay = 2.seconds,
                jitter = 0.2
            )
        private val DEFAULT_TASK_POLL_INTERVAL = 25.milliseconds
        private val DEFAULT_TASK_POLL_TIMEOUT = 30.seconds

        internal fun withHttpClient(
            client: MeiliHttpClient,
            indexName: String = "tanseki",
            logger: TansekiLogger = TansekiLogger.Noop,
            httpRetryPolicy: RetryPolicy = DEFAULT_HTTP_RETRY_POLICY,
            taskPollAttempts: Int = DEFAULT_TASK_POLL_ATTEMPTS,
            taskPollInterval: Duration = DEFAULT_TASK_POLL_INTERVAL,
            taskPollTimeout: Duration = DEFAULT_TASK_POLL_TIMEOUT
        ): MeiliLookup =
            MeiliLookup(
                indexName,
                client,
                logger,
                httpRetryPolicy,
                taskPollAttempts,
                taskPollInterval,
                taskPollTimeout
            )
    }
}
