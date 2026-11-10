package io.github.carlos19979.fraud

import io.github.carlos19979.contracts.ledger.EventHeaders
import io.github.carlos19979.contracts.ledger.LedgerTopics
import io.github.carlos19979.contracts.ledger.TransferCompletedV1
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant
import java.util.UUID

/** The listener's routing and mapping, with the detector mocked out: no Spring, no broker, no database. */
class TransferEventListenerSpec : FunSpec({
    val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    val detector = mockk<FraudDetector>()
    val listener = TransferEventListener(json, detector)

    fun event(kind: String = "TRANSFER", status: String = "SETTLED") =
        TransferCompletedV1(
            eventId = UUID.randomUUID(),
            transferId = UUID.randomUUID(),
            kind = kind,
            sourceAccountId = UUID.randomUUID(),
            targetAccountId = UUID.randomUUID(),
            amountMinor = 4_200,
            currency = "EUR",
            status = status,
            rejectionCode = null,
            occurredAt = Instant.parse("2026-11-10T10:00:00Z"),
        )

    fun record(payload: String, type: String = "TransferSettled", version: String = "1") =
        ConsumerRecord(LedgerTopics.TRANSFERS, 0, 0L, "key", payload).apply {
            headers().add(EventHeaders.EVENT_TYPE, type.toByteArray())
            headers().add(EventHeaders.SCHEMA_VERSION, version.toByteArray())
        }

    beforeTest {
        clearMocks(detector)
        every { detector.observe(any()) } returns emptyList()
    }

    test("maps a settled customer transfer onto an observation of its source account") {
        val event = event()
        val observed = slot<Observation>()
        every { detector.observe(capture(observed)) } returns emptyList()

        listener.onMessage(record(json.writeValueAsString(event)))

        observed.captured shouldBe
            Observation(event.transferId, event.sourceAccountId, 4_200, "EUR", true, event.occurredAt)
    }

    test("rejections are observed as not settled") {
        listener.onMessage(record(json.writeValueAsString(event(status = "REJECTED")), type = "TransferRejected"))

        verify { detector.observe(match { !it.settled }) }
    }

    test("deposits and unknown event types never reach the detector") {
        listener.onMessage(record(json.writeValueAsString(event(kind = "DEPOSIT"))))
        listener.onMessage(record("""{"whatever":1}""", type = "AccountRenamed"))

        verify(exactly = 0) { detector.observe(any()) }
    }

    test("an unsupported schema version is refused, so the error handler can dead-letter it") {
        shouldThrow<UnprocessableEventException> { listener.onMessage(record("{}", version = "2")) }
        verify(exactly = 0) { detector.observe(any()) }
    }
})
