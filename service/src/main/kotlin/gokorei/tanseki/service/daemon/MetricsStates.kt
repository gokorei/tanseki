package gokorei.tanseki.service.daemon

/**
 * The closed sets of state the metrics and readiness responses publish, each
 * carrying the wire spelling it has always used.
 *
 * They were raw strings, so a value could be misspelled into a silent fallthrough
 * and a new state could be forgotten in a `when`. As enums, an unhandled state is
 * a compile error, an invalid one cannot be constructed, and the JSON contract is
 * unchanged because each value maps back through [wire].
 */
internal enum class WatcherState(val wire: String) {
    NOT_CONFIGURED("not_configured"),
    STARTING("starting"),
    RUNNING("running"),
    RECOVERING("recovering"),
    STOPPED("stopped"),
    NOT_APPLICABLE("not_applicable")
}

internal enum class ProjectionState(val wire: String) {
    FRESH("fresh"),
    STALE("stale"),
    DEGRADED("degraded")
}

internal enum class DependencyState(val wire: String) {
    AVAILABLE("available"),
    UNAVAILABLE("unavailable"),
    NOT_CONFIGURED("not_configured")
}

internal enum class ReadinessStatus(val wire: String) {
    READY("ready"),
    UNAVAILABLE("unavailable"),
    DEGRADED("degraded")
}
