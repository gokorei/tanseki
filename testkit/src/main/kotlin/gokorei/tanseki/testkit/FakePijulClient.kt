package gokorei.tanseki.testkit

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.PijulClient
import gokorei.tanseki.core.ports.PijulPatch
import gokorei.tanseki.core.ports.PijulStatus
import kotlin.time.Instant

/**
 * Deterministic in-memory [PijulClient] for unit tests and adapter contract
 * suites. It is supplied by `testkit` so every adapter shares the same fake.
 */
class FakePijulClient(
    private val clock: () -> Instant = { Instant.fromEpochSeconds(0) },
    private val hashPrefix: String = "patch"
) : PijulClient {
    private var counter = 0
    private val recordedCalls = mutableListOf<List<String>>()

    /** Recorded `record` path arguments, as a snapshot safe to read concurrently. */
    val recordCalls: List<List<String>>
        @Synchronized
        get() = recordedCalls.toList()

    private val patches = mutableMapOf<String, MutableList<PijulPatch>>()

    override fun status(vault: VaultPath): PijulStatus = PijulStatus(clean = true)

    @Synchronized
    override fun log(vault: VaultPath, docId: DocId?, limit: Int): List<PijulPatch> =
        docId?.let { patches["${it.value}.md"].orEmpty() } ?: patches.values.flatten()

    override fun diff(vault: VaultPath, path: String?): String = ""

    @Synchronized
    override fun record(
        vault: VaultPath,
        message: String,
        author: String,
        paths: List<String>,
        operationId: String?
    ): PijulPatch {
        recordedCalls += paths
        val patch =
            PijulPatch(RevisionId("$hashPrefix-${++counter}"), author, message, clock(), operationId = operationId)
        paths.forEach { path -> patches.getOrPut(path) { mutableListOf() } += patch }
        return patch
    }

    override fun apply(vault: VaultPath, patch: String) = Unit
}
