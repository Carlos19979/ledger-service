package io.github.carlos19979.activity

import io.github.carlos19979.contracts.ledger.EntryPostedV1
import io.github.carlos19979.contracts.ledger.EventHeaders
import io.github.carlos19979.contracts.ledger.EventTypes
import io.github.carlos19979.contracts.ledger.LedgerTopics
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.untilNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

@TestConfiguration(proxyBeanMethods = false)
class Containers {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = POSTGRES

    @Bean
    @ServiceConnection
    fun kafka(): KafkaContainer = KAFKA

    companion object {
        val POSTGRES = PostgreSQLContainer("postgres:17-alpine").apply { start() }
        val KAFKA = KafkaContainer("apache/kafka:4.1.0").apply { start() }
    }
}

@SpringBootTest
@Import(Containers::class)
class ActivityProjectionTest(
    @Autowired private val kafka: KafkaTemplate<String, String>,
    @Autowired private val json: JsonMapper,
    @Autowired private val repository: ActivityRepository,
) {
    private val account = UUID.randomUUID()

    private fun entry(
        version: Long,
        direction: String,
        amount: Long,
        balanceAfter: Long,
        eventId: UUID =
            UUID
                .randomUUID(),
    ) =
        EntryPostedV1(
            eventId = eventId,
            entryId = UUID.randomUUID(),
            accountId = account,
            transferId = UUID.randomUUID(),
            direction = direction,
            amountMinor = amount,
            currency = "EUR",
            balanceAfterMinor = balanceAfter,
            accountVersion = version,
            postedAt = Instant.parse("2026-11-10T10:00:00Z").plusSeconds(version),
        )

    private fun send(value: String, type: String = EventTypes.ENTRY_POSTED, version: String = "1") {
        val record =
            ProducerRecord(LedgerTopics.ENTRIES, null, account.toString(), value).apply {
                headers().add(EventHeaders.EVENT_TYPE, type.toByteArray())
                headers().add(EventHeaders.SCHEMA_VERSION, version.toByteArray())
            }
        kafka.send(record).get()
    }

    private fun send(event: EntryPostedV1) = send(json.writeValueAsString(event))

    @Test
    fun `builds per-account totals and keeps the newest balance whatever the delivery order`() {
        val first = entry(version = 1, direction = "CREDIT", amount = 10_000, balanceAfter = 10_000)
        val second = entry(version = 2, direction = "DEBIT", amount = 2_500, balanceAfter = 7_500)
        val third = entry(version = 3, direction = "CREDIT", amount = 500, balanceAfter = 8_000)

        // Delivered out of order and with a duplicate.
        listOf(third, first, second, first).forEach(::send)

        await atMost Duration.ofSeconds(20) untilAsserted {
            val activity = repository.find(account).shouldNotBeNull()
            activity.entryCount shouldBe 3
            activity.balanceMinor shouldBe 8_000
            activity.lastAccountVersion shouldBe 3
            activity.creditsMinor shouldBe 10_500
            activity.debitsMinor shouldBe 2_500
        }
    }

    @Test
    fun `unknown event types are skipped and unreadable messages go to the dead-letter topic`() {
        send("""{"anything":true}""", type = "SomethingNew")
        send("not json at all")
        send(json.writeValueAsString(entry(1, "CREDIT", 100, 100)), version = "99")
        send(entry(version = 1, direction = "CREDIT", amount = 100, balanceAfter = 100))

        // The partition is not blocked: the valid message after the bad ones is applied.
        await atMost Duration.ofSeconds(20) untilNotNull { repository.find(account) }

        KafkaConsumer<String, String>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to Containers.KAFKA.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "dlt-reader-${UUID.randomUUID()}",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ),
        ).use { consumer ->
            consumer.subscribe(listOf(KafkaErrorHandling.deadLetterTopic(LedgerTopics.ENTRIES)))
            val dead = mutableListOf<String>()
            await atMost Duration.ofSeconds(20) untilAsserted {
                consumer.poll(Duration.ofMillis(500)).forEach { if (it.key() == account.toString()) dead += it.value() }
                dead.size shouldBe 2
            }
        }
    }
}
