package gokorei.tanseki.service.mcp

import gokorei.tanseki.composition.Composition
import gokorei.tanseki.composition.Compositions
import gokorei.tanseki.composition.Profile
import gokorei.tanseki.composition.TansekiConfig
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.domain.VaultDirs
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.service.daemon.VaultLock
import gokorei.tanseki.service.logging.JsonStructuredLogger
import gokorei.tanseki.service.logging.LoggingConfig
import gokorei.tanseki.service.logging.Redaction
import java.util.concurrent.CountDownLatch

/**
 * `tanseki-mcp` entry point: an MCP server over **stdio**.
 *
 * stdout is reserved for JSON-RPC, so all logs go to **stderr**. Store access is
 * either the running daemon's HTTP API (`TANSEKI_URL`) or an embedded composition.
 *
 * Env: `TANSEKI_URL` (daemon base URL, e.g. http://localhost:8088), `TANSEKI_API_KEY`,
 * plus the usual `TANSEKI_PROFILE`/`TANSEKI_PATH`/`TANSEKI_INDEX_DIR` for embedded mode.
 */
fun main() {
    val level = LoggingConfig.levelFromEnv()
    val logger = JsonStructuredLogger(sink = { System.err.println(it) }, level = level)
    val config = TansekiConfig.fromEnv()
    val daemonUrl = System.getenv("TANSEKI_URL")?.takeIf { it.isNotBlank() }

    var composition: Composition? = null
    var lock: VaultLock? = null
    val store: McpStore =
        if (daemonUrl != null) {
            logger.info("tanseki-mcp proxying to daemon", mapOf("endpoint" to Redaction.endpoint(daemonUrl)))
            HttpMcpStore(daemonUrl, config.apiKey)
        } else {
            if (config.profile == Profile.VAULT) {
                val vaultLock =
                    VaultLock(
                        java.nio.file.Path
                            .of(config.path.toString())
                            .resolve(VaultDirs.DERIVED)
                            .resolve("daemon.lock")
                    )
                try {
                    vaultLock.acquire()
                } catch (error: IllegalStateException) {
                    logger.error(
                        "vault is already owned by another writer (is tanseki-daemon running?); set TANSEKI_URL to proxy to it",
                        error
                    )
                    throw error
                }
                lock = vaultLock
            }
            val opened =
                try {
                    Compositions.open(config)
                } catch (error: Throwable) {
                    // A throw from open must not leak the vault lock acquired above: the
                    // shutdown hook is registered later, so nothing else would release it.
                    lock?.release()
                    throw error
                }
            composition = opened
            val facade =
                QueryFacade(
                    store = opened.store,
                    lookup = opened.lookup,
                    clock =
                        Clock {
                            kotlin.time.Clock.System
                                .now()
                        },
                    logger = logger,
                    embedder = opened.embedder
                )
            logger.info(
                "tanseki-mcp using embedded store",
                mapOf("profile" to config.profile.name)
            )
            LocalMcpStore(facade)
        }

    val server = TansekiMcpServer(store, logger).build(System.`in`, System.out)
    logger.info(
        "tanseki-mcp ready",
        mapOf(
            "transport" to "stdio",
            "mode" to if (daemonUrl != null) "daemon" else "embedded"
        )
    )

    val latch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { server.close() }
            runCatching { (store as? AutoCloseable)?.close() }
            runCatching { composition?.close() }
            runCatching { lock?.release() }
            latch.countDown()
        }
    )
    latch.await()
}
