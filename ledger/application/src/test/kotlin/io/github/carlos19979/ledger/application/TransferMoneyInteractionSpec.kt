package io.github.carlos19979.ledger.application

import io.github.carlos19979.ledger.application.port.AccountRepository
import io.github.carlos19979.ledger.application.port.EventEncoder
import io.github.carlos19979.ledger.application.port.IdempotencyClaim
import io.github.carlos19979.ledger.application.port.IdempotencyStore
import io.github.carlos19979.ledger.application.port.LedgerEntryRepository
import io.github.carlos19979.ledger.application.port.OutboxMessage
import io.github.carlos19979.ledger.application.port.OutboxStore
import io.github.carlos19979.ledger.application.port.TransactionRunner
import io.github.carlos19979.ledger.application.port.TransferRepository
import io.github.carlos19979.ledger.application.transfer.TransferCommand
import io.github.carlos19979.ledger.application.transfer.TransferMoney
import io.github.carlos19979.ledger.application.transfer.TransferResult
import io.github.carlos19979.ledger.domain.Account
import io.github.carlos19979.ledger.domain.AccountId
import io.github.carlos19979.ledger.domain.AccountStatus
import io.github.carlos19979.ledger.domain.AccountType
import io.github.carlos19979.ledger.domain.Money
import io.github.carlos19979.ledger.domain.TransferStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID

/**
 * Interaction tests with mocks: the in-memory specs prove the outcome, these prove the protocol with the ports —
 * what is called, in which order, and what must never be called.
 */
class TransferMoneyInteractionSpec : FunSpec({
    val eur = Currency.getInstance("EUR")
    val now = Instant.parse("2026-11-10T10:00:00Z")
    val accounts = mockk<AccountRepository>(relaxUnitFun = true)
    val transfers = mockk<TransferRepository>(relaxUnitFun = true)
    val entries = mockk<LedgerEntryRepository>(relaxUnitFun = true)
    val idempotency = mockk<IdempotencyStore>()
    val outbox = mockk<OutboxStore>(relaxUnitFun = true)
    val encoder =
        EventEncoder { OutboxMessage(it.eventId.value, "t", "k", it.javaClass.simpleName, 1, "{}", it.occurredAt) }
    // The runner just runs the block, so the test sees every call the use case makes inside the transaction.
    val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }
    val useCase =
        TransferMoney(accounts, transfers, entries, idempotency, outbox, encoder, tx, Clock.fixed(now, ZoneOffset.UTC))

    fun account(balance: String, limit: String? = null) =
        Account.rehydrate(
            AccountId(UUID.randomUUID()),
            "Ada",
            AccountType.CUSTOMER,
            eur,
            Money.of(balance, "EUR"),
            AccountStatus.ACTIVE,
            limit?.let { Money.of(it, "EUR") },
            version = 7,
            openedAt = now,
        )

    beforeTest {
        clearAllMocks()
        every { idempotency.claim(any(), any(), any(), any()) } returns IdempotencyClaim.Claimed
    }

    test("claims the key, locks both accounts at once, then writes transfer, balances, entries and events") {
        val source = account("100.00")
        val target = account("0.00")
        every { accounts.lockForUpdate(setOf(source.id, target.id)) } returns
            mapOf(source.id to source, target.id to target)
        val written = slot<List<OutboxMessage>>()
        every { outbox.append(capture(written)) } returns Unit

        useCase
            .transfer(TransferCommand("k", source.id, target.id, Money.of("30.00", "EUR"), null))
            .shouldBeInstanceOf<TransferResult.Completed>()
            .transfer.status shouldBe TransferStatus.SETTLED

        verifyOrder {
            idempotency.claim("TRANSFER", "k", any(), any())
            accounts.lockForUpdate(setOf(source.id, target.id))
            transfers.insert(any())
            accounts.update(match { it.id == source.id && it.balance == Money.of("70.00", "EUR") }, 7)
            accounts.update(match { it.id == target.id && it.balance == Money.of("30.00", "EUR") }, 7)
            entries.insertAll(match { it.size == 2 })
            outbox.append(any())
        }
        written.captured.map { it.eventType } shouldBe listOf("EntryPosted", "EntryPosted", "TransferSettled")
        // Without a daily limit there is nothing to sum, so the entries table is not queried.
        verify(exactly = 0) { entries.debitedSince(any(), any(), any()) }
    }

    test("a rejection stores the transfer and its event but never touches balances or entries") {
        val source = account("1.00")
        val target = account("0.00")
        every { accounts.lockForUpdate(any()) } returns mapOf(source.id to source, target.id to target)

        useCase.transfer(TransferCommand("k", source.id, target.id, Money.of("5.00", "EUR"), null))

        verify(exactly = 1) { transfers.insert(match { it.status == TransferStatus.REJECTED }) }
        verify(exactly = 1) { outbox.append(match { it.single().eventType == "TransferRejected" }) }
        verify(exactly = 0) { accounts.update(any(), any()) }
        verify(exactly = 0) { entries.insertAll(any()) }
    }

    test("the daily limit is read from midnight UTC of the transfer's day") {
        val source = account("1000.00", limit = "500.00")
        val target = account("0.00")
        every { accounts.lockForUpdate(any()) } returns mapOf(source.id to source, target.id to target)
        every { entries.debitedSince(source.id, any(), eur) } returns Money.of("100.00", "EUR")

        useCase.transfer(TransferCommand("k", source.id, target.id, Money.of("10.00", "EUR"), null))

        verify { entries.debitedSince(source.id, Instant.parse("2026-11-10T00:00:00Z"), eur) }
    }

    test("a replay reads the stored transfer and writes nothing") {
        val source = account("10.00")
        val target = account("0.00")
        val command = TransferCommand("k", source.id, target.id, Money.of("1.00", "EUR"), null)
        val hash = slot<String>()
        every { idempotency.claim(any(), any(), capture(hash), any()) } returns IdempotencyClaim.Claimed
        every { accounts.lockForUpdate(any()) } returns mapOf(source.id to source, target.id to target)
        val first = useCase.transfer(command).shouldBeInstanceOf<TransferResult.Completed>()

        clearAllMocks()
        every { idempotency.claim(any(), any(), any(), any()) } returns
            IdempotencyClaim.AlreadyUsed(hash.captured, first.transfer.id)
        every { transfers.findById(first.transfer.id) } returns first.transfer

        useCase.transfer(command).shouldBeInstanceOf<TransferResult.Completed>().replayed shouldBe true

        verify(exactly = 0) { accounts.lockForUpdate(any()) }
        verify(exactly = 0) { transfers.insert(any()) }
        verify(exactly = 0) { outbox.append(any()) }
    }
})
