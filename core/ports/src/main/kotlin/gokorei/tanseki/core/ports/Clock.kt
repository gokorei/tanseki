package gokorei.tanseki.core.ports

import kotlin.time.Instant

/** Injectable time source; keeps core testable and free of wall-clock coupling. */
fun interface Clock {
    fun now(): Instant
}
