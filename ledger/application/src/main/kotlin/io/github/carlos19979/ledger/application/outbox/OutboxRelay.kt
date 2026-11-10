package io.github.carlos19979.ledger.application.outbox

import io.github.carlos19979.ledger.application.port.MessageBroker
import io.github.carlos19979.ledger.application.port.OutboxStore
import io.github.carlos19979.ledger.application.port.TransactionRunner
import java.time.Clock

/**
 * Moves committed events from the outbox table to the broker. Delivery is at-least-once: a crash between the
 * broker's ack and the commit below republishes the batch, so consumers deduplicate by event id.
 *
 * Publishing stops at the first failure instead of skipping ahead, so events of the same key never overtake
 * each other.
 */
class OutboxRelay(
    private val outbox: OutboxStore,
    private val broker: MessageBroker,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val batchSize: Int = 100,
) {
    /** Publishes one batch and returns how many messages were delivered. */
    fun publishPending(): Int =
        tx.inTransaction {
            val batch = outbox.lockPendingBatch(batchSize)
            val delivered = mutableListOf<Long>()
            for (pending in batch) {
                @Suppress("TooGenericExceptionCaught") // any broker failure means: stop, keep order, retry next run
                try {
                    broker.publish(pending.message)
                    delivered += pending.id
                } catch (e: Exception) {
                    outbox.recordFailure(pending.id, e.message ?: e.javaClass.simpleName)
                    break
                }
            }
            outbox.markPublished(delivered, clock.instant())
            delivered.size
        }
}
