package gokorei.tanseki.adapters.meili

import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.testkit.LookupContract
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/** Runs the shared [LookupContract] against the Meilisearch Lookup. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "TANSEKI_RUN_MEILI_INTEGRATION", matches = "true")
class MeiliLookupContractTest : LookupContract() {
    override fun newLookup(): Lookup =
        MeiliLookup(
            baseUrl = "http://${meili.host}:${meili.getMappedPort(7700)}",
            apiKey = "test-key",
            indexName = "tanseki-${UUID.randomUUID().toString().take(8)}"
        )

    override fun closeLookup(lookup: Lookup) {
        (lookup as AutoCloseable).close()
    }

    companion object {
        @Container
        @JvmStatic
        val meili: GenericContainer<*> =
            GenericContainer(
                "getmeili/meilisearch:v1.15.2@sha256:fe500cf9cca05cb9f027981583f28eccf17d35d94499c1f8b7b844e7418152fc"
            ).withExposedPorts(7700)
                .withEnv("MEILI_MASTER_KEY", "test-key")
                .withEnv("MEILI_NO_ANALYTICS", "true")
                .waitingFor(Wait.forHttp("/health").forPort(7700).forStatusCode(200))
    }
}
