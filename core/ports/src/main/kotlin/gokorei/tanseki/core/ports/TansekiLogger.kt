package gokorei.tanseki.core.ports

/** Severity, ordered from most to least verbose. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Structured logging port. Cross-cutting: the daemon, indexer, and adapters all
 * emit structured events through it, so core stays free of a logging framework.
 * Implementations add the timestamp and serialize; callers pass typed fields.
 */
interface TansekiLogger {
    /** Minimum level this logger emits. */
    val level: LogLevel

    fun isEnabled(level: LogLevel): Boolean = level.ordinal >= this.level.ordinal

    fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?)

    fun debug(message: String, fields: Map<String, Any?> = emptyMap()) =
        log(LogLevel.DEBUG, message, fields, null)

    fun info(message: String, fields: Map<String, Any?> = emptyMap()) =
        log(LogLevel.INFO, message, fields, null)

    fun warn(message: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null) =
        log(LogLevel.WARN, message, fields, error)

    fun error(message: String, error: Throwable? = null, fields: Map<String, Any?> = emptyMap()) =
        log(LogLevel.ERROR, message, fields, error)

    /** Returns a logger that attaches [fields] to every record. */
    fun with(fields: Map<String, Any?>): TansekiLogger = FieldsTansekiLogger(this, fields)

    /** Returns a logger carrying a correlation id (for request/indexer tracing). */
    fun withCorrelationId(id: String): TansekiLogger = with(mapOf(FIELD_CORRELATION_ID to id))

    companion object {
        const val FIELD_CORRELATION_ID = "correlationId"

        val Noop: TansekiLogger =
            object : TansekiLogger {
                override val level: LogLevel = LogLevel.ERROR

                override fun isEnabled(level: LogLevel): Boolean = false

                override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) = Unit
            }
    }
}

private class FieldsTansekiLogger(
    private val delegate: TansekiLogger,
    private val fields: Map<String, Any?>
) : TansekiLogger {
    override val level: LogLevel get() = delegate.level

    override fun isEnabled(level: LogLevel): Boolean = delegate.isEnabled(level)

    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) =
        delegate.log(level, message, this.fields + fields, error)

    override fun with(fields: Map<String, Any?>): TansekiLogger = FieldsTansekiLogger(delegate, this.fields + fields)
}
