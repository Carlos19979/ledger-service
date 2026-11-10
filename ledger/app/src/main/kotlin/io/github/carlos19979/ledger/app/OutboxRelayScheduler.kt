package io.github.carlos19979.ledger.app

import io.github.carlos19979.ledger.application.outbox.OutboxRelay
import io.github.carlos19979.ledger.application.port.OutboxStore
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Drains the outbox on a short fixed delay. Exposes the backlog as `ledger.outbox.pending`: a growing value is the
 * signal that Kafka is unreachable, and the thing to alert on.
 */
@Component
class OutboxRelayScheduler(
    private val relay: OutboxRelay,
    outbox: OutboxStore,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val published = registry.counter("ledger.outbox.published")

    init {
        Gauge
            .builder("ledger.outbox.pending") { outbox.pendingCount().toDouble() }
            .description("Committed events not yet acknowledged by Kafka")
            .register(registry)
    }

    @Scheduled(fixedDelayString = "\${ledger.outbox.poll-interval:200ms}")
    fun drain() {
        @Suppress("TooGenericExceptionCaught") // a failed run is retried on the next tick; never kill the scheduler
        try {
            var sent: Int
            do {
                sent = relay.publishPending()
                published.increment(sent.toDouble())
            } while (sent > 0)
        } catch (e: Exception) {
            log.warn("Outbox relay run failed, retrying on next tick: {}", e.message)
        }
    }
}
