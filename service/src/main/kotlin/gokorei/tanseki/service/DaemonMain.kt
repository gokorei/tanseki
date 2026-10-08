package gokorei.tanseki.service

import gokorei.tanseki.composition.TansekiConfig
import gokorei.tanseki.service.api.ServerApiPolicy
import gokorei.tanseki.service.api.StoreApiServer
import gokorei.tanseki.service.daemon.DaemonConfig
import gokorei.tanseki.service.daemon.DaemonFactory
import gokorei.tanseki.service.logging.JsonStructuredLogger
import gokorei.tanseki.service.logging.Redaction

fun main() {
    val config = TansekiConfig.fromEnv()
    val serverPolicy = ServerApiPolicy.fromConfig(config)
    val logger = JsonStructuredLogger(sink = { println(it) }, level = config.logLevel)
    val handles =
        DaemonFactory.open(
            config,
            DaemonConfig.watchDebounceFromEnv(),
            logger,
            DaemonConfig.reconcileIntervalFromEnv()
        )
    handles.daemon.start()

    val http =
        StoreApiServer.start(
            facade = handles.facade,
            serverPolicy = serverPolicy,
            logger = logger,
            metrics = handles.metrics,
            changeFeed = handles.changeFeed
        )
    logger.info(
        "tanseki daemon running",
        mapOf(
            "profile" to config.profile.name.lowercase(),
            "path" to Redaction.fingerprint(config.path.toString()),
            "socket" to config.socketPath?.toString()?.let(Redaction::fileName),
            "http_host" to serverPolicy.host,
            "http_port" to serverPolicy.port,
            "tls_mode" to serverPolicy.tlsMode.name,
            "trust_proxy" to serverPolicy.trustProxy,
            "auth" to serverPolicy.authPolicy.authenticationRequired,
            "level" to config.logLevel.name
        )
    )

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { http.stop(500, 1_000) }
            runCatching { handles.close() }
        }
    )
    while (true) {
        Thread.sleep(60_000)
    }
}
