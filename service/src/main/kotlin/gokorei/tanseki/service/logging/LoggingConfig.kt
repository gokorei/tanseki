package gokorei.tanseki.service.logging

import gokorei.tanseki.core.ports.LogLevel

/** Resolves the configured log level from the environment. */
object LoggingConfig {
    const val ENV_LEVEL = "TANSEKI_LOG_LEVEL"

    fun levelFromEnv(env: Map<String, String> = System.getenv()): LogLevel =
        env[ENV_LEVEL]?.trim()?.uppercase()?.let { name ->
            LogLevel.entries.firstOrNull { it.name == name }
        } ?: LogLevel.INFO
}
