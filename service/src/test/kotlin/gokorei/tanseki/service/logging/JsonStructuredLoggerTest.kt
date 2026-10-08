package gokorei.tanseki.service.logging

import gokorei.tanseki.core.ports.LogLevel
import gokorei.tanseki.core.ports.TansekiLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonStructuredLoggerTest {
    private val lines = mutableListOf<String>()

    private fun logger(level: LogLevel = LogLevel.DEBUG) = JsonStructuredLogger(lines::add, level)

    @Test
    fun `emits a json line with level and fields`() {
        logger().info("indexed document", mapOf("doc" to "notes/a", "chunks" to 2))

        val record = Json.parseToJsonElement(lines.single()).jsonObject
        assertEquals("INFO", record["level"]!!.jsonPrimitive.content)
        assertEquals("indexed document", record["message"]!!.jsonPrimitive.content)
        assertEquals("notes/a", record["doc"]!!.jsonPrimitive.content)
        assertEquals("2", record["chunks"]!!.jsonPrimitive.content)
        assertTrue(record["timestamp"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `filters below the configured level`() {
        val logger = logger(LogLevel.WARN)

        logger.debug("noise")
        logger.info("also noise")
        logger.warn("kept")

        assertEquals(1, lines.size)
        assertEquals(
            "WARN",
            Json
                .parseToJsonElement(lines.single())
                .jsonObject["level"]!!
                .jsonPrimitive.content
        )
    }

    @Test
    fun `child fields and correlation id merge`() {
        logger()
            .with(mapOf("component" to "indexer"))
            .withCorrelationId("corr-1")
            .info("ran")

        val record = Json.parseToJsonElement(lines.single()).jsonObject
        assertEquals("indexer", record["component"]!!.jsonPrimitive.content)
        assertEquals("corr-1", record[TansekiLogger.FIELD_CORRELATION_ID]!!.jsonPrimitive.content)
    }

    @Test
    fun `includes error type without the error message`() {
        logger().error("failed", IllegalStateException("boom"))
        val record = Json.parseToJsonElement(lines.single()).jsonObject
        assertEquals("IllegalStateException", record["errorType"]!!.jsonPrimitive.content)
        assertFalse(lines.single().contains("boom"))
    }

    @Test
    fun `level is configurable from the environment`() {
        assertEquals(LogLevel.WARN, LoggingConfig.levelFromEnv(mapOf(LoggingConfig.ENV_LEVEL to "warn")))
        assertEquals(LogLevel.INFO, LoggingConfig.levelFromEnv(emptyMap()))
        assertEquals(LogLevel.DEBUG, LoggingConfig.levelFromEnv(mapOf(LoggingConfig.ENV_LEVEL to "Debug")))
    }
}
