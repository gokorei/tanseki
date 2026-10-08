package gokorei.tanseki.adapters.context.sqlite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.testkit.ContextStoreRetentionContract
import kotlin.time.Instant

/** Runs the shared trash/restore contract against the SQLite library store. */
class SqliteContextStoreRetentionTest : ContextStoreRetentionContract() {
    private lateinit var driver: JdbcSqliteDriver

    override fun newStore(): ContextStore {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SqliteSchema.create(driver)
        return SqliteContextStore(driver, Clock { Instant.fromEpochSeconds(0) })
    }

    override fun closeStore(store: ContextStore) {
        driver.close()
    }

    /** Rewrites blob content in place, so the stored digest no longer matches. */
    override fun corruptStoredBlob(hash: String) {
        // The hash is a validated lowercase digest, so inlining it here cannot
        // inject; this matches the existing corruption test in this module.
        driver.execute(
            null,
            "UPDATE blob_content SET bytes = X'74616D7065726564' WHERE hash = '$hash';",
            0
        ) {}
    }
}
