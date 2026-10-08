package gokorei.tanseki.core.domain

/** Consistency guarantee an adapter can offer. */
enum class Consistency {
    STRONG,
    READ_YOUR_WRITES,
    EVENTUAL
}

/**
 * Honest advertising of what a [gokorei.tanseki.core.ports.ContextStore] adapter can do,
 * so callers degrade gracefully instead of guessing.
 */
data class StoreCapabilities(
    val supportsHistory: Boolean,
    val supportsPatchGraph: Boolean,
    val supportsTransactions: Boolean,
    val consistency: Consistency,
    /**
     * Whether foreign revision records can be imported via
     * [gokorei.tanseki.core.ports.ContextStore.appendHistory]. Storage-backed adapters
     * (SQLite/Postgres) can; vault mode cannot, because its history is owned by
     * the Pijul patch DAG.
     */
    val supportsHistoryImport: Boolean = false,
    val supportsTombstoneEnumeration: Boolean = false,
    val supportsTombstoneImport: Boolean = false,
    /**
     * Whether the adapter can act as a multi-device sync replica (ADR 0001).
     * Vault (Pijul channel transport) and Postgres (TransferState batches)
     * participate; SQLite and local-file stores do not initially.
     */
    val supportsSync: Boolean = false
)
