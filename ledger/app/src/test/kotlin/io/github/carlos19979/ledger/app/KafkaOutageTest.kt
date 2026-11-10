package io.github.carlos19979.ledger.app

import io.github.carlos19979.contracts.ledger.EventHeaders
import io.github.carlos19979.contracts.ledger.LedgerTopics
import io.github.carlos19979.ledger.application.port.OutboxStore
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestClient
import org.testcontainers.DockerClientFactory
import org.testcontainers.kafka.KafkaContainer
import tools.jackson.databind.JsonNode
import java.time.Duration

/**
 * The reason for the outbox: transfers keep working while Kafka is down, their events wait in PostgreSQL, and once
 * Kafka is back every one of them is delivered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Containers::class)
@TestPropertySource(
    properties = [
        "ledger.outbox.publish-timeout=2s",
        "spring.kafka.producer.properties.delivery.timeout.ms=2000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.max.block.ms=1000",
    ],
)
class KafkaOutageTest(
    @LocalServerPort port: Int,
    @Autowired private val kafka: KafkaContainer,
    @Autowired private val outbox: OutboxStore,
) {
    private val http = RestClient.create("http://localhost:$port")

    private fun post(path: String, body: String, key: String? = null): JsonNode =
        http
            .post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (key != null) header("Idempotency-Key", key) }
            .body(body)
            .retrieve()
            .body(JsonNode::class.java)!!

    @Test
    fun `transfers succeed while Kafka is down and their events are delivered once it is back`() {
        val alice = post("/api/v1/accounts", """{"owner":"Alice","currency":"EUR"}""")["id"].asString()
        val bob = post("/api/v1/accounts", """{"owner":"Bob","currency":"EUR"}""")["id"].asString()
        post("/api/v1/accounts/$alice/deposits", """{"amount":{"amount":"50.00","currency":"EUR"}}""", "seed")
        await atMost Duration.ofSeconds(20) untilAsserted { outbox.pendingCount() shouldBe 0 }

        val docker = DockerClientFactory.instance().client()
        docker.pauseContainerCmd(kafka.containerId).exec()
        val transferIds =
            try {
                (1..5)
                    .map {
                        post(
                            "/api/v1/transfers",
                            """
                    {"sourceAccountId":"$alice","targetAccountId":"$bob","amount":{"amount":"1.00","currency":"EUR"}}
                    """,
                            "outage-$it",
                        )["id"].asString()
                    }.also {
                        // The API kept answering; the events are parked in the outbox.
                        Thread.sleep(3_000)
                        outbox.pendingCount() shouldBeGreaterThan 0
                    }
            } finally {
                docker.unpauseContainerCmd(kafka.containerId).exec()
            }

        await atMost Duration.ofSeconds(60) untilAsserted { outbox.pendingCount() shouldBe 0 }
        TopicReader(kafka.bootstrapServers, listOf(LedgerTopics.TRANSFERS)).use { reader ->
            await atMost Duration.ofSeconds(30) untilAsserted {
                val settled = reader.poll().filter { it.header(EventHeaders.EVENT_TYPE) == "TransferSettled" }
                transferIds.all { id -> settled.any { it.value().contains(id) } } shouldBe true
            }
        }
    }
}
