package gokorei.tanseki.service.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS

/**
 * The wire contract `docs/openapi.json` promises.
 *
 * These assertions are about the *exported spec*, not about handler behaviour:
 * a dependent generates its client from `docs/openapi.json`, so a route that
 * forgets a 403, an `ETag` response header, or `required: true` on its body
 * silently degrades every generated client even when the runtime is correct.
 */
@TestInstance(PER_CLASS)
class OpenApiContractTest {
    private val spec: JsonObject by lazy {
        var captured: JsonObject? = null
        withSpecJson { captured = it }
        requireNotNull(captured) { "openapi.json was not produced" }
    }

    private val operations: Map<String, JsonObject> by lazy {
        spec["paths"]!!
            .jsonObject
            .flatMap { (path, item) ->
                item.jsonObject
                    .filterKeys { it in HTTP_METHODS }
                    .map { (method, operation) -> "${method.uppercase()} $path" to operation.jsonObject }
            }.toMap()
    }

    // ---- metrics -----------------------------------------------------------

    @Test
    fun `metrics endpoint is published with its full projection`() {
        val metrics = operation("GET /v1/metrics")
        assertEquals("MetricsResponse", metrics.responseSchema("200").refName())
        assertTrue("401" in metrics.responses(), "metrics must document the unauthenticated case")
        assertTrue("503" in metrics.responses(), "metrics must document the degraded case")

        val response = schema("MetricsResponse")
        val properties = response.properties()
        listOf(
            "collections",
            "dependencies",
            "projection",
            "pendingOverlay",
            "outbox",
            "reconcile",
            "watcher",
            "requests",
            "indexing"
        ).forEach { field ->
            assertTrue(field in properties, "MetricsResponse must publish '$field'")
            assertTrue(field in response.requiredNames(), "MetricsResponse must require '$field'")
        }
        val collections = properties.getValue("collections").jsonObject
        assertEquals("array", collections.getValue("type").jsonPrimitive.content)
        assertEquals(
            "CollectionMetrics",
            collections.getValue("items").jsonObject.refName(),
            "per-collection metrics must stay typed"
        )
    }

    // ---- correlation and concurrency headers -------------------------------

    @Test
    fun `every operation accepts a caller supplied request id`() {
        operations.forEach { (id, operation) ->
            val header = operation.headerParameter("X-Request-Id")
            assertNotNull(header, "$id must accept X-Request-Id")
            assertEquals(false, header!!["required"]!!.jsonPrimitive.boolean, "$id: X-Request-Id is optional")
        }
    }

    @Test
    fun `mutating operations publish If-Match and Idempotency-Key`() {
        listOf("POST /v1/documents:upsert", "POST /v1/documents:delete").forEach { id ->
            val operation = operation(id)
            assertNotNull(operation.headerParameter("If-Match"), "$id must accept If-Match")
            assertNotNull(operation.headerParameter("Idempotency-Key"), "$id must accept Idempotency-Key")
        }
    }

    @Test
    fun `document reads and writes publish the revision ETag`() {
        listOf("POST /v1/documents:get", "POST /v1/documents:upsert", "POST /v1/documents:delete").forEach { id ->
            val headers =
                operation(id)
                    .responses()
                    .getValue("200")
                    .jsonObject["headers"]!!
                    .jsonObject
            val etag = headers["ETag"]
            assertNotNull(etag, "$id must publish an ETag response header")
            assertEquals(
                "string",
                etag!!
                    .jsonObject["schema"]!!
                    .jsonObject["type"]!!
                    .jsonPrimitive.content
            )
        }
    }

    // ---- authorization -----------------------------------------------------

    @Test
    fun `scoped operations publish 401 and 403`() {
        val anonymous = setOf("GET /v1/health", "GET /v1/live", "GET /v1/ready")
        operations.keys.filterNot { it in anonymous }.forEach { id ->
            val responses = operation(id).responses()
            assertTrue("401" in responses, "$id must document 401")
            assertTrue("403" in responses, "$id must document the collection-scope denial")
            assertEquals(
                "ErrorEnvelope",
                responses
                    .getValue("403")
                    .jsonObject
                    .contentSchema()
                    .refName(),
                "$id: 403 must carry the shared error envelope"
            )
        }
    }

    @Test
    fun `liveness probes stay unauthenticated`() {
        listOf("GET /v1/health", "GET /v1/live", "GET /v1/ready").forEach { id ->
            val operation = operation(id)
            assertTrue("security" !in operation, "$id must be reachable without a credential")
            assertTrue("200" in operation.responses(), "$id must document its 200")
        }
        assertTrue("503" in operation("GET /v1/ready").responses(), "readiness must document 503")
    }

    // ---- request bodies ----------------------------------------------------

    /**
     * Operations carrying raw bytes rather than a JSON object, split by direction.
     *
     * These are the only two, both attachment routes, and they differ: the
     * upload sends bytes and answers with JSON, the download sends nothing and
     * answers with bytes. They are named explicitly rather than sniffed from
     * content types so a future route cannot quietly fall outside the JSON rules.
     */
    private val binaryRequestOperations = setOf("POST /v1/blobs")

    private val binaryResponseOperations =
        setOf(
            "GET /v1/blobs/{hash}",
            // text/event-stream: a typed stream, not a JSON document.
            "GET /v1/events"
        )

    @Test
    fun `every POST body is required and names exactly one schema`() {
        operations.filterKeys { it.startsWith("POST ") && it !in binaryRequestOperations }.forEach { (id, operation) ->
            val body = operation.getValue("requestBody").jsonObject
            assertEquals(true, body.getValue("required").jsonPrimitive.boolean, "$id: request body must be required")
            assertTrue(
                "application/json" in body.getValue("content").jsonObject,
                "$id: request body must be application/json"
            )
            val references = body.contentSchema().schemaRefs()
            assertEquals(1, references.size, "$id: request body must name exactly one schema, found $references")
        }
    }

    @Test
    fun `a binary POST body is required and published as bytes`() {
        operations.filterKeys { it in binaryRequestOperations }.forEach { (id, operation) ->
            val body = operation.getValue("requestBody").jsonObject
            assertEquals(true, body.getValue("required").jsonPrimitive.boolean, "$id: request body must be required")
            val schema = body.contentSchema("application/octet-stream")
            assertEquals(
                "binary",
                schema["format"]?.jsonPrimitive?.content,
                "$id: a raw body must be published as string/binary, not as a JSON shape"
            )
        }
    }

    @Test
    fun `every request body is published as a closed set`() {
        operations.filterKeys { it.startsWith("POST ") && it !in binaryRequestOperations }.forEach { (id, operation) ->
            val schema = operation.getValue("requestBody").jsonObject.contentSchema()
            assertEquals(
                false,
                schema["additionalProperties"]?.jsonPrimitive?.boolean,
                "$id: the server rejects unknown fields, so the contract must publish additionalProperties: false"
            )
        }
    }

    @Test
    fun `every success response names exactly one schema`() {
        operations.filterKeys { it !in binaryResponseOperations }.forEach { (id, operation) ->
            val success =
                operation
                    .responses()
                    .getValue("200")
                    .jsonObject
                    .contentSchema()
            assertEquals(1, success.schemaRefs().size, "$id: 200 must be typed")
        }
    }

    @Test
    fun `a binary response is published as bytes`() {
        operations.filterKeys { it in binaryResponseOperations }.forEach { (id, operation) ->
            val schema =
                operation
                    .responses()
                    .getValue("200")
                    .jsonObject
                    .contentSchema("application/octet-stream")
            assertEquals(
                "binary",
                schema["format"]?.jsonPrimitive?.content,
                "$id: a raw response must be published as string/binary"
            )
        }
    }

    // ---- repeated search filters -------------------------------------------

    /**
     * The relationship vocabulary is a closed enum, not a free string.
     *
     * A traversal asking for a frontmatter key (`files`, `repo`, …) used to
     * answer `200` with an empty list because `rel` accepted any string. The
     * request schemas now reference the `TraverseRel` enum, so a generated
     * client cannot send an invalid value and a hand-written one fails with the
     * documented `400`.
     */
    @Test
    fun `traverse and backlinks constrain rel to the relationship vocabulary`() {
        val valid = setOf("links-to", "references", "embeds", "mentions")
        mapOf(
            "POST /v1/documents:traverse" to "TraverseRequest",
            "POST /v1/documents:backlinks" to "BacklinksRequest"
        ).forEach { (id, requestSchema) ->
            val op = operation(id)
            assertTrue("400" in op.responses(), "$id must document the rejected rel")
            val body = op.getValue("requestBody").jsonObject.contentSchema()
            val refs = body.schemaRefs()
            assertEquals(
                listOf(requestSchema),
                refs.map { it.substringAfterLast('/') },
                "$id must carry $requestSchema"
            )
            val properties = schema(requestSchema).properties()
            val rel = properties.getValue("rel").jsonObject
            val variants = rel["oneOf"]!!.jsonArray.map { it.jsonObject }
            assertTrue(
                variants.any { it["type"]?.jsonPrimitive?.content == "null" },
                "$id: rel stays optional"
            )
            val enumRef =
                variants.mapNotNull { it["\$ref"]?.jsonPrimitive?.content }.singleOrNull()
                    ?: error("$id: rel must reference the relationship enum")
            val enumValues =
                schema(enumRef.substringAfterLast('/'))["enum"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet()
            assertEquals(valid, enumValues, "$id: rel must publish exactly the relationship vocabulary")
        }
    }

    @Test
    fun `search repeats tags and frontmatter filters`() {
        val search = operation("GET /v1/search")
        listOf("tags", "fm").forEach { name ->
            val parameter = search.queryParameter(name)
            assertNotNull(parameter, "search must publish the '$name' filter")
            val schema = parameter!!.jsonObject["schema"]!!.jsonObject
            assertEquals("array", schema["type"]!!.jsonPrimitive.content, "'$name' must be repeatable")
            assertEquals(
                "string",
                schema["items"]!!.jsonObject["type"]!!.jsonPrimitive.content,
                "'$name' items must be strings"
            )
            assertEquals(
                true,
                parameter.jsonObject["explode"]!!.jsonPrimitive.boolean,
                "'$name' must explode so it can repeat"
            )
        }
    }

    @Test
    fun `no operation declares the same parameter twice`() {
        operations.forEach { (id, operation) ->
            val names = operation["parameters"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertEquals(names.size, names.toSet().size, "$id declares a duplicate parameter: $names")
        }
    }

    // ---- helpers -----------------------------------------------------------

    private fun operation(id: String): JsonObject = requireNotNull(operations[id]) { "no such operation: $id" }

    private fun schema(name: String): JsonObject {
        val schemas =
            spec
                .getValue("components")
                .jsonObject
                .getValue("schemas")
                .jsonObject
        return schemas[name]?.jsonObject ?: error("no such schema: $name")
    }

    private fun JsonObject.responses(): JsonObject = this.getValue("responses").jsonObject

    private fun JsonObject.properties(): JsonObject = this.getValue("properties").jsonObject

    private fun JsonObject.requiredNames(): Set<String> =
        (this["required"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty()

    private fun JsonObject.headerParameter(name: String): JsonObject? = parameter("header", name)

    private fun JsonObject.queryParameter(name: String): JsonObject? = parameter("query", name)

    private fun JsonObject.parameter(
        location: String,
        name: String
    ): JsonObject? =
        (this["parameters"] as? JsonArray)
            ?.map { it.jsonObject }
            ?.firstOrNull {
                it.getValue("name").jsonPrimitive.content == name &&
                    it.getValue("in").jsonPrimitive.content == location
            }

    /** The schema of a request body or a response object, whichever carries it. */
    private fun JsonObject.contentSchema(mediaType: String = "application/json"): JsonObject =
        this
            .getValue("content")
            .jsonObject
            .getValue(mediaType)
            .jsonObject
            .getValue("schema")
            .jsonObject

    private fun JsonObject.responseSchema(code: String): JsonObject =
        responses()
            .getValue(
                code
            ).jsonObject
            .contentSchema()

    /**
     * The schema names a media type resolves to. The generator wraps a single
     * `$ref` in `allOf` for Kotlin data classes, so both shapes count — and
     * anything else means the operation's type is not what a client expects.
     */
    private fun JsonObject.schemaRefs(): List<String> =
        (directRefs() + allOfRefs()).sorted()

    private fun JsonObject.directRefs(): List<String> =
        this["\$ref"]
            ?.jsonPrimitive
            ?.content
            ?.let(::listOf)
            .orEmpty()

    private fun JsonObject.allOfRefs(): List<String> =
        (this["allOf"] as? JsonArray)
            ?.map { it.jsonObject }
            ?.mapNotNull { it["\$ref"]?.jsonPrimitive?.content }
            .orEmpty()

    private fun JsonObject.refName(): String {
        val reference = this["\$ref"]?.jsonPrimitive?.content ?: error("expected a schema reference, found: $this")
        return reference.substringAfterLast('/')
    }

    private companion object {
        val HTTP_METHODS = setOf("get", "post", "put", "delete", "patch", "head", "options")
    }
}
