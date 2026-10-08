package gokorei.tanseki.core.ports

import gokorei.tanseki.core.domain.VaultPath
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** Filesystem change emitted by the OS watcher, already coalesced. */
sealed interface FsEvent {
    val path: String
    val occurredAt: Instant

    data class Created(override val path: String, override val occurredAt: Instant) : FsEvent

    data class Modified(override val path: String, override val occurredAt: Instant) : FsEvent

    data class Deleted(override val path: String, override val occurredAt: Instant) : FsEvent

    data class Overflow(override val occurredAt: Instant, override val path: String = "") : FsEvent
}

/**
 * Recursive filesystem watch behind a port so the daemon can debounce, coalesce
 * editor temp-write/rename pairs, and suppress its own writes.
 */
fun interface Watcher {
    fun events(vault: VaultPath): Flow<FsEvent>
}
