package gokorei.tanseki.service.api

import gokorei.tanseki.core.application.ChangeEvent
import gokorei.tanseki.core.application.ChangeFeed
import gokorei.tanseki.core.application.ChangeKind
import gokorei.tanseki.core.application.DEFAULT_CHANGE_SUBSCRIBER_BUFFER
import gokorei.tanseki.core.application.DocumentCommandService
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.application.UpsertDocumentCommand
import gokorei.tanseki.core.domain.BlobCorruptionException
import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.Bytes
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.FrontmatterFilterValue
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.RequestLimits
import gokorei.tanseki.core.domain.RequestValidation
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.SearchMode
import gokorei.tanseki.core.domain.UnsupportedStoreOperationException
import gokorei.tanseki.core.domain.explain
import gokorei.tanseki.core.domain.resolveFrontmatterFilter
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.StorePage
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.service.TansekiVersion
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthKeyLocation
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.config.OutputFormat
import io.github.smiley4.ktoropenapi.config.RequestConfig
import io.github.smiley4.ktoropenapi.config.ResponseConfig
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.config.descriptors.type
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readAvailable
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.plugins.cors.routing.CORS as RoutingCORS

private val requestIdKey = AttributeKey<String>("tanseki.request-id")
private val requestStartKey = AttributeKey<Long>("tanseki.request-start")

const val HEADER_API_KEY = "X-API-Key"
const val HEADER_REQUEST_ID = "X-Request-Id"
const val HEADER_IDEMPOTENCY = "Idempotency-Key"

private const val JSON_ELEMENT_SCHEMA = "kotlinx.serialization.json.JsonElement"
private const val FRONTMATTER_PROPERTY = "frontmatter"

/**
 * What a frontmatter value may be, and what comes back.
 *
 * The store's canonical form is Markdown, so every value takes a YAML round trip.
 * That round trip preserves shape and type, which is the whole contract: an array
 * is written as a YAML block sequence and reads back as a array, not as the repr
 * string an earlier renderer produced. `files` is the case that matters — it is a
 * graph relation, and a relation whose value is a repr is not a relation.
 *
 * Scalars are typed too, and deliberately so: a number is held as its digits so
 * no float rounding can alter it, and re-emitted unquoted, so `42` reads back as
 * `42` and `9007199254740993` reads back exactly. A value that arrived quoted
 * stays a string, because re-typing `"0042"` into `42` would silently rewrite the
 * caller's data.
 *
 * The five contract keys are the exception, because each has a typed field of its
 * own: they take a scalar, `tags` takes an array of strings, and a shape they
 * cannot hold is refused by name rather than dropped. Keys outside that set are
 * extras and take any JSON value.
 *
 * Nothing here is a migration: a document keeps whatever shape it was stored as,
 * so one written before this behaviour existed still reads back the text it was
 * written with.
 */
private const val FRONTMATTER_CONTRACT =
    "An open, string-keyed map of JSON values. A value may be a string, number, " +
        "boolean, array, or object, and keeps that type across a write and a read: " +
        "an array is stored as a YAML block sequence and returns as an array, not as " +
        "its repr string, and a number keeps its exact digits (`9007199254740993` " +
        "round-trips unchanged). A value sent as a quoted string stays a string and " +
        "is never re-typed. Keys outside the contract set (`title`, `author`, `tags`, " +
        "`updated_at`, `content_hash`) take any JSON value; those five take a scalar " +
        "instead, `tags` takes an array of strings, and a shape they cannot hold is " +
        "refused with the key and the shape named rather than dropped. A document " +
        "keeps whatever shape it was stored as, so one written before this behaviour " +
        "existed still reads back the text it was written with. `updated_at` is the " +
        "one key whose text form is NOT preserved: it is read as an instant and " +
        "re-rendered in a single canonical spelling, so `2026-10-03T12:00:00+00:00` " +
        "is stored as `2026-10-03T12:00:00Z`. The instant survives; the spelling does " +
        "not. Compare instants, not strings."
const val MAX_REQUEST_BODY_BYTES = RequestLimits.MAX_REQUEST_BODY_BYTES
const val MAX_CONTENT_BYTES = RequestLimits.MAX_CONTENT_BYTES
const val MAX_DOCUMENT_ID_LENGTH = RequestLimits.MAX_DOCUMENT_ID_LENGTH
const val MAX_COLLECTION_LENGTH = RequestLimits.MAX_COLLECTION_LENGTH
const val MAX_PATH_LENGTH = RequestLimits.MAX_PATH_LENGTH
const val MAX_QUERY_BYTES = RequestLimits.MAX_QUERY_BYTES
const val MAX_AUTHOR_LENGTH = RequestLimits.MAX_AUTHOR_LENGTH
const val MAX_MESSAGE_LENGTH = RequestLimits.MAX_MESSAGE_LENGTH
const val MAX_REVISION_LENGTH = 128
const val MAX_BATCH_IDS = RequestLimits.MAX_BATCH_IDS
const val MAX_FRONTMATTER_ENTRIES = RequestLimits.MAX_FRONTMATTER_ENTRIES
const val MAX_FRONTMATTER_ARRAY_ITEMS = RequestLimits.MAX_FRONTMATTER_ARRAY_ITEMS
const val MAX_FRONTMATTER_DEPTH = RequestLimits.MAX_FRONTMATTER_DEPTH
const val MAX_FRONTMATTER_VALUE_BYTES = RequestLimits.MAX_FRONTMATTER_VALUE_BYTES
const val MAX_FRONTMATTER_BYTES = RequestLimits.MAX_FRONTMATTER_BYTES
const val MAX_SEARCH_FILTERS = RequestLimits.MAX_TAG_COUNT
const val MAX_COLLECTIONS_PER_REQUEST = RequestLimits.MAX_COLLECTIONS_PER_REQUEST
const val MAX_PAGE_LIMIT = RequestLimits.MAX_PAGE_LIMIT
const val MAX_PAGE_OFFSET = RequestLimits.MAX_PAGE_OFFSET
const val MAX_IDEMPOTENCY_KEY_LENGTH = RequestLimits.MAX_IDEMPOTENCY_KEY_LENGTH
const val MAX_CREDENTIAL_LENGTH = 4_096
const val MAX_REQUEST_DEPTH = RequestLimits.MAX_TRAVERSAL_DEPTH

/** How long an idempotent retry waits for the in-flight holder to publish. */
private val IDEMPOTENCY_IN_FLIGHT_WAIT: Duration = 5.seconds

private class RequestBodyTooLargeException : RuntimeException()

/**
 * A stored document is larger than a read is allowed to return.
 *
 * Writes are bounded by [MAX_CONTENT_BYTES], but a document can also arrive from
 * another writer, a mode transfer, or a file dropped into a vault, so the read
 * seam has to hold the same line: the limit protects the client from an
 * unbounded response body, not just the server from an unbounded request.
 */
private class ContentTooLargeException : RuntimeException()

/** Accepts or generates `X-Request-Id` and echoes it on the response. */
val RequestIdPlugin =
    createApplicationPlugin("RequestId") {
        onCall { call ->
            val id = requestIdOrGenerated(call.request.headers[HEADER_REQUEST_ID])
            call.attributes.put(requestIdKey, id)
            call.response.header(HEADER_REQUEST_ID, id)
        }
    }

private fun requestMetricsPlugin(metrics: MetricsRecorder, logger: TansekiLogger) =
    createApplicationPlugin("RequestMetrics") {
        onCall { call ->
            call.attributes.put(requestStartKey, System.nanoTime())
        }
        onCallRespond { call, _ ->
            val startedAt = call.attributes.getOrNull(requestStartKey) ?: return@onCallRespond
            val statusCode = call.response.status()?.value ?: 0
            val durationMillis = (System.nanoTime() - startedAt) / 1_000_000
            val requestId = call.requestId()
            val fields =
                mapOf(
                    "requestId" to requestId,
                    "correlationId" to requestId,
                    "status" to statusCode,
                    "durationMs" to durationMillis
                )
            if (statusCode >= 400) {
                logger.error("api request failed", fields = fields)
            } else {
                logger.info("api request completed", fields)
            }
            metrics.recordRequest(
                System.nanoTime() - startedAt,
                statusCode,
                if (statusCode >= 400) "http_$statusCode" else null
            )
        }
    }

internal fun ApplicationCall.requestId(): String = attributes.getOrNull(requestIdKey) ?: ""

/**
 * Canonical Tanseki `/v1` seam.
 *
 * Ids are path-derived and contain `/`, so documents are addressed in the
 * body/query via custom methods (`/documents:get`, `:upsert`, ...) rather than a
 * path segment. Routes delegate only to [QueryFacade].
 */
fun Application.storeApi(
    facade: QueryFacade,
    apiKey: String? = null,
    idempotency: IdempotencyStore = IdempotencyStore(),
    logger: TansekiLogger = TansekiLogger.Noop,
    authPolicy: ApiAuthPolicy? = null,
    metrics: MetricsRecorder? = null,
    maxRequestBodyBytes: Long = MAX_REQUEST_BODY_BYTES,
    cors: CorsPolicy = CorsPolicy(),
    /**
     * Change feed to serve [GET /v1/events] from.
     *
     * Null means the route answers `501`: a profile with no feed configured must
     * say so rather than open a stream that silently never emits, because a
     * client waiting on a change feed cannot tell that from a quiet vault.
     */
    changeFeed: ChangeFeed? = null
) {
    val policy = authPolicy ?: ApiAuthPolicy.forLegacyOrUnrestricted(apiKey)
    require(maxRequestBodyBytes > 0 && maxRequestBodyBytes <= MAX_REQUEST_BODY_BYTES) {
        "maxRequestBodyBytes is out of range"
    }
    val documentCommands =
        DocumentCommandService(
            facade = facade,
            onFrontmatterDegraded = { _, reason -> metrics?.recordFrontmatterDegraded(reason) },
            logger = logger
        )
    val deps =
        StoreApiDeps(
            facade = facade,
            policy = policy,
            idempotency = idempotency,
            logger = logger,
            metrics = metrics,
            maxRequestBodyBytes = maxRequestBodyBytes,
            changeFeed = changeFeed,
            documentCommands = documentCommands
        )
    installStoreApiPlugins(metrics, logger, maxRequestBodyBytes)
    routing {
        // Route-scoped so it wraps the route resolution below: a preflight for a
        // protected route is answered by CORS rather than reaching the route and
        // failing authentication first. No allowCredentials — the seam
        // authenticates with header-borne keys, so credentialed CORS would only
        // widen the surface.
        if (cors.enabled) {
            install(RoutingCORS) {
                cors.allowedHeaders.forEach(::allowHeader)
                cors.allowedMethods.forEach(::allowMethod)
                cors.exposedHeaders.forEach(::exposeHeader)
                // Exact origin match against the configured set. A predicate
                // rather than allowHost/anyHost because the allowlist is
                // scheme+host+port; allowHost would need that split and rebuilt.
                allowOrigins { origin -> origin in cors.allowedOrigins }
                maxAgeInSeconds = cors.preflightMaxAgeSeconds
            }
        }
        route("openapi.json") { openApi() }

        healthApi(deps)
        metricsApi(deps)

        documentListApi(deps)
        documentGetApi(deps)
        queryApi(deps)
        documentUpsertApi(deps)
        documentDeleteApi(deps)
        historyApi(deps)
        relationshipApi(deps)

        get(
            "/v1/events",
            {
                tags("events")
                summary = "Subscribe to committed document changes (SSE)"
                description =
                    "A `text/event-stream` of changes that have actually become searchable. " +
                    "Each event carries the feed's own `sequence`, so a client that " +
                    "reconnects can send the last one it saw as `Last-Event-ID` (or " +
                    "`?after=`) and receive only what it missed. A sequence that has " +
                    "aged out of the retained window yields a `resync` event instead of a " +
                    "silently incomplete stream.\n\n" +
                    "The feed is process-local: a daemon restart resets sequences, so a " +
                    "client resuming across one must reload rather than resume.\n\n" +
                    "Events are scoped to the collections the credential may read; a " +
                    "document outside that scope never appears, not even as a gap."
                protected = true
                securitySchemeNames("apiKey", "bearerAuth")
                request { correlationHeader() }
                response {
                    HttpStatusCode.OK to {
                        binaryBody()
                        header<String>("Content-Type") {
                            required = true
                            description = "Always text/event-stream."
                        }
                    }
                    HttpStatusCode.NotImplemented to { body<ErrorEnvelope>() }
                    HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                    HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
                }
            }
        ) {
            val principal = call.authenticate(deps.policy, ApiOperation.LIST) ?: return@get
            val feed = deps.changeFeed
            if (feed == null) {
                // A profile with no feed must say so. Opening a stream that never
                // emits is indistinguishable from a quiet vault, and a client
                // waiting on changes cannot tell the difference.
                call.error(
                    HttpStatusCode.NotImplemented,
                    "not_implemented",
                    "no change feed is configured for this profile"
                )
                return@get
            }
            // ScopedCollections intersects the requested collection with the
            // credential's own scope: `?collection=` can narrow an unscoped
            // credential but never widen a scoped one, and null means "every
            // collection this credential may read".
            val requested =
                call.request.queryParameters["collection"]
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            validateCollection(requested)
            val collections = principal.scopedCollections(requested)
            val after =
                call.request.headers["Last-Event-ID"]
                    ?.trim()
                    ?.toLongOrNull()
                    ?: call.request.queryParameters["after"]
                        ?.trim()
                        ?.toLongOrNull()
            val forceResync = call.request.queryParameters["resync"]?.equals("true", ignoreCase = true) == true

            // Proxies buffer by default, which would hold events until the
            // response ended — i.e. never, for a stream that does not end.
            call.response.header("Cache-Control", "no-cache")
            call.response.header("X-Accel-Buffering", "no")
            call.respondTextWriter(ContentType.Text.EventStream) {
                // Bridge the feed's flow to a channel so an idle stream can send a
                // heartbeat: a client or proxy that sees nothing for a while will
                // otherwise decide the connection is dead and reconnect in a loop.
                //
                // Bounded on purpose. An unbounded bridge lets the subscription
                // coroutine drain the feed without ever suspending, so the feed's own
                // slow-subscriber detection never fires and a client that stops
                // reading makes the daemon buffer events until it runs out of memory.
                // With a bounded channel the writer's backpressure reaches the feed,
                // which then tells the subscriber to resync.
                val events =
                    Channel<ChangeEvent>(
                        capacity = DEFAULT_CHANGE_SUBSCRIBER_BUFFER,
                        onBufferOverflow = BufferOverflow.SUSPEND
                    )
                val subscription =
                    launch {
                        try {
                            feed.subscribe(after, collections, forceResync).collect { events.send(it) }
                        } finally {
                            // The feed closes a subscriber it told to resync; closing
                            // the bridge here lets the writer loop end instead of
                            // waiting forever for events that will never come.
                            events.close()
                        }
                    }
                try {
                    var live = true
                    while (live) {
                        val received =
                            withTimeoutOrNull(EVENT_HEARTBEAT_MILLIS) {
                                events.receiveCatching()
                            }
                        when {
                            received == null -> {
                                write(": keep-alive\n\n")
                            }

                            received.isClosed -> {
                                // The subscription ended (a resync was delivered, or
                                // the feed dropped a stalled subscriber); the stream
                                // is over.
                                live = false
                            }

                            else -> {
                                // `id:` is what makes Last-Event-ID resume work, so
                                // it must be the sequence and nothing else.
                                val event = received.getOrNull()
                                if (event == null) {
                                    live = false
                                } else {
                                    write("id: ${event.sequence}\n")
                                    write("event: ${event.kind.name.lowercase()}\n")
                                    write(
                                        "data: " +
                                            json.encodeToString(
                                                ChangeEventDto.serializer(),
                                                event.toDto()
                                            ) + "\n\n"
                                    )
                                }
                            }
                        }
                        flush()
                    }
                } finally {
                    // Runs on disconnect too, which is what keeps a dropped client
                    // from leaving a subscriber attached to the feed forever.
                    subscription.cancel()
                    events.close()
                }
            }
        }

        blobsApi(deps)
        deletedApi(deps)
        documentRestoreApi(deps)
        documentRenameApi(deps)
        searchApi(deps)
    }
}

private val json =
    Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }

/**
 * Wraps a request-body schema so the contract declares a closed object: a
 * single-element `allOf` keeps the generated model reference intact while
 * `additionalProperties: false` documents the strict decoding the server
 * enforces.
 */
private fun closedRequestSchema(schema: Schema<*>?): Schema<*>? {
    if (schema == null || schema.additionalProperties != null) return schema
    if (schema.`$ref` == null && schema.properties.isNullOrEmpty()) return schema
    val referenced = schema.`$ref`
    return Schema<Any>()
        .apply {
            if (referenced != null) {
                `allOf` = listOf(Schema<Any>().apply { `$ref` = referenced })
            } else {
                type = schema.type
                properties = schema.properties
                required = schema.required
            }
            additionalProperties = false
        }
}

private fun RequestConfig.correlationHeader() {
    headerParameter<String>(HEADER_REQUEST_ID) {
        required = false
        description = "Caller-provided correlation id; generated when omitted."
    }
}

private inline fun <reified T> RequestConfig.requiredBody(description: String) {
    body<T> {
        required = true
        this.description = description
    }
}

/**
 * Declares a raw binary request body.
 *
 * Attachment bytes are not JSON: describing them as a `ByteArray` would publish
 * them as an array of integers, which is base64-inflation by another name and
 * would invite a client to send megabytes of JSON. `string`/`binary` with an
 * explicit octet-stream media type is the honest description.
 */
private fun RequestConfig.requiredBinaryBody(description: String) {
    body(
        Schema<Any>()
            .type("string")
            .format("binary")
    ) {
        required = true
        this.description = description
        mediaTypes(ContentType.Application.OctetStream)
    }
}

/** Declares a raw binary response body: see [requiredBinaryBody]. */
private fun ResponseConfig.binaryBody() {
    body(
        Schema<Any>()
            .type("string")
            .format("binary")
    ) {
        mediaTypes(ContentType.Application.OctetStream)
    }
}

private fun RequestConfig.writeHeaders() {
    correlationHeader()
    headerParameter<String>("If-Match") {
        required = false
        description = "Current revision ETag required for this write."
    }
    headerParameter<String>(HEADER_IDEMPOTENCY) {
        required = false
        description = "Request-bound key used to reserve and replay a write."
    }
}

private fun ApplicationCall.limit(): Int {
    val raw = request.queryParameters["limit"] ?: return 50
    val value = raw.toIntOrNull() ?: invalid("limit must be an integer")
    if (value !in 1..MAX_PAGE_LIMIT) invalid("limit must be between 1 and $MAX_PAGE_LIMIT")
    return value
}

private fun ApplicationCall.offset(): Int {
    val raw = request.queryParameters["offset"] ?: return 0
    val value = raw.toIntOrNull() ?: invalid("offset must be an integer")
    if (value !in 0..MAX_PAGE_OFFSET) invalid("offset must be between 0 and $MAX_PAGE_OFFSET")
    return value
}

/**
 * Opaque cursor for keyset pagination: the base64url-encoded id the next page
 * starts after.
 *
 * Opaque to clients by design. Ids are path-derived and contain `/`, `:` and
 * `.`, none of which belong unescaped in a query value, and a client that
 * parses a cursor has coupled itself to the ordering behind it. Treat it as a
 * token to echo back, not a value to construct.
 */
private fun encodeCursor(id: DocId): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(id.value.toByteArray(StandardCharsets.UTF_8))

/**
 * The idempotency fingerprint for a restore: the route, key, precondition, and
 * body together, so reusing a key with a different restore is a conflict rather
 * than a silent replay of someone else's write.
 */
private fun restoreFingerprint(
    call: ApplicationCall,
    request: RestoreDocumentRequest,
    credentialId: String
): String =
    buildString {
        append("/v1/documents:restore")
        append('\u0000')
        append(credentialId)
        append('\u0000')
        append(call.request.headers[HEADER_IDEMPOTENCY].orEmpty())
        append('\u0000')
        append(call.request.headers["If-Match"].orEmpty())
        append('\u0000')
        append(json.encodeToString(RestoreDocumentRequest.serializer(), request))
    }

/**
 * The idempotency fingerprint for a rename: the route, key, precondition and body
 * together. Reusing a key with a different target is a conflict rather than a
 * replay of someone else's write — which matters more here than for most routes,
 * because a replayed rename would be replayed against a document that has already
 * moved.
 */
private fun renameFingerprint(
    call: ApplicationCall,
    request: RenameDocumentRequest,
    credentialId: String
): String =
    buildString {
        append("/v1/documents:rename")
        append('\u0000')
        append(credentialId)
        append('\u0000')
        append(call.request.headers[HEADER_IDEMPOTENCY].orEmpty())
        append('\u0000')
        append(call.request.headers["If-Match"].orEmpty())
        append('\u0000')
        append(json.encodeToString(RenameDocumentRequest.serializer(), request))
    }

/** Idle interval before a comment heartbeat keeps an event stream visibly alive. */
private const val EVENT_HEARTBEAT_MILLIS = 15_000L

private fun ChangeEvent.toDto() =
    ChangeEventDto(
        sequence = sequence,
        kind = kind.name.lowercase(),
        documentId = documentId?.value,
        collection = collection?.value,
        revision = revision?.value,
        contentHash = contentHash
    )

/** Tombstones are bounded: a trash view is a recovery affordance, not a full listing. */
private const val MAX_DELETED_PAGE_LIMIT = 200

private fun validateRestoreRequest(request: RestoreDocumentRequest) {
    validateDocumentRequest(request.id, request.collection)
    RequestValidation.validateMessage(request.message)
    RequestValidation.validateAuthor(request.author)
    RequestValidation.validateRevision(request.ifRevision)
}

/**
 * A rename is validated as strictly as a write on both ids.
 *
 * `newId` goes through [validateDocumentId] because it becomes a real document id
 * and a real path: accepting a value the id type would reject here would turn a
 * client mistake into a store error after the request was already accepted.
 * Renaming onto the same id is refused with the same argument the port uses —
 * there is nothing to move, and treating it as a no-op would make a replay look
 * like a successful new revision.
 */
private fun validateRenameRequest(request: RenameDocumentRequest) {
    validateDocumentRequest(request.id, request.collection)
    validateDocumentId(request.newId)
    if (request.id == request.newId) invalid("newId must differ from id")
    RequestValidation.validateMessage(request.message)
    RequestValidation.validateAuthor(request.author)
    RequestValidation.validateRevision(request.ifRevision)
}

/**
 * A path prefix is a literal path fragment, not a pattern. It is validated for
 * length and for the LIKE metacharacters the store escapes, and normalized so
 * `notes`, `notes/` and `./notes` cannot each become a different subtree.
 */
private fun validatePathPrefix(value: String?) {
    if (value == null) return
    if (value.length > MAX_PATH_LENGTH) invalid("pathPrefix is too long")
    if (value.contains('\u0000')) invalid("pathPrefix contains a control character")
}

private fun decodeCursor(raw: String): DocId {
    // Bound the encoded length before decoding: a huge base64 token must be
    // rejected without first allocating its decoded form.
    if (raw.length > MAX_DOCUMENT_ID_LENGTH * 2) invalid("cursor is not a valid pagination token")
    val decoded =
        runCatching { String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8) }
            .getOrElse { invalid("cursor is not a valid pagination token") }
    if (decoded.isBlank() || decoded.length > MAX_DOCUMENT_ID_LENGTH) invalid("cursor is not a valid pagination token")
    return DocId(decoded)
}

private fun validateDocumentId(value: String) = RequestValidation.validateDocumentId(value)

private fun validateDocumentRequest(id: String, collection: String?) {
    validateDocumentId(id)
    validateCollection(collection)
}

private fun validateCollection(value: String?) = RequestValidation.validateCollection(value)

private fun validateOptionalText(name: String, value: String?, maxBytes: Int) {
    if (value == null) return
    if (value.isBlank()) invalid("$name must not be blank")
    if (value.toByteArray(StandardCharsets.UTF_8).size > maxBytes) invalid("$name is too long")
    if (value.any(Char::isISOControl)) invalid("$name contains control characters")
}

private fun validateUpsertRequest(request: UpsertDocumentRequest) {
    validateDocumentRequest(request.id, request.collection)
    RequestValidation.validateRevision(request.ifRevision)
    validateOptionalText("contentHash", request.contentHash, MAX_REVISION_LENGTH)
    RequestValidation.validateContent(request.content)
    RequestValidation.validatePath(request.path)
    RequestValidation.validateAuthor(request.author)
    RequestValidation.validateMessage(request.message)
    request.frontmatter?.let { frontmatter ->
        if (frontmatter.size > MAX_FRONTMATTER_ENTRIES) invalid("too many frontmatter entries")
        if (JsonObject(frontmatter)
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
                .size
                .toLong() >
            MAX_FRONTMATTER_BYTES
        ) {
            invalid("frontmatter is too large")
        }
        frontmatter.forEach { (key, value) ->
            if (key.isBlank()) invalid("frontmatter key must not be blank")
            if (key.toByteArray(StandardCharsets.UTF_8).size > MAX_COLLECTION_LENGTH) {
                invalid("frontmatter key is too long")
            }
            validateTypedFrontmatterList(key, value)
            validateFrontmatterValue(value, key)
        }
    }
}

/**
 * `tags` is the exception to the structural rule, and deliberately so.
 *
 * It is modelled as a `List<String>`, so a collection underneath it would be
 * accepted here and then silently absent from the typed view. Refused instead.
 */
private fun validateTypedFrontmatterList(key: String, value: JsonElement) {
    if (key !in TYPED_FRONTMATTER_LIST_KEYS) return
    val array = value as? JsonArray ?: return
    array.forEach { element ->
        if (element !is JsonPrimitive || !element.isString) {
            invalid("frontmatter list '$key' must contain strings only")
        }
    }
}

/**
 * Frontmatter holds scalars, arrays of scalars, and nested objects and arrays of
 * objects. Nested values are kept as structure rather than flattened to an opaque
 * string, so the document round-trips and every leaf stays filterable.
 */
private fun validateFrontmatterValue(
    value: JsonElement,
    key: String
) {
    when (value) {
        is JsonObject -> {
            value.forEach { (nestedKey, nested) ->
                if (nestedKey.isBlank()) invalid("frontmatter key must not be blank")
                if (nestedKey.toByteArray(StandardCharsets.UTF_8).size > MAX_COLLECTION_LENGTH) {
                    invalid("frontmatter key is too long")
                }
                validateFrontmatterValue(nested, nestedKey)
            }
        }

        is JsonArray -> {
            value.forEach { element -> validateFrontmatterValue(element, key) }
        }

        is JsonPrimitive -> {
            validateJsonElement(value)
        }
    }
}

private fun validateDeleteRequest(request: DeleteDocumentRequest) {
    validateDocumentRequest(request.id, request.collection)
    validateOptionalText("ifRevision", request.ifRevision, MAX_REVISION_LENGTH)
    validateOptionalText("author", request.author, MAX_AUTHOR_LENGTH)
    if (request.message
            ?.toByteArray(StandardCharsets.UTF_8)
            ?.size
            ?.let { it > MAX_MESSAGE_LENGTH } == true
    ) {
        invalid("message is too long")
    }
}

private fun validateHistoryRequest(request: HistoryRequest) {
    request.limit?.let { if (it !in 1..MAX_PAGE_LIMIT) invalid("limit must be between 1 and $MAX_PAGE_LIMIT") }
    request.offset?.let { if (it !in 0..MAX_PAGE_OFFSET) invalid("offset must be between 0 and $MAX_PAGE_OFFSET") }
}

private fun validateJsonElement(value: JsonElement, depth: Int = 0) {
    if (depth > MAX_FRONTMATTER_DEPTH) invalid("frontmatter is too deeply nested")
    when (value) {
        is JsonArray -> validateJsonArray(value, depth)
        is JsonObject -> validateJsonObject(value, depth)
        is JsonPrimitive -> validateJsonPrimitive(value)
    }
}

private fun validateJsonArray(value: JsonArray, depth: Int) {
    if (value.size > MAX_FRONTMATTER_ARRAY_ITEMS) invalid("frontmatter array is too large")
    value.forEach { validateJsonElement(it, depth + 1) }
}

private fun validateJsonObject(value: JsonObject, depth: Int) {
    if (value.size > MAX_FRONTMATTER_ENTRIES) invalid("frontmatter object is too large")
    value.forEach { (key, element) ->
        validateJsonKey(key)
        validateJsonElement(element, depth + 1)
    }
}

private fun validateJsonKey(key: String) {
    if (key.isBlank()) invalid("request field name must not be blank")
    if (key.toByteArray(StandardCharsets.UTF_8).size > MAX_COLLECTION_LENGTH) {
        invalid("frontmatter key is too long")
    }
}

private fun validateJsonPrimitive(value: JsonPrimitive) {
    if (value is JsonNull) return
    if (value.content.any(Char::isISOControl)) {
        invalid("frontmatter value contains control characters")
    }
    if (value.content
            .toByteArray(StandardCharsets.UTF_8)
            .size
            .toLong() > MAX_FRONTMATTER_VALUE_BYTES
    ) {
        invalid("frontmatter value is too long")
    }
}

/**
 * Reads the body as raw bytes under a hard ceiling.
 *
 * Bounded while streaming rather than after, so an oversized body is refused
 * without ever being fully buffered. A declared Content-Length above the
 * ceiling short-circuits before a single byte is read.
 */
private suspend fun ApplicationCall.readBodyBounded(maxBytes: Long): ByteArray {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && (declared < 0 || declared > maxBytes)) throw RequestBodyTooLargeException()
    val channel = request.receiveChannel()
    val output = ByteArrayOutputStream(minOf(maxBytes, 8_192L).toInt())
    val buffer = ByteArray(8_192)
    var total = 0L
    while (true) {
        val count = channel.readAvailable(buffer)
        if (count == -1) break
        if (count > 0) {
            total += count
            if (total > maxBytes) throw RequestBodyTooLargeException()
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}

private suspend inline fun <reified T> ApplicationCall.receiveBounded(maxBytes: Long): T {
    val text = readBodyBounded(maxBytes).toString(StandardCharsets.UTF_8)
    val element = json.parseToJsonElement(text)
    validateRequestJson(element)
    return json.decodeFromJsonElement(serializer(), element)
}

private fun validateRequestJson(value: JsonElement, depth: Int = 0) {
    if (depth > MAX_FRONTMATTER_DEPTH) invalid("request body is too deeply nested")
    if (value is JsonArray) {
        if (value.size > MAX_BATCH_IDS) invalid("request body contains too many list entries")
        value.forEach { validateRequestJson(it, depth + 1) }
    } else if (value is JsonObject) {
        if (value.size > MAX_FRONTMATTER_ENTRIES) invalid("request body contains too many fields")
        value.forEach { (key, element) ->
            if (key.isBlank()) invalid("request field name must not be blank")
            if (key.toByteArray(StandardCharsets.UTF_8).size > MAX_COLLECTION_LENGTH) {
                invalid("request field name is too long")
            }
            validateRequestJson(element, depth + 1)
        }
    }
}

private suspend fun ApplicationCall.replay(stored: StoredResponse) {
    stored.headers.forEach { (name, value) -> response.header(name, value) }
    respondText(stored.body, ContentType.Application.Json, HttpStatusCode.fromValue(stored.status))
}

private fun ApplicationCall.expectedIfMatch(
    existing: Document?,
    bodyRevision: String?,
    defaultToCurrent: Boolean = false
): String? {
    val raw = request.headers["If-Match"]?.trim()?.takeIf(String::isNotEmpty)
    if (raw == null) return bodyRevision ?: if (defaultToCurrent) existing?.revision?.value else null
    val candidates = parseIfMatch(raw)
    val current =
        existing?.revision?.value ?: throw PreconditionFailedException("If-Match requires an existing document")
    if (raw == "*") {
        if (bodyRevision != null && bodyRevision != current) invalid("If-Match and ifRevision disagree")
        return current
    }
    val match =
        candidates.firstOrNull { it.trim('"', ' ') == current }
            ?: throw PreconditionFailedException("If-Match does not match current revision")
    if (bodyRevision != null && bodyRevision != match) invalid("If-Match and ifRevision disagree")
    return current
}

/**
 * The non-null precondition for a caller that has already established [existing]
 * is live, so the value flows through the type rather than being asserted.
 */
private fun ApplicationCall.expectedIfMatchForExisting(existing: Document, bodyRevision: String?): String =
    expectedIfMatch(existing, bodyRevision, defaultToCurrent = true)
        ?: throw PreconditionFailedException("If-Match requires an existing document")

private fun parseIfMatch(raw: String): List<String> {
    if (raw.length > MAX_REVISION_LENGTH * 4 || raw.any(Char::isISOControl)) {
        invalid("If-Match is too long or contains control characters")
    }
    val candidates = raw.split(',').map(String::trim)
    if (candidates.size > 16) invalid("If-Match must contain at most 16 revisions")
    if (candidates.any { it.startsWith("W/", ignoreCase = true) }) invalid("weak If-Match values are not supported")
    return candidates
}

private fun requestFingerprint(vararg values: String): String =
    Bytes.hex(
        MessageDigest
            .getInstance("SHA-256")
            .digest(values.joinToString("\u0000").toByteArray(StandardCharsets.UTF_8))
    )

/**
 * Namespaces an `Idempotency-Key` by operation and credential.
 *
 * The idempotency store is shared across credentials, so without the credential
 * in the key one principal's key could replay (or conflict with) another
 * principal's write. The operation separates routes that reuse the same
 * [ApiOperation] value (restore and rename both authenticate as UPSERT).
 */
private fun scopedIdempotencyKey(
    operation: String,
    principal: ApiPrincipal,
    raw: String?
): String? = raw?.let { "$operation:${principal.credentialId}:$it" }

private fun Document.visibleTo(principal: ApiPrincipal, requested: String?): Boolean =
    principal.allows(collection.value) && (requested == null || collection.value == requested)

private fun gokorei.tanseki.core.domain.DocRef.visibleTo(principal: ApiPrincipal, requested: String?): Boolean =
    principal.allows(collection.value) && (requested == null || collection.value == requested)

private suspend fun ApplicationCall.ensureCollection(principal: ApiPrincipal, requested: String?): Boolean {
    if (principal.allows(requested)) return true
    notFound()
    return false
}

/**
 * Rejects a scope that resolves to no collections. An empty scope is never
 * widened to the whole store: a credential that grants nothing must see
 * nothing, and a store query with an empty collection set means "unfiltered".
 */
private suspend fun ApplicationCall.ensureScope(collections: Set<String>): Boolean {
    if (collections.isNotEmpty()) return true
    error(HttpStatusCode.Forbidden, "forbidden", "access denied")
    return false
}

/**
 * Reserves an idempotency key, serializing behind an in-flight holder so a
 * retry replays the original response instead of failing or double-writing.
 */
private suspend fun settle(
    store: IdempotencyStore,
    key: String,
    fingerprint: String
): IdempotencyReservation =
    withContext(Dispatchers.IO) {
        val reserved = store.reserve(key, fingerprint)
        if (reserved !is IdempotencyReservation.InFlight) return@withContext reserved
        val settled =
            store.awaitCompletion(key, fingerprint, IDEMPOTENCY_IN_FLIGHT_WAIT)
                ?: store.reserve(key, fingerprint)
        if (settled is IdempotencyReservation.InFlight) {
            IdempotencyReservation.Conflict("A request with this Idempotency-Key is in flight")
        } else {
            settled
        }
    }

/**
 * Records the response of an already-committed write, best-effort.
 *
 * By the time this runs the mutation has persisted, so a reservation the store
 * evicted for capacity or let expire must not turn the request into a 500: the
 * caller would retry and double-write, which is exactly what idempotency exists
 * to prevent. A dropped completion is a lost optimization, not a failed write,
 * so it is logged and the committed response is returned regardless.
 */
internal fun completeIdempotency(
    idempotency: IdempotencyStore,
    key: String,
    fingerprint: String,
    token: String,
    response: StoredResponse,
    logger: TansekiLogger
) {
    if (idempotency.complete(key, fingerprint, token, response)) return
    logger.warn(
        "idempotency completion dropped (reservation evicted or expired); returning the committed response",
        mapOf("operation" to "write")
    )
}

private suspend fun ApplicationCall.notFound() {
    error(HttpStatusCode.NotFound, "not_found", "document not found")
}

private suspend fun ApplicationCall.authenticate(
    policy: ApiAuthPolicy,
    operation: ApiOperation? = null
): ApiPrincipal? {
    val bearer =
        request.headers["Authorization"]
            ?.trim()
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.trim()
    val provided = request.headers[HEADER_API_KEY]?.trim()?.takeIf(String::isNotBlank) ?: bearer
    if (provided != null && provided.length > MAX_CREDENTIAL_LENGTH) {
        invalid("API credential is too long")
    }
    val principal = policy.authenticate(provided)
    if (principal == null) {
        error(HttpStatusCode.Unauthorized, "unauthenticated", "authentication required")
        return null
    }
    if (operation != null && !principal.allows(operation)) {
        error(HttpStatusCode.Forbidden, "forbidden", "access denied")
        return null
    }
    return principal
}

/**
 * Applies the caller's collection filter to a provider that only knows the
 * credential's own scope.
 *
 * Document *and* edge counts are recomputed from the store, so a single
 * collection never answers with another collection's graph; the per-collection
 * breakdown carries both. Everything else in the snapshot is left as the
 * provider reported it: it is pipeline-wide, and a provider able to narrow it
 * answers through [ScopedMetricsProvider] instead of this path.
 */
private suspend fun MetricsResponse.withScope(
    facade: QueryFacade,
    collections: Set<String>?
): MetricsResponse {
    val scope = MetricsScope(collections?.sorted(), collections != null)
    if (collections == null) return copy(scope = scope)
    val scoped =
        collections.sorted().map { collection ->
            val refs = facade.list(Collection(collection))
            CollectionMetrics(
                collection = collection,
                currentDocuments = refs.size,
                currentEdges = refs.sumOf { facade.neighbors(it.id).size }
            )
        }
    return copy(
        currentDocuments = scoped.sumOf { it.currentDocuments ?: 0 },
        currentEdges = scoped.sumOf { it.currentEdges ?: 0 },
        scope = scope,
        collections = scoped
    )
}

private suspend fun search(
    facade: QueryFacade,
    query: String,
    mode: SearchMode,
    filters: Filters,
    limit: Int,
    offset: Int
): List<gokorei.tanseki.core.ports.Hit> =
    if (mode == SearchMode.Hybrid) {
        facade.searchHybridPage(query, filters, limit, offset)
    } else {
        facade.searchTextPage(query, filters, limit, offset)
    }

private fun logApiFailure(logger: TansekiLogger, call: ApplicationCall, cause: Throwable) {
    val requestId = call.requestId()
    logger.error(
        "api request failed",
        fields =
            mapOf(
                "requestId" to requestId,
                "correlationId" to requestId,
                "errorType" to (cause::class.simpleName ?: "error")
            )
    )
}

private fun etag(revision: String): String = "\"$revision\""

private fun invalid(message: String): Nothing = throw InvalidInputException(message)

private suspend fun ApplicationCall.error(
    status: HttpStatusCode,
    code: String,
    message: String,
    detail: String? = null
) {
    respond(status, ErrorEnvelope(ErrorBody(code, message, detail)))
}

private const val INVALID_REQUEST = "invalid request"

private val TYPED_FRONTMATTER_LIST_KEYS = setOf("tags")

/**
 * Every route handler's shared wiring, bundled so a per-route [Route] extension
 * does not take an eight-parameter signature.
 */
private data class StoreApiDeps(
    val facade: QueryFacade,
    val policy: ApiAuthPolicy,
    val idempotency: IdempotencyStore,
    val logger: TansekiLogger,
    val metrics: MetricsRecorder?,
    val maxRequestBodyBytes: Long,
    val changeFeed: ChangeFeed?,
    val documentCommands: DocumentCommandService
)

/** Liveness and readiness probes. */
private fun Route.healthApi(deps: StoreApiDeps) {
    get(
        "/v1/health",
        {
            tags("health")
            summary = "Liveness check"
            description = "Unauthenticated process-only liveness probe."
            protected = false
            request { correlationHeader() }
            response { HttpStatusCode.OK to { body<HealthDto>() } }
        }
    ) { call.respond(HealthDto("ok")) }

    get(
        "/v1/live",
        {
            tags("health")
            summary = "Liveness check"
            description = "Unauthenticated process-only liveness probe."
            protected = false
            request { correlationHeader() }
            response { HttpStatusCode.OK to { body<HealthDto>() } }
        }
    ) { call.respond(HealthDto("ok")) }

    get(
        "/v1/ready",
        {
            tags("health")
            summary = "Readiness check"
            description = "Unauthenticated dependency, projection, backlog, and watcher readiness."
            protected = false
            request { correlationHeader() }
            response {
                HttpStatusCode.OK to { body<ReadinessResponse>() }
                HttpStatusCode.ServiceUnavailable to { body<ReadinessResponse>() }
            }
        }
    ) {
        val recorder = deps.metrics
        if (recorder == null) {
            call.error(HttpStatusCode.ServiceUnavailable, "readiness_unavailable", "readiness is unavailable")
            return@get
        }
        // Cheap on purpose: this route is unauthenticated, and a full
        // readiness() call would count documents by scanning the store. An
        // anonymous probe must not be able to make the daemon scan it; counts
        // are served under authentication via /v1/metrics.
        val snapshot = recorder.readinessCheap().copy(requestId = call.requestId())
        if (snapshot.ready) {
            call.respond(snapshot)
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, snapshot)
        }
    }
}

/** Authoritative operational metrics. */
private fun Route.metricsApi(deps: StoreApiDeps) {
    get(
        "/v1/metrics",
        {
            tags("metrics")
            summary = "Read operational metrics"
            description = "Authoritative current projection, backlog, watcher, and latency signals."
            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                correlationHeader()
                queryParameter<String>("collection") { description = "Collection scope for this metrics read." }
            }
            response {
                HttpStatusCode.OK to { body<MetricsResponse>() }
                HttpStatusCode.ServiceUnavailable to { body<ErrorEnvelope>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
                HttpStatusCode.NotFound to { body<ErrorEnvelope>() }
            }
        }
    ) {
        val principal = call.authenticate(deps.policy, ApiOperation.METRICS) ?: return@get
        val requested = call.request.queryParameters["collection"]
        validateCollection(requested)
        if (!call.ensureCollection(principal, requested)) return@get
        val scoped = principal.scopedCollections(requested)
        if (scoped != null && !call.ensureScope(scoped)) return@get
        val recorder = deps.metrics
        val request =
            ScopedMetricsRequest(
                credentialId = principal.credentialId,
                collections = scoped,
                principal = principal
            )
        val snapshot =
            when (recorder) {
                is ScopedMetricsProvider -> recorder.scopedMetrics(request)
                else -> recorder?.metrics(request)
            }
        if (snapshot == null) {
            call.error(HttpStatusCode.ServiceUnavailable, "metrics_unavailable", "metrics are unavailable")
        } else if (recorder is ScopedMetricsProvider) {
            val scope = MetricsScope(request.collections?.sorted(), request.collections != null)
            call.respond(snapshot.copy(scope = snapshot.scope ?: scope))
        } else {
            call.respond(snapshot.withScope(deps.facade, request.collections))
        }
    }
}

/** Batch fetch. */
private fun Route.queryApi(deps: StoreApiDeps) {
    post(
        "/v1/documents:query",
        {
            tags("documents")
            summary = "Get many documents"
            description = "Batch-fetch documents by id; missing ids are omitted from the response."
            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                correlationHeader()
                requiredBody<QueryDocumentsRequest>("Document ids and optional collection.")
            }
            response {
                HttpStatusCode.OK to { body<QueryDocumentsResponse>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
            }
        }
    ) {
        val principal = call.authenticate(deps.policy, ApiOperation.QUERY) ?: return@post
        val request = call.receiveBounded<QueryDocumentsRequest>(deps.maxRequestBodyBytes)
        validateCollection(request.collection)
        if (request.ids.isEmpty()) invalid("ids must not be empty")
        if (request.ids.size > MAX_BATCH_IDS) invalid("too many document ids")
        request.ids.forEach { validateDocumentId(it) }
        if (!call.ensureCollection(principal, request.collection)) return@post
        val documents =
            deps.facade
                .getMany(request.ids.map(::DocId))
                .filter { it.visibleTo(principal, request.collection) }
        call.respond(QueryDocumentsResponse(documents.map { it.toDto() }))
    }
}

/** Revision history. */
private fun Route.historyApi(deps: StoreApiDeps) {
    post(
        "/v1/documents:history",
        {
            tags("documents")
            summary = "Document history"
            description = "Revision list including the deletion revision, oldest-first and paginated."
            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                correlationHeader()
                requiredBody<HistoryRequest>("Document id, collection, and pagination bounds.")
            }
            response {
                HttpStatusCode.OK to { body<HistoryResponse>() }
                HttpStatusCode.NotFound to { body<ErrorEnvelope>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
            }
        }
    ) {
        val principal = call.authenticate(deps.policy, ApiOperation.HISTORY) ?: return@post
        val request = call.receiveBounded<HistoryRequest>(deps.maxRequestBodyBytes)
        validateDocumentRequest(request.id, request.collection)
        validateHistoryRequest(request)
        if (!call.ensureCollection(principal, request.collection)) return@post
        val id = DocId(request.id)
        val owner = deps.facade.historyOwner(id)
        if (owner == null || !owner.visibleTo(principal, request.collection)) {
            call.notFound()
            return@post
        }
        val limit = request.limit ?: 100
        val offset = request.offset ?: 0
        val page = deps.facade.historyPage(id, limit, offset)
        call.respond(
            HistoryResponse(
                revisions = page.items.map { it.toDto() },
                total = page.total,
                limit = limit,
                offset = offset,
                hasMore = page.hasMore
            )
        )
    }
}

/** Graph traversal, both directions. */
private fun Route.relationshipApi(deps: StoreApiDeps) {
    post(
        "/v1/documents:traverse",
        {
            tags("documents")
            summary = "Traverse the graph"
            description =
                "Walk typed edges (links-to, references, embeds, mentions) from a document. " +
                "`rel` names a relationship type, not a frontmatter key: `files`, `repo`, `pr`, " +
                "`jira` and `author` say where an edge comes from and are rejected with a 400 " +
                "naming the four valid values rather than answered with an empty list."
            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                correlationHeader()
                requiredBody<TraverseRequest>("Document id, relationship, depth, and collection.")
            }
            response {
                HttpStatusCode.OK to { body<TraverseResponse>() }
                HttpStatusCode.BadRequest to { body<ErrorEnvelope>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
            }
        }
    ) {
        val principal = call.authenticate(deps.policy, ApiOperation.TRAVERSE) ?: return@post
        val request = call.receiveBounded<TraverseRequest>(deps.maxRequestBodyBytes)
        validateDocumentRequest(request.id, request.collection)
        if (request.depth != null &&
            request.depth !in 0..MAX_REQUEST_DEPTH
        ) {
            invalid("traversal depth is out of range")
        }
        if (!call.ensureCollection(principal, request.collection)) return@post
        val existing = deps.facade.get(DocId(request.id))
        if (existing == null || !existing.visibleTo(principal, request.collection)) {
            call.respond(TraverseResponse(emptyList()))
            return@post
        }
        val rel = request.rel?.toRelType() ?: RelTypes.LinksTo
        val depth = request.depth ?: 3
        val ids =
            deps.facade
                .traverse(DocId(request.id), rel, depth)
                .mapNotNull { id ->
                    val target = deps.facade.get(id)
                    target?.takeIf { it.visibleTo(principal, null) }?.id
                }.map { it.value }
        call.respond(TraverseResponse(ids))
    }

    post(
        "/v1/documents:backlinks",
        {
            tags("documents")
            summary = "List documents linking to a document"
            description =
                "Return the documents that link TO a document, optionally narrowed to one relationship " +
                "(links-to, references, embeds, mentions). " +
                "The reverse of documents:traverse."
            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                correlationHeader()
                requiredBody<BacklinksRequest>("Document id, optional relationship, and collection.")
            }
            response {
                HttpStatusCode.OK to { body<BacklinksResponse>() }
                HttpStatusCode.BadRequest to { body<ErrorEnvelope>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
            }
        }
    ) {
        // TRAVERSE, not a new operation: a credential authorized to walk the
        // graph is authorized to ask who is tied to it. Adding an enum entry
        // would hand it to every existing credential via ApiOperation.entries.
        val principal = call.authenticate(deps.policy, ApiOperation.TRAVERSE) ?: return@post
        val request = call.receiveBounded<BacklinksRequest>(deps.maxRequestBodyBytes)
        validateDocumentRequest(request.id, request.collection)
        if (!call.ensureCollection(principal, request.collection)) return@post
        val existing = deps.facade.get(DocId(request.id))
        if (existing == null || !existing.visibleTo(principal, request.collection)) {
            call.respond(BacklinksResponse(emptyList()))
            return@post
        }
        val rel = request.rel?.toRelType()
        val ids =
            deps.facade
                .backlinks(DocId(request.id), rel)
                .mapNotNull { id ->
                    val source = deps.facade.get(id)
                    source?.takeIf { it.visibleTo(principal, null) }?.id
                }.map { it.value }
        call.respond(BacklinksResponse(ids))
    }
}

/** Attachments, content-addressed. */
private fun Route.blobsApi(deps: StoreApiDeps) {
    post(
        "/v1/blobs",
        {
            tags("attachments")
            summary = "Upload an attachment"
            description =
                "Stores the request body verbatim and returns its content-addressed " +
                "reference. Uploading identical bytes twice yields the same reference. " +
                "The body is read as raw binary, never base64 inside JSON."
            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                correlationHeader()
                requiredBinaryBody(
                    "Raw attachment bytes, read verbatim. At most " +
                        "${RequestLimits.MAX_ATTACHMENT_BYTES} bytes."
                )
            }
            response {
                HttpStatusCode.OK to { body<BlobRefDto>() }
                HttpStatusCode.PayloadTooLarge to { body<ErrorEnvelope>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
            }
        }
    ) {
        call.authenticate(deps.policy, ApiOperation.UPSERT) ?: return@post
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > RequestLimits.MAX_ATTACHMENT_BYTES) {
            throw RequestBodyTooLargeException()
        }
        val bytes = call.readBodyBounded(RequestLimits.MAX_ATTACHMENT_BYTES)
        val ref = deps.facade.putBlob(bytes)
        call.respond(BlobRefDto(ref.hash, ref.size, ref.algorithm))
    }

    get(
        "/v1/blobs/{hash}",
        {
            tags("attachments")
            summary = "Download an attachment by digest"
            description =
                "Returns the original bytes, after verifying them against the digest the " +
                "hash identifies. A blob whose bytes no longer match is refused with " +
                "500 blob_corrupt rather than served.\n\n" +
                "The response is always application/octet-stream: a blob stores bytes and " +
                "no media type, and inferring one from the content would be a guess. " +
                "Store the type alongside the reference if you need it."
            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                correlationHeader()
                pathParameter("hash", type<String>()) {
                    description = "Lowercase SHA-256 digest, as returned by the upload route."
                    required = true
                }
            }
            response {
                HttpStatusCode.OK to {
                    binaryBody()
                    header<String>("Content-Type") {
                        required = true
                        description = "Always application/octet-stream: a blob carries no media type."
                    }
                }
                HttpStatusCode.NotFound to { body<ErrorEnvelope>() }
                HttpStatusCode.InternalServerError to { body<ErrorEnvelope>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
            }
        }
    ) {
        call.authenticate(deps.policy, ApiOperation.LIST) ?: return@get
        val hash = call.parameters["hash"]?.trim().orEmpty()
        // A blob carries no media type, so the response is octet-stream. The
        // client knows what it uploaded; guessing from bytes would be a lie.
        val bytes = deps.facade.getBlob(BlobRef(hash = hash, size = -1L))
        call.response.header(
            HttpHeaders.ContentType,
            ContentType.Application.OctetStream.toString()
        )
        call.respondBytes(bytes)
    }
}

/** Tombstone listing. */
private fun Route.deletedApi(deps: StoreApiDeps) {
    post(
        "/v1/documents:deleted",
        {
            tags("documents")
            summary = "List deleted documents"
            description =
                "Tombstones only, with the revision and time each was deleted. " +
                "Deleted documents are absent from every other listing. The page size " +
                "is capped at $MAX_DELETED_PAGE_LIMIT, like every other listing."
            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                correlationHeader()
                requiredBody<ListDeletedRequest>("Optional collection and page size.")
            }
            response {
                HttpStatusCode.OK to { body<ListDeletedResponse>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
            }
        }
    ) {
        val principal = call.authenticate(deps.policy, ApiOperation.LIST) ?: return@post
        val request = call.receiveBounded<ListDeletedRequest>(deps.maxRequestBodyBytes)
        validateCollection(request.collection)
        if (!call.ensureCollection(principal, request.collection)) return@post
        val limit = (request.limit ?: MAX_DELETED_PAGE_LIMIT).coerceIn(1, MAX_DELETED_PAGE_LIMIT)
        val collections = principal.scopedCollections(request.collection)

        // A tombstone out of the principal's scope is omitted rather than
        // reported, matching how every other listing hides what a credential
        // cannot read. Its existence is not disclosed.
        val refs =
            if (collections == null) {
                deps.facade.listDeleted(null)
            } else {
                collections.flatMap { name -> deps.facade.listDeleted(Collection(name)) }
            }
        val visible =
            refs.filter { ref ->
                ref.collection.value in (collections ?: emptySet()) ||
                    collections == null
            }
        call.respond(
            ListDeletedResponse(
                documents = visible.take(limit).map { it.toDto() },
                total = visible.size
            )
        )
    }
}

/** Paginated document listing. */
private fun Route.documentListApi(deps: StoreApiDeps) {
    get(
        "/v1/documents",
        {
            tags("documents")
            summary = "List documents"
            description = "Paginated document references, optionally scoped to a collection."
            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                correlationHeader()
                queryParameter<String>("collection") { description = "Collection to list." }
                queryParameter<String>("pathPrefix") {
                    description = "Only documents whose path starts with this prefix, for building a folder tree."
                }
                queryParameter<Int>("limit") { description = "Page size (1..500, default 50)." }
                queryParameter<Int>("offset") {
                    description = "Zero-based offset. Retained for existing clients; prefer `cursor`."
                }
                queryParameter<String>("cursor") {
                    description =
                        "Opaque token from a previous response's `nextCursor`; resumes after it. " +
                        "Mutually exclusive with `offset`."
                }
            }
            response {
                HttpStatusCode.OK to { body<DocumentListResponse>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
            }
        }
    ) { handleDocumentList(call, deps) }
}

private suspend fun handleDocumentList(call: ApplicationCall, deps: StoreApiDeps) {
    val principal = call.authenticate(deps.policy, ApiOperation.LIST) ?: return
    val collection = call.request.queryParameters["collection"]
    validateCollection(collection)
    if (!call.ensureCollection(principal, collection)) return
    val limit = call.limit()
    val pathPrefix = call.request.queryParameters["pathPrefix"]
    validatePathPrefix(pathPrefix)
    val rawCursor = call.request.queryParameters["cursor"]
    val collections = principal.scopedCollections(collection)
    if (collections != null && collections.size > MAX_COLLECTIONS_PER_REQUEST) {
        invalid("too many collections requested")
    }
    if (collections != null && !call.ensureScope(collections)) return
    // Cursor and offset are two ways to ask for the same window. Accepting both
    // at once would let the two disagree about which page this is, and a client
    // that gets it wrong sees duplicates or gaps. One wins.
    if (rawCursor != null && call.request.queryParameters.contains("offset")) {
        invalid("cursor and offset cannot be combined")
    }
    val scoped = collections?.map(::Collection)?.toSet()
    val page =
        listPageOf(
            deps.facade,
            collection?.let(::Collection),
            pathPrefix,
            scoped,
            rawCursor,
            limit,
            call.offset()
        )
    call.respond(
        DocumentListResponse(
            documents = page.items.map { it.toDto() },
            total = page.total,
            limit = limit,
            offset = if (rawCursor != null) 0 else call.offset(),
            hasMore = page.hasMore,
            nextCursor = nextCursorOf(page)
        )
    )
}

/**
 * The page for a listing request, cursor or offset.
 *
 * Cursor and offset are two ways to ask for the same window. Accepting both at
 * once would let the two disagree about which page this is, and a client that
 * gets it wrong sees duplicates or gaps. One wins.
 */
private suspend fun listPageOf(
    facade: QueryFacade,
    collection: Collection?,
    pathPrefix: String?,
    scoped: Set<Collection>?,
    rawCursor: String?,
    limit: Int,
    offset: Int
): StorePage<DocRef> =
    if (rawCursor != null) {
        if (scoped == null) {
            facade.listPageAfter(
                collection = collection,
                pathPrefix = pathPrefix,
                after = decodeCursor(rawCursor),
                limit = limit
            )
        } else {
            facade.listPageAfter(
                collections = scoped,
                pathPrefix = pathPrefix,
                after = decodeCursor(rawCursor),
                limit = limit
            )
        }
    } else if (scoped == null) {
        facade.listPage(
            collection = collection,
            limit = limit,
            offset = offset,
            pathPrefix = pathPrefix
        )
    } else {
        facade.listPage(
            collections = scoped,
            limit = limit,
            offset = offset,
            pathPrefix = pathPrefix
        )
    }

/**
 * The cursor comes from the last item actually returned rather than from a
 * position, so echoing it resumes past exactly what the client has seen.
 * Emitted only when there is more to fetch and at least one item to resume from.
 */
private fun nextCursorOf(page: StorePage<DocRef>): String? =
    if (page.hasMore && page.items.isNotEmpty()) {
        page.items
            .last()
            .id
            .let(::encodeCursor)
    } else {
        null
    }

/** Fetch one document. */
private fun Route.documentGetApi(deps: StoreApiDeps) {
    post(
        "/v1/documents:get",
        {
            tags("documents")
            summary = "Get a document"
            description =
                "Fetch one document by id. Ids are path-derived and contain '/', so they travel in the body."
            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                correlationHeader()
                requiredBody<GetDocumentRequest>("Document id and optional collection.")
            }
            response {
                HttpStatusCode.OK to {
                    body<DocumentDto>()
                    header<String>("ETag") {
                        required = true
                        description = "Current document revision."
                    }
                }
                HttpStatusCode.NotFound to { body<ErrorEnvelope>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
            }
        }
    ) { handleDocumentGet(call, deps) }
}

private suspend fun handleDocumentGet(call: ApplicationCall, deps: StoreApiDeps) {
    val principal = call.authenticate(deps.policy, ApiOperation.GET) ?: return
    val request = call.receiveBounded<GetDocumentRequest>(deps.maxRequestBodyBytes)
    validateDocumentRequest(request.id, request.collection)
    if (!call.ensureCollection(principal, request.collection)) return
    val document = deps.facade.get(DocId(request.id))
    if (document == null || !document.visibleTo(principal, request.collection)) {
        call.notFound()
        return
    }
    if (document.content
            .toByteArray(StandardCharsets.UTF_8)
            .size
            .toLong() > MAX_CONTENT_BYTES
    ) {
        throw ContentTooLargeException()
    }
    call.response.header("ETag", etag(document.revision.value))
    call.respond(document.toDto())
}

/** Create or update, idempotent on content hash. */
private fun Route.documentUpsertApi(deps: StoreApiDeps) {
    post(
        "/v1/documents:upsert",
        {
            tags("documents")
            summary = "Create or update a document"
            description =
                "Idempotent on content hash. A supplied contentHash is a precondition on the existing " +
                "document content. Honours `If-Match` (optimistic concurrency) and `Idempotency-Key` " +
                "(response replay)."
            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                writeHeaders()
                requiredBody<UpsertDocumentRequest>("Complete create-or-update request.")
            }
            response {
                HttpStatusCode.OK to {
                    body<UpsertResult>()
                    header<String>("ETag") {
                        required = true
                        description = "Revision produced by this write."
                    }
                }
                HttpStatusCode.Conflict to { body<ErrorEnvelope>() }
                HttpStatusCode.PreconditionFailed to { body<ErrorEnvelope>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
            }
        }
    ) { handleDocumentUpsert(call, deps) }
}

private suspend fun handleDocumentUpsert(call: ApplicationCall, deps: StoreApiDeps) {
    val principal = call.authenticate(deps.policy, ApiOperation.UPSERT) ?: return
    val request = call.receiveBounded<UpsertDocumentRequest>(deps.maxRequestBodyBytes)
    validateUpsertRequest(request)
    if (request.collection != null && !call.ensureCollection(principal, request.collection)) return
    val raw =
        call.request.headers[HEADER_IDEMPOTENCY]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    if (raw != null && (raw.length > MAX_IDEMPOTENCY_KEY_LENGTH || raw.any(Char::isISOControl))) {
        invalid("idempotency key must contain at most $MAX_IDEMPOTENCY_KEY_LENGTH safe characters")
    }
    val prep =
        call.settleIdempotency(
            deps,
            ApiOperation.UPSERT.name,
            principal,
            raw,
            requestFingerprint(
                "POST",
                "/v1/documents:upsert",
                raw.orEmpty(),
                call.request.headers["If-Match"].orEmpty(),
                json.encodeToString(
                    UpsertDocumentRequest.serializer(),
                    request.copy(frontmatter = request.frontmatter?.toSortedMap())
                )
            )
        ) ?: return
    val existing = deps.facade.get(DocId(request.id))
    val historyOwner = deps.facade.historyOwner(DocId(request.id))
    val target =
        resolveUpsertTarget(call, deps, principal, request, existing, historyOwner, prep) ?: return
    runUpsert(deps, call, request, target, existing, historyOwner, prep)
}

/** Releases an acquired reservation that will not be completed. */
private suspend fun releaseIdempotency(deps: StoreApiDeps, prep: IdempotencyPrep) {
    if (prep.key != null && prep.reservation is IdempotencyReservation.Acquired) {
        deps.idempotency.release(prep.key, prep.fingerprint, prep.reservation.token)
    }
}

/**
 * The collection-ownership decision for an upsert; null means the call already
 * responded.
 */
private suspend fun resolveUpsertTarget(
    call: ApplicationCall,
    deps: StoreApiDeps,
    principal: ApiPrincipal,
    request: UpsertDocumentRequest,
    existing: Document?,
    historyOwner: gokorei.tanseki.core.domain.DocRef?,
    prep: IdempotencyPrep
): String? {
    val ownerMismatch =
        historyOwner != null &&
            request.collection != null &&
            historyOwner.collection.value != request.collection
    if (ownerMismatch && !principal.allows(historyOwner.collection.value)) {
        releaseIdempotency(deps, prep)
        call.notFound()
        return null
    }
    val target =
        request.collection ?: existing?.collection?.value ?: historyOwner?.collection?.value ?: "vault"
    if (!call.ensureCollection(principal, target)) {
        releaseIdempotency(deps, prep)
        return null
    }
    if (existing != null && !existing.visibleTo(principal, null)) {
        if (prep.key != null && prep.reservation is IdempotencyReservation.Acquired) {
            deps.idempotency.release(prep.key, prep.fingerprint, prep.reservation.token)
        }
        call.notFound()
        return null
    }
    return target
}

/** Runs the upsert command and answers with the new revision. */
private suspend fun runUpsert(
    deps: StoreApiDeps,
    call: ApplicationCall,
    request: UpsertDocumentRequest,
    targetCollection: String,
    existing: Document?,
    historyOwner: gokorei.tanseki.core.domain.DocRef?,
    prep: IdempotencyPrep
) {
    val result =
        try {
            val ifRevision =
                call.expectedIfMatch(
                    existing = existing,
                    bodyRevision = request.ifRevision,
                    defaultToCurrent = request.contentHash != null
                )
            if (request.contentHash != null && existing?.contentHash != request.contentHash) {
                throw PreconditionFailedException("contentHash does not match current document content")
            }
            deps.documentCommands.upsert(
                UpsertDocumentCommand(
                    id = request.id,
                    content = request.content,
                    collection = targetCollection,
                    path = request.path ?: historyOwner?.path,
                    frontmatter = request.frontmatter?.toFrontmatter(),
                    message = request.message ?: "upsert",
                    author = request.author ?: "api",
                    ifRevision = ifRevision
                )
            )
        } catch (error: Throwable) {
            if (prep.key != null && prep.reservation is IdempotencyReservation.Acquired) {
                deps.idempotency.release(prep.key, prep.fingerprint, prep.reservation.token)
            }
            throw error
        }
    val body =
        json.encodeToString(
            UpsertResult.serializer(),
            UpsertResult(
                request.id,
                result.document.collection.value,
                result.revision.revision.value,
                result.document.contentHash,
                result.created,
                result.frontmatterError
            )
        )
    val responseHeaders = mapOf("ETag" to etag(result.revision.revision.value))
    if (prep.key != null && prep.reservation is IdempotencyReservation.Acquired) {
        completeIdempotency(
            deps.idempotency,
            prep.key,
            prep.fingerprint,
            prep.reservation.token,
            StoredResponse(200, body, responseHeaders),
            deps.logger
        )
    }
    call.response.header("ETag", responseHeaders.getValue("ETag"))
    call.respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
}

/** The result of settling an Idempotency-Key for one write; null means the call already responded. */
private data class IdempotencyPrep(
    val key: String?,
    val fingerprint: String,
    val reservation: IdempotencyReservation?
)

/**
 * Scopes the `Idempotency-Key` to the principal and operation and settles a
 * reservation, responding (and returning null) when the key is a replay or a
 * conflict. Shared by every write route so the four copies of this preamble
 * cannot drift apart; each caller keeps its own raw-key validation so per-route
 * error messages stay exact.
 */
private suspend fun ApplicationCall.settleIdempotency(
    deps: StoreApiDeps,
    operation: String,
    principal: ApiPrincipal,
    raw: String?,
    fingerprint: String
): IdempotencyPrep? {
    val key = scopedIdempotencyKey(operation, principal, raw)
    val reservation = key?.let { settle(deps.idempotency, it, fingerprint) }
    when (reservation) {
        is IdempotencyReservation.Replay -> {
            replay(stored = reservation.response)
            return null
        }

        is IdempotencyReservation.Conflict -> {
            error(HttpStatusCode.Conflict, "idempotency_conflict", reservation.reason)
            return null
        }

        else -> {
            Unit
        }
    }
    return IdempotencyPrep(key, fingerprint, reservation)
}

/** Delete (tombstone) a document. */
private fun Route.documentDeleteApi(deps: StoreApiDeps) {
    post(
        "/v1/documents:delete",
        {
            tags("documents")
            summary = "Delete a document"
            description =
                "Tombstones the document while preserving its history; supports `If-Match` and `Idempotency-Key`."

            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                writeHeaders()
                requiredBody<DeleteDocumentRequest>("Document id and optional deletion metadata.")
            }
            response {
                HttpStatusCode.OK to {
                    body<DeleteResult>()
                    header<String>("ETag") {
                        required = true
                        description = "Deletion revision produced by this write."
                    }
                }
                HttpStatusCode.NotFound to { body<ErrorEnvelope>() }
                HttpStatusCode.PreconditionFailed to { body<ErrorEnvelope>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
            }
        }
    ) { handleDocumentDelete(call, deps) }
}

private suspend fun handleDocumentDelete(call: ApplicationCall, deps: StoreApiDeps) {
    val principal = call.authenticate(deps.policy, ApiOperation.DELETE) ?: return
    val request = call.receiveBounded<DeleteDocumentRequest>(deps.maxRequestBodyBytes)
    validateDeleteRequest(request)
    if (!call.ensureCollection(principal, request.collection)) return
    val raw =
        call.request.headers[HEADER_IDEMPOTENCY]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    if (raw != null && (raw.length > MAX_IDEMPOTENCY_KEY_LENGTH || raw.any(Char::isISOControl))) {
        invalid("idempotency key must contain at most $MAX_IDEMPOTENCY_KEY_LENGTH safe characters")
    }
    val prep =
        call.settleIdempotency(
            deps,
            ApiOperation.DELETE.name,
            principal,
            raw,
            requestFingerprint(
                "POST",
                "/v1/documents:delete",
                raw.orEmpty(),
                call.request.headers["If-Match"].orEmpty(),
                json.encodeToString(DeleteDocumentRequest.serializer(), request)
            )
        ) ?: return
    val existing = deps.facade.get(DocId(request.id))
    if (existing == null || !existing.visibleTo(principal, request.collection)) {
        if (prep.key != null && prep.reservation is IdempotencyReservation.Acquired) {
            deps.idempotency.release(prep.key, prep.fingerprint, prep.reservation.token)
        }
        call.notFound()
        return
    }
    runDelete(deps, call, request, existing, prep)
}

/** Runs the tombstoning delete and answers with the new revision. */
private suspend fun runDelete(
    deps: StoreApiDeps,
    call: ApplicationCall,
    request: DeleteDocumentRequest,
    existing: Document?,
    prep: IdempotencyPrep
) {
    val doc = existing ?: return
    val result =
        try {
            val expectedRevision =
                RevisionId(call.expectedIfMatchForExisting(existing, request.ifRevision))
            val revision =
                deps.facade.delete(
                    DocId(request.id),
                    request.message ?: "delete",
                    request.author ?: "api",
                    expectedRevision
                )
            val deleted = DeleteResult(request.id, doc.collection.value, revision.revision.value)
            val responseBody = json.encodeToString(DeleteResult.serializer(), deleted)
            val responseHeaders = mapOf("ETag" to etag(revision.revision.value))
            if (prep.key != null && prep.reservation is IdempotencyReservation.Acquired) {
                completeIdempotency(
                    deps.idempotency,
                    prep.key,
                    prep.fingerprint,
                    prep.reservation.token,
                    StoredResponse(200, responseBody, responseHeaders),
                    deps.logger
                )
            }
            call.response.header("ETag", responseHeaders.getValue("ETag"))
            responseBody
        } catch (error: Throwable) {
            if (prep.key != null && prep.reservation is IdempotencyReservation.Acquired) {
                deps.idempotency.release(prep.key, prep.fingerprint, prep.reservation.token)
            }
            throw error
        }
    call.respondText(result, ContentType.Application.Json, HttpStatusCode.OK)
}

/** Restore a tombstoned document. */
private fun Route.documentRestoreApi(deps: StoreApiDeps) {
    post(
        "/v1/documents:restore",
        {
            tags("documents")
            summary = "Restore a deleted document"
            description =
                "Return a tombstoned document to the live set under its original id and path. " +
                "Fails with a conflict when the path has since been claimed by another document."
            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                correlationHeader()
                requiredBody<RestoreDocumentRequest>("Document id, optional message, author, and revision.")
            }
            response {
                HttpStatusCode.OK to { body<RestoreResult>() }
                HttpStatusCode.NotFound to { body<ErrorEnvelope>() }
                HttpStatusCode.Conflict to { body<ErrorEnvelope>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
            }
        }
    ) { handleDocumentRestore(call, deps) }
}

private suspend fun handleDocumentRestore(call: ApplicationCall, deps: StoreApiDeps) {
    val principal = call.authenticate(deps.policy, ApiOperation.UPSERT) ?: return
    val request = call.receiveBounded<RestoreDocumentRequest>(deps.maxRequestBodyBytes)
    validateRestoreRequest(request)
    if (!call.ensureCollection(principal, request.collection)) return
    val raw =
        call.request.headers[HEADER_IDEMPOTENCY]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    if (raw != null &&
        (
            raw.length > MAX_IDEMPOTENCY_KEY_LENGTH ||
                raw.any(Char::isISOControl)
        )
    ) {
        call.error(HttpStatusCode.BadRequest, "invalid_argument", "invalid Idempotency-Key")
        return
    }
    val prep =
        call.settleIdempotency(
            deps,
            "RESTORE",
            principal,
            raw,
            restoreFingerprint(call, request, principal.credentialId)
        ) ?: return
    val tombstone = deps.facade.getIncludingDeleted(DocId(request.id))
    // An out-of-scope id is indistinguishable from an unknown one: a
    // credential must not be able to probe for deleted documents it
    // cannot read.
    if (tombstone == null || !tombstone.visibleTo(principal, request.collection)) {
        if (prep.key != null && prep.reservation is IdempotencyReservation.Acquired) {
            deps.idempotency.release(prep.key, prep.fingerprint, prep.reservation.token)
        }
        call.notFound()
        return
    }
    runRestore(deps, call, request, tombstone, prep)
}

/** Runs the restore and answers with the new revision. */
private suspend fun runRestore(
    deps: StoreApiDeps,
    call: ApplicationCall,
    request: RestoreDocumentRequest,
    tombstone: Document?,
    prep: IdempotencyPrep
) {
    val tomb = tombstone ?: return
    val created = tomb.deleted
    val revision =
        deps.facade.restore(
            DocId(request.id),
            request.message ?: "restore",
            request.author ?: "api",
            request.ifRevision?.let(::RevisionId)
        )
    val restored = deps.facade.get(tomb.id)
    val result =
        RestoreResult(
            id = tomb.id.value,
            collection = (restored ?: tomb).collection.value,
            revision = revision.revision.value,
            contentHash = revision.contentHash,
            created = created
        )
    val responseBody = json.encodeToString(RestoreResult.serializer(), result)
    val responseHeaders = mapOf("ETag" to etag(revision.revision.value))
    if (prep.key != null && prep.reservation is IdempotencyReservation.Acquired) {
        completeIdempotency(
            deps.idempotency,
            prep.key,
            prep.fingerprint,
            prep.reservation.token,
            StoredResponse(200, responseBody, responseHeaders),
            deps.logger
        )
    }
    call.response.header("ETag", responseHeaders.getValue("ETag"))
    call.respond(HttpStatusCode.OK, result)
}

/** Rename a document, rewriting inbound wikilinks. */
private fun Route.documentRenameApi(deps: StoreApiDeps) {
    post(
        "/v1/documents:rename",
        {
            tags("documents")
            summary = "Rename a document"
            description =
                "Move a document to a new id, rewriting the inbound `[[wikilinks]]` that pointed at it. " +
                "A document's id is its path, so the two move together; this cannot be expressed as a " +
                "create plus a delete. " +
                "Fails with a conflict when the target path is held by a live document, or when " +
                "`If-Match` does not match the current revision. A store that cannot rename reports 501. " +
                "Frontmatter references (`files:`, `repo:`, `pr:`) are not rewritten, so a document that " +
                "embeds the renamed note by frontmatter rather than by wikilink loses that edge on the " +
                "next index."
            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                correlationHeader()
                requiredBody<RenameDocumentRequest>(
                    "Current document id, new id, optional message, author, and revision."
                )
            }
            response {
                HttpStatusCode.OK to { body<RenameResult>() }
                HttpStatusCode.NotFound to { body<ErrorEnvelope>() }
                HttpStatusCode.Conflict to { body<ErrorEnvelope>() }
                HttpStatusCode.NotImplemented to { body<ErrorEnvelope>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
            }
        }
    ) { handleDocumentRename(call, deps) }
}

private suspend fun handleDocumentRename(call: ApplicationCall, deps: StoreApiDeps) {
    val principal = call.authenticate(deps.policy, ApiOperation.UPSERT) ?: return
    val request = call.receiveBounded<RenameDocumentRequest>(deps.maxRequestBodyBytes)
    validateRenameRequest(request)
    if (!call.ensureCollection(principal, request.collection)) return
    val raw =
        call.request.headers[HEADER_IDEMPOTENCY]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    if (raw != null &&
        (
            raw.length > MAX_IDEMPOTENCY_KEY_LENGTH ||
                raw.any(Char::isISOControl)
        )
    ) {
        call.error(HttpStatusCode.BadRequest, "invalid_argument", "invalid Idempotency-Key")
        return
    }
    val prep =
        call.settleIdempotency(
            deps,
            "RENAME",
            principal,
            raw,
            renameFingerprint(call, request, principal.credentialId)
        ) ?: return
    val from = DocId(request.id)
    val to = DocId(request.newId)
    // An out-of-scope id is indistinguishable from an unknown one: a
    // credential must not be able to probe for documents it cannot read.
    val source = deps.facade.get(from)
    if (source == null || !source.visibleTo(principal, request.collection)) {
        if (prep.key != null && prep.reservation is IdempotencyReservation.Acquired) {
            deps.idempotency.release(prep.key, prep.fingerprint, prep.reservation.token)
        }
        call.notFound()
        return
    }
    runRename(deps, call, request, from, to, source, prep)
}

/** Runs the rename and answers with the new id. */
private suspend fun runRename(
    deps: StoreApiDeps,
    call: ApplicationCall,
    request: RenameDocumentRequest,
    from: DocId,
    to: DocId,
    source: Document?,
    prep: IdempotencyPrep
) {
    val src = source ?: return
    val revision =
        deps.facade.rename(
            from,
            to,
            request.message ?: "rename",
            request.author ?: "api",
            request.ifRevision?.let(::RevisionId)
        )
    val moved = deps.facade.get(to)
    val result =
        RenameResult(
            id = to.value,
            previousId = from.value,
            collection = (moved ?: src).collection.value,
            path = (moved ?: src).path,
            revision = revision.revision.value,
            contentHash = revision.contentHash
        )
    val responseBody = json.encodeToString(RenameResult.serializer(), result)
    val responseHeaders = mapOf("ETag" to etag(revision.revision.value))
    if (prep.key != null && prep.reservation is IdempotencyReservation.Acquired) {
        completeIdempotency(
            deps.idempotency,
            prep.key,
            prep.fingerprint,
            prep.reservation.token,
            StoredResponse(200, responseBody, responseHeaders),
            deps.logger
        )
    }
    call.response.header("ETag", responseHeaders.getValue("ETag"))
    call.respond(HttpStatusCode.OK, result)
}

/** Full-text search. */
private fun Route.searchApi(deps: StoreApiDeps) {
    get(
        "/v1/search",
        {
            tags("search")
            summary = "Search documents"
            description =
                "Full-text search, with optional filters. `q` may be omitted when a filter is " +
                "present: `fm=repo=org/repo` alone answers \"which documents carry this " +
                "property\", bounded by `limit`. With neither `q` nor a filter the query is " +
                "empty and returns nothing rather than the whole store, so an omitted " +
                "parameter is never mistaken for a successful empty result. A filter value " +
                "is resolved by the ordinary scalar rules — `42` and `true` select the number " +
                "42 and the boolean true, because that is what the document's own YAML said — " +
                "and quoting forces the string: `fm=pr=\"42\"` selects a document storing \"42\" " +
                "as text, and `fm=pr=42` will not. `mode=hybrid` adds vector kNN ranking."
            protected = true
            securitySchemeNames("apiKey", "bearerAuth")
            request {
                correlationHeader()
                queryParameter<String>("q") { description = "Query keywords." }
                queryParameter<String>("collection") { description = "Restrict to a collection." }
                queryParameter<List<String>>("tags") {
                    description = "Require every listed tag; repeat for multiple tags."
                    style = Parameter.StyleEnum.FORM
                    explode = true
                }
                queryParameter<List<String>>("fm") {
                    description =
                        "Frontmatter equality filters, `key=value`; repeat for multiple filters. " +
                        "Usable without `q`: a filter on its own enumerates what matches. A value " +
                        "is resolved by ordinary scalar rules — `42`, `1.5` and `true` select " +
                        "numbers and a boolean, `org/repo` and `1.2.3` select text — and quoting " +
                        "forces a string: `\"42\"` selects a stored string, unquoted `42` does " +
                        "not. An empty value (`fm=pr=`), an unclosed quote, or a contract key " +
                        "(`fm=tags=review`) is rejected; filter tags with `tags` instead."
                    style = Parameter.StyleEnum.FORM
                    explode = true
                }
                queryParameter<String>("mode") { description = "`lexical` (default) or `hybrid`." }
                queryParameter<Int>("limit") { description = "Page size (1..500, default 50)." }
                queryParameter<Int>("offset") { description = "Zero-based offset." }
            }
            response {
                HttpStatusCode.OK to { body<SearchResponse>() }
                HttpStatusCode.BadRequest to { body<ErrorEnvelope>() }
                HttpStatusCode.Unauthorized to { body<ErrorEnvelope>() }
                HttpStatusCode.Forbidden to { body<ErrorEnvelope>() }
            }
        }
    ) { handleSearch(call, deps) }
}

/** The `tags` query parameter, validated and de-duplicated. */
private fun parseSearchTags(call: ApplicationCall): Set<String> =
    call.request.queryParameters
        .getAll("tags")
        .orEmpty()
        .also {
            if (it.size > MAX_SEARCH_FILTERS) invalid("too many tags")
            it.forEach { tag ->
                if (tag.isBlank()) invalid("tag must not be blank")
                if (tag.toByteArray(StandardCharsets.UTF_8).size > MAX_COLLECTION_LENGTH) {
                    invalid("tag is too long")
                }
            }
        }.toSet()

/**
 * The `fm` frontmatter filters, parsed and validated here, where the parameter
 * is still in hand, so the rejection can name the exact filter a caller got
 * wrong.
 */
private fun parseSearchFrontmatter(call: ApplicationCall): Map<String, String> =
    call.request.queryParameters
        .getAll("fm")
        .orEmpty()
        .also {
            if (it.size > MAX_SEARCH_FILTERS) invalid("too many frontmatter filters")
            it.forEach { entry ->
                if (entry.toByteArray(StandardCharsets.UTF_8).size.toLong() > MAX_FRONTMATTER_VALUE_BYTES) {
                    invalid("frontmatter filter is too long")
                }
            }
        }.map { entry ->
            val key = entry.substringBefore('=', "").trim()
            if (key.isEmpty()) invalid("frontmatter filters must use key=value")
            if (key.toByteArray(StandardCharsets.UTF_8).size > MAX_COLLECTION_LENGTH) {
                invalid("frontmatter filter key is too long")
            }
            val value = entry.substringAfter('=', "")
            if (value.toByteArray(StandardCharsets.UTF_8).size > MAX_FRONTMATTER_VALUE_BYTES) {
                invalid("frontmatter filter value is too long")
            }
            when (val resolved = resolveFrontmatterFilter(key, value)) {
                is FrontmatterFilterValue.Invalid -> invalid(resolved.explain("$key=$value"))
                is FrontmatterFilterValue.Resolved -> Unit
            }
            key to value
        }.toMap()

private suspend fun handleSearch(call: ApplicationCall, deps: StoreApiDeps) {
    val principal = call.authenticate(deps.policy, ApiOperation.SEARCH) ?: return
    val query = call.request.queryParameters["q"] ?: ""
    if (query.toByteArray(StandardCharsets.UTF_8).size.toLong() > MAX_QUERY_BYTES) {
        invalid("search query is too long")
    }
    val limit = call.limit()
    val offset = call.offset()
    val collection = call.request.queryParameters["collection"]
    validateCollection(collection)
    if (!call.ensureCollection(principal, collection)) return
    val tags = parseSearchTags(call)
    val frontmatter = parseSearchFrontmatter(call)
    val requestedMode = call.request.queryParameters["mode"]?.lowercase() ?: SearchMode.DEFAULT.wire
    validateOptionalText("mode", requestedMode, MAX_COLLECTION_LENGTH)
    val mode =
        SearchMode.parse(requestedMode)
            ?: throw InvalidInputException(
                "unknown search mode '$requestedMode' (use ${SearchMode.WIRES.joinToString(" or ")})"
            )
    val collections = principal.scopedCollections(collection)
    if (collections != null && collections.size > MAX_COLLECTIONS_PER_REQUEST) {
        invalid("too many collections requested")
    }
    if (collections != null && !call.ensureScope(collections)) return
    val searchFilters = Filters.fromStrings(tags = tags, frontmatter = frontmatter)
    val (page, hasMore) =
        if (collections == null) {
            val raw = search(deps.facade, query, mode, searchFilters, limit, offset)
            raw.take(limit) to (raw.size > limit)
        } else {
            // Each collection is searched from its start with no per-collection
            // offset. `offset` is a position in the globally ranked result set,
            // so applying it per collection would skip that many hits in every
            // collection and drop globally-ranked documents the caller should
            // see (and hasMore would lie, because each collection returns
            // limit+1). Fetch `offset+limit` (one past the window) from each,
            // merge, sort globally, then slice.
            val merged =
                collections
                    .sorted()
                    .flatMap { allowed ->
                        search(
                            deps.facade,
                            query,
                            mode,
                            searchFilters.copy(collections = setOf(allowed)),
                            offset + limit,
                            0
                        )
                    }.sortedWith(
                        compareByDescending<gokorei.tanseki.core.ports.Hit> { it.score }
                            .thenBy { it.id.value }
                    )
            merged.drop(offset).take(limit) to (merged.size > offset + limit)
        }
    call.respond(
        SearchResponse(
            hits = page.map { it.toDto() },
            limit = limit,
            offset = offset,
            hasMore = hasMore
        )
    )
}

/** The OpenAPI contract: schema derivation and response defaults. */
private fun Application.installOpenApiContract() {
    install(OpenApi) {
        info {
            title = "Tanseki API"
            version = TansekiVersion.value
            description = "Canonical Tanseki `/v1` knowledge-store seam: documents, search and traversal."
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
            }
        }
        outputFormat = OutputFormat.JSON
        security {
            securityScheme("apiKey") {
                type = AuthType.API_KEY
                name = HEADER_API_KEY
                location = AuthKeyLocation.HEADER
                description = "Tanseki API credential."
            }
            securityScheme("bearerAuth") {
                type = AuthType.HTTP
                scheme = AuthScheme.BEARER
                bearerFormat = "opaque"
                description = "Tanseki API credential."
            }
            defaultSecuritySchemeNames("apiKey", "bearerAuth")
        }
        schemas {
            // Derive JSON schemas from the kotlinx serializers so schema names follow
            // @SerialName and match the DTOs / generated SDK model names.
            generator = SchemaGenerator.kotlinx(json)
        }
        // openapi-generator requires a description on every response; the route
        // DSL does not force one, so default it here and spell out the two
        // authorization failures any protected route can return. Request bodies
        // are also closed sets: the server rejects unknown fields, so the
        // contract says so.
        postBuild = { openApi, _ ->
            openApi.paths?.values?.forEach { item ->
                item.readOperationsMap().values.forEach { operation ->
                    operation.responses?.values?.forEach { response ->
                        if (response.description == null) response.description = ""
                    }
                    operation.responses
                        ?.get(HttpStatusCode.Unauthorized.value.toString())
                        ?.description = "No credential was presented, or the credential is unknown."
                    operation.responses
                        ?.get(HttpStatusCode.Forbidden.value.toString())
                        ?.description =
                        "The credential may not perform this operation on the requested collection."
                    operation.requestBody?.content?.values?.forEach { media ->
                        media.schema = closedRequestSchema(media.schema)
                    }
                }
            }
            // Frontmatter is an open, string-keyed bag of JSON values: strings,
            // numbers, booleans, arrays, and nested objects. kotlinx.serialization
            // has no schema for JsonElement, so the generated definition would pin
            // every value to an empty object and clients would reject the flat maps
            // the API actually serves. Publish it as free-form.
            openApi.components?.schemas?.get(JSON_ELEMENT_SCHEMA)?.let {
                openApi.components?.schemas?.put(JSON_ELEMENT_SCHEMA, Schema<Any>())
            }
            // No schema in this document describes a frontmatter *value* usefully,
            // and the generated client therefore types the map `Map<String, Any?>`,
            // which reads as "a string, probably". A caller cannot tell from the
            // type whether a list belongs there, so the type is not enough and the
            // contract has to say it in words. This is the only field-level
            // description in the spec, and it is here because it is the one field
            // whose generated type understates what it carries.
            listOf("DocumentDto", "UpsertDocumentRequest").forEach { schema ->
                openApi.components?.schemas?.get(schema)?.properties?.get(FRONTMATTER_PROPERTY)?.let {
                    it.description = FRONTMATTER_CONTRACT
                }
            }
        }
    }
}

/** JSON content negotiation with a closed, defaulted, non-null JSON config. */
private fun Application.installJsonContentNegotiation() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = false
                encodeDefaults = true
                explicitNulls = false
            }
        )
    }
}

/** The StatusPages exception-to-envelope mapping. */
private fun Application.installErrorEnvelopes(
    logger: TansekiLogger,
    maxRequestBodyBytes: Long
) {
    install(StatusPages) {
        exception<NotFoundException> {
            call,
            _
            ->
            call.error(HttpStatusCode.NotFound, "not_found", "document not found")
        }
        exception<ConflictException> { call, cause ->
            logApiFailure(logger, call, cause)
            call.error(HttpStatusCode.Conflict, "conflict", "request conflicts with current state")
        }
        exception<InvalidInputException> { call, cause ->
            logApiFailure(logger, call, cause)
            call.error(HttpStatusCode.BadRequest, "invalid_argument", callerReason(cause) ?: INVALID_REQUEST)
        }
        exception<BlobCorruptionException> { call, cause ->
            // The bytes on disk disagree with the digest they are addressed by.
            // That is a data-integrity failure, not a client error, so it is
            // reported distinctly rather than as a generic failure.
            logApiFailure(logger, call, cause)
            call.error(
                HttpStatusCode.InternalServerError,
                "blob_corrupt",
                "stored attachment failed integrity verification"
            )
        }
        exception<UnsupportedStoreOperationException> { call, cause ->
            // A store may hold tombstones but not restore them (Postgres today).
            // That is a capability gap, not a client error: report it as such
            // instead of letting it surface as an opaque 500.
            //
            // Only the domain type is mapped: the JDK's UnsupportedOperationException
            // is also thrown by immutable collections and iterators, so mapping it
            // here would report a latent bug as a false claim about the store.
            logApiFailure(logger, call, cause)
            call.error(
                HttpStatusCode.NotImplemented,
                "not_implemented",
                "this store does not support that operation"
            )
        }
        exception<PreconditionFailedException> { call, cause ->
            logApiFailure(logger, call, cause)
            call.error(HttpStatusCode.PreconditionFailed, "failed_precondition", "precondition failed")
        }
        exception<RequestBodyTooLargeException> { call, cause ->
            logApiFailure(logger, call, cause)
            call.error(
                HttpStatusCode.PayloadTooLarge,
                "request_too_large",
                "request is too large",
                "body must be at most $maxRequestBodyBytes bytes"
            )
        }
        exception<ContentTooLargeException> { call, cause ->
            logApiFailure(logger, call, cause)
            call.error(
                HttpStatusCode.PayloadTooLarge,
                "content_too_large",
                "document content is too large to return",
                "content must be at most $MAX_CONTENT_BYTES bytes"
            )
        }
        exception<UntrustedProxyException> { call, cause ->
            logApiFailure(logger, call, cause)
            call.error(HttpStatusCode.Forbidden, "untrusted_proxy", "request source is not an allowed proxy")
        }
        exception<BadRequestException> { call, cause ->
            logApiFailure(logger, call, cause)
            call.error(HttpStatusCode.BadRequest, "invalid_argument", callerReason(cause) ?: INVALID_REQUEST)
        }
        exception<SerializationException> { call, cause ->
            logApiFailure(logger, call, cause)
            call.error(HttpStatusCode.BadRequest, "invalid_argument", callerReason(cause) ?: INVALID_REQUEST)
        }
        exception<IllegalArgumentException> { call, cause ->
            logApiFailure(logger, call, cause)
            call.error(HttpStatusCode.BadRequest, "invalid_argument", callerReason(cause) ?: INVALID_REQUEST)
        }
        exception<Exception> { call, cause ->
            // Coroutine cancellation is control flow, not a request failure:
            // converting it to a 500 would swallow the cancellation and keep the
            // coroutine alive. It is rethrown so the caller sees the cancellation.
            if (cause is CancellationException) throw cause
            // Exceptions, not Throwable: an OutOfMemoryError, StackOverflowError or
            // LinkageError means the process or the JVM state is already broken, and
            // dressing it up as an ordinary 500 would hide a fatal condition behind a
            // response a client is entitled to retry.
            logApiFailure(logger, call, cause)
            call.error(HttpStatusCode.InternalServerError, "internal", "internal error")
        }
    }
}

/** Installs the plugins the seam needs before any route is registered. */
private fun Application.installStoreApiPlugins(
    metrics: MetricsRecorder?,
    logger: TansekiLogger,
    maxRequestBodyBytes: Long
) {
    install(RequestIdPlugin)
    metrics?.let { install(requestMetricsPlugin(it, logger)) }
    installOpenApiContract()
    installJsonContentNegotiation()
    installErrorEnvelopes(logger, maxRequestBodyBytes)
}
