package gokorei.tanseki.adapters.pijul

import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.UnavailableException
import gokorei.tanseki.core.ports.PijulPatch
import gokorei.tanseki.core.ports.PijulStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

/**
 * Parses the `--json` output of the `pijul` CLI. The CLI's JSON shape is not
 * frozen across versions, so this codec is deliberately lenient: unknown keys
 * are ignored and missing optional fields degrade instead of throwing.
 */
internal object PijulOutput {
    /**
     * Pijul has no `status` subcommand; derive it from `diff --json`, whose shape
     * is `{ "<path>": [ { "operation": "file add" | "root" | ... } ] }`. Clean when
     * the working tree has only `root` operations.
     */
    fun parseStatus(text: String): PijulStatus {
        if (text.isBlank()) return PijulStatus(clean = true)
        val obj = text.toJsonObject()
        val changed = mutableListOf<String>()
        for ((path, operations) in obj) {
            if (path == "/") continue
            val array =
                operations as? JsonArray
                    ?: throw UnavailableException("pijul diff returned an invalid operation list for $path")
            val significant =
                array.any { element ->
                    val operation =
                        (element as? JsonObject)?.string("operation")
                            ?: throw UnavailableException("pijul diff returned an invalid operation for $path")
                    operation != "root"
                }
            if (significant) changed += path
        }
        return PijulStatus(clean = changed.isEmpty(), modified = changed)
    }

    fun parseLog(text: String): List<PijulPatch> =
        text.toJsonArray().map { element ->
            val patch =
                element as? JsonObject
                    ?: throw UnavailableException("pijul log returned a non-object patch")
            toPatch(patch)
        }

    private fun toPatch(obj: JsonObject): PijulPatch {
        val hash =
            obj.string("hash")
                ?: obj.string("id")
                ?: throw UnavailableException("pijul patch is missing a hash")
        val rawMessage = obj.string("message") ?: obj.string("description") ?: ""
        val markerIndex = rawMessage.lastIndexOf("\n\n$OPERATION_MARKER")
        // `pijul record --description` keeps the marker in the change description,
        // so that field is authoritative. The in-message form is still read: a
        // message written by an earlier revision appended the marker to the body.
        val operationId =
            obj.string("description")?.markedOperationId()
                ?: if (markerIndex >= 0) {
                    rawMessage.substring(markerIndex + MARKER_PREFIX_LENGTH)
                } else {
                    null
                }
        val message = if (markerIndex >= 0) rawMessage.substring(0, markerIndex) else rawMessage
        return PijulPatch(
            hash = RevisionId(hash),
            author = authorOf(obj),
            message = message,
            timestamp = timestampOf(obj),
            dependencies =
                obj
                    .stringList("dependencies")
                    .map { RevisionId(it) },
            operationId = operationId?.takeIf { it.isNotBlank() }
        )
    }

    private fun String.markedOperationId(): String? =
        takeIf { OPERATION_MARKER in it }?.substringAfter(OPERATION_MARKER)?.takeIf { it.isNotBlank() }

    private fun authorOf(obj: JsonObject): String {
        when (val author = obj["author"]) {
            is JsonPrimitive -> author.contentOrNull?.let { return it }
            is JsonObject -> author.string("name")?.let { return it }
            else -> Unit
        }
        val authors = obj["authors"]
        if (authors is JsonArray) {
            when (val first = authors.firstOrNull()) {
                is JsonPrimitive -> first.contentOrNull?.let { return it }
                is JsonObject -> first.string("name")?.let { return it }
                else -> Unit
            }
        }
        return ""
    }

    private fun timestampOf(obj: JsonObject): Instant {
        val raw =
            obj.string("timestamp")
                ?: obj.string("created_at")
                ?: obj.string("date")
                ?: throw UnavailableException("pijul patch is missing a timestamp")
        return runCatching { Instant.parse(raw) }.getOrElse {
            throw UnavailableException("pijul patch has an invalid timestamp", it)
        }
    }

    internal fun String.toJsonArray(): List<JsonElement> =
        try {
            pijulJson.parseToJsonElement(trim()).jsonArray.toList()
        } catch (error: Exception) {
            throw UnavailableException("pijul log returned malformed JSON", error)
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.stringList(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        if (element !is JsonArray) throw UnavailableException("pijul patch field '$key' is not an array")
        return element.map { item ->
            (item as? JsonPrimitive)?.contentOrNull
                ?: throw UnavailableException("pijul patch field '$key' contains a non-string value")
        }
    }

    private const val OPERATION_MARKER = "tanseki-operation-id:"
    private const val MARKER_PREFIX_LENGTH = "\n\n$OPERATION_MARKER".length
}

private val pijulJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

private fun String.toJsonObject(): JsonObject =
    try {
        pijulJson.parseToJsonElement(trim()).jsonObject
    } catch (error: Exception) {
        throw UnavailableException("pijul diff returned malformed JSON", error)
    }

/**
 * Slices one `diff --json` document into per-path payloads.
 *
 * Top-level (not an object member) so the [PijulOutput] codec stays under the
 * function-count gate. `status` and every filtered `diff` read the same whole-vault map, so one
 * subprocess serves all of them: callers that need diffs for many paths
 * (or status plus diffs) run a single `diff --json -- a b` and partition
 * here instead of spawning once per path. Requested paths with no changes
 * are absent from the result.
 */
internal fun partitionDiff(text: String, paths: List<String>): Map<String, String> {
    if (text.isBlank()) return emptyMap()
    val obj = text.toJsonObject()
    return paths
        .distinct()
        .mapNotNull { path ->
            val operations = obj[path] as? JsonArray ?: return@mapNotNull null
            path to pijulJson.encodeToString(JsonElement.serializer(), operations)
        }.toMap()
}
