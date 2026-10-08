package gokorei.tanseki.service.mcp

import dev.toonformat.jtoon.Delimiter
import dev.toonformat.jtoon.EncodeOptions
import dev.toonformat.jtoon.JToon
import dev.toonformat.jtoon.KeyFolding
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * TOON (Token-Oriented Object Notation) encoding for the MCP seam.
 *
 * The format itself is implemented by JToon, the reference JVM implementation
 * from the TOON project (`dev.toonformat:jtoon`, MIT licensed). This object only
 * adapts Tanseki's [JsonElement] payloads onto that library's `Object` API and
 * holds the encoding options Tanseki pins; it deliberately owns no TOON logic of
 * its own.
 *
 * The rationale, which used to live in an ADR this repository no longer carries:
 * MCP tool results are injected straight into a model's context, so JSON's
 * repeated keys, quotes and brackets are pure token tax, and TOON is 30-60%
 * cheaper for the same values while staying human-readable. That is why only this
 * seam encodes to TOON — the HTTP seam keeps JSON, where a programmatic consumer
 * is reading structure rather than spending a context window on it.
 *
 * `THIRD_PARTY_NOTICES.md` carries the required attribution notice.
 */
object Toon {
    private const val INDENT = 2

    /**
     * Tanseki's pinned encoding options: two-space indent, comma delimiters, no
     * length marker, and key folding off. Folding is off deliberately — it
     * collapses single-key wrapper chains, which would rewrite document shapes
     * rather than just their serialisation.
     */
    private val OPTIONS =
        EncodeOptions(
            INDENT,
            Delimiter.COMMA,
            false,
            KeyFolding.OFF,
            Int.MAX_VALUE
        )

    /**
     * Encode a tool payload. The response *shape* — which keys a tool returns, and
     * what an error looks like — belongs to the tool layer, which shapes it with
     * `buildJsonObject` before calling this. The encoder owns the wire format and
     * nothing else; an earlier version also decided the envelope, which duplicated
     * the array length TOON already emits and gave each tool a different key.
     *
     * A null root is the one shape `JToon.encode` cannot be handed directly, and
     * TOON writes it as a bare `null` literal, so it is emitted directly.
     */
    fun encode(value: JsonElement): String = JToon.encode(value.toPlainJava() ?: return "null", OPTIONS)

    /**
     * Convert to the plain Java tree JToon accepts. [JToon.encode] takes
     * `Object` and normalizes it internally, so only the JSON data model needs
     * translating here — arrays become [List], objects become [Map], and
     * primitives become the narrowest matching boxed type. Insertion order is
     * preserved, because TOON's tabular header is emitted in key order.
     */
    private fun JsonElement.toPlainJava(): Any? =
        when (this) {
            is JsonNull -> null
            is JsonObject -> toPlainJavaMap()
            is JsonArray -> map { it.toPlainJava() }
            is JsonPrimitive -> toPlainJavaPrimitive()
            else -> toString()
        }

    /**
     * Copied into a [LinkedHashMap] rather than collected from a stream so the
     * declaration order of the JSON object survives: TOON emits a tabular
     * header in key order, so a reordering map would silently change the wire
     * format of an otherwise identical document.
     */
    private fun JsonObject.toPlainJavaMap(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>(size)
        for ((key, value) in this) map[key] = value.toPlainJava()
        return map
    }

    private fun JsonPrimitive.toPlainJavaPrimitive(): Any? {
        if (isString) return content
        booleanOrNull?.let { return it }
        longOrNull?.let { return it }
        doubleOrNull?.let { return it }
        return content
    }
}
