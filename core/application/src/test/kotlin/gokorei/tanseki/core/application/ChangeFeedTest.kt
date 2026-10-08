package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.RevisionId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The feed's contract: ordering, resumption, and what it does when it cannot
 * keep up.
 *
 * These are the properties a client depends on to decide whether it is current.
 * A feed that quietly dropped or duplicated events would leave a UI showing stale
 * content while claiming to be live, which is the failure this exists to remove.
 *
 * Every test here attaches before publishing. Collecting a stream and waiting for
 * an element that a later statement produces would deadlock instead of failing,
 * so [collectAfter] subscribes undispatched, publishes, then cancels.
 */
class ChangeFeedTest {
    private fun feed(history: Int = 8, buffer: Int = 16) = ChangeFeed(history, buffer)

    /**
     * Subscribes [attach], publishes with [publish], waits for [expected] events,
     * then cancels.
     *
     * The wait is explicit and bounded. Yielding a fixed number of times instead
     * would make these tests depend on how many dispatch slots the
     * publisher-to-collector hand-off happens to take, which is exactly the kind
     * of timing assumption that turns into a flake rather than a failure.
     */
    private fun collectAfter(
        feed: ChangeFeed,
        expected: Int,
        attach: () -> Flow<ChangeEvent>,
        publish: () -> Unit
    ): List<ChangeEvent> =
        runBlocking {
            val received = mutableListOf<ChangeEvent>()
            val job: Job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    attach().collect { received += it }
                }
            // channelFlow runs its body in a child coroutine, so UNDISPATCHED does
            // not make the subscription register inline.
            yield()
            publish()
            withTimeoutOrNull(2.seconds) {
                while (received.size < expected) yield()
            }
            job.cancel()
            received.toList()
        }

    @Test
    fun `events are numbered in publication order`() {
        val feed = feed()

        val first = feed.publish(DocId("a"), Collection("vault"), ChangeKind.UPSERT)
        val second = feed.publish(DocId("b"), Collection("vault"), ChangeKind.UPSERT)
        val third = feed.publish(DocId("c"), Collection("vault"), ChangeKind.DELETE)

        assertEquals(listOf(1L, 2L, 3L), listOf(first.sequence, second.sequence, third.sequence))
        assertEquals(3L, feed.currentSequence())
    }

    @Test
    fun `a fresh subscriber is given the present, not the retained window`() {
        val feed = feed()
        feed.publish(DocId("before"), Collection("vault"), ChangeKind.UPSERT)

        val received =
            collectAfter(
                feed,
                expected = 1,
                attach = { feed.subscribe() },
                publish = { feed.publish(DocId("after"), Collection("vault"), ChangeKind.UPSERT) }
            )

        assertEquals(
            listOf("after"),
            received.map { it.documentId?.value },
            "a new subscriber is not owed the retained window, only the present"
        )
    }

    @Test
    fun `a reconnecting subscriber receives exactly what it missed`() {
        val feed = feed()
        feed.publish(DocId("a"), Collection("vault"), ChangeKind.UPSERT)
        val seen = feed.publish(DocId("b"), Collection("vault"), ChangeKind.UPSERT).sequence
        feed.publish(DocId("c"), Collection("vault"), ChangeKind.UPSERT)
        feed.publish(DocId("d"), Collection("vault"), ChangeKind.UPSERT)

        val received =
            collectAfter(
                feed,
                expected = 3,
                attach = { feed.subscribe(afterSequence = seen) },
                publish = { feed.publish(DocId("e"), Collection("vault"), ChangeKind.UPSERT) }
            )

        assertEquals(
            listOf("c", "d", "e"),
            received.map { it.documentId?.value },
            "resuming must replay the gap and nothing already seen"
        )
    }

    @Test
    fun `a sequence older than the retained window is told to resync`() {
        val feed = feed(history = 2)
        repeat(5) { feed.publish(DocId("n$it"), Collection("vault"), ChangeKind.UPSERT) }

        val received =
            collectAfter(
                feed,
                expected = 1,
                attach = { feed.subscribe(afterSequence = 1) },
                publish = { feed.publish(DocId("next"), Collection("vault"), ChangeKind.UPSERT) }
            )

        assertEquals(ChangeKind.RESYNC, received.first().kind)
    }

    @Test
    fun `a subscriber may ask for a resync instead of resuming`() {
        val feed = feed()

        val received =
            collectAfter(
                feed,
                expected = 1,
                attach = { feed.subscribe(resyncOnSubscribe = true) },
                publish = { feed.publish(DocId("x"), Collection("vault"), ChangeKind.UPSERT) }
            )

        assertEquals(ChangeKind.RESYNC, received.first().kind)
    }

    @Test
    fun `a subscriber only sees collections it may read`() {
        val feed = feed()
        feed.publish(DocId("mine"), Collection("alpha"), ChangeKind.UPSERT)
        feed.publish(DocId("theirs"), Collection("beta"), ChangeKind.UPSERT)

        val received =
            collectAfter(
                feed,
                expected = 2,
                attach = { feed.subscribe(afterSequence = 0, collections = setOf("alpha")) },
                publish = {
                    feed.publish(DocId("mine-again"), Collection("alpha"), ChangeKind.UPSERT)
                    feed.publish(DocId("theirs-again"), Collection("beta"), ChangeKind.UPSERT)
                }
            )

        assertEquals(
            listOf("mine", "mine-again"),
            received.map { it.documentId?.value },
            "an out-of-scope document must not appear at all"
        )
    }

    @Test
    fun `a resync still reaches a scoped subscriber`() {
        val feed = feed(history = 1)
        repeat(4) { feed.publish(DocId("n$it"), Collection("beta"), ChangeKind.UPSERT) }

        val received =
            collectAfter(
                feed,
                expected = 1,
                attach = { feed.subscribe(afterSequence = 0, collections = setOf("alpha")) },
                publish = { feed.publish(DocId("n9"), Collection("beta"), ChangeKind.UPSERT) }
            )

        // Every retained event was beta. The subscriber is told it is out of date
        // rather than being allowed to conclude that nothing happened.
        assertEquals(ChangeKind.RESYNC, received.first().kind)
    }

    @Test
    fun `a subscriber that stops reading is detached rather than left accumulating`() =
        runBlocking {
            // A rendezvous gate stalls the collector after its first element, so
            // this subscriber genuinely cannot keep up. Publishing a burst then
            // has to overflow a buffer of one.
            val feed = feed(buffer = 1)
            val gate = Channel<ChangeEvent>(Channel.RENDEZVOUS)
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    feed.subscribe().collect { gate.send(it) }
                }
            yield()
            assertEquals(1, feed.subscriberCount())

            repeat(8) { feed.publish(DocId("n$it"), Collection("vault"), ChangeKind.UPSERT) }

            // The guarantee is detachment: a client that stops reading must not
            // stay attached holding memory for a stream nobody is consuming.
            assertEquals(
                0,
                feed.subscriberCount(),
                "a subscriber that cannot keep up must be dropped, not retained"
            )
            job.cancel()
        }

    @Test
    fun `subscribers are released when their collector ends`() =
        runBlocking {
            val feed = feed()
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    feed.subscribe().collect { }
                }
            kotlinx.coroutines.yield()
            assertEquals(1, feed.subscriberCount())

            job.cancel()
            kotlinx.coroutines.yield()
            assertEquals(0, feed.subscriberCount(), "a finished subscription must not stay attached")
        }

    @Test
    fun `an event carries the revision and digest a client needs to refetch`() {
        val feed = feed()

        val event =
            feed.publish(
                documentId = DocId("a"),
                collection = Collection("vault"),
                kind = ChangeKind.UPSERT,
                revision = RevisionId("rev-7"),
                contentHash = "hash-7"
            )

        assertEquals(RevisionId("rev-7"), event.revision)
        assertEquals("hash-7", event.contentHash)
    }

    @Test
    fun `the retained window is deep enough for a brief disconnection`() {
        // Not a correctness test: a one-event history would make every reconnect
        // a resync, which is correct but useless.
        assertTrue(DEFAULT_CHANGE_HISTORY >= 64)
        assertEquals(512, DEFAULT_CHANGE_HISTORY)
        assertTrue(DEFAULT_CHANGE_SUBSCRIBER_BUFFER > 0)
    }
}
