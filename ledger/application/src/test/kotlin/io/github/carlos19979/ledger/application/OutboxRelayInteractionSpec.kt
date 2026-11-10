package io.github.carlos19979.ledger.application

import io.github.carlos19979.ledger.application.outbox.OutboxRelay
import io.github.carlos19979.ledger.application.port.MessageBroker
import io.github.carlos19979.ledger.application.port.OutboxMessage
import io.github.carlos19979.ledger.application.port.OutboxStore
import io.github.carlos19979.ledger.application.port.PendingMessage
import io.github.carlos19979.ledger.application.port.TransactionRunner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class OutboxRelayInteractionSpec : FunSpec({
    val now = Instant.parse("2026-11-10T10:00:00Z")
    val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    fun pending(id: Long) =
        PendingMessage(
            id,
            OutboxMessage(UUID.randomUUID(), "t", "k", "E", 1, "{}", now),
            attempts = 0,
        )

    test("never calls the broker when another instance holds the publishing lock") {
        val outbox = mockk<OutboxStore>(relaxUnitFun = true) { every { lockPendingBatch(any()) } returns emptyList() }
        val broker = mockk<MessageBroker>()

        OutboxRelay(outbox, broker, tx, Clock.fixed(now, ZoneOffset.UTC)).publishPending() shouldBe 0

        verify(exactly = 0) { broker.publish(any()) }
    }

    test("a broker failure is recorded against that message, and the ones after it are not attempted") {
        val batch = listOf(pending(1), pending(2), pending(3))
        val outbox = mockk<OutboxStore>(relaxUnitFun = true) { every { lockPendingBatch(50) } returns batch }
        val broker =
            mockk<MessageBroker> {
                every { publish(batch[0].message) } returns Unit
                every { publish(batch[1].message) } throws IllegalStateException("broker timeout")
            }

        OutboxRelay(outbox, broker, tx, Clock.fixed(now, ZoneOffset.UTC), batchSize = 50).publishPending() shouldBe 1

        verify { outbox.recordFailure(2, "broker timeout") }
        verify { outbox.markPublished(listOf(1L), now) }
        verify(exactly = 0) { broker.publish(batch[2].message) }
    }
})
