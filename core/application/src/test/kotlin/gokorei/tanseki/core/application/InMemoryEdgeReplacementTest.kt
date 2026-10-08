package gokorei.tanseki.core.application

import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.testkit.ContextStoreEdgeReplacementContract
import gokorei.tanseki.testkit.InMemoryContextStore

/** Runs the shared edge-replacement contract against the in-memory double. */
class InMemoryEdgeReplacementTest : ContextStoreEdgeReplacementContract() {
    override fun newStore(): ContextStore = InMemoryContextStore()
}
