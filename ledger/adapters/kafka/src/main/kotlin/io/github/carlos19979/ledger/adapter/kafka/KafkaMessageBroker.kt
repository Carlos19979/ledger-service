package io.github.carlos19979.ledger.adapter.kafka

import io.github.carlos19979.contracts.ledger.EventHeaders
import io.github.carlos19979.ledger.application.port.MessageBroker
import io.github.carlos19979.ledger.application.port.OutboxMessage
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Sends one message and waits for the broker's acknowledgement (acks=all, idempotent producer), so the relay only
 * marks a message as published once Kafka really has it.
 */
class KafkaMessageBroker(
    private val kafka: KafkaTemplate<String, String>,
    private val timeout: Duration,
) : MessageBroker {
    override fun publish(message: OutboxMessage) {
        val record =
            ProducerRecord(message.topic, null, message.key, message.payload).apply {
                headers().add(RecordHeader(EventHeaders.EVENT_ID, message.eventId.toString().toByteArray()))
                headers().add(RecordHeader(EventHeaders.EVENT_TYPE, message.eventType.toByteArray()))
                headers().add(RecordHeader(EventHeaders.SCHEMA_VERSION, message.schemaVersion.toString().toByteArray()))
            }
        kafka.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}
