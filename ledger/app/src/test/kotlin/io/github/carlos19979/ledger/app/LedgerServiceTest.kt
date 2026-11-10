package io.github.carlos19979.ledger.app

import io.github.carlos19979.contracts.ledger.EventHeaders
import io.github.carlos19979.contracts.ledger.LedgerTopics
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.testcontainers.kafka.KafkaContainer
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Containers::class)
class LedgerServiceTest(
    @LocalServerPort port: Int,
    @Autowired private val kafka: KafkaContainer,
) {
    private val http =
        RestClient
            .builder()
            .baseUrl("http://localhost:$port")
            .defaultStatusHandler(HttpStatusCode::isError) { _, _ -> } // assertions look at the status themselves
            .build()

    private fun post(path: String, body: String, key: String? = null) =
        http
            .post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (key != null) header("Idempotency-Key", key) }
            .body(body)
            .retrieve()
            .toEntity(JsonNode::class.java)

    private fun openAccount(owner: String) =
        post("/api/v1/accounts", """{"owner":"$owner","currency":"EUR"}""").body!!["id"].asString()

    private fun money(amount: String) = """{"amount":"$amount","currency":"EUR"}"""

    @Test
    fun `money moves through the API and every change is published to Kafka`() {
        val alice = openAccount("Alice")
        val bob = openAccount("Bob")
        post("/api/v1/accounts/$alice/deposits", """{"amount":${money("100.00")}}""", "seed-$alice")
            .statusCode
            .value() shouldBe 201

        val transfer =
            post(
                "/api/v1/transfers",
                """{"sourceAccountId":"$alice","targetAccountId":"$bob","amount":${money(
                    "40.00",
                )},"reference":"dinner"}""",
                "dinner-$alice",
            )
        transfer.statusCode.value() shouldBe 201
        val transferId = transfer.body!!["id"].asString()

        http
            .get()
            .uri("/api/v1/accounts/$bob")
            .retrieve()
            .body<JsonNode>()!!["balance"]["amount"]
            .asString() shouldBe
            "40.00"

        TopicReader(kafka.bootstrapServers, listOf(LedgerTopics.ENTRIES, LedgerTopics.TRANSFERS)).use { reader ->
            await atMost Duration.ofSeconds(20) untilAsserted {
                // Entries and the settlement go to different topics, so only the set of events is guaranteed.
                reader
                    .poll()
                    .filter { it.value().contains(transferId) }
                    .map { it.header(EventHeaders.EVENT_TYPE) }
                    .shouldContainExactlyInAnyOrder(listOf("EntryPosted", "EntryPosted", "TransferSettled"))
            }
            val entries =
                reader.records.filter {
                    it.topic() == LedgerTopics.ENTRIES &&
                        it.value().contains(
                            transferId,
                        )
                }
            entries.map { it.key() }.toSet() shouldBe setOf(alice, bob)
            reader.records
                .single {
                    it.topic() == LedgerTopics.TRANSFERS &&
                        it.value().contains(
                            transferId,
                        )
                }.key() shouldBe
                alice
        }
    }

    @Test
    fun `a rejected transfer is answered with a problem and still published`() {
        val broke = openAccount("Broke")
        val other = openAccount("Other")

        val response =
            post(
                "/api/v1/transfers",
                """{"sourceAccountId":"$broke","targetAccountId":"$other","amount":${money("5.00")}}""",
                "broke-${UUID.randomUUID()}",
            )

        response.statusCode.value() shouldBe 422
        response.body!!["code"].asString() shouldBe "insufficient-funds"
        val transferId = response.body!!["transfer"]["id"].asString()
        TopicReader(kafka.bootstrapServers, listOf(LedgerTopics.TRANSFERS)).use { reader ->
            await atMost Duration.ofSeconds(20) untilAsserted {
                reader.poll().single { it.value().contains(transferId) }.header(EventHeaders.EVENT_TYPE) shouldBe
                    "TransferRejected"
            }
        }
    }

    @Test
    fun `operational endpoints expose health and the outbox backlog`() {
        http
            .get()
            .uri("/actuator/health")
            .retrieve()
            .body<String>()!! shouldContain "UP"
        http
            .get()
            .uri("/actuator/prometheus")
            .retrieve()
            .body<String>()!! shouldContain "ledger_outbox_pending"
        http
            .get()
            .uri("/v3/api-docs")
            .retrieve()
            .body<String>()!! shouldContain "/api/v1/transfers"
    }
}
