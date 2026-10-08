package gokorei.tanseki.adapters.lucene

import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.testkit.LookupContract

/** Runs the shared [LookupContract] against the Lucene Lookup. */
class LuceneLookupContractTest : LookupContract() {
    override fun newLookup(): Lookup = LuceneLookup()

    override fun closeLookup(lookup: Lookup) {
        (lookup as AutoCloseable).close()
    }
}
