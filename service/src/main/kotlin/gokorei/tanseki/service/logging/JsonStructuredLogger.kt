package gokorei.tanseki.service.logging

import gokorei.tanseki.core.ports.LogLevel
import gokorei.tanseki.core.ports.TansekiLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

/**
 * JSON-lines [TansekiLogger]: one JSON object per record on [sink]. Fields are
 * flattened onto the record; the level is filtered by [level].
 */
class JsonStructuredLogger(
    private val sink: (String) -> Unit,
    override val level: LogLevel,
    private val clock: Clock = Clock.System
) : TansekiLogger {
    private val json = Json

    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        if (!isEnabled(level)) return
        val record =
            buildJsonObject {
                put("timestamp", clock.now().toString())
                put("level", level.name)
                put("message", message)
                // The throwable's message is never emitted: it can carry adapter
                // stderr, endpoints, credentials, or document content.
                error?.let { put("errorType", Redaction.errorType(it)) }
                fields.forEach { (key, value) -> put(key, render(value)) }
            }
        sink(json.encodeToString(JsonObject.serializer(), record))
    }

    private fun render(value: Any?): JsonPrimitive =
        when (value) {
            null -> JsonPrimitive(null as String?)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
}
