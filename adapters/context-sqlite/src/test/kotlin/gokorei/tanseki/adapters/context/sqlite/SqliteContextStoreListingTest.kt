package gokorei.tanseki.adapters.context.sqlite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.testkit.ContextStoreListingContract
import kotlin.time.Instant

/** Runs the shared listing/cursor contract against the SQLite library store. */
class SqliteContextStoreListingTest : ContextStoreListingContract() {
    private lateinit var driver: JdbcSqliteDriver

    override fun newStore(): ContextStore {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SqliteSchema.create(driver)
        return SqliteContextStore(driver, Clock { Instant.fromEpochSeconds(0) })
    }

    override fun closeStore(store: ContextStore) {
        driver.close()
    }
}
