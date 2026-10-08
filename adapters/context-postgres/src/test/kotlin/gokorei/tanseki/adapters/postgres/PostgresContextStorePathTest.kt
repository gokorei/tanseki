package gokorei.tanseki.adapters.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.testkit.ContextStorePathContract
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.time.Instant

/** Runs the shared [ContextStorePathContract] against the Postgres Context Store. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresContextStorePathTest : ContextStorePathContract() {
    override fun newStore(): ContextStore {
        val dataSource = dataSource()
        PostgresSchema.migrate(dataSource)
        PostgresSchema.reset(dataSource)
        return PostgresContextStore(dataSource, Clock { Instant.fromEpochSeconds(0) })
    }

    override fun closeStore(store: ContextStore) = Unit

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName
                    .parse("postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea")
                    .asCompatibleSubstituteFor("postgres")
            ).withDatabaseName("tanseki")
                .withUsername("tanseki")
                .withPassword("tanseki")
                .waitingFor(
                    org.testcontainers.containers.wait.strategy.Wait
                        .forListeningPort()
                )

        private var dataSource: HikariDataSource? = null

        private fun dataSource(): HikariDataSource =
            dataSource ?: HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 2
                    initializationFailTimeout = 20_000
                }
            ).also { dataSource = it }
    }
}
