package gokorei.tanseki.composition

import gokorei.tanseki.core.domain.VaultDirs
import gokorei.tanseki.core.ports.LogLevel
import java.nio.file.Path

enum class ApiTlsMode {
    NONE,
    TRUSTED_PROXY
}

/**
 * Runtime configuration for a local Tanseki instance, loaded from the environment
 * (or a map, for tests). Secrets (e.g. the API key) are never logged.
 */
data class TansekiConfig(
    val profile: Profile,
    val path: Path,
    val indexDir: Path,
    val logLevel: LogLevel,
    val apiKey: String? = null,
    val socketPath: Path? = null,
    val pijulBinary: String = "pijul",
    val postgresJdbcUrl: String? = null,
    val postgresUser: String? = null,
    val postgresPassword: String? = null,
    val meiliUrl: String? = null,
    val meiliApiKey: String? = null,
    val embeddingDir: Path? = null,
    val embeddingModel: String? = null,
    val httpHost: String = "127.0.0.1",
    val httpPort: Int = 8088,
    val apiTlsMode: ApiTlsMode = ApiTlsMode.NONE,
    val trustProxy: Boolean = false,
    val trustedProxySources: Set<String> = emptySet(),
    val apiCredentials: String? = null,
    /**
     * Explicit cross-origin allowlist for browser clients, comma separated. Empty
     * disables CORS entirely — there is no wildcard default on purpose.
     */
    val corsAllowOrigins: String? = null
) {
    companion object {
        const val ENV_PROFILE = "TANSEKI_PROFILE"
        const val ENV_PATH = "TANSEKI_PATH"
        const val ENV_INDEX_DIR = "TANSEKI_INDEX_DIR"
        const val ENV_API_KEY = "TANSEKI_API_KEY"
        const val ENV_SOCKET = "TANSEKI_SOCKET"
        const val ENV_PIJUL = "TANSEKI_PIJUL_BINARY"
        const val ENV_LOG_LEVEL = "TANSEKI_LOG_LEVEL"
        const val ENV_POSTGRES_URL = "TANSEKI_POSTGRES_URL"
        const val ENV_POSTGRES_USER = "TANSEKI_POSTGRES_USER"
        const val ENV_POSTGRES_PASSWORD = "TANSEKI_POSTGRES_PASSWORD"
        const val ENV_MEILI_URL = "TANSEKI_MEILI_URL"
        const val ENV_MEILI_KEY = "TANSEKI_MEILI_KEY"
        const val ENV_EMBEDDING_DIR = "TANSEKI_EMBEDDING_DIR"
        const val ENV_EMBEDDING_MODEL = "TANSEKI_EMBEDDING_MODEL"
        const val ENV_HTTP_HOST = "TANSEKI_HTTP_HOST"
        const val ENV_HTTP_PORT = "TANSEKI_HTTP_PORT"
        const val ENV_TLS_MODE = "TANSEKI_TLS_MODE"
        const val ENV_TRUST_PROXY = "TANSEKI_TRUST_PROXY"
        const val ENV_TRUSTED_PROXY_SOURCES = "TANSEKI_TRUSTED_PROXY_SOURCES"
        const val ENV_CORS_ALLOW_ORIGINS = "TANSEKI_CORS_ALLOW_ORIGINS"
        const val ENV_API_CREDENTIALS = "TANSEKI_API_CREDENTIALS"

        /**
         * Reads [name] from the environment map.
         *
         * The variable names are a published configuration contract (documented
         * in the README), so they are read exactly as declared and never inferred
         * from an alternative spelling.
         */
        fun read(env: Map<String, String>, name: String): String? = env[name]

        fun profileFromName(value: String): Profile {
            val normalized = value.trim().uppercase()
            return Profile.entries.firstOrNull { it.name == normalized }
                ?: throw IllegalArgumentException(
                    "unknown profile '$value': expected one of ${Profile.entries.joinToString { it.name.lowercase() }}"
                )
        }

        fun fromEnv(env: Map<String, String> = System.getenv()): TansekiConfig {
            val path = Path.of(read(env, ENV_PATH) ?: ".")
            val profile = read(env, ENV_PROFILE)?.let(::profileFromName) ?: Profile.VAULT
            return TansekiConfig(
                profile = profile,
                path = path,
                indexDir = read(env, ENV_INDEX_DIR)?.let(Path::of) ?: VaultDirs.derived(path).resolve("index"),
                logLevel = levelFromEnv(env),
                apiKey = read(env, ENV_API_KEY)?.trim()?.takeIf(String::isNotEmpty),
                socketPath = read(env, ENV_SOCKET)?.let(Path::of),
                pijulBinary = read(env, ENV_PIJUL) ?: "pijul",
                postgresJdbcUrl = read(env, ENV_POSTGRES_URL),
                postgresUser = read(env, ENV_POSTGRES_USER),
                postgresPassword = read(env, ENV_POSTGRES_PASSWORD),
                meiliUrl = read(env, ENV_MEILI_URL),
                meiliApiKey = read(env, ENV_MEILI_KEY),
                embeddingDir = read(env, ENV_EMBEDDING_DIR)?.let(Path::of),
                embeddingModel = read(env, ENV_EMBEDDING_MODEL),
                httpHost = read(env, ENV_HTTP_HOST)?.trim()?.takeIf(String::isNotEmpty) ?: "127.0.0.1",
                httpPort =
                    read(env, ENV_HTTP_PORT)?.let { value ->
                        value.toIntOrNull() ?: throw IllegalArgumentException("invalid $ENV_HTTP_PORT '$value'")
                    } ?: 8088,
                apiTlsMode = tlsModeFromEnv(env),
                trustProxy = trustProxyFromEnv(env),
                trustedProxySources = trustedProxySourcesFromEnv(env),
                apiCredentials = read(env, ENV_API_CREDENTIALS)?.trim()?.takeIf(String::isNotEmpty),
                corsAllowOrigins = read(env, ENV_CORS_ALLOW_ORIGINS)?.trim()?.takeIf(String::isNotEmpty)
            )
        }

        private fun tlsModeFromEnv(env: Map<String, String>): ApiTlsMode {
            val value = read(env, ENV_TLS_MODE)?.trim()?.takeIf(String::isNotEmpty) ?: return ApiTlsMode.NONE
            return ApiTlsMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("invalid $ENV_TLS_MODE '$value'")
        }

        private fun trustProxyFromEnv(env: Map<String, String>): Boolean {
            val value = read(env, ENV_TRUST_PROXY)?.trim()?.takeIf(String::isNotEmpty)?.lowercase() ?: return false
            return value.toBooleanStrictOrNull()
                ?: throw IllegalArgumentException("invalid $ENV_TRUST_PROXY '$value'")
        }

        private fun trustedProxySourcesFromEnv(env: Map<String, String>): Set<String> =
            read(env, ENV_TRUSTED_PROXY_SOURCES)
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.toSet()
                ?: emptySet()

        private fun levelFromEnv(env: Map<String, String>): LogLevel {
            // An unset or empty value takes the default; a value that is set but
            // unmatched is an error rather than a silent downgrade to INFO, which
            // would hide a typo in a level the operator explicitly asked for.
            val value = read(env, ENV_LOG_LEVEL)?.trim()?.takeIf(String::isNotEmpty) ?: return LogLevel.INFO
            return LogLevel.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("invalid $ENV_LOG_LEVEL '$value'")
        }
    }
}
