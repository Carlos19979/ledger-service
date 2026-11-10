package io.github.carlos19979.activity

import io.github.carlos19979.contracts.ledger.EntryPostedV1
import io.github.carlos19979.contracts.ledger.EventHeaders
import io.github.carlos19979.contracts.ledger.LedgerTopics
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant
import java.util.UUID
import java.util.function.Consumer

class EntryEventListenerSpec : FunSpec({
    val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    val repository = mockk<ActivityRepository>(relaxUnitFun = true)
    // Runs the callback inline, so the test sees exactly what happens inside the transaction.
    val tx =
        mockk<TransactionTemplate> {
            every { executeWithoutResult(any()) } answers { firstArg<Consumer<TransactionStatus>>().accept(mockk()) }
        }
    val listener = EntryEventListener(json, repository, tx)
    val event =
        EntryPostedV1(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "CREDIT",
            1_000,
            "EUR",
            1_000,
            1,
            Instant.parse("2026-11-10T10:00:00Z"),
        )
    val record =
        ConsumerRecord(LedgerTopics.ENTRIES, 0, 0L, "key", json.writeValueAsString(event)).apply {
            headers().add(EventHeaders.EVENT_TYPE, "EntryPosted".toByteArray())
            headers().add(EventHeaders.SCHEMA_VERSION, "1".toByteArray())
        }

    beforeTest { clearMocks(repository, answers = false) }

    test("a new event is marked as processed and applied") {
        every { repository.markProcessed(event.eventId) } returns true

        listener.onMessage(record)

        verify { repository.apply(event) }
    }

    test("a duplicate is recognised and not applied twice") {
        every { repository.markProcessed(event.eventId) } returns false

        listener.onMessage(record)

        verify(exactly = 0) { repository.apply(any()) }
    }
})
