package gokorei.tanseki.service.api

import gokorei.tanseki.core.application.ChangeFeed
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.ports.TansekiLogger
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import kotlinx.coroutines.runBlocking
import java.net.InetAddress

internal class UntrustedProxyException : RuntimeException()

private data class TrustedProxyRule(
    val address: InetAddress,
    val prefixBits: Int
) {
    fun matches(candidate: InetAddress): Boolean {
        val network = normalizedAddress(address)
        val source = normalizedAddress(candidate)
        if (network.size != source.size) return false
        var remaining = prefixBits
        network.indices.forEach { index ->
            if (remaining <= 0) return@forEach
            val bits = minOf(remaining, Byte.SIZE_BITS)
            val mask = (0xff shl (Byte.SIZE_BITS - bits)) and 0xff
            if ((network[index].toInt() and mask) != (source[index].toInt() and mask)) return false
            remaining -= bits
        }
        return true
    }
}

internal fun validateTrustedProxySource(value: String) {
    require(value.isNotBlank()) { "Trusted-proxy sources must not be blank" }
    val parts = value.split('/', limit = 2)
    val address = parseAddress(parts[0])
    val prefix =
        if (parts.size == 1) {
            normalizedAddress(address).size * Byte.SIZE_BITS
        } else {
            parts[1].trim().toIntOrNull()
                ?: throw IllegalArgumentException("Invalid trusted-proxy prefix in '$value'")
        }
    val maximum = normalizedAddress(address).size * Byte.SIZE_BITS
    require(prefix in 0..maximum) { "Invalid trusted-proxy prefix in '$value'" }
}

internal fun isTrustedProxySource(
    remoteHost: String,
    sources: Set<String>
): Boolean {
    val candidate =
        runCatching {
            InetAddress.getByName(remoteHost.trim().removePrefix("[").removeSuffix("]"))
        }.getOrNull() ?: return false
    return sources.any { source ->
        val parts = source.split('/', limit = 2)
        val address = parseAddress(parts[0])
        val prefix =
            if (parts.size == 1) {
                normalizedAddress(address).size * Byte.SIZE_BITS
            } else {
                parts[1].trim().toInt()
            }
        TrustedProxyRule(address, prefix).matches(candidate)
    }
}

private fun parseAddress(value: String): InetAddress {
    val literal = value.trim().removePrefix("[").removeSuffix("]")
    require(isIpLiteral(literal)) {
        "Trusted-proxy sources must be IP literals or CIDR ranges: '$value'"
    }
    return runCatching { InetAddress.getByName(literal) }
        .getOrElse { throw IllegalArgumentException(invalidSourceMessage(value)) }
}

private fun invalidSourceMessage(value: String): String =
    "Trusted-proxy sources must be IP literals or CIDR ranges: '$value'"

/**
 * A syntactically valid IP literal, with no name resolution.
 *
 * Character-class checks alone are not enough: `[0-9A-Fa-f:.]+` also accepts
 * `127.0.0.1:1234` and `:`, which would let a client smuggle a port or garbage
 * into the trusted-proxy allowlist or the claimed client address.
 */
internal fun isIpLiteral(value: String): Boolean = isIpv4Literal(value) || isIpv6Literal(value)

private fun isIpv4Literal(value: String): Boolean =
    IPV4_LITERAL.matches(value) && value.split('.').all { (it.toIntOrNull() ?: 256) <= 255 }

private fun isIpv6Literal(value: String): Boolean {
    if (value.isEmpty() || !IPV6_CHARS.matches(value)) return false
    val halves = value.split("::")
    if (halves.size > 2) return false
    // An elision is exactly two colons: a half may not still start or end with
    // one, which is what separates `2001:db8::1` from `2001:db8:::1`.
    if (halves.any { it.startsWith(':') || it.endsWith(':') }) return false
    val head = halves[0].split(':').filter(String::isNotEmpty)
    val tail =
        halves
            .getOrNull(1)
            ?.split(':')
            ?.filter(String::isNotEmpty)
            .orEmpty()
    val groups = head + tail
    if (groups.isEmpty()) return false
    val embedded = groups.indexOfLast { it.contains('.') }
    if (embedded >= 0 && (embedded != groups.lastIndex || !isIpv4Literal(groups[embedded]))) return false
    val hextets = groups.filter { !it.contains('.') }
    if (hextets.any { !HEXTET.matches(it) }) return false
    val expected = if (embedded >= 0) 6 else 8
    return if (halves.size == 2) hextets.size < expected else hextets.size == expected
}

private fun normalizedAddress(value: InetAddress): ByteArray {
    val bytes = value.address
    if (bytes.size == 16 && bytes.take(12).all { it == 0.toByte() } && bytes[12] == 0xff.toByte()) {
        return bytes.copyOfRange(12, 16)
    }
    return bytes
}

private fun Application.installTrustedProxySourcePolicy(
    trustProxy: Boolean,
    sources: Set<String>
) {
    install(
        createApplicationPlugin("TrustedProxySources") {
            onCall { call -> enforceProxySource(call, trustProxy, sources) }
        }
    )
}

private fun enforceProxySource(
    call: ApplicationCall,
    trustProxy: Boolean,
    sources: Set<String>
) {
    val forwarding = forwardingMetadata(call)
    if (trustProxy) {
        requireTrustedForwarding(call, sources, forwarding)
    } else if (forwarding != null) {
        throw UntrustedProxyException()
    }
}

/**
 * A trusted-proxy listener only answers a request that arrived from an
 * allowlisted source *and* carries a complete forwarded client identity over
 * HTTPS; anything else is a client talking to the proxy port directly.
 */
private fun requireTrustedForwarding(
    call: ApplicationCall,
    sources: Set<String>,
    forwarding: Pair<String?, String?>?
) {
    if (!isTrustedProxySource(call.request.origin.remoteHost, sources)) throw UntrustedProxyException()
    val (proto, clientAddress) = forwarding ?: (null to null)
    val forwardedHttps = proto.equals(HTTPS_SCHEME, ignoreCase = true)
    if (!forwardedHttps || clientAddress == null || !isValidClientAddress(clientAddress)) {
        throw UntrustedProxyException()
    }
}

private fun forwardingMetadata(call: ApplicationCall): Pair<String?, String?>? {
    val headers = call.request.headers
    if (FORWARDING_HEADERS.none { headers.contains(it) }) return null
    val proto = headers[HttpHeaders.XForwardedProto]?.trim()?.takeIf { it.isNotEmpty() }
    val client = headers[HttpHeaders.XForwardedFor]?.trim()?.takeIf { it.isNotEmpty() }
    return proto to client
}

/**
 * The client address claimed by a trusted proxy: the right-most hop is the
 * closest to us, and it must be a real IP literal (never a hostname, a
 * `host:port` pair, or an `unknown` placeholder injected by a client).
 */
private fun isValidClientAddress(raw: String): Boolean {
    val hops = raw.split(',').map(String::trim)
    if (hops.isEmpty() || hops.size > MAX_FORWARDED_HOPS) return false
    return hops.all { hop ->
        if (hop.isEmpty() || hop != hop.trim()) return@all false
        if (hop.startsWith("[")) {
            val close = hop.indexOf(']')
            close > 1 && close == hop.lastIndex && isIpLiteral(hop.substring(1, close))
        } else {
            isIpLiteral(hop)
        }
    }
}

private const val HTTPS_SCHEME = "https"
private const val MAX_FORWARDED_HOPS = 32

private val FORWARDING_HEADERS =
    listOf("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto")
private val IPV4_LITERAL = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
private val IPV6_CHARS = Regex("^[0-9A-Fa-f:.]+$")
private val HEXTET = Regex("^[0-9A-Fa-f]{1,4}$")

/** A running [StoreApiServer] bound to an OS-assigned loopback port. */
class RunningApiServer(
    val port: Int,
    private val server: EmbeddedServer<*, *>
) : AutoCloseable {
    val baseUrl: String get() = "http://127.0.0.1:$port"

    override fun close() {
        server.stop(100, 500)
    }
}

/** Starts the HTTP seam in-process; returns the running server for lifecycle control. */
object StoreApiServer {
    /**
     * Binds to an OS-assigned port and reports it. Callers that would otherwise
     * probe a port with `ServerSocket(0)`, close it, and bind later cannot lose
     * the port to another process or a parallel test.
     */
    fun startEphemeral(
        facade: QueryFacade,
        apiKey: String? = null,
        logger: TansekiLogger = TansekiLogger.Noop,
        idempotency: IdempotencyStore = IdempotencyStore(),
        metrics: MetricsRecorder? = null,
        trustProxy: Boolean = false,
        trustedProxySources: Set<String> = emptySet(),
        changeFeed: ChangeFeed? = null
    ): RunningApiServer {
        val server =
            start(facade, 0, apiKey, logger, idempotency, metrics, trustProxy, trustedProxySources, changeFeed)
        val port = runBlocking { server.engine.resolvedConnectors() }.first().port
        return RunningApiServer(port, server)
    }

    /** Ephemeral loopback bind for tests and embedded callers with an explicit auth policy. */
    fun startEphemeral(
        facade: QueryFacade,
        authPolicy: ApiAuthPolicy,
        logger: TansekiLogger = TansekiLogger.Noop,
        idempotency: IdempotencyStore = IdempotencyStore(),
        metrics: MetricsRecorder? = null,
        trustProxy: Boolean = authPolicy.trustProxy,
        trustedProxySources: Set<String> = emptySet(),
        cors: CorsPolicy = CorsPolicy(),
        changeFeed: ChangeFeed? = null
    ): RunningApiServer {
        val server =
            startInternal(
                facade = facade,
                host = "127.0.0.1",
                port = 0,
                authPolicy = authPolicy,
                logger = logger,
                idempotency = idempotency,
                metrics = metrics,
                trustProxy = trustProxy,
                trustedProxySources = trustedProxySources,
                cors = cors,
                changeFeed = changeFeed
            )
        val port = runBlocking { server.engine.resolvedConnectors() }.first().port
        return RunningApiServer(port, server)
    }

    /** Ephemeral bind that keeps a [ServerApiPolicy]'s authorization and TLS posture. */
    fun startEphemeral(
        facade: QueryFacade,
        serverPolicy: ServerApiPolicy,
        logger: TansekiLogger = TansekiLogger.Noop,
        idempotency: IdempotencyStore = IdempotencyStore(),
        metrics: MetricsRecorder? = null
    ): RunningApiServer {
        val server =
            startInternal(
                facade = facade,
                host = serverPolicy.host,
                port = 0,
                authPolicy = serverPolicy.authPolicy,
                logger = logger,
                idempotency = idempotency,
                metrics = metrics,
                trustProxy = serverPolicy.trustProxy,
                trustedProxySources = serverPolicy.trustedProxySources,
                cors = serverPolicy.cors
            )
        val port = runBlocking { server.engine.resolvedConnectors() }.first().port
        return RunningApiServer(port, server)
    }

    fun start(
        facade: QueryFacade,
        port: Int,
        apiKey: String? = null,
        logger: TansekiLogger = TansekiLogger.Noop,
        idempotency: IdempotencyStore = IdempotencyStore(),
        metrics: MetricsRecorder? = null,
        trustProxy: Boolean = false,
        trustedProxySources: Set<String> = emptySet(),
        changeFeed: ChangeFeed? = null
    ): EmbeddedServer<*, *> =
        start(
            facade = facade,
            host = "127.0.0.1",
            port = port,
            authPolicy = ApiAuthPolicy.forLegacyOrUnrestricted(apiKey),
            logger = logger,
            idempotency = idempotency,
            metrics = metrics,
            trustProxy = trustProxy,
            trustedProxySources = trustedProxySources,
            changeFeed = changeFeed
        )

    fun start(
        facade: QueryFacade,
        serverPolicy: ServerApiPolicy,
        logger: TansekiLogger = TansekiLogger.Noop,
        idempotency: IdempotencyStore = IdempotencyStore(),
        metrics: MetricsRecorder? = null,
        changeFeed: ChangeFeed? = null
    ): EmbeddedServer<*, *> =
        startInternal(
            facade = facade,
            host = serverPolicy.host,
            port = serverPolicy.port,
            authPolicy = serverPolicy.authPolicy,
            logger = logger,
            idempotency = idempotency,
            metrics = metrics,
            trustProxy = serverPolicy.trustProxy,
            trustedProxySources = serverPolicy.trustedProxySources,
            changeFeed = changeFeed
        )

    fun start(
        facade: QueryFacade,
        host: String,
        port: Int,
        authPolicy: ApiAuthPolicy,
        logger: TansekiLogger = TansekiLogger.Noop,
        idempotency: IdempotencyStore = IdempotencyStore(),
        metrics: MetricsRecorder? = null,
        trustProxy: Boolean = authPolicy.trustProxy,
        trustedProxySources: Set<String> = emptySet(),
        changeFeed: ChangeFeed? = null
    ): EmbeddedServer<*, *> {
        require(host.isLoopbackAddress()) { "Remote HTTP binds must use ServerApiPolicy" }
        validateProxyTrust(trustProxy, trustedProxySources)
        return startInternal(
            facade = facade,
            host = host,
            port = port,
            authPolicy = authPolicy,
            logger = logger,
            idempotency = idempotency,
            metrics = metrics,
            trustProxy = trustProxy,
            trustedProxySources = trustedProxySources,
            changeFeed = changeFeed
        )
    }

    private fun validateProxyTrust(trustProxy: Boolean, sources: Set<String>) {
        require(!trustProxy || sources.isNotEmpty()) {
            "Trusted-proxy mode requires at least one trusted proxy source"
        }
        require(sources.none(String::isBlank)) { "Trusted-proxy sources must not be blank" }
        sources.forEach(::validateTrustedProxySource)
    }

    private fun startInternal(
        facade: QueryFacade,
        host: String,
        port: Int,
        authPolicy: ApiAuthPolicy,
        logger: TansekiLogger,
        idempotency: IdempotencyStore,
        metrics: MetricsRecorder?,
        trustProxy: Boolean,
        trustedProxySources: Set<String>,
        cors: CorsPolicy = CorsPolicy(),
        changeFeed: ChangeFeed? = null
    ): EmbeddedServer<*, *> {
        validateProxyTrust(trustProxy, trustedProxySources)
        return embeddedServer(CIO, host = host, port = port) {
            storeApi(
                facade = facade,
                authPolicy = authPolicy,
                idempotency = idempotency,
                logger = logger,
                metrics = metrics,
                cors = cors,
                changeFeed = changeFeed
            )
            installTrustedProxySourcePolicy(trustProxy, trustedProxySources)
        }.start(wait = false)
    }
}
