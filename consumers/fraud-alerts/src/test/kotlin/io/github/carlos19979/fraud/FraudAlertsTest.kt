package io.github.carlos19979.fraud

import io.github.carlos19979.contracts.ledger.EventHeaders
import io.github.carlos19979.contracts.ledger.LedgerTopics
import io.github.carlos19979.contracts.ledger.TransferCompletedV1
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.producer.ProducerRecord
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
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
class FraudAlertsTest(
    @Autowired private val kafka: KafkaTemplate<String, String>,
    @Autowired private val json: JsonMapper,
    @Autowired private val repository: FraudRepository,
) {
    private val start = Instant.parse("2026-11-10T12:00:00Z")

    private fun transfer(
        account: UUID,
        secondsAfterStart: Long,
        amount: Long = 1_000,
        status: String = "SETTLED",
        kind: String = "TRANSFER",
        transferId: UUID = UUID.randomUUID(),
    ) = TransferCompletedV1(
        eventId = UUID.randomUUID(),
        transferId = transferId,
        kind = kind,
        sourceAccountId = account,
        targetAccountId = UUID.randomUUID(),
        amountMinor = amount,
        currency = "EUR",
        status = status,
        rejectionCode = if (status == "REJECTED") "insufficient-funds" else null,
        occurredAt = start.plusSeconds(secondsAfterStart),
    )

    private fun send(event: TransferCompletedV1) {
        val type = if (event.status == "SETTLED") "TransferSettled" else "TransferRejected"
        val record =
            ProducerRecord(
                LedgerTopics.TRANSFERS,
                null,
                event.sourceAccountId.toString(),
                json.writeValueAsString(event),
            ).apply {
                headers().add(EventHeaders.EVENT_TYPE, type.toByteArray())
                headers().add(EventHeaders.SCHEMA_VERSION, "1".toByteArray())
            }
        kafka.send(record).get()
    }

    @Test
    fun `a burst of transfers raises one velocity alert and a large one raises its own`() {
        val account = UUID.randomUUID()
        (0L..7L).forEach { send(transfer(account, secondsAfterStart = it * 5)) }
        send(transfer(account, secondsAfterStart = 50, amount = 750_000))

        await atMost Duration.ofSeconds(20) untilAsserted {
            repository.alertsFor(account).map { it.rule } shouldContainExactlyInAnyOrder
                listOf("velocity", "large-amount")
        }
    }

    @Test
    fun `transfers spread beyond the window are not flagged, and redeliveries are not counted twice`() {
        val account = UUID.randomUUID()
        val duplicate = UUID.randomUUID()
        (0L..5L).forEach { send(transfer(account, secondsAfterStart = it * 60)) } // one per minute
        repeat(5) { send(transfer(account, secondsAfterStart = 400, transferId = duplicate)) }
        val marker = UUID.randomUUID()
        send(transfer(marker, secondsAfterStart = 0, amount = 999_999))

        // Once the marker is processed, everything before it on the topic has been too.
        await atMost Duration.ofSeconds(20) untilAsserted { repository.alertsFor(marker).size shouldBe 1 }
        repository.alertsFor(account).shouldBeEmpty()
    }

    @Test
    fun `repeated rejections are flagged and deposits are ignored`() {
        val account = UUID.randomUUID()
        repeat(3) { send(transfer(account, secondsAfterStart = it * 10L, status = "REJECTED")) }
        val funding = UUID.randomUUID()
        (0L..10L).forEach { send(transfer(funding, secondsAfterStart = it, amount = 900_000, kind = "DEPOSIT")) }

        await atMost Duration.ofSeconds(20) untilAsserted {
            repository.alertsFor(account).map { it.rule } shouldBe listOf("repeated-rejections")
        }
        repository.alertsFor(funding).shouldBeEmpty()
    }
}
