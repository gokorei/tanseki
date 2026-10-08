package gokorei.tanseki.service.daemon

/**
 * The IPC operations the daemon answers, each with every wire spelling it accepts.
 *
 * The two names per operation are a compatibility affordance (`live`/`liveness`,
 * `readiness`/`health`), so keeping them beside the op as data — rather than as a
 * chain of string equality checks — makes the accepted set explicit and a new op
 * a compile error at its `when`.
 */
internal enum class IpcOp(val wires: List<String>) {
    LIVENESS(listOf("liveness", "live")),
    HEALTH(listOf("health", "readiness"));

    companion object {
        /** The op named by [value], or `null` when the caller named something else. */
        fun parse(value: String?): IpcOp? = entries.firstOrNull { value in it.wires }
    }
}
