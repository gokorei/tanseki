package gokorei.tanseki.testkit

import org.junit.jupiter.api.Test

class InMemoryContextStoreContractTest : ContextStoreContract() {
    override fun newStore() = InMemoryContextStore()
}
