package gokorei.tanseki.adapters.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.testkit.ContextStoreRetentionContract
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.time.Instant

/**
 * Runs the shared trash/restore plus blob-retention contract against Postgres.
 *
 * This suite is why the Postgres digest-only blob divergence was caught: the shared
 * contract fetches a blob with `size = -1`, which File and SQLite accept and Postgres
 * used to reject. Without it, only [PostgresContextStoreContractTest] ran, and that
 * contract never exercises a digest-only fetch.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresContextStoreRetentionTest : ContextStoreRetentionContract() {
    /** Postgres retains tombstones but cannot reverse a delete yet. */
    override fun supportsRestore(): Boolean = false

    override fun newStore(): ContextStore {
        val source = dataSource()
        PostgresSchema.migrate(source)
        PostgresSchema.reset(source)
        return PostgresContextStore(source, Clock { Instant.fromEpochSeconds(0) })
    }

    /** Rewrites stored bytes, leaving the row's digest and size untouched. */
    override fun corruptStoredBlob(hash: String) {
        dataSource().connection.use { connection ->
            connection.prepareStatement("UPDATE blob_content SET bytes = ? WHERE hash = ?").use { statement ->
                statement.setBytes(1, "tampered".toByteArray())
                statement.setString(2, hash)
                statement.executeUpdate()
            }
        }
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
