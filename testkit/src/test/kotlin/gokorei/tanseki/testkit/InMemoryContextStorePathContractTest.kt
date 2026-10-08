package gokorei.tanseki.testkit

/** Runs the shared path contract against the in-memory test double. */
class InMemoryContextStorePathContractTest : ContextStorePathContract() {
    override fun newStore() = InMemoryContextStore()
}
