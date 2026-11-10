package io.github.carlos19979.fraud

import io.github.carlos19979.contracts.ledger.EventHeaders
import io.github.carlos19979.contracts.ledger.EventTypes
import io.github.carlos19979.contracts.ledger.LedgerTopics
import io.github.carlos19979.contracts.ledger.TransferCompletedV1
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.stereotype.Component
import org.springframework.util.backoff.FixedBackOff
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID

class UnprocessableEventException(message: String) : RuntimeException(message)

@Component
class TransferEventListener(private val json: JsonMapper, private val detector: FraudDetector) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [LedgerTopics.TRANSFERS])
    fun onMessage(record: ConsumerRecord<String, String>) {
        val type =
            record
                .headers()
                .lastHeader(EventHeaders.EVENT_TYPE)
                ?.value()
                ?.let(::String)
        if (type != EventTypes.TRANSFER_SETTLED && type != EventTypes.TRANSFER_REJECTED) return
        val version =
            record
                .headers()
                .lastHeader(EventHeaders.SCHEMA_VERSION)
                ?.value()
                ?.let(::String)
        if (version != "1") throw UnprocessableEventException("Unsupported schema version $version")

        val event = json.readValue<TransferCompletedV1>(record.value())
        // Deposits come from the bank's own funding account; only customer-initiated transfers are screened.
        if (event.kind != "TRANSFER") return

        detector
            .observe(
                Observation(
                    transferId = event.transferId,
                    accountId = event.sourceAccountId,
                    amountMinor = event.amountMinor,
                    currency = event.currency,
                    settled = event.status == "SETTLED",
                    occurredAt = event.occurredAt,
                ),
            ).forEach { log.warn("Fraud alert {} on account {}: {}", it.rule, it.accountId, it.detail) }
    }
}

@RestController
@RequestMapping("/api/v1/alerts")
class AlertController(private val repository: FraudRepository) {
    @GetMapping
    fun list(
        @RequestParam accountId: UUID,
    ): ResponseEntity<List<Alert>> = ResponseEntity.ok(repository.alertsFor(accountId))
}

@Configuration(proxyBeanMethods = false)
class KafkaErrorHandling {
    @Bean
    fun errorHandler(kafka: KafkaTemplate<String, String>) =
        DefaultErrorHandler(
            DeadLetterPublishingRecoverer(kafka) { record, _ -> TopicPartition(deadLetterTopic(record.topic()), -1) },
            FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES),
        ).apply {
            addNotRetryableExceptions(JacksonException::class.java, UnprocessableEventException::class.java)
        }

    @Bean
    fun deadLetterTopic(): NewTopic = TopicBuilder.name(deadLetterTopic(LedgerTopics.TRANSFERS)).partitions(1).build()

    companion object {
        private const val RETRY_INTERVAL_MS = 500L
        private const val MAX_RETRIES = 3L

        fun deadLetterTopic(topic: String) = "$topic.fraud-alerts.dlt"
    }
}
