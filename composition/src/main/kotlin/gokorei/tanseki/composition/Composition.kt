package gokorei.tanseki.composition

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import gokorei.tanseki.adapters.context.sqlite.SqliteContextStore
import gokorei.tanseki.adapters.context.sqlite.SqliteSchema
import gokorei.tanseki.adapters.embedder.EmbeddingModels
import gokorei.tanseki.adapters.embedder.OnnxEmbedders
import gokorei.tanseki.adapters.file.FileContextStore
import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.adapters.meili.MeiliLookup
import gokorei.tanseki.adapters.pijul.PijulCliClient
import gokorei.tanseki.adapters.plain.PlainDirectoryStore
import gokorei.tanseki.adapters.postgres.PostgresContextStore
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Embedder
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.core.ports.VectorWriter
import java.nio.file.Path

enum class Profile { VAULT, LIBRARY, SERVER, DIRECTORY }

/** A composed ContextStore + Lookup pair for one profile, plus an optional embedder. */
class Composition(
    val profile: Profile,
    val store: ContextStore,
    val lookup: Lookup,
    /** Local embedder when a model is configured and available; null = lexical-only. */
    val embedder: Embedder? = null,
    private val onClose: () -> Unit = {}
) : AutoCloseable {
    val vectorWriter: VectorWriter? = (lookup as? VectorWriter)

    override fun close() {
        runCatching { (embedder as? AutoCloseable)?.close() }
        runCatching { (lookup as? AutoCloseable)?.close() }
        runCatching { (store as? AutoCloseable)?.close() }
        runCatching(onClose)
    }
}

/**
 * The single composition point. `openLocal` selects vault/library/directory adapters;
 * `open(config)` dispatches to the server profile (Postgres + Meili) when
 * configured. Both `service` and `cli` use this factory.
 */
object Compositions {
    private val systemClock =
        Clock {
            kotlin.time.Clock.System
                .now()
        }

    /**
     * Builds the configured local embedder, or null (the documented fallback) when
     * no directory is set or the model/runtime is unavailable.
     */
    fun openEmbedder(config: TansekiConfig): Embedder? {
        val dir = config.embeddingDir ?: return null
        return OnnxEmbedders.open(dir, EmbeddingModels.resolveOrDefault(config.embeddingModel))
    }

    fun open(
        config: TansekiConfig,
        logger: TansekiLogger = TansekiLogger.Noop
    ): Composition {
        if (config.profile == Profile.SERVER) requireServerConfig(config)
        val embedder = openEmbedder(config)
        return when (config.profile) {
            Profile.SERVER -> openServer(config, embedder, logger)
            else -> openLocal(config.path, config.profile, config.indexDir, config.pijulBinary, embedder, logger)
        }
    }

    fun openLocal(
        path: Path,
        mode: Profile,
        indexDir: Path,
        pijulBinary: String = "pijul",
        embedder: Embedder? = null,
        logger: TansekiLogger = TansekiLogger.Noop
    ): Composition =
        when (mode) {
            Profile.VAULT -> {
                val store =
                    FileContextStore(
                        vault = VaultPath(path.toString()),
                        pijul = PijulCliClient(binary = pijulBinary, logger = logger),
                        clock = systemClock
                    )
                Composition(mode, store, LuceneLookup.open(indexDir, logger), embedder)
            }

            Profile.LIBRARY -> {
                path.toAbsolutePath().parent?.let {
                    java.nio.file.Files
                        .createDirectories(it)
                }
                val driver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}")
                SqliteSchema.configure(driver)
                SqliteSchema.ensure(driver)
                Composition(
                    profile = mode,
                    store = SqliteContextStore(driver, systemClock),
                    lookup = LuceneLookup.open(indexDir, logger),
                    embedder = embedder,
                    onClose = { driver.close() }
                )
            }

            Profile.SERVER -> {
                throw UnsupportedOperationException(
                    "server profile requires configuration: use TansekiConfig + Compositions.open(config)"
                )
            }

            Profile.DIRECTORY -> {
                Composition(mode, PlainDirectoryStore(path), LuceneLookup.open(indexDir, logger), embedder)
            }
        }

    /** Server profile: Postgres Context Store + Meilisearch Lookup. */
    fun openServer(
        config: TansekiConfig,
        embedder: Embedder? = null,
        logger: TansekiLogger = TansekiLogger.Noop
    ): Composition {
        requireServerConfig(config)
        val store =
            PostgresContextStore.open(
                jdbcUrl = checkNotNull(config.postgresJdbcUrl),
                user = checkNotNull(config.postgresUser),
                password = checkNotNull(config.postgresPassword),
                clock = systemClock
            )
        val lookup =
            MeiliLookup(
                baseUrl = checkNotNull(config.meiliUrl),
                apiKey = config.meiliApiKey,
                logger = logger
            )
        return Composition(Profile.SERVER, store, lookup, embedder)
    }

    internal fun requireServerConfig(config: TansekiConfig) {
        val missing =
            buildList {
                if (config.postgresJdbcUrl.isNullOrBlank()) add(TansekiConfig.ENV_POSTGRES_URL)
                if (config.postgresUser.isNullOrBlank()) add(TansekiConfig.ENV_POSTGRES_USER)
                if (config.postgresPassword.isNullOrBlank()) add(TansekiConfig.ENV_POSTGRES_PASSWORD)
                if (config.meiliUrl.isNullOrBlank()) add(TansekiConfig.ENV_MEILI_URL)
            }
        require(missing.isEmpty()) {
            "server profile requires ${missing.joinToString()}"
        }
    }
}
