package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.RevisionId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** What happened to a document, as a subscriber sees it. */
enum class ChangeKind {
    /** The document was written and is now searchable. */
    UPSERT,

    /** The document was deleted and is no longer searchable. */
    DELETE,

    /**
     * The subscriber missed events and must reload rather than assume it is
     * current.
     *
     * This is the honest answer to a gap. A client that silently misses a change
     * shows stale content indefinitely, which is worse than being told to resync.
     */
    RESYNC
}

/**
 * One committed change.
 *
 * [sequence] is the feed's own monotonically increasing counter, not a revision:
 * revisions are per document, so they cannot order events across documents or
 * tell a client what it missed while offline.
 */
data class ChangeEvent(
    val sequence: Long,
    val documentId: DocId?,
    val collection: Collection?,
    val kind: ChangeKind,
    val revision: RevisionId? = null,
    val contentHash: String? = null
)

/** How many past events stay available for a reconnecting subscriber. */
const val DEFAULT_CHANGE_HISTORY = 512

/** Per-subscriber queue depth before that subscriber is told to resync. */
const val DEFAULT_CHANGE_SUBSCRIBER_BUFFER = 256

/**
 * A resumable feed of committed changes.
 *
 * Events are published where the Lookup is written, which is the single point
 * both write paths converge: an API write projects through it, and a file the
 * watcher observed is reconciled through it. That means an event is only
 * published for a change that actually became searchable, so a failed or
 * dead-lettered projection never announces itself.
 *
 * Resumption is by sequence. A subscriber that reconnects with the last sequence
 * it saw is replayed the gap from [historyLimit] retained events; one that
 * reconnects with a sequence older than the retained window, or one whose queue
 * overflowed because it read too slowly, is sent a [ChangeKind.RESYNC] instead of
 * a silently incomplete stream.
 *
 * This is process-local. It is a notification channel, not a durable log: a
 * daemon restart resets sequences, so a client that reconnects across a restart
 * must reload rather than resume. [resyncOnSubscribe] marks that boundary.
 */
class ChangeFeed(
    private val historyLimit: Int = DEFAULT_CHANGE_HISTORY,
    private val subscriberBuffer: Int = DEFAULT_CHANGE_SUBSCRIBER_BUFFER
) {
    init {
        require(historyLimit > 0) { "historyLimit must be positive" }
        require(subscriberBuffer > 0) { "subscriberBuffer must be positive" }
    }

    // A plain monitor rather than a Mutex: publish is called from Indexer, which
    // is synchronous and runs on pool threads, so blocking a coroutine dispatcher
    // here would be a deadlock risk for no benefit. Nothing under the lock
    // suspends or blocks.
    private val lock = Any()
    private var sequence = 0L
    private val history = ArrayDeque<ChangeEvent>()
    private val subscribers = linkedSetOf<Channel<ChangeEvent>>()

    /** The most recently assigned sequence, or 0 before anything is published. */
    fun currentSequence(): Long = synchronized(lock) { sequence }

    /** Subscribers currently attached. Asserted on in leak tests. */
    fun subscriberCount(): Int = synchronized(lock) { subscribers.size }

    /**
     * Publishes a committed change and returns the event as numbered.
     *
     * Failures here are swallowed on purpose: a client that cannot be told about
     * a change is a degraded feed, and must not be able to fail the write that
     * already committed.
     */
    fun publish(
        documentId: DocId?,
        collection: Collection?,
        kind: ChangeKind,
        revision: RevisionId? = null,
        contentHash: String? = null
    ): ChangeEvent =
        synchronized(lock) {
            sequence++
            val event =
                ChangeEvent(
                    sequence = sequence,
                    documentId = documentId,
                    collection = collection,
                    kind = kind,
                    revision = revision,
                    contentHash = contentHash
                )
            history.addLast(event)
            while (history.size > historyLimit) history.removeFirst()
            // A subscriber that cannot keep up is not silently starved: it is
            // told to resync, which is the only honest answer.
            val dropped =
                subscribers.filterNot { subscriber ->
                    subscriber.trySend(event).isSuccess
                }
            dropped.forEach { slow ->
                subscribers.remove(slow)
                slow.trySend(resyncEvent())
                slow.close()
            }
            event
        }

    private fun resyncEvent() =
        ChangeEvent(
            sequence = sequence,
            documentId = null,
            collection = null,
            kind = ChangeKind.RESYNC
        )

    /**
     * Events from just after [afterSequence] onwards.
     *
     * A null [afterSequence] means "from now": a fresh subscriber is not owed the
     * retained window, it is owed the present. [resyncOnSubscribe] forces the
     * resync signal instead, which is what a client does after a daemon restart
     * when it knows its sequences are meaningless.
     *
     * [collections] scopes the feed. Null means every collection the credential
     * may read; a set means only those. Scoping happens before the sequence is
     * consumed, so a subscriber never observes that an out-of-scope document
     * exists — not even as a gap in its sequence numbers.
     */
    fun subscribe(
        afterSequence: Long? = null,
        collections: Set<String>? = null,
        resyncOnSubscribe: Boolean = false
    ): Flow<ChangeEvent> =
        channelFlow {
            val channel = Channel<ChangeEvent>(subscriberBuffer)
            val backlog =
                synchronized(lock) {
                    val replay =
                        when {
                            resyncOnSubscribe -> {
                                listOf(resyncEvent())
                            }

                            afterSequence == null -> {
                                emptyList()
                            }

                            history.isEmpty() -> {
                                emptyList()
                            }

                            // The first retained event is the oldest we can still
                            // serve; anything before it has aged out.
                            history.first().sequence > afterSequence + 1 -> {
                                listOf(resyncEvent())
                            }

                            else -> {
                                history.filter { it.sequence > afterSequence }
                            }
                        }.filter { event -> visible(event, collections) }
                    subscribers.add(channel)
                    replay
                }
            backlog.forEach { send(it) }
            try {
                for (event in channel) {
                    if (visible(event, collections)) send(event)
                }
            } finally {
                synchronized(lock) { subscribers.remove(channel) }
                channel.close()
            }
        }

    private fun visible(event: ChangeEvent, collections: Set<String>? = null): Boolean =
        when {
            event.kind == ChangeKind.RESYNC -> true
            collections == null -> true
            event.collection == null -> false
            else -> event.collection.value in collections
        }
}
