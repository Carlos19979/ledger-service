package io.github.carlos19979.activity

import io.github.carlos19979.contracts.ledger.EntryPostedV1
import io.github.carlos19979.contracts.ledger.EventHeaders
import io.github.carlos19979.contracts.ledger.EventTypes
import io.github.carlos19979.contracts.ledger.LedgerTopics
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.backoff.FixedBackOff
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

/** A message this consumer can never process, however often it is retried. */
class UnprocessableEventException(message: String) : RuntimeException(message)

@Component
class EntryEventListener(
    private val json: JsonMapper,
    private val repository: ActivityRepository,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [LedgerTopics.ENTRIES])
    fun onMessage(record: ConsumerRecord<String, String>) {
        val type = record.header(EventHeaders.EVENT_TYPE)
        if (type != EventTypes.ENTRY_POSTED) {
            // New event types are added without coordinating consumers; ignoring unknown ones keeps us compatible.
            log.debug("Ignoring event type {} at offset {}", type, record.offset())
            return
        }
        if (record.header(EventHeaders.SCHEMA_VERSION) != "1") {
            throw UnprocessableEventException(
                "Unsupported schema version ${record.header(EventHeaders.SCHEMA_VERSION)}",
            )
        }
        val event = json.readValue<EntryPostedV1>(record.value())

        tx.executeWithoutResult {
            if (repository.markProcessed(event.eventId)) repository.apply(event)
        }
    }

    private fun ConsumerRecord<String, String>.header(name: String) = headers().lastHeader(name)?.value()?.let(::String)
}

@Configuration(proxyBeanMethods = false)
class KafkaErrorHandling {
    /**
     * Transient failures (database blip) are retried in place; a message that can never be read goes to a
     * dead-letter topic so it stops blocking its partition and someone can inspect it.
     */
    @Bean
    fun errorHandler(kafka: KafkaTemplate<String, String>) =
        DefaultErrorHandler(
            DeadLetterPublishingRecoverer(kafka) { record, _ -> TopicPartition(deadLetterTopic(record.topic()), -1) },
            FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES),
        ).apply {
            addNotRetryableExceptions(JacksonException::class.java, UnprocessableEventException::class.java)
        }

    @Bean
    fun deadLetterTopic(): NewTopic = TopicBuilder.name(deadLetterTopic(LedgerTopics.ENTRIES)).partitions(1).build()

    companion object {
        private const val RETRY_INTERVAL_MS = 500L
        private const val MAX_RETRIES = 3L

        fun deadLetterTopic(topic: String) = "$topic.activity-projection.dlt"
    }
}
