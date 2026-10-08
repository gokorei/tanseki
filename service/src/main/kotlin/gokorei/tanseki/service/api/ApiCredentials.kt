package gokorei.tanseki.service.api

import gokorei.tanseki.composition.ApiTlsMode
import gokorei.tanseki.composition.Profile
import gokorei.tanseki.composition.TansekiConfig
import java.security.MessageDigest

enum class ApiOperation {
    LIST,
    GET,
    QUERY,
    UPSERT,
    DELETE,
    HISTORY,
    SEARCH,
    TRAVERSE,
    METRICS
}

class ApiCredential(
    val key: String,
    val name: String,
    val collections: Set<String>?,
    val operations: Set<ApiOperation> = ApiOperation.entries.toSet()
) {
    init {
        require(key.isNotBlank()) { "API credential key must not be blank" }
        require(name.isNotBlank()) { "API credential name must not be blank" }
        require(operations.isNotEmpty()) { "API credential operations must not be empty" }
        require(collections?.none(String::isBlank) != false) { "API credential collections must not be blank" }
        require(collections == null || collections.isNotEmpty()) {
            "A scoped API credential must name at least one collection"
        }
        require(collections.orEmpty().size <= MAX_CREDENTIAL_COLLECTIONS) {
            "API credential collections exceed the supported limit"
        }
        require(collections.orEmpty().all { it.length <= MAX_CREDENTIAL_COLLECTION_LENGTH }) {
            "API credential collection name is too long"
        }
    }

    fun allows(operation: ApiOperation): Boolean = operation in operations

    fun allows(collection: String?): Boolean =
        collections == null || (collection != null && collection in collections)

    override fun toString(): String = "ApiCredential(name=$name, collections=$collections, operations=$operations)"
}

class CredentialRegistry private constructor(
    val credentials: List<ApiCredential>,
    scopedOnly: Boolean
) {
    init {
        require(credentials.isNotEmpty()) { "At least one API credential is required" }
        require(credentials.map { it.key }.toSet().size == credentials.size) {
            "API credential keys must be unique"
        }
        require(credentials.map { it.name }.toSet().size == credentials.size) {
            "API credential names must be unique"
        }
        if (scopedOnly) {
            require(credentials.all { it.collections != null }) {
                "Server credentials must be scoped to collections"
            }
        }
    }

    fun authenticate(providedKey: String?): ApiCredential? {
        val key = providedKey?.takeIf(String::isNotBlank) ?: return null
        return credentials.firstOrNull { constantTimeEquals(it.key, key) }
    }

    fun forServer(): CredentialRegistry = CredentialRegistry(credentials, scopedOnly = true)

    companion object {
        fun of(credentials: List<ApiCredential>): CredentialRegistry = CredentialRegistry(credentials, false)

        fun forServer(credentials: List<ApiCredential>): CredentialRegistry =
            CredentialRegistry(credentials, scopedOnly = true)

        fun parse(value: String): CredentialRegistry {
            require(value.isNotBlank()) { "API credential specification must not be blank" }
            val entries =
                value.split(';').map(String::trim).filter(String::isNotEmpty)
            require(entries.isNotEmpty()) { "API credential specification must contain an entry" }
            return of(entries.mapIndexed { index, entry -> parseEntry(entry, index) })
        }

        private fun parseEntry(value: String, index: Int): ApiCredential {
            val parts = value.split('|').map(String::trim)
            require(parts.size in 2..4) {
                "Invalid API credential entry ${index + 1}"
            }
            val collections =
                if (parts.size >= 3 && parts[2].isNotBlank()) {
                    parts[2].split(',').map(String::trim).toSet()
                } else {
                    null
                }
            val operations =
                if (parts.size == 4 && parts[3].isNotBlank()) {
                    parts[3]
                        .split(',')
                        .map(String::trim)
                        .map { operation ->
                            ApiOperation.entries.firstOrNull { it.name.equals(operation, ignoreCase = true) }
                                ?: throw IllegalArgumentException("Invalid API credential operation '$operation'")
                        }.toSet()
                } else {
                    ApiOperation.entries.toSet()
                }
            return ApiCredential(
                key = parts[1],
                name = parts[0],
                collections = collections,
                operations = operations
            )
        }

        private fun constantTimeEquals(expected: String, actual: String): Boolean =
            MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                actual.toByteArray(Charsets.UTF_8)
            )
    }
}

class ApiPrincipal internal constructor(
    val name: String,
    val collections: Set<String>?,
    val operations: Set<ApiOperation>,
    internal val credentialId: String
) {
    init {
        require(collections == null || collections.isNotEmpty()) {
            "A scoped API principal must name at least one collection"
        }
    }

    fun allows(operation: ApiOperation): Boolean = operation in operations

    fun allows(collection: String?): Boolean =
        collection == null || collections == null || collection in collections

    /**
     * The collections a read may span. `null` means "unscoped" (the whole
     * store); a scoped credential always yields a non-empty set, so an empty
     * scope can never be widened into a global read.
     */
    fun scopedCollections(requested: String?): Set<String>? {
        val scope = if (requested == null) collections else setOf(requested)
        require(scope == null || scope.isNotEmpty()) { "A scoped principal must resolve to a collection" }
        return scope
    }
}

class ApiAuthPolicy(
    val registry: CredentialRegistry? = null,
    private val unauthenticated: ApiPrincipal? = null,
    val trustProxy: Boolean = false
) {
    val authenticationRequired: Boolean get() = registry != null

    init {
        require(registry != null || unauthenticated != null) { "An API authentication policy is required" }
        // Both together is ambiguous: authenticate() would return the unauthenticated
        // principal and silently never consult the registry, so every credential in
        // it would be ignored. Reject the combination rather than pick a winner.
        require(registry == null || unauthenticated == null) {
            "An API authentication policy cannot combine a credential registry with an unauthenticated principal"
        }
    }

    fun authenticate(providedKey: String?): ApiPrincipal? {
        if (unauthenticated != null) return unauthenticated
        val credential = registry?.authenticate(providedKey) ?: return null
        return ApiPrincipal(credential.name, credential.collections, credential.operations, credential.name)
    }

    companion object {
        fun unrestricted(): ApiAuthPolicy =
            ApiAuthPolicy(
                unauthenticated = ApiPrincipal("unauthenticated", null, ApiOperation.entries.toSet(), "")
            )

        fun legacy(apiKey: String): ApiAuthPolicy {
            require(apiKey.isNotBlank()) { "API key must not be blank" }
            return ApiAuthPolicy(
                registry =
                    CredentialRegistry.of(
                        listOf(ApiCredential(apiKey, "legacy", null, ApiOperation.entries.toSet()))
                    )
            )
        }

        fun forRegistry(registry: CredentialRegistry, trustProxy: Boolean = false): ApiAuthPolicy =
            ApiAuthPolicy(registry = registry, trustProxy = trustProxy)

        fun forLegacyOrUnrestricted(apiKey: String?): ApiAuthPolicy =
            apiKey?.takeIf(String::isNotBlank)?.let(::legacy) ?: unrestricted()
    }
}

class ServerApiPolicy private constructor(
    val host: String,
    val port: Int,
    val authPolicy: ApiAuthPolicy,
    val tlsMode: ApiTlsMode,
    val trustProxy: Boolean,
    val trustedProxySources: Set<String>,
    /** Explicit cross-origin allowlist for browser clients. Disabled unless origins are named. */
    val cors: CorsPolicy = CorsPolicy()
) {
    init {
        require(host.isNotBlank()) { "HTTP bind host must not be blank" }
        require(port in 1..65_535) { "HTTP port must be between 1 and 65535" }
        require(!host.isWildcardAddress()) { "Wildcard HTTP binds are not permitted" }
        require(tlsMode != ApiTlsMode.NONE || !trustProxy) {
            "Proxy trust requires a trusted-proxy TLS policy"
        }
        require(tlsMode != ApiTlsMode.TRUSTED_PROXY || trustProxy) {
            "Trusted-proxy TLS policy requires explicit proxy trust"
        }
        require(!trustProxy || trustedProxySources.isNotEmpty()) {
            "Trusted-proxy mode requires TANSEKI_TRUSTED_PROXY_SOURCES"
        }
        require(trustedProxySources.none(String::isBlank)) { "Trusted-proxy sources must not be blank" }
        trustedProxySources.forEach(::validateTrustedProxySource)
    }

    companion object {
        fun fromConfig(config: TansekiConfig, registry: CredentialRegistry? = null): ServerApiPolicy {
            val loopback = config.httpHost.isLoopbackAddress()
            val secure =
                config.profile == Profile.SERVER ||
                    !loopback ||
                    config.apiTlsMode == ApiTlsMode.TRUSTED_PROXY
            if (secure) {
                require(!config.httpHost.isWildcardAddress()) {
                    "Remote HTTP exposure requires an explicit loopback or interface bind"
                }
                val configured = registry ?: config.apiCredentials?.let(CredentialRegistry::parse)
                requireNotNull(configured) {
                    "Remote/server exposure requires TANSEKI_API_CREDENTIALS"
                }
                val scoped = configured.forServer()
                if (config.trustProxy) {
                    require(config.httpHost.isLoopbackAddress()) {
                        "Trusted-proxy exposure requires a loopback listener"
                    }
                }
                if (config.profile == Profile.SERVER || !loopback) {
                    require(config.apiTlsMode == ApiTlsMode.TRUSTED_PROXY && config.trustProxy) {
                        "Remote/server exposure requires trusted-proxy TLS and TANSEKI_TRUST_PROXY=true"
                    }
                }
                return ServerApiPolicy(
                    host = config.httpHost,
                    port = config.httpPort,
                    authPolicy = ApiAuthPolicy.forRegistry(scoped, trustProxy = config.trustProxy),
                    tlsMode = config.apiTlsMode,
                    trustProxy = config.trustProxy,
                    trustedProxySources = config.trustedProxySources,
                    cors = CorsPolicy.parse(config.corsAllowOrigins)
                )
            }

            val credentialSpec = config.apiCredentials
            val legacyKey = config.apiKey
            val authPolicy =
                when {
                    registry != null -> ApiAuthPolicy.forRegistry(registry, trustProxy = config.trustProxy)
                    credentialSpec != null -> ApiAuthPolicy.forRegistry(CredentialRegistry.parse(credentialSpec))
                    legacyKey != null -> ApiAuthPolicy.legacy(legacyKey)
                    else -> ApiAuthPolicy.unrestricted()
                }
            return ServerApiPolicy(
                host = config.httpHost,
                port = config.httpPort,
                authPolicy = authPolicy,
                tlsMode = config.apiTlsMode,
                trustProxy = config.trustProxy,
                trustedProxySources = config.trustedProxySources,
                // The loopback branch honours the same CORS allowlist as the secure
                // branch: a developer running a local daemon with a browser client
                // needs the headers too, and omitting them here silently disabled
                // CORS for exactly the case the env var is set for.
                cors = CorsPolicy.parse(config.corsAllowOrigins)
            )
        }
    }
}

internal fun String.isLoopbackAddress(): Boolean =
    equals("localhost", ignoreCase = true) || startsWith("127.") || this == "::1" || this == "[::1]"

private fun String.isWildcardAddress(): Boolean = this in setOf("0.0.0.0", "::", "[::]", "*")

private const val MAX_CREDENTIAL_COLLECTIONS = 64
private const val MAX_CREDENTIAL_COLLECTION_LENGTH = 128
