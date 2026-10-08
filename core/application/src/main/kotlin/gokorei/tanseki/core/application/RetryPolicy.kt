package gokorei.tanseki.core.application

import gokorei.tanseki.core.ports.RetryPolicy as PortsRetryPolicy
import gokorei.tanseki.core.ports.retry as portsRetry

/**
 * Backward-compatible re-export of the generic retry policy.
 *
 * The canonical type lives in `core:ports` ([PortsRetryPolicy]) so adapters can
 * reuse retry behavior without depending on application orchestration (see
 * ADR-0001). This alias stays until external consumers migrate off the old
 * import path.
 */
@Deprecated(
    "Moved to core:ports. Import gokorei.tanseki.core.ports.RetryPolicy instead.",
    ReplaceWith("gokorei.tanseki.core.ports.RetryPolicy", "gokorei.tanseki.core.ports.RetryPolicy")
)
typealias RetryPolicy = PortsRetryPolicy

/** Backward-compatible re-export of [portsRetry]. */
@Deprecated(
    "Moved to core:ports. Import gokorei.tanseki.core.ports.retry instead.",
    ReplaceWith("gokorei.tanseki.core.ports.retry(policy, block)", "gokorei.tanseki.core.ports.retry")
)
suspend fun <T> retry(
    policy: PortsRetryPolicy = PortsRetryPolicy(),
    block: suspend () -> T
): T = portsRetry(policy, block)
